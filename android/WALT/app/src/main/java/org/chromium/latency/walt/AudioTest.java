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

class AudioTest {

    static {
        System.loadLibrary("sync_clock_jni");
    }

    private SimpleLogger logger;
    private ClockManager clockManager;
    private Handler handler = new Handler();

    private AutoRunFragment.ResultHandler resultHandler;

    // Sound params
    private final double duration = 0.3; // seconds
    private final int sampleRate = 8000;
    private final int numSamples = (int) (duration * sampleRate);
    private final byte generatedSnd[] = new byte[2 * numSamples];
    private final double freqOfTone = 880; // hz

    // Audio in
    private long last_tb = 0;
    private int msToRecord = 1000;
    private int framesToRecord;
    private int frameRateInt;

    private int mInitiatedBeeps, mDetectedBeeps;
    private int mPlaybackRepetitions = 10;
    private static final int playbackSyncAfterRepetitions = 20;

    // Audio out
    private int mRequestedBeeps;
    private int mRecordingRepetitions = 5;
    private static int recorderSyncAfterRepetitions = 10;

    private ArrayList<Double> deltas = new ArrayList<>();
    private ArrayList<Double> deltasJ2N = new ArrayList<>();

    long mLastBeepTime;

    public static native long playTone();
    // public static native void stopPlaying();
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
        clockManager = ClockManager.getInstance(context);
        logger = SimpleLogger.getInstance(context);

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
        mPlaybackRepetitions = beepCount;
    }

    void setRecordingRepetitions(int beepCount) {
        mRecordingRepetitions = beepCount;
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

        mRequestedBeeps = 0;
        doRecordingTestRepetition();
    }

    private void doRecordingTestRepetition() {
        if (mRequestedBeeps > mRecordingRepetitions) {
            finishRecordingTest();
            return;
        }

        if (mRequestedBeeps % recorderSyncAfterRepetitions == 0) {
            try {
                clockManager.syncClock();
            } catch (IOException e) {
                logger.log("Error syncing clocks: " + e.getMessage());
                finishRecordingTest();
                return;
            }
        }

        mRequestedBeeps++;
        startRecording();
        handler.postDelayed(requestBeepRunnable, msToRecord / 2);
    }

    void startMeasurement() {
        try {
            clockManager.syncClock();
            clockManager.startListener();
        } catch (IOException e) {
            logger.log("Error starting test: " + e.getMessage());
            return;
        }
        deltas.clear();
        deltasJ2N.clear();

        logger.log("Starting playback test");

        mInitiatedBeeps = 0;
        mDetectedBeeps = 0;

        clockManager.setTriggerHandler(triggerHandler);

        handler.postDelayed(doBeepRunnable, 300);

    }

    private ClockManager.TriggerHandler triggerHandler = new ClockManager.TriggerHandler() {
        @Override
        public void onReceive(ClockManager.TriggerMessage tmsg) {
            // remove the far away doBeep callback(s)
            handler.removeCallbacks(doBeepRunnable);

            mDetectedBeeps++;
            long te = getTePlay();
            double dt = (tmsg.t - mLastBeepTime) / 1000.;

            double dt2 = (tmsg.t - te) / 1000.;
            deltas.add(dt2);

            logger.log(String.format(Locale.US,
                    "beep detected, dt = %.2f, dt_enqueue = %.2f, mInitiatedBeeps = %d, mDetectedBeeps = %d",
                    dt, dt2, mInitiatedBeeps, mDetectedBeeps
            ));

            // Schedule another beep soon-ish
            handler.postDelayed(doBeepRunnable, 500); // TODO: randomize the delay
        }
    };

    private Runnable doBeepRunnable = new Runnable() {
        @Override
        public void run() {
            // activity.handler.removeCallbacks(doBlinkRunnable);
            logger.log("\nBeeping...");
            // Check if we saw some transitions without beeping, might be noise audio cable.
            if (mInitiatedBeeps == 0 && mDetectedBeeps > 1) {
                logger.log("Unexpected beeps detected, noisy cable?");
                return;
            }

            if (mInitiatedBeeps >= mPlaybackRepetitions) {
                finishPlaybackTest();
                return;
            }


            // deltas[mInitiatedBeeps] = 0;
            mInitiatedBeeps++;

            if (mInitiatedBeeps % playbackSyncAfterRepetitions == 0) {
                try {
                    clockManager.stopListener();
                    clockManager.syncClock();
                    clockManager.startListener();
                } catch (IOException e) {
                    logger.log("Error re-syncing clock: " + e.getMessage());
                    finishPlaybackTest();
                    return;
                }
            }

            try {
                clockManager.command(ClockManager.CMD_AUDIO);
            } catch (IOException e) {
                logger.log("Error sending command AUDIO: " + e.getMessage());
                return;
            }
            long javaBeepTime = clockManager.micros();
            mLastBeepTime = playTone();
            double dtJ2N = (mLastBeepTime - javaBeepTime)/1000.;
            deltasJ2N.add(dtJ2N);
            logger.log(String.format(Locale.US, "Beeped, dtJ2N = %.3f ms", dtJ2N));


            // Repost doBeep to some far away time to blink again even if nothing arrives from
            // Teensy. This callback will almost always get cancelled by onIncomingTimestamp()
            handler.postDelayed(doBeepRunnable, 1500); // TODO: config and or randomize the delay,

        }
    };


    private Runnable requestBeepRunnable = new Runnable() {
        @Override
        public void run() {
            // logger.log("\nRequesting beep from WALT...");
            String s;
            try {
                s = clockManager.command(ClockManager.CMD_BEEP);
            } catch (IOException e) {
                logger.log("Error sending command BEEP: " + e.getMessage());
                return;
            }
            last_tb = Integer.parseInt(s);
            logger.log("Beeped, reply: " + s);
            handler.postDelayed(processRecordingRunnable, msToRecord); // TODO: config and or randomize the delay,
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
        clockManager.stopListener();
        clockManager.clearTriggerHandler();
        clockManager.checkDrift();

        logger.log("deltas: " + deltas.toString());
        logger.log(String.format(Locale.US,
                "Median Java to native latency %.3f ms\nMedian audio latency %.1f ms",
                Utils.median(deltasJ2N),
                Utils.median(deltas)
        ));

        if (resultHandler != null) {
            resultHandler.onResult(deltas);
        }
    }

    private void finishRecordingTest() {
        clockManager.checkDrift();

        logger.log("deltas: " + deltas.toString());
        logger.log(String.format(Locale.US,
                "Median audio recording latency %.1f ms",
                Utils.median(deltas)
        ));

        if (resultHandler != null) {
            resultHandler.onResult(deltas);
        }
    }
}
