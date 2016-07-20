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

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Measurement of screen response time when switching between black and white.
 */
public class ScreenResponseFragment extends Fragment implements View.OnClickListener {

    private static final int curveTimeout = 1000;  // milliseconds
    private static final int curveBlinkTime = 250;  // milliseconds
    private MainActivity activity;
    private SimpleLogger logger;
    private ClockManager clockManager;
    private Handler handler = new Handler();
    TextView mBlackBox;
    int timesToBlink = 20; // TODO: load this from settings
    int mInitiatedBlinks = 0;
    int mDetectedBlinks = 0;
    boolean mIsBoxWhite = false;
    long mLastFlipTime;
    ArrayList<Double> deltas = new ArrayList<>();
    private static final int color_gray = Color.argb(0xFF, 0xBB, 0xBB, 0xBB);
    private StringBuilder brightnessCurveData = new StringBuilder();

    public ScreenResponseFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        clockManager = ClockManager.getInstance(getContext());
        logger = SimpleLogger.getInstance(getContext());
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_screen_response, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        // restartMeasurement();
        mBlackBox = (TextView) activity.findViewById(R.id.txt_black_box_screen);


        // Register this fragment class as the listener for some button clicks
        activity.findViewById(R.id.button_restart_screen_response).setOnClickListener(this);
        activity.findViewById(R.id.button_start_screen_response).setOnClickListener(this);
        activity.findViewById(R.id.button_brightness_curve).setOnClickListener(this);
    }


    void startMeasurement() {
        // TODO: Add a stop button to interrupt the measurement
        deltas.clear();

        try {
            clockManager.syncClock();
        } catch (IOException e) {
            logger.log("Error syncing clocks: " + e.getMessage());
            return;
        }

        mInitiatedBlinks = 0;
        mDetectedBlinks = 0;

        mBlackBox.setText("");
        mBlackBox.setBackgroundColor(Color.WHITE);
        mIsBoxWhite = true;

        handler.postDelayed(startBlinking, 300);
    }

    Runnable startBlinking = new Runnable() {
        @Override
        public void run() {
            try {
                // Check for PWM
                ClockManager.TriggerMessage tmsg = clockManager.readTriggerMessage(ClockManager.CMD_SEND_LAST_SCREEN);
                logger.log("Blink count was: "+ tmsg.count);
                clockManager.command(ClockManager.CMD_AUTO_SCREEN_ON);

                // Start the listener
                clockManager.syncClock();
                clockManager.startListener();
            } catch (IOException e) {
                logger.log("Error: " + e.getMessage());
            }

            // Register a callback for triggers
            clockManager.setTriggerHandler(triggerHandler);

            // post doBlink runnable
            handler.postDelayed(doBlinkRunnable, 100);
        }
    };

    Runnable doBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            logger.log("======\ndoBlink.run(), mInitiatedBlinks = " + mInitiatedBlinks + " mDetectedBlinks = " + mDetectedBlinks);
            // Check if we saw some transitions without blinking, this would usually mean
            // the screen has PWM enabled, warn and ask the user to turn it off.
            if (mInitiatedBlinks == 0 && mDetectedBlinks > 1) {
                logger.log("Unexpected blinks detected, probably PWM, turn it off");
                // TODO: show a dialog here instructing to turn off PWM and finish this properly
                return;
            }

            if (mInitiatedBlinks >= timesToBlink) {
                finishAndShowStats();
                return;
            }

            // * 2 flip the screen, save time as last flip time (last flip direction?)

            mIsBoxWhite = !mIsBoxWhite;
            int nextColor = mIsBoxWhite ? Color.WHITE : Color.BLACK;
            mInitiatedBlinks++;
            mBlackBox.setBackgroundColor(nextColor);
            mLastFlipTime = clockManager.micros(); // TODO: is this the right time to save?


            // Repost doBlink to some far away time to blink again even if nothing arrives from
            // Teensy. This callback will almost always get cancelled by onIncomingTimestamp()
            handler.postDelayed(doBlinkRunnable, 600); // TODO: config and or randomiz the delay,

        }
    };

    private ClockManager.TriggerHandler triggerHandler = new ClockManager.TriggerHandler() {
        @Override
        public void onReceive(ClockManager.TriggerMessage tmsg) {
            // Remove the far away doBlink callback
            handler.removeCallbacks(doBlinkRunnable);

            mDetectedBlinks++;
            logger.log("blink counts " + mInitiatedBlinks + " " + mDetectedBlinks);
            if (mInitiatedBlinks == 0) {
                if (mDetectedBlinks < 5) {
                    logger.log("got incoming but mInitiatedBlinks = 0");
                    return;
                } else {
                    logger.log("Looks like PWM is used for this screen, turn auto brightness off and set it to max brightness");
                    // TODO: show a modal dialog here saying the same as the log msg above

                    return;
                }
            }

            double dt = (tmsg.t - mLastFlipTime) / 1000.;
            deltas.add(dt);

            // Schedule another blink soon-ish
            handler.postDelayed(doBlinkRunnable, 50); // TODO: randomize the delay

        }
    };


    void finishAndShowStats() {
        // Stop the USB listener
        clockManager.stopListener();

        // Unregister trigger handler
        clockManager.clearTriggerHandler();

        clockManager.checkDrift();

        // Show deltas and the median
        logger.log("deltas: " + deltas.toString());
        double medianDelta = Utils.median(deltas);
        logger.log(String.format(
                "Median latency %.1f ms",
                medianDelta
        ));

        mBlackBox.setText(logger.getLogText());
        mBlackBox.setMovementMethod(new ScrollingMovementMethod());
        mBlackBox.setBackgroundColor(color_gray);

        // Show histogram of the results
        int [] hist = Utils.histogram(deltas, 0, 120, 1.0);
        String histLable = String.format("N=%d, median=%.1f ms", deltas.size() , medianDelta);
        HistogramFragment histogramFragment = new HistogramFragment();
        histogramFragment.addHist(hist, histLable);
        activity.switchScreen(histogramFragment, "Screen response stats");
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_restart_screen_response) {
            // TODO: change to "Stop measurement?"
            mBlackBox.setBackgroundColor(Color.BLACK);
            return;
        }

        if (v.getId() == R.id.button_start_screen_response) {
            logger.log("Starting screen response measurement");
            startMeasurement();
            return;
        }

        if (v.getId() == R.id.button_brightness_curve) {
            logger.log("Starting screen brightness curve measurement");
            startBrightnessCurve();
            return;
        }

    }

    private ClockManager.TriggerHandler brightnessTriggerHandler = new ClockManager.TriggerHandler() {
        @Override
        public void onReceive(ClockManager.TriggerMessage tmsg) {
            logger.log("ERROR: Brightness curve trigger got a trigger message, " +
                    "this should never happen."
            );
        }

        @Override
        public void onReceiveRaw(String s) {
            brightnessCurveData.append(s);
            if (s.trim().equals("end")) {
                // Remove the delayed callbed and run it now
                handler.removeCallbacks(finishBrightnessCurve);
                handler.post(finishBrightnessCurve);
            }
        }
    };

    void startBrightnessCurve() {
        try {
            clockManager.syncClock();
            clockManager.startListener();
        } catch (IOException e) {
            logger.log("Error starting test: " + e.getMessage());
            return;
        }

        clockManager.setTriggerHandler(brightnessTriggerHandler);

        mBlackBox.setText("");

        long tStart = clockManager.micros();

        try {
            clockManager.command(ClockManager.CMD_BRIGHTNESS_CURVE);
        } catch (IOException e) {
            logger.log("Error sending command CMD_BRIGHTNESS_CURVE: " + e.getMessage());
            return;
        }

        mBlackBox.setBackgroundColor(Color.WHITE);

        logger.log("=== Screen brightness curve: ===\nt_start: " + tStart);

        handler.postDelayed(finishBrightnessCurve, curveTimeout);

        // Schedule the screen to flip back to black in curveBlinkTime ms
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long tBack = clockManager.micros();
                mBlackBox.setBackgroundColor(Color.BLACK);
                logger.log("t_back: " + tBack);

            }
        }, curveBlinkTime);

    }

    Runnable finishBrightnessCurve = new Runnable() {
        @Override
        public void run() {
            clockManager.stopListener();
            clockManager.clearTriggerHandler();

            // TODO: Add option to save this data into a separate file rather than the main log.
            logger.log(brightnessCurveData.toString());
            logger.log("=== End of screen brightness data ===");

            mBlackBox.setText(logger.getLogText());
            mBlackBox.setMovementMethod(new ScrollingMovementMethod());
            mBlackBox.setBackgroundColor(color_gray);
        }
    };
}
