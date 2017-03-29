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
    private String logText;

    LogUploader(Context context, String urlString, String logText) {
        super(context);
        this.urlString = urlString;
        this.logText = logText;
    }

    @Override
    public Integer loadInBackground() {
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
            writer.write(logText);
            writer.flush();

            return urlConnection.getResponseCode();
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }
}
