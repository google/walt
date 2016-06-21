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
import java.util.Locale;

@TargetApi(23)
class MidiTest {

    private SimpleLogger logger;
    private ClockManager clockManager;
    private Handler handler = new Handler();

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

    private static final int noteDelay = 300;
    private static final int timeout = 1000;

    MidiTest(Context context) {
        clockManager = ClockManager.getInstance(context);
        logger = SimpleLogger.getInstance(context);
        mMidiManager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
        findMidiDevice();
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
        scheduleNote();
        handler.postDelayed(cancelMidiOutRunnable, noteDelay + timeout);
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
        mInputPort = mMidiDevice.openInputPort(0);

        clockManager.syncClock();
        clockManager.command(ClockManager.CMD_MIDI);
        clockManager.startListener();
        clockManager.setTriggerHandler(triggerHandler);
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

    private ClockManager.TriggerHandler triggerHandler = new ClockManager.TriggerHandler() {
        @Override
        public void onReceive(ClockManager.TriggerMessage tmsg) {
            last_tWalt = tmsg.t + clockManager.baseTime;
            double dt = (last_tWalt - last_tSys) / 1000.;

            logger.log(String.format(Locale.US, "Note detected: latency of %.3f ms", dt));

            finishMidiOut();
        }
    };

    private void scheduleNote() {
        if(mInputPort == null) {
            logger.log("mInputPort is not open");
            return;
        }
        long t = System.nanoTime() + noteDelay * 1000 * 1000;
        try {
            mInputPort.send(noteMsg, 0, noteMsg.length, t);
        } catch(IOException e) {
            logger.log("Unable to schedule note: " + e.getMessage());
            return;
        }
        last_tSys = t / 1000;
    }

    private void finishMidiOut() {
        logger.log("All notes detected");
        handler.removeCallbacks(cancelMidiOutRunnable);

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

        clockManager.stopListener();
        clockManager.clearTriggerHandler();
        clockManager.logDrift();
    }

    private Runnable requestNoteRunnable = new Runnable() {
        @Override
        public void run() {
            logger.log("Requesting note from WALT...");
            String s;
            try {
                s = clockManager.command(ClockManager.CMD_NOTE);
            } catch (IOException e) {
                logger.log("Error sending NOTE command: " + e.getMessage());
                return;
            }
            last_tWalt = Integer.parseInt(s);
            handler.postDelayed(finishMidiInRunnable, noteDelay);
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
                handler.postDelayed(finishMidiInRunnable, noteDelay);
            }
        }
    };

    private class WaltReceiver extends MidiReceiver {
        public void onSend(byte[] data, int offset,
                           int count, long timestamp) throws IOException {
            if(count > 0 && data[offset] == (byte) 0x90) { // NoteOn message on channel 1
                last_tJava = clockManager.micros();
                last_tSys = timestamp / 1000 - clockManager.baseTime;
            } else {
                logger.log(String.format(Locale.US, "Expected 0x90, got 0x%x and count was %d",
                        data[offset], count));
            }
        }
    }

    private void setupMidiIn() throws IOException {
        mOutputPort = mMidiDevice.openOutputPort(0);
        mOutputPort.connect(new WaltReceiver());
        clockManager.syncClock();
    }

    private void teardownMidiIn() {
        try {
            mOutputPort.close();
        } catch (IOException e) {
            logger.log("Error, failed to close output port: " + e.getMessage());
        }
    }
}
