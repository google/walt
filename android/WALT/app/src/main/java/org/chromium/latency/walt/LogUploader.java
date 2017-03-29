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

import android.content.Context;
import android.support.v4.content.AsyncTaskLoader;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;

class LogUploader extends AsyncTaskLoader<Integer> {

    private String urlString;
    private SimpleLogger logger;

    LogUploader(Context context) {
        super(context);
        urlString = Utils.getStringPreference(context, R.string.preference_log_url, "");
        logger = SimpleLogger.getInstance(context);

    }

    LogUploader(Context context, String urlString) {
        super(context);
        this.urlString = urlString;
        logger = SimpleLogger.getInstance(context);
    }

    @Override
    public Integer loadInBackground() {
        if (urlString.isEmpty()) return -1;
        try {
            URL url = new URL(urlString);
            HttpURLConnection urlConnection =
                    (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("POST");
            urlConnection.setDoOutput(true);
            urlConnection.setRequestProperty("Content-Type", "text/plain");
            BufferedOutputStream out =
                    new BufferedOutputStream(urlConnection.getOutputStream());
            PrintWriter writer = new PrintWriter(out);
            writer.write(logger.getLogText());
            writer.flush();
            final int responseCode = urlConnection.getResponseCode();
            if (responseCode / 100 == 2) {
                logger.log("Log successfully uploaded");
            } else {
                logger.log("Log upload may have failed. Server return status code " + responseCode);
            }
            return responseCode;
        } catch (IOException e) {
            logger.log("Failed to upload log");
            return -1;
        }
    }

    void startUpload() {
        super.forceLoad();
    }

    static void uploadIfAutoEnabled(Context context) {
        if (Utils.getBooleanPreference(context, R.string.preference_auto_upload_log, false)) {
            new LogUploader(context).startUpload();
        }
    }
}
