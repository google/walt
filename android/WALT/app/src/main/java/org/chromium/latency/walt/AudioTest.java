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

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import static org.chromium.latency.walt.Utils.getIntPreference;

class AudioTest extends BaseTest {

    static {
        System.loadLibrary("sync_clock_jni");
    }

    static final int CONTINUOUS_TEST_PERIOD = 500;
    static final int COLD_TEST_PERIOD = 5000;

    enum AudioMode {COLD, CONTINUOUS}

    private Handler handler = new Handler();

    // Sound params
    private final double duration = 0.3; // seconds
    private final int sampleRate = 8000;
    private final int numSamples = (int) (duration * sampleRate);
    private final byte generatedSnd[] = new byte[2 * numSamples];
    private final double freqOfTone = 880; // hz

    private AudioMode audioMode;
    private int period = 500; // time between runs in ms

    // Audio in
    private long last_tb = 0;
    private int msToRecord = 1000;
    private int framesToRecord;
    private int frameRateInt;

    private int initiatedBeeps, detectedBeeps;
    private int playbackRepetitions;
    private static final int playbackSyncAfterRepetitions = 20;

    // Audio out
    private int requestedBeeps;
    private int recordingRepetitions;
    private static int recorderSyncAfterRepetitions = 10;

    private ArrayList<Double> deltas = new ArrayList<>();
    private ArrayList<Double> deltas2 = new ArrayList<>();
    private ArrayList<Double> deltasJ2N = new ArrayList<>();

    long lastBeepTime;

    public static native long playTone();
    public static native void startWarmTest();
    public static native void stopTests();
    public static native void createEngine();
    public static native void destroyEngine();
    public static native void createBufferQueueAudioPlayer(int frameRate, int framesPerBuffer);

    public static native void startRecording();
    public static native void createAudioRecorder(int frameRate, int framesToRecord);
    public static native short[] getRecordedWave();
    public static native long getTeRec();
    public static native long getTcRec();
    public static native long getTePlay();

    AudioTest(Context context) {
        super(context);
        playbackRepetitions = getIntPreference(context, R.string.preference_audio_out_reps, 10);
        recordingRepetitions = getIntPreference(context, R.string.preference_audio_in_reps, 5);

        //Check for optimal output sample rate and buffer size
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        String frameRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        String framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        logger.log("Optimal frame rate is: " + frameRate);
        logger.log("Optimal frames per buffer is: " + framesPerBuffer);

        //Convert to ints
        frameRateInt = Integer.parseInt(frameRate);
        int framesPerBufferInt = Integer.parseInt(framesPerBuffer);

        //Create the audio engine
        createEngine();
        createBufferQueueAudioPlayer(frameRateInt, framesPerBufferInt);
        logger.log("Audio engine created");
    }

    AudioTest(Context context, AutoRunFragment.ResultHandler resultHandler) {
        this(context);
        this.resultHandler = resultHandler;
    }

    void setPlaybackRepetitions(int beepCount) {
        playbackRepetitions = beepCount;
    }

    void setRecordingRepetitions(int beepCount) {
        recordingRepetitions = beepCount;
    }

    void setPeriod(int period) {
        this.period = period;
    }

    void setAudioMode(AudioMode mode) {
        audioMode = mode;
    }

    void teardown() {
        destroyEngine();
        logger.log("Audio engine destroyed");
    }

    void beginRecordingTest() {
        deltas.clear();

        framesToRecord = (int) (0.001 * msToRecord * frameRateInt);
        createAudioRecorder(frameRateInt, framesToRecord);
        logger.log("Audio recorder created; starting test");

        requestedBeeps = 0;
        doRecordingTestRepetition();
    }

    private void doRecordingTestRepetition() {
        if (requestedBeeps > recordingRepetitions) {
            finishRecordingTest();
            return;
        }

        if (requestedBeeps % recorderSyncAfterRepetitions == 0) {
            try {
                waltDevice.syncClock();
            } catch (IOException e) {
                logger.log("Error syncing clocks: " + e.getMessage());
                finishRecordingTest();
                return;
            }
        }

        requestedBeeps++;
        startRecording();
        switch (audioMode) {
            case CONTINUOUS:
                handler.postDelayed(requestBeepRunnable, msToRecord / 2);
                break;
            case COLD: // TODO: find a more accurate method to measure cold input latency
                requestBeepRunnable.run();
                break;
        }
        handler.postDelayed(stopBeepRunnable, msToRecord);
    }

    void startMeasurement() {
        if (audioMode == AudioMode.CONTINUOUS) {
            startWarmTest();
        }
        try {
            waltDevice.syncClock();
            waltDevice.startListener();
        } catch (IOException e) {
            logger.log("Error starting test: " + e.getMessage());
            if (testStateListener != null) testStateListener.onTestStopped();
            return;
        }
        deltas.clear();
        deltas2.clear();
        deltasJ2N.clear();

        logger.log("Starting playback test");

        initiatedBeeps = 0;
        detectedBeeps = 0;

        waltDevice.setTriggerHandler(triggerHandler);

        handler.postDelayed(doBeepRunnable, 300);

    }

    private WaltDevice.TriggerHandler triggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
            // remove the far away doBeep callback(s)
            handler.removeCallbacks(doBeepRunnable);

            detectedBeeps++;
            long te = getTePlay();
            double dt = (tmsg.t - lastBeepTime) / 1000.;

            double dt2 = (tmsg.t - te) / 1000.;
            deltas.add(dt);
            deltas2.add(dt2);

            logger.log(String.format(Locale.US,
                    "beep detected, total latency = %.2f, normal latency = %.2f, "
                            + "initiatedBeeps = %d, detectedBeeps = %d",
                    dt, dt2, initiatedBeeps, detectedBeeps
            ));

            // Schedule another beep soon-ish
            handler.postDelayed(doBeepRunnable, period); // TODO: randomize the delay
        }
    };

    private Runnable doBeepRunnable = new Runnable() {
        @Override
        public void run() {
            // activity.handler.removeCallbacks(doBlinkRunnable);
            logger.log("\nBeeping...");
            // Check if we saw some transitions without beeping, might be noise audio cable.
            if (initiatedBeeps == 0 && detectedBeeps > 1) {
                logger.log("Unexpected beeps detected, noisy cable?");
                return;
            }

            if (initiatedBeeps >= playbackRepetitions) {
                finishPlaybackTest();
                return;
            }


            // deltas[initiatedBeeps] = 0;
            initiatedBeeps++;

            if (initiatedBeeps % playbackSyncAfterRepetitions == 0) {
                try {
                    waltDevice.stopListener();
                    waltDevice.syncClock();
                    waltDevice.startListener();
                } catch (IOException e) {
                    logger.log("Error re-syncing clock: " + e.getMessage());
                    finishPlaybackTest();
                    return;
                }
            }

            try {
                waltDevice.command(WaltDevice.CMD_AUDIO);
            } catch (IOException e) {
                logger.log("Error sending command AUDIO: " + e.getMessage());
                return;
            }
            long javaBeepTime = waltDevice.clock.micros();
            lastBeepTime = playTone();
            double dtJ2N = (lastBeepTime - javaBeepTime)/1000.;
            deltasJ2N.add(dtJ2N);
            logger.log(String.format(Locale.US, "Beeped, dtJ2N = %.3f ms", dtJ2N));


            // Repost doBeep to some far away time to blink again even if nothing arrives from
            // Teensy. This callback will almost always get cancelled by onIncomingTimestamp()
            handler.postDelayed(doBeepRunnable, period * 3); // TODO: config and or randomize the delay,

        }
    };


    private Runnable requestBeepRunnable = new Runnable() {
        @Override
        public void run() {
            // logger.log("\nRequesting beep from WALT...");
            String s;
            try {
                s = waltDevice.command(WaltDevice.CMD_BEEP);
            } catch (IOException e) {
                logger.log("Error sending command BEEP: " + e.getMessage());
                return;
            }
            last_tb = Integer.parseInt(s);
            logger.log("Beeped, reply: " + s);
            handler.postDelayed(processRecordingRunnable, msToRecord * 2); // TODO: config and or randomize the delay,
        }
    };

    private Runnable stopBeepRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                waltDevice.command(WaltDevice.CMD_BEEP_STOP);
            } catch (IOException e) {
                logger.log("Error stopping tone from WALT: " + e.getMessage());
            }
        }
    };

    private Runnable processRecordingRunnable = new Runnable() {
        @Override
        public void run() {
            long te = getTeRec();
            long tc = getTcRec();
            long tb= last_tb;
            short[] wave = getRecordedWave();
            int thresh = 20000;
            int noisyAtFrame = 0;
            while (noisyAtFrame < wave.length && wave[noisyAtFrame] < thresh)
                noisyAtFrame++;
            if (noisyAtFrame == wave.length) {
                logger.log("WARNING: No sound detected");
                doRecordingTestRepetition();
                return;
            }

            double duration_us = wave.length * 1e6 / frameRateInt;
            double delta_us = (wave.length - noisyAtFrame) * 1e6 / frameRateInt;
            double latencyCb_ms = (tc - tb - delta_us) / 1000.;
            double latencyEnqueue_ms = (tb - te - (duration_us - delta_us)) / 1000.;
            logger.log(String.format(Locale.US,
                    "Processed: L_cb = %.3f ms, L_eq = %.3f ms, noisy frame = %d",
                    latencyCb_ms,
                    latencyEnqueue_ms,
                    noisyAtFrame
            ));

            deltas.add(latencyCb_ms);
            doRecordingTestRepetition();
        }
    };

    private void finishPlaybackTest() {
        stopTests();
        waltDevice.stopListener();
        waltDevice.clearTriggerHandler();
        waltDevice.checkDrift();

        logger.log("deltas: " + deltas.toString());
        logger.log(String.format(Locale.US,
                "Median Java to native latency %.3f ms\nMedian total audio latency %.1f ms"
                        + "\nMedian callback to output time %.1f ms",
                Utils.median(deltasJ2N),
                Utils.median(deltas),
                Utils.median(deltas2)
        ));

        if (resultHandler != null) {
            resultHandler.onResult(deltas, deltas2);
        }
        if (testStateListener != null) testStateListener.onTestStopped();
    }

    private void finishRecordingTest() {
        waltDevice.checkDrift();

        logger.log("deltas: " + deltas.toString());
        logger.log(String.format(Locale.US,
                "Median audio recording latency %.1f ms",
                Utils.median(deltas)
        ));

        if (resultHandler != null) {
            resultHandler.onResult(deltas);
        }
        if (testStateListener != null) testStateListener.onTestStopped();
    }
}
