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


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;


/**
 * This screen allows to perform different tasks useful for diagnostics.
 */
public class DiagnosticsFragment extends Fragment {

    private SimpleLogger logger;
    private TextView logTextView;


    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            DiagnosticsFragment.this.appendLogText(msg);
        }
    };

    public DiagnosticsFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        logger = SimpleLogger.getInstance(getContext());
        // Inflate the layout for this fragment
        final View view = inflater.inflate(R.layout.fragment_diagnostics, container, false);
        logTextView = (TextView) view.findViewById(R.id.txt_log_diag);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        logTextView.setMovementMethod(new ScrollingMovementMethod());
        logTextView.setText(logger.getLogText());
        logger.registerReceiver(logReceiver);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(logReceiver);
        super.onPause();
    }


    public void appendLogText(String msg) {
        logTextView.append(msg + "\n");
    }
}
