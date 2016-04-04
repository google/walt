/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

/**
 * A very simple mLogger that keeps its data in a StringBuilder. We need on screen log because the
 * USB port is often taken and we don't have easy access to adb log.
 */
public class SimpleLogger {
    public final String LOG_INTENT = "log-message";
    public static final String TAG = "WALTlogger";


    public StringBuilder sb = new StringBuilder();
    public LocalBroadcastManager broadcastManager;

    public void setBroadcastManager(LocalBroadcastManager bm) {
        broadcastManager = bm;
    }

    public void log(String msg) {
        Log.i(TAG, msg);
        sb.append(msg);
        sb.append('\n');
        if (broadcastManager != null) {
            Intent intent = new Intent(LOG_INTENT);
            intent.putExtra("message", msg);
            broadcastManager.sendBroadcast(intent);
        }
    }

    public String getLogText() {
        return sb.toString();
    }

    public void clear() {
        sb = new StringBuilder();
    }

}
