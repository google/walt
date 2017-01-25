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
import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import static org.chromium.latency.walt.Utils.getIntPreference;

@TargetApi(23)
class MidiTest {

    private SimpleLogger logger;
    private WaltDevice waltDevice;
    private Handler handler = new Handler();

    private AutoRunFragment.ResultHandler resultHandler;

    private static final String TEENSY_MIDI_NAME = "Teensyduino Teensy MIDI";
    private static final byte[] noteMsg = {(byte) 0x90, (byte) 99, (byte) 0};

    private MidiManager mMidiManager;
    private MidiDevice mMidiDevice;
    // Output and Input here are with respect to the MIDI device, not the Android device.
    private MidiOutputPort mOutputPort;
    private MidiInputPort mInputPort;

    private boolean mConnecting = false;

    private long last_tWalt = 0;
    private long last_tSys = 0;
    private long last_tJava = 0;

    private int mInputSyncAfterRepetitions = 100;
    private int mOutputSyncAfterRepetitions = 20; // TODO: implement periodic clock sync for output
    private int mInputRepetitions;
    private int mOutputRepetitions;
    private int mRepetitionsDone;
    private ArrayList<Double> deltasToSys = new ArrayList<>();
    private ArrayList<Double> deltasTotal = new ArrayList<>();

    private static final int noteDelay = 300;
    private static final int timeout = 1000;

    MidiTest(Context context) {
        mInputRepetitions = getIntPreference(context, R.string.preference_midi_in_reps, 100);
        mOutputRepetitions = getIntPreference(context, R.string.preference_midi_out_reps, 10);
        waltDevice = WaltDevice.getInstance(context);
        logger = SimpleLogger.getInstance(context);
        mMidiManager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
        findMidiDevice();
    }

    MidiTest(Context context, AutoRunFragment.ResultHandler resultHandler) {
        this(context);
        this.resultHandler = resultHandler;
    }

    void setInputRepetitions(int repetitions) {
        mInputRepetitions = repetitions;
    }

    void setOutputRepetitions(int repetitions) {
        mOutputRepetitions = repetitions;
    }

    void testMidiOut() {
        if (mMidiDevice == null) {
            if (mConnecting) {
                logger.log("Still connecting...");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        testMidiOut();
                    }
                });
            } else {
                logger.log("MIDI device is not open!");
            }
            return;
        }
        try {
            setupMidiOut();
        } catch (IOException e) {
            logger.log("Error setting up test: " + e.getMessage());
            return;
        }
        handler.postDelayed(cancelMidiOutRunnable, noteDelay * mInputRepetitions + timeout);
    }

    void testMidiIn() {
        if (mMidiDevice == null) {
            if (mConnecting) {
                logger.log("Still connecting...");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        testMidiIn();
                    }
                });
            } else {
                logger.log("MIDI device is not open!");
            }
            return;
        }
        try {
            setupMidiIn();
        } catch (IOException e) {
            logger.log("Error setting up test: " + e.getMessage());
            return;
        }
        handler.postDelayed(requestNoteRunnable, noteDelay);
    }

    private void setupMidiOut() throws IOException {
        mRepetitionsDone = 0;
        deltasTotal.clear();

        mInputPort = mMidiDevice.openInputPort(0);

        waltDevice.syncClock();
        waltDevice.command(WaltDevice.CMD_MIDI);
        waltDevice.startListener();
        waltDevice.setTriggerHandler(triggerHandler);

        scheduleNotes();
    }

    private void findMidiDevice() {
        MidiDeviceInfo[] infos = mMidiManager.getDevices();
        for(MidiDeviceInfo info : infos) {
            String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
            logger.log("Found MIDI device named " + name);
            if(TEENSY_MIDI_NAME.equals(name)) {
                logger.log("^^^ using this device ^^^");
                mConnecting = true;
                mMidiManager.openDevice(info, new MidiManager.OnDeviceOpenedListener() {
                    @Override
                    public void onDeviceOpened(MidiDevice device) {
                        if (device == null) {
                            logger.log("Error, unable to open MIDI device");
                        } else {
                            logger.log("Opened MIDI device successfully!");
                            mMidiDevice = device;
                        }
                        mConnecting = false;
                    }
                }, null);
                break;
            }
        }
    }

    private WaltDevice.TriggerHandler triggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
            last_tWalt = tmsg.t + waltDevice.clock.baseTime;
            double dt = (last_tWalt - last_tSys) / 1000.;

            logger.log(String.format(Locale.US, "Note detected: latency of %.3f ms", dt));

            last_tSys += noteDelay * 1000;
            mRepetitionsDone++;

            if (mRepetitionsDone < mOutputRepetitions) {
                try {
                    waltDevice.command(WaltDevice.CMD_MIDI);
                } catch (IOException e) {
                    logger.log("Failed to send command CMD_MIDI: " + e.getMessage());
                }
            } else {
                finishMidiOut();
            }
        }
    };

    private void scheduleNotes() {
        if(mInputPort == null) {
            logger.log("mInputPort is not open");
            return;
        }
        long t = System.nanoTime() + ((long) noteDelay) * 1000000L;
        try {
            // TODO: only schedule some, then sync clock
            for (int i = 0; i < mOutputRepetitions; i++) {
                mInputPort.send(noteMsg, 0, noteMsg.length, t + ((long) noteDelay) * 1000000L * i);
            }
        } catch(IOException e) {
            logger.log("Unable to schedule note: " + e.getMessage());
            return;
        }
        last_tSys = t / 1000;
    }

    private void finishMidiOut() {
        logger.log("All notes detected");
        handler.removeCallbacks(cancelMidiOutRunnable);

        if (resultHandler != null) {
            resultHandler.onResult(deltasTotal);
        }
        teardownMidiOut();
    }

    private Runnable cancelMidiOutRunnable = new Runnable() {
        @Override
        public void run() {
            logger.log("Timed out waiting for notes to be detected by WALT");
            teardownMidiOut();
        }
    };

    private void teardownMidiOut() {
        try {
            mInputPort.close();
        } catch(IOException e) {
            logger.log("Error, failed to close input port: " + e.getMessage());
        }

        waltDevice.stopListener();
        waltDevice.clearTriggerHandler();
        waltDevice.checkDrift();
    }

    private Runnable requestNoteRunnable = new Runnable() {
        @Override
        public void run() {
            logger.log("Requesting note from WALT...");
            String s;
            try {
                s = waltDevice.command(WaltDevice.CMD_NOTE);
            } catch (IOException e) {
                logger.log("Error sending NOTE command: " + e.getMessage());
                return;
            }
            last_tWalt = Integer.parseInt(s);
            handler.postDelayed(finishMidiInRunnable, timeout);
        }
    };

    private Runnable finishMidiInRunnable = new Runnable() {
        @Override
        public void run() {
            waltDevice.checkDrift();

            logger.log("deltas: " + deltasToSys.toString());
            logger.log(String.format(Locale.US,
                    "Median MIDI subsystem latency %.1f ms\nMedian total latency %.1f ms",
                    Utils.median(deltasToSys), Utils.median(deltasTotal)
            ));

            if (resultHandler != null) {
                resultHandler.onResult(deltasToSys, deltasTotal);
            }
            teardownMidiIn();
        }
    };

    private class WaltReceiver extends MidiReceiver {
        public void onSend(byte[] data, int offset,
                           int count, long timestamp) throws IOException {
            if(count > 0 && data[offset] == (byte) 0x90) { // NoteOn message on channel 1
                handler.removeCallbacks(finishMidiInRunnable);
                last_tJava = waltDevice.clock.micros();
                last_tSys = timestamp / 1000 - waltDevice.clock.baseTime;

                double d1 = (last_tSys - last_tWalt) / 1000.;
                double d2 = (last_tJava - last_tSys) / 1000.;
                double dt = (last_tJava - last_tWalt) / 1000.;
                logger.log(String.format(Locale.US,
                        "Result: Time to MIDI subsystem = %.3f ms, Time to Java = %.3f ms, " +
                                "Total = %.3f ms",
                        d1, d2, dt));
                deltasToSys.add(d1);
                deltasTotal.add(dt);

                mRepetitionsDone++;
                if (mRepetitionsDone % mInputSyncAfterRepetitions == 0) {
                    try {
                        waltDevice.syncClock();
                    } catch (IOException e) {
                        logger.log("Error syncing clocks: " + e.getMessage());
                        handler.post(finishMidiInRunnable);
                        return;
                    }
                }
                if (mRepetitionsDone < mInputRepetitions) {
                    handler.post(requestNoteRunnable);
                } else {
                    handler.post(finishMidiInRunnable);
                }
            } else {
                logger.log(String.format(Locale.US, "Expected 0x90, got 0x%x and count was %d",
                        data[offset], count));
            }
        }
    }

    private void setupMidiIn() throws IOException {
        mRepetitionsDone = 0;
        mOutputPort = mMidiDevice.openOutputPort(0);
        mOutputPort.connect(new WaltReceiver());
        waltDevice.syncClock();
    }

    private void teardownMidiIn() {
        handler.removeCallbacks(requestNoteRunnable);
        handler.removeCallbacks(finishMidiInRunnable);
        try {
            mOutputPort.close();
        } catch (IOException e) {
            logger.log("Error, failed to close output port: " + e.getMessage());
        }
    }
}
