/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chromium.latency.walt;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;

/**
 * Used to log events for Android systrace
 */
class TraceLogger {

    private static final Object LOCK = new Object();
    private static TraceLogger instance;

    private ArrayList<TraceEvent> traceEvents;

    public static TraceLogger getInstance() {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new TraceLogger();
            }
            return instance;
        }
    }

    private TraceLogger() {
        traceEvents = new ArrayList<>();
    }

    public synchronized void log(long startTimeMicros, long finishTimeMicros, String title, String description) {
        traceEvents.add(new TraceEvent(startTimeMicros, finishTimeMicros, title, description));
    }

    public String getLogText() {
        DecimalFormat df = new DecimalFormat(".000000");
        StringBuilder sb = new StringBuilder();
        int pid = android.os.Process.myPid();
        for (TraceEvent e : traceEvents) {
            sb.append(String.format(
                    "WALTThread-1234 (%d) [000] ...1 %s: tracing_mark_write: B|%d|%s|description=%s|WALT\n",
                    pid, df.format(e.startTimeMicros / 1e6), pid, e.title, e.description));
            sb.append(String.format(
                    "WALTThread-1234 (%d) [000] ...1 %s: tracing_mark_write: E|%d|%s||WALT\n",
                    pid, df.format(e.finishTimeMicros / 1e6), pid, e.title));
        }
        return sb.toString();
    }

    void flush(Context context) {
        SimpleLogger logger = SimpleLogger.getInstance(context);
        if (!isExternalStorageWritable()) {
            logger.log("ERROR: could not write systrace logs to file");
            return;
        }
        writeSystraceLogs(context);
        traceEvents.clear();
    }

    private void writeSystraceLogs(Context context) {
        File file = new File(context.getExternalFilesDir(null), "trace.txt");
        SimpleLogger logger = SimpleLogger.getInstance(context);
        try {
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file, true));
            writer.write(getLogText());
            writer.close();
            logger.log(String.format("TraceLogger wrote %d events to %s",
                    traceEvents.size(), file.getAbsolutePath()));
        } catch (IOException e) {
            logger.log("ERROR: IOException writing to trace.txt");
            e.printStackTrace();
        }
    }

    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    private class TraceEvent {
        long startTimeMicros;
        long finishTimeMicros;
        String title;
        String description;
        TraceEvent(long startTimeMicros, long finishTimeMicros, String title, String description) {
            this.startTimeMicros = startTimeMicros;
            this.finishTimeMicros = finishTimeMicros;
            this.title = title;
            this.description = description;
        }
    }
}
