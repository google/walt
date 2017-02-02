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
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;

public class AutoRunFragment extends Fragment {

    static final String TEST_ACTION = "org.chromium.latency.walt.START_TEST";
    static final String MODE_COLD = "Cold";

    private WaltDevice waltDevice;
    private AudioTest toTearDown; // TODO: figure out a better way to destroy the engine
    Handler handler = new Handler();

    private class AudioResultHandler implements ResultHandler {
        private FileWriter fileWriter;

        AudioResultHandler(String fileName) throws IOException {
            fileWriter = new FileWriter(fileName);
        }

        @Override
        public void onResult(Iterable[] results) {
            if (results.length == 0) {
                logger.log("Can't write empty data!");
                return;
            }
            logger.log("Writing data file");

            Iterator its[] = new Iterator[results.length];

            for (int i = 0; i < results.length; i++) {
                its[i] = results[i].iterator();
            }
            try {
                while (its[0].hasNext()) {
                    for (Iterator it : its) {
                        if (it.hasNext()) {
                            fileWriter.write(it.next().toString() + ",");
                        }
                    }
                    fileWriter.write("\n");
                }
            } catch (IOException e) {
                logger.log("Error writing output file: " + e.getMessage());
            } finally {
                try {
                    fileWriter.close();
                } catch (IOException e) {
                    logger.log("Error closing output file: " + e.getMessage());
                }
            }
        }
    }

    private void doTest(@NonNull Bundle args) {
        final int reps = args.getInt("Reps", 10);
        String fileName = args.getString("FileName", null);
        ResultHandler r = null;
        if (fileName != null) {
            try {
                r = new AudioResultHandler(fileName);
            } catch (IOException e) {
                logger.log("Unable to open output file " + e.getMessage());
                return;
            }
        }
        final String mode = args.getString("Mode", "");
        final ResultHandler resultHandler = r;
        Runnable testRunnable = null;
        switch (args.getString("TestType", "")) {
            case "MidiIn": {
                testRunnable = new Runnable() {
                    @Override
                    public void run() {
                        MidiTest midiTest = new MidiTest(getContext(), resultHandler);
                        midiTest.setInputRepetitions(reps);
                        midiTest.testMidiIn();
                    }
                };
                break;
            }
            case "MidiOut": {
                testRunnable = new Runnable() {
                    @Override
                    public void run() {
                        MidiTest midiTest = new MidiTest(getContext(), resultHandler);
                        midiTest.setOutputRepetitions(reps);
                        midiTest.testMidiOut();
                    }
                };
                break;
            }
            case "AudioIn": {
                testRunnable = new Runnable() {
                    @Override
                    public void run() {
                        AudioTest audioTest = new AudioTest(getContext(), resultHandler);
                        audioTest.setRecordingRepetitions(reps);
                        audioTest.setAudioMode(MODE_COLD.equals(mode) ?
                                AudioTest.AudioMode.COLD : AudioTest.AudioMode.CONTINUOUS);
                        audioTest.beginRecordingMeasurement();
                        toTearDown = audioTest;
                    }
                };
                break;
            }
            case "AudioOut": {
                final int period = args.getInt("Period", -1);
                testRunnable = new Runnable() {
                    @Override
                    public void run() {
                        AudioTest audioTest = new AudioTest(getContext(), resultHandler);
                        audioTest.setPlaybackRepetitions(reps);
                        audioTest.setAudioMode(MODE_COLD.equals(mode) ?
                                AudioTest.AudioMode.COLD : AudioTest.AudioMode.CONTINUOUS);
                        if (period > 0) {
                            audioTest.setPeriod(period);
                        } else {
                            audioTest.setPeriod(MODE_COLD.equals(mode) ?
                                    AudioTest.COLD_TEST_PERIOD : AudioTest.CONTINUOUS_TEST_PERIOD);
                        }
                        audioTest.beginPlaybackMeasurement();
                        toTearDown = audioTest;
                    }
                };
                break;
            }
        }

        // Not sure we need the handler.post() here, but just in case.
        final Runnable finalTestRunnable = testRunnable;
        waltDevice.setConnectionStateListener(new WaltConnection.ConnectionStateListener() {
            @Override
            public void onConnect() {
                handler.post(finalTestRunnable);
            }

            @Override
            public void onDisconnect() {}
        });

    }

    interface ResultHandler {
        void onResult(Iterable... r);
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

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
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
        waltDevice = WaltDevice.getInstance(getContext());

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
        logger.registerReceiver(logReceiver);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(logReceiver);
        super.onPause();
    }
}
