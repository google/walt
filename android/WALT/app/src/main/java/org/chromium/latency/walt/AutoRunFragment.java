/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class AutoRunFragment extends Fragment {

    static final String TEST_ACTION = "org.chromium.latency.walt.START_TEST";

    private ClockManager clockManager;
    private AudioTest toTearDown; // TODO: figure out a better way to destroy the engine

    private void doTest(@NonNull Bundle args) {
        switch (args.getString("TestType", "")) {
            case "MidiIn": {
                clockManager.registerConnectCallback(new Runnable() {
                    @Override
                    public void run() {
                        MidiTest midiTest = new MidiTest(getContext());
                        midiTest.testMidiIn();
                    }
                });
                break;
            }
            case "MidiOut": {
                clockManager.registerConnectCallback(new Runnable() {
                    @Override
                    public void run() {
                        MidiTest midiTest = new MidiTest(getContext());
                        midiTest.testMidiOut();
                    }
                });
                break;
            }
            case "AudioIn": {
                clockManager.registerConnectCallback(new Runnable() {
                    @Override
                    public void run() {
                        AudioTest audioTest = new AudioTest(getContext());
                        audioTest.beginRecordingTest();
                        toTearDown = audioTest;
                    }
                });
                break;
            }
            case "AudioOut": {
                clockManager.registerConnectCallback(new Runnable() {
                    @Override
                    public void run() {
                        AudioTest audioTest = new AudioTest(getContext());
                        audioTest.startMeasurement();
                        toTearDown = audioTest;
                    }
                });
                break;
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (toTearDown != null) {
            toTearDown.teardown();
        }
        super.onDestroyView();
    }

    private TextView txtLogAutoRun;
    private SimpleLogger logger;

    public AutoRunFragment() {
        // Required empty public constructor
    }

    private BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            txtLogAutoRun.append("\n" + msg);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logger = SimpleLogger.getInstance(getContext());
        clockManager = ClockManager.getInstance(getContext());

        View view = inflater.inflate(R.layout.fragment_auto_run, container, false);

        Bundle args = getArguments();
        if (args != null) {
            doTest(args);
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        txtLogAutoRun = (TextView) getActivity().findViewById(R.id.txt_log_auto_run);
        txtLogAutoRun.setMovementMethod(new ScrollingMovementMethod());
        txtLogAutoRun.setText(logger.getLogText());
        logger.registerReceiver(mLogReceiver);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(mLogReceiver);
        super.onPause();
    }
}
