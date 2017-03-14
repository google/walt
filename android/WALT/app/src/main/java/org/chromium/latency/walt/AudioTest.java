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
    private boolean userStoppedTest = false;

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
    private final int frameRate;
    private final int framesPerBuffer;

    private int initiatedBeeps, detectedBeeps;
    private int playbackRepetitions;
    private static final int playbackSyncAfterRepetitions = 20;

    // Audio out
    private int requestedBeeps;
    private int recordingRepetitions;
    private static int recorderSyncAfterRepetitions = 10;
    private final int threshold;

    ArrayList<Double> deltas_mic = new ArrayList<>();
    private ArrayList<Double> deltas_play2queue = new ArrayList<>();
    ArrayList<Double> deltas_queue2wire = new ArrayList<>();
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
        threshold = getIntPreference(context, R.string.preference_audio_in_threshold, 5000);

        //Check for optimal output sample rate and buffer size
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        String frameRateStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        String framesPerBufferStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        logger.log("Optimal frame rate is: " + frameRateStr);
        logger.log("Optimal frames per buffer is: " + framesPerBufferStr);

        //Convert to ints
        frameRate = Integer.parseInt(frameRateStr);
        framesPerBuffer = Integer.parseInt(framesPerBufferStr);

        //Create the audio engine
        createEngine();
        createBufferQueueAudioPlayer(frameRate, framesPerBuffer);
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

    AudioMode getAudioMode() {
        return audioMode;
    }

    int getOptimalFrameRate() {
        return frameRate;
    }

    int getThreshold() {
        return threshold;
    }

    void stopTest() {
        userStoppedTest = true;
    }

    void teardown() {
        destroyEngine();
        logger.log("Audio engine destroyed");
    }

    void beginRecordingMeasurement() {
        userStoppedTest = false;
        deltas_mic.clear();
        deltas_play2queue.clear();
        deltas_queue2wire.clear();
        deltasJ2N.clear();

        int framesToRecord = (int) (0.001 * msToRecord * frameRate);
        createAudioRecorder(frameRate, framesToRecord);
        logger.log("Audio recorder created; starting test");

        requestedBeeps = 0;
        doRecordingTestRepetition();
    }

    private void doRecordingTestRepetition() {
        if (requestedBeeps >= recordingRepetitions || userStoppedTest) {
            finishRecordingMeasurement();
            return;
        }

        if (requestedBeeps % recorderSyncAfterRepetitions == 0) {
            try {
                waltDevice.syncClock();
            } catch (IOException e) {
                logger.log("Error syncing clocks: " + e.getMessage());
                if (testStateListener != null) testStateListener.onTestStoppedWithError();
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

    void beginPlaybackMeasurement() {
        userStoppedTest = false;
        if (audioMode == AudioMode.CONTINUOUS) {
            startWarmTest();
        }
        try {
            waltDevice.syncClock();
            waltDevice.startListener();
        } catch (IOException e) {
            logger.log("Error starting test: " + e.getMessage());
            if (testStateListener != null) testStateListener.onTestStoppedWithError();
            return;
        }
        deltas_mic.clear();
        deltas_play2queue.clear();
        deltas_queue2wire.clear();
        deltasJ2N.clear();

        logger.log("Starting playback test");

        initiatedBeeps = 0;
        detectedBeeps = 0;

        waltDevice.setTriggerHandler(playbackTriggerHandler);

        handler.postDelayed(playBeepRunnable, 300);
    }

    private WaltDevice.TriggerHandler playbackTriggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
            // remove the far away playBeep callback(s)
            handler.removeCallbacks(playBeepRunnable);

            detectedBeeps++;
            long enqueueTime = getTePlay() - waltDevice.clock.baseTime;
            double dt_play2queue = (enqueueTime - lastBeepTime) / 1000.;
            deltas_play2queue.add(dt_play2queue);

            double dt_queue2wire = (tmsg.t - enqueueTime) / 1000.;
            deltas_queue2wire.add(dt_queue2wire);

            logger.log(String.format(Locale.US,
                    "Beep detected, initiatedBeeps=%d, detectedBeeps=%d\n" +
                            "dt native playTone to Enqueue = %.2f ms\n" +
                            "dt Enqueue to wire  = %.2f ms\n",
                    initiatedBeeps, detectedBeeps,
                    dt_play2queue,
                    dt_queue2wire
            ));

            if (traceLogger != null) {
                traceLogger.log(lastBeepTime + waltDevice.clock.baseTime,
                        enqueueTime + waltDevice.clock.baseTime,
                        "Play-to-queue",
                        "Bar starts at play time, ends when enqueued");
                traceLogger.log(enqueueTime + waltDevice.clock.baseTime,
                        tmsg.t + waltDevice.clock.baseTime,
                        "Enqueue-to-wire",
                        "Bar starts at enqueue time, ends when beep is detected");
            }
            if (testStateListener != null) testStateListener.onTestPartialResult(dt_queue2wire);

            // Schedule another beep soon-ish
            handler.postDelayed(playBeepRunnable, (long) (period + Math.random() * 50 - 25));
        }
    };

    private Runnable playBeepRunnable = new Runnable() {
        @Override
        public void run() {
            // debug: logger.log("\nPlaying tone...");

            // Check if we saw some transitions without beeping, might be noise audio cable.
            if (initiatedBeeps == 0 && detectedBeeps > 1) {
                logger.log("Unexpected beeps detected, noisy cable?");
                return;
            }

            if (initiatedBeeps >= playbackRepetitions || userStoppedTest) {
                finishPlaybackMeasurement();
                return;
            }

            initiatedBeeps++;

            if (initiatedBeeps % playbackSyncAfterRepetitions == 0) {
                try {
                    waltDevice.stopListener();
                    waltDevice.syncClock();
                    waltDevice.startListener();
                } catch (IOException e) {
                    logger.log("Error re-syncing clock: " + e.getMessage());
                    finishPlaybackMeasurement();
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
            lastBeepTime = playTone() - waltDevice.clock.baseTime;
            double dtJ2N = (lastBeepTime - javaBeepTime)/1000.;
            deltasJ2N.add(dtJ2N);
            if (traceLogger != null) {
                traceLogger.log(javaBeepTime + waltDevice.clock.baseTime,
                        lastBeepTime + waltDevice.clock.baseTime, "Java-to-native",
                        "Bar starts when Java tells native to beep and ends when buffer written in native");
            }
            logger.log(String.format(Locale.US,
                    "Called playTone(), dt Java to native = %.3f ms",
                    dtJ2N
            ));


            // Repost doBeep to some far away time to blink again even if nothing arrives from
            // Teensy. This callback will almost always get cancelled by onIncomingTimestamp()
            handler.postDelayed(playBeepRunnable, (long) (period * 3 + Math.random() * 100 - 50));

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
            handler.postDelayed(processRecordingRunnable, (long) (msToRecord * 2 + Math.random() * 100 - 50));
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
            long te = getTeRec() - waltDevice.clock.baseTime;  // When a buffer was enqueued for recording
            long tc = getTcRec() - waltDevice.clock.baseTime;  // When callback receiving a recorded buffer fired
            long tb = last_tb;  // When WALT started a beep (according to WALT clock)
            short[] wave = getRecordedWave();
            int noisyAtFrame = 0;  // First frame when some noise starts
            while (noisyAtFrame < wave.length && wave[noisyAtFrame] < threshold)
                noisyAtFrame++;
            if (noisyAtFrame == wave.length) {
                logger.log("WARNING: No sound detected");
                doRecordingTestRepetition();
                return;
            }

            // Length of recorded buffer
            double duration_us = wave.length * 1e6 / frameRate;

            // Duration in microseconds of the initial silent part of the buffer, and the remaining
            // part after the beep started.
            double silent_us =  noisyAtFrame * 1e6 / frameRate;
            double remaining_us = duration_us - silent_us;

            // Time from the last frame in the buffer until the callback receiving the buffer fired
            double latencyCb_ms = (tc - tb - remaining_us) / 1000.;

            // Time from the moment a buffer was enqueued for recording until the first frame in
            // the buffer was recorded
            double latencyEnqueue_ms = (tb - te - silent_us) / 1000.;

            logger.log(String.format(Locale.US,
                    "Processed: L_cb = %.3f ms, L_eq = %.3f ms, noisy frame = %d",
                    latencyCb_ms,
                    latencyEnqueue_ms,
                    noisyAtFrame
            ));

            if (testStateListener != null) testStateListener.onTestPartialResult(latencyCb_ms);
            if (traceLogger != null) {
                traceLogger.log((long) (tb + waltDevice.clock.baseTime + remaining_us),
                        tc + waltDevice.clock.baseTime,
                        "Beep-to-rec-callback",
                        "Bar starts when WALT plays beep and ends when recording callback received");
            }

            deltas_mic.add(latencyCb_ms);
            doRecordingTestRepetition();
        }
    };

    private void finishPlaybackMeasurement() {
        stopTests();
        waltDevice.stopListener();
        waltDevice.clearTriggerHandler();
        waltDevice.checkDrift();

        // Debug: logger.log("deltas_play2queue = array(" + deltas_play2queue.toString() +")");
        logger.log(String.format(Locale.US,
                "\n%s audio playback results:\n" +
                        "Detected %d beeps out of %d initiated\n" +
                        "Median Java to native time is %.3f ms\n" +
                        "Median native playTone to Enqueue time is %.1f ms\n" +
                        "Buffer length is %d frames at %d Hz = %.2f ms\n" +
                        "-------------------------------\n" +
                        "Median time from Enqueue to wire is %.1f ms\n" +
                        "-------------------------------\n",
                audioMode == AudioMode.COLD? "Cold" : "Continuous",
                detectedBeeps, initiatedBeeps,
                Utils.median(deltasJ2N),
                Utils.median(deltas_play2queue),
                framesPerBuffer, frameRate, 1000.0 / frameRate * framesPerBuffer,
                Utils.median(deltas_queue2wire)
        ));

        if (resultHandler != null) {
            resultHandler.onResult(deltas_play2queue, deltas_queue2wire);
        }
        if (testStateListener != null) testStateListener.onTestStopped();
        if (traceLogger != null) traceLogger.flush(context);
    }

    private void finishRecordingMeasurement() {
        waltDevice.checkDrift();

        // Debug: logger.log("deltas_mic: " + deltas_mic.toString());

        logger.log(String.format(Locale.US,
                "\nAudio recording/microphone results:\n" +
                        "Recorded %d beeps.\n" +
                        "-------------------------------\n" +
                        "Median callback latency - " +
                        "time from sampling the last frame to recorder callback is %.1f ms\n" +
                        "-------------------------------\n",
                deltas_mic.size(),
                Utils.median(deltas_mic)
        ));

        if (resultHandler != null) {
            resultHandler.onResult(deltas_mic);
        }
        if (testStateListener != null) testStateListener.onTestStopped();
        if (traceLogger != null) traceLogger.flush(context);
    }
}
