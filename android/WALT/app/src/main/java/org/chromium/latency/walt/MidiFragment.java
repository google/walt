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

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;

@TargetApi(23)
public class MidiFragment extends Fragment implements View.OnClickListener {

    private static final String TEENSY_MIDI_NAME = "Teensyduino Teensy MIDI";

    private MainActivity activity;
    private SimpleLogger logger;
    private TextView mTextView;

    private MidiDevice mMidiDevice;
    // Output here is with respect to the MIDI device, not the Android device.
    private MidiOutputPort mOutputPort;

    private long last_tWalt = 0;
    private long last_tSys = 0;
    private long last_tJava = 0;

    private final int noteDelay = 300;

    public MidiFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        activity = (MainActivity) getActivity();
        logger = activity.logger;

        findMidiDevice((MidiManager) container.getContext().getSystemService(Context.MIDI_SERVICE));

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_midi, container, false);
    }

    private void findMidiDevice(MidiManager m) {
        MidiDeviceInfo[] infos = m.getDevices();
        for(MidiDeviceInfo info : infos) {
            String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
            logger.log("Found MIDI device named " + name);
            if(TEENSY_MIDI_NAME.equals(name)) {
                logger.log("^^^ using this device ^^^");
                m.openDevice(info, new MidiManager.OnDeviceOpenedListener() {
                            @Override
                            public void onDeviceOpened(MidiDevice device) {
                                if (device == null) {
                                    logger.log("Error, unable to open MIDI device");
                                } else {
                                    logger.log("Opened MIDI device successfully!");
                                    mMidiDevice = device;
                                }
                            }
                        }, null);
                break;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mTextView = (TextView) activity.findViewById(R.id.txt_box_midi);

        // Register this fragment class as the listener for some button clicks
        activity.findViewById(R.id.button_start_midi_in).setOnClickListener(this);

        // mLogTextView.setMovementMethod(new ScrollingMovementMethod());
        mTextView.setText(activity.logger.getLogText());
        activity.logger.broadcastManager.registerReceiver(mLogReceiver,
                new IntentFilter(activity.logger.LOG_INTENT));

    }

    @Override
    public void onPause() {
        logger.broadcastManager.unregisterReceiver(mLogReceiver);
        super.onPause();
    }

    @Override
    public void onClick(View v) {
        if(mMidiDevice == null) {
            logger.log("Wait until the MIDI device connects");
            return;
        }
        switch (v.getId()) {
            case R.id.button_start_midi_in:
                setupMidiIn();
                activity.handler.postDelayed(requestNoteRunnable, noteDelay);
                break;
            default:
                break;
        }
    }

    private BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            mTextView.append(msg + "\n");
        }
    };

    private Runnable requestNoteRunnable = new Runnable() {
        @Override
        public void run() {
            activity.logger.log("Requesting note from WALT...");
            String s = activity.clockManager.sendReceive(ClockManager.CMD_NOTE);
            if(s.length() == 0) {
                logger.log("Error, failed to send message to WALT");
                return;
            }
            if (s.charAt(0) != 'n') {
                logger.log("Error, got unexpected reply to CMD_NOTE: " + s);
                return;
            }
            last_tWalt = Integer.parseInt(s.trim().substring(2));
            activity.handler.postDelayed(finishMidiInRunnable, noteDelay);
        }
    };

    private Runnable finishMidiInRunnable = new Runnable() {
        @Override
        public void run() {
            if(last_tSys != 0) {
                teardownMidiIn();
                double d1 = (last_tSys - last_tWalt) / 1000.;
                double d2 = (last_tJava - last_tSys) / 1000.;
                double dt = (last_tJava - last_tWalt) / 1000.;
                logger.log(String.format(Locale.US,
                        "Result: Time to MIDI subsystem = %.3f ms, Time to Java = %.3f ms, " +
                                "Total = %.3f ms",
                        d1, d2, dt));
            } else {
                activity.handler.postDelayed(finishMidiInRunnable, noteDelay);
            }
        }
    };

    private class WaltReceiver extends MidiReceiver {
        public void onSend(byte[] data, int offset,
            int count, long timestamp) throws IOException {
            if(count > 0 && data[offset] == (byte) 0x90) { // NoteOn message on channel 1
                last_tJava = activity.clockManager.micros();
                last_tSys = timestamp / 1000 - activity.clockManager.baseTime;
            } else {
                logger.log(String.format(Locale.US, "Expected 0x90, got 0x%x and count was %d",
                        data[offset], count));
            }
        }
    }

    private void setupMidiIn() {
        mOutputPort = mMidiDevice.openOutputPort(0);
        mOutputPort.connect(new WaltReceiver());
        activity.clockManager.syncClock();
    }

    private void teardownMidiIn() {
        try {
            mOutputPort.close();
        } catch (IOException e) {
            logger.log("Error, failed to close output port: " + e.getMessage());
        }
    }
}
