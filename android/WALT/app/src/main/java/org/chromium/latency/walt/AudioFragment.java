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
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.media.AudioTrack;

import java.util.ArrayList;


/**
 * A simple {@link Fragment} subclass.
 */
public class AudioFragment extends Fragment implements View.OnClickListener {

    static {
        System.loadLibrary("sync_clock_jni");
    }

    MainActivity activity;
    SimpleLogger logger;
    TextView mTextView;

    // Sound params
    private final double duration = 0.3; // seconds
    private final int sampleRate = 8000;
    private final int numSamples = (int) (duration * sampleRate);
    private final byte generatedSnd[] = new byte[2 * numSamples];
    private final double freqOfTone = 880; // hz

    // Audio in
    long last_tb = 0;
    int msToRecord = 1000;
    int framesToRecord;
    int frameRateInt;

    AudioTrack mAudioTrack;


    int mInitiatedBeeps, mDetectedBeeps;
    private final int timesToBeep = 10;
    // long[] deltas = new long[timesToBeep];
    ArrayList<Double> deltas = new ArrayList<>();
    ArrayList<Double> deltasJ2N = new ArrayList<>();

    long mLastBeepTime;

    public static native long playTone();
    public static native void stopPlaying();
    public static native void createEngine();
    public static native void createBufferQueueAudioPlayer(int frameRate, int framesPerBuffer);

    public static native void startRecording();
    public static native void createAudioRecorder(int frameRate, int framesToRecord);
    public static native short[] getRecordedWave();
    public static native long getTeRec();
    public static native long getTcRec();
    public static native long getTePlay();


    public AudioFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        activity = (MainActivity) getActivity();
        logger = activity.logger;

        //Check for optimal output sample rate and buffer size
        AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
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
        framesToRecord = (int) (0.001 * msToRecord * frameRateInt);
        createAudioRecorder(frameRateInt, framesToRecord);
        logger.log("Audio engine created");

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_audio, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        // restartMeasurement();
        mTextView = (TextView) activity.findViewById(R.id.txt_box_audio);

        // Register this fregment class as the listener for some button clicks
        ((ImageButton) activity.findViewById(R.id.button_start_audio_play)).setOnClickListener(this);
        ((ImageButton) activity.findViewById(R.id.button_start_audio_rec)).setOnClickListener(this);

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
        if (v.getId() == R.id.button_start_audio_rec) {
            startRecording();
            activity.handler.postDelayed(requestBeepRunnable, msToRecord / 2);
            return;
        }

        if (v.getId() == R.id.button_start_audio_play) {
            activity.logger.log("Starting audio measurement");
            startMeasurement();
            // activity.clockManager.startUsbListener();
            return;
        }

    }

    private BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            mTextView.append(msg + "\n");
        }
    };


    void startMeasurement() {
        activity.clockManager.syncClock();
        deltas.clear();

        // activity.handler.postDelayed(startBlinking, 300);
        logger.log("Starting");
        // genTone();

        mInitiatedBeeps = 0;
        mDetectedBeeps = 0;

        activity.clockManager.startUsbListener();

        activity.broadcastManager.registerReceiver(
                onIncomingTimestamp,
                new IntentFilter(activity.clockManager.INCOMING_DATA_INTENT)
        );

        activity.handler.postDelayed(doBeepRunnable, 300);

    }


    private BroadcastReceiver onIncomingTimestamp = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String msg = intent.getStringExtra("message");
            // logger.log("Incoming timestamp received " + msg);

            if (msg.charAt(0) == 'a') {
                // logger.log("Incoming ack on CMD_AUDIO");
                return;
            }

            // TODO: check that the msg starts like a serialized trigger "G" or "G A"
            // or allow the parseTriggerMessage below to raise something meaningful if it's not
            // remove the far away doBeep callback(s)
            activity.handler.removeCallbacks(doBeepRunnable);

            ClockManager.TriggerMessage tmsg = activity.clockManager.parseTriggerMessage(msg);
            mDetectedBeeps++;
            long te = getTePlay();
            double dt = (tmsg.t - mLastBeepTime) / 1000.;

            double dt2 = (tmsg.t - te) / 1000.;
            deltas.add(dt2);

            logger.log(String.format(
                    "beep detected, dt = %.2f, dt_enqueue = %.2f, mInitiatedBeeps = %d, mDetectedBeeps = %d",
                    dt, dt2, mInitiatedBeeps, mDetectedBeeps
            ));

            // Schedule another beep soon-ish
            activity.handler.postDelayed(doBeepRunnable, 500); // TODO: randomize the delay
        }
    };

    Runnable doBeepRunnable = new Runnable() {
        @Override
        public void run() {
            // activity.handler.removeCallbacks(doBlinkRunnable);
            activity.logger.log("\nBeeping...");
            // Check if we saw some transitions without beeping, might be noise audio cable.
            if (mInitiatedBeeps == 0 && mDetectedBeeps > 1) {
                activity.logger.log("Unexpected beeps detected, noisy cable?");
                return;
            }

            if (mInitiatedBeeps >= timesToBeep) {
                finishAndShowStats();
                return;
            }


            // deltas[mInitiatedBeeps] = 0;
            mInitiatedBeeps++;
            activity.clockManager.sendByte(ClockManager.CMD_AUDIO);
            long javaBeepTime = activity.clockManager.micros();
            mLastBeepTime = playTone();
            double dtJ2N = (mLastBeepTime - javaBeepTime)/1000.;
            deltasJ2N.add(dtJ2N);
            activity.logger.log(String.format("Beeped, dtJ2N = %.3f ms", dtJ2N));


            // Repost doBeep to some far away time to blink again even if nothing arrives from
            // Teensy. This callback will almost always get cancelled by onIncomingTimestamp()
            activity.handler.postDelayed(doBeepRunnable, 1500); // TODO: config and or randomiz the delay,

        }
    };


    Runnable requestBeepRunnable = new Runnable() {
        @Override
        public void run() {
            // activity.logger.log("\nRequesting beep from WALT...");
            String s = activity.clockManager.sendReceive(ClockManager.CMD_BEEP);
            if (s.charAt(0) != 'b') {
                logger.log("Error, got unexpected reply to CMD_BEEP: " + s);
                return;
            }
            last_tb = Integer.parseInt(s.trim().substring(2));
            activity.logger.log("Beeped, reply: " + s);
            activity.handler.postDelayed(processRecordingRunnable, msToRecord); // TODO: config and or randomiz the delay,
        }
    };

    Runnable processRecordingRunnable = new Runnable() {
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
                return;
            }

            double duration_us = wave.length * 1e6 / frameRateInt;
            double delta_us = (wave.length - noisyAtFrame) * 1e6 / frameRateInt;
            double latencyCb_ms = (tc - tb - delta_us) / 1000.;
            double latencyEnqueue_ms = (tb - te - (duration_us - delta_us)) / 1000.;
            logger.log(String.format(
                    "Processed: L_cb = %.3f ms, L_eq = %.3f ms, noisy frame = %d",
                    latencyCb_ms,
                    latencyEnqueue_ms,
                    noisyAtFrame
            ));
        }
    };

    void finishAndShowStats() {
        activity.clockManager.stopUsbListener();
        activity.broadcastManager.unregisterReceiver(onIncomingTimestamp);
        activity.clockManager.logDrift();

        activity.logger.log("deltas: " + deltas.toString());
        activity.logger.log(String.format(
                "Median Java to native latency %.3f ms\nMedian audio latency %.1f ms",
                Utils.median(deltasJ2N),
                Utils.median(deltas)
        ));
    }
}
