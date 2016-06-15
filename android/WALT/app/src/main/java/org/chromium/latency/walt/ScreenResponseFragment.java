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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.os.Handler;

import java.util.ArrayList;

/**
 * Measurement of screen response time when switching between black and white.
 */
public class ScreenResponseFragment extends Fragment implements View.OnClickListener {
    private Activity activity;
    private SimpleLogger logger;
    private ClockManager clockManager;
    private Handler handler = new Handler();
    private LocalBroadcastManager broadcastManager;
    TextView mBlackBox;
    int timesToBlink = 20; // TODO: load this from settings
    int mInitiatedBlinks = 0;
    int mDetectedBlinks = 0;
    boolean mIsBoxWhite = false;
    long mLastFlipTime;
    ArrayList<Double> deltas = new ArrayList<>();

    public ScreenResponseFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        activity = getActivity();
        clockManager = ClockManager.getInstance(getContext());
        logger = SimpleLogger.getInstance(getContext());
        broadcastManager = LocalBroadcastManager.getInstance(getContext());
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
    }


    void startMeasurement() {
        // TODO: Add a stop button to interrupt the measurement
        deltas.clear();

        mInitiatedBlinks = 0;
        mDetectedBlinks = 0;

        mBlackBox.setText("");
        mBlackBox.setBackgroundColor(Color.WHITE);
        mIsBoxWhite = true;
        clockManager.syncClock();
        handler.postDelayed(startBlinking, 300);
    }

    Runnable startBlinking = new Runnable() {
        @Override
        public void run() {
            // Check for PWM
            ClockManager.TriggerMessage tmsg = clockManager.readTriggerMessage(ClockManager.CMD_SEND_LAST_SCREEN);
            logger.log("Blink count was: "+ tmsg.count);

            clockManager.sendReceive(ClockManager.CMD_AUTO_SCREEN_ON);


            // Start the listener
            clockManager.syncClock();
            clockManager.startUsbListener();

            // Register a callback for broadcasts
            broadcastManager.registerReceiver(
                    onIncomingTimestamp,
                    new IntentFilter(clockManager.INCOMING_DATA_INTENT)
            );

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


    private BroadcastReceiver onIncomingTimestamp = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Remove the far away doBlink callback
            handler.removeCallbacks(doBlinkRunnable);

            // Save timestamp data
            String msg = intent.getStringExtra("message");
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

            ClockManager.TriggerMessage tmsg = clockManager.parseTriggerMessage(msg);
            double dt = (tmsg.t - mLastFlipTime) / 1000.;
            deltas.add(dt);

            // Schedule another blink soon-ish
            handler.postDelayed(doBlinkRunnable, 50); // TODO: randomize the delay

        }
    };

    void finishAndShowStats() {
        // Stop the USB listener
        clockManager.stopUsbListener();

        // Unregister broadcast receiver
        broadcastManager.unregisterReceiver(onIncomingTimestamp);

        // Show deltas and the median
        logger.log("deltas: " + deltas.toString());
        logger.log(String.format(
                "Median latency %.1f ms",
                Utils.median(deltas)
        ));

        mBlackBox.setText(logger.getLogText());
        mBlackBox.setMovementMethod(new ScrollingMovementMethod());
        mBlackBox.setBackgroundColor(Color.argb(0xFF, 0xBB, 0xBB, 0xBB));;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_restart_screen_response) {
            // TODO: change to "Stop measurement"
            return;
        }

        if (v.getId() == R.id.button_start_screen_response) {
            logger.log("Starting screen response measurement");
            startMeasurement();
            return;
        }

    }
}
