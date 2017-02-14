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

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;


/**
 * A separate activity to display exception trace on the screen in case of a crash.
 * This is useful because we dont have the USB cable connected for debugging in many cases, because
 * the USB port is taken by the WALT device.
 */
public class CrashLogActivity extends AppCompatActivity {

    TextView txtCrashLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_log);
        txtCrashLog = (TextView) findViewById(R.id.txt_crash_log);
        txtCrashLog.setText(getIntent().getStringExtra("crash_log"));
        txtCrashLog.setMovementMethod(new ScrollingMovementMethod());
    }

}
