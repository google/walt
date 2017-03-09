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
class MidiTest extends BaseTest {

    private Handler handler = new Handler();

    private static final String TEENSY_MIDI_NAME = "Teensyduino Teensy MIDI";
    private static final byte[] noteMsg = {(byte) 0x90, (byte) 99, (byte) 0};

    private MidiManager midiManager;
    private MidiDevice midiDevice;
    // Output and Input here are with respect to the MIDI device, not the Android device.
    private MidiOutputPort midiOutputPort;
    private MidiInputPort midiInputPort;
    private boolean isConnecting = false;
    private long last_tWalt = 0;
    private long last_tSys = 0;
    private long last_tJava = 0;
    private int inputSyncAfterRepetitions = 100;
    private int outputSyncAfterRepetitions = 20; // TODO: implement periodic clock sync for output
    private int inputRepetitions;
    private int outputRepetitions;
    private int repetitionsDone;
    private ArrayList<Double> deltasToSys = new ArrayList<>();
    ArrayList<Double> deltasInputTotal = new ArrayList<>();
    ArrayList<Double> deltasOutputTotal = new ArrayList<>();

    private static final int noteDelay = 300;
    private static final int timeout = 1000;

    MidiTest(Context context) {
        super(context);
        inputRepetitions = getIntPreference(context, R.string.preference_midi_in_reps, 100);
        outputRepetitions = getIntPreference(context, R.string.preference_midi_out_reps, 10);
        midiManager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
        findMidiDevice();
    }

    MidiTest(Context context, AutoRunFragment.ResultHandler resultHandler) {
        this(context);
        this.resultHandler = resultHandler;
    }

    void setInputRepetitions(int repetitions) {
        inputRepetitions = repetitions;
    }

    void setOutputRepetitions(int repetitions) {
        outputRepetitions = repetitions;
    }

    void testMidiOut() {
        if (midiDevice == null) {
            if (isConnecting) {
                logger.log("Still connecting...");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        testMidiOut();
                    }
                });
            } else {
                logger.log("MIDI device is not open!");
                if (testStateListener != null) testStateListener.onTestStoppedWithError();
            }
            return;
        }
        try {
            setupMidiOut();
        } catch (IOException e) {
            logger.log("Error setting up test: " + e.getMessage());
            if (testStateListener != null) testStateListener.onTestStoppedWithError();
            return;
        }
        handler.postDelayed(cancelMidiOutRunnable, noteDelay * inputRepetitions + timeout);
    }

    void testMidiIn() {
        if (midiDevice == null) {
            if (isConnecting) {
                logger.log("Still connecting...");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        testMidiIn();
                    }
                });
            } else {
                logger.log("MIDI device is not open!");
                if (testStateListener != null) testStateListener.onTestStoppedWithError();
            }
            return;
        }
        try {
            setupMidiIn();
        } catch (IOException e) {
            logger.log("Error setting up test: " + e.getMessage());
            if (testStateListener != null) testStateListener.onTestStoppedWithError();
            return;
        }
        handler.postDelayed(requestNoteRunnable, noteDelay);
    }

    private void setupMidiOut() throws IOException {
        repetitionsDone = 0;
        deltasInputTotal.clear();
        deltasOutputTotal.clear();

        midiInputPort = midiDevice.openInputPort(0);

        waltDevice.syncClock();
        waltDevice.command(WaltDevice.CMD_MIDI);
        waltDevice.startListener();
        waltDevice.setTriggerHandler(triggerHandler);

        scheduleNotes();
    }

    private void findMidiDevice() {
        MidiDeviceInfo[] infos = midiManager.getDevices();
        for(MidiDeviceInfo info : infos) {
            String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
            logger.log("Found MIDI device named " + name);
            if(TEENSY_MIDI_NAME.equals(name)) {
                logger.log("^^^ using this device ^^^");
                isConnecting = true;
                midiManager.openDevice(info, new MidiManager.OnDeviceOpenedListener() {
                    @Override
                    public void onDeviceOpened(MidiDevice device) {
                        if (device == null) {
                            logger.log("Error, unable to open MIDI device");
                        } else {
                            logger.log("Opened MIDI device successfully!");
                            midiDevice = device;
                        }
                        isConnecting = false;
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

            deltasOutputTotal.add(dt);
            logger.log(String.format(Locale.US, "Note detected: latency of %.3f ms", dt));
            if (testStateListener != null) testStateListener.onTestPartialResult(dt);
            if (traceLogger != null) {
                traceLogger.log(last_tSys, last_tWalt, "MIDI Output",
                        "Bar starts when system sends audio and ends when WALT receives note");
            }

            last_tSys += noteDelay * 1000;
            repetitionsDone++;

            if (repetitionsDone < outputRepetitions) {
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
        if(midiInputPort == null) {
            logger.log("midiInputPort is not open");
            return;
        }
        long t = System.nanoTime() + ((long) noteDelay) * 1000000L;
        try {
            // TODO: only schedule some, then sync clock
            for (int i = 0; i < outputRepetitions; i++) {
                midiInputPort.send(noteMsg, 0, noteMsg.length, t + ((long) noteDelay) * 1000000L * i);
            }
        } catch(IOException e) {
            logger.log("Unable to schedule note: " + e.getMessage());
            return;
        }
        last_tSys = t / 1000;
    }

    private void finishMidiOut() {
        logger.log("All notes detected");
        logger.log(String.format(
                Locale.US, "Median total output latency %.1f ms", Utils.median(deltasOutputTotal)));

        handler.removeCallbacks(cancelMidiOutRunnable);

        if (resultHandler != null) {
            resultHandler.onResult(deltasOutputTotal);
        }
        if (testStateListener != null) testStateListener.onTestStopped();
        if (traceLogger != null) traceLogger.flush(context);
        teardownMidiOut();
    }

    private Runnable cancelMidiOutRunnable = new Runnable() {
        @Override
        public void run() {
            logger.log("Timed out waiting for notes to be detected by WALT");
            if (testStateListener != null) testStateListener.onTestStoppedWithError();
            teardownMidiOut();
        }
    };

    private void teardownMidiOut() {
        try {
            midiInputPort.close();
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
                if (testStateListener != null) testStateListener.onTestStoppedWithError();
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
            logger.log("MIDI Input Test Results:");
            logger.log(String.format(Locale.US,
                    "Median MIDI subsystem latency %.1f ms\nMedian total latency %.1f ms",
                    Utils.median(deltasToSys), Utils.median(deltasInputTotal)
            ));

            if (resultHandler != null) {
                resultHandler.onResult(deltasToSys, deltasInputTotal);
            }
            if (testStateListener != null) testStateListener.onTestStopped();
            if (traceLogger != null) traceLogger.flush(context);
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

                final double d1 = (last_tSys - last_tWalt) / 1000.;
                final double d2 = (last_tJava - last_tSys) / 1000.;
                final double dt = (last_tJava - last_tWalt) / 1000.;
                logger.log(String.format(Locale.US,
                        "Result: Time to MIDI subsystem = %.3f ms, Time to Java = %.3f ms, " +
                                "Total = %.3f ms",
                        d1, d2, dt));
                deltasToSys.add(d1);
                deltasInputTotal.add(dt);
                if (testStateListener != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            testStateListener.onTestPartialResult(dt);
                        }
                    });
                }
                if (traceLogger != null) {
                    traceLogger.log(last_tWalt + waltDevice.clock.baseTime,
                            last_tSys + waltDevice.clock.baseTime, "MIDI Input Subsystem",
                            "Bar starts when WALT sends note and ends when received by MIDI subsystem");
                    traceLogger.log(last_tSys + waltDevice.clock.baseTime,
                            last_tJava + waltDevice.clock.baseTime, "MIDI Input Java",
                            "Bar starts when note received by MIDI subsystem and ends when received by app");
                }

                repetitionsDone++;
                if (repetitionsDone % inputSyncAfterRepetitions == 0) {
                    try {
                        waltDevice.syncClock();
                    } catch (IOException e) {
                        logger.log("Error syncing clocks: " + e.getMessage());
                        handler.post(finishMidiInRunnable);
                        return;
                    }
                }
                if (repetitionsDone < inputRepetitions) {
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
        repetitionsDone = 0;
        deltasInputTotal.clear();
        deltasOutputTotal.clear();
        midiOutputPort = midiDevice.openOutputPort(0);
        midiOutputPort.connect(new WaltReceiver());
        waltDevice.syncClock();
    }

    private void teardownMidiIn() {
        handler.removeCallbacks(requestNoteRunnable);
        handler.removeCallbacks(finishMidiInRunnable);
        try {
            midiOutputPort.close();
        } catch (IOException e) {
            logger.log("Error, failed to close output port: " + e.getMessage());
        }
    }
}
