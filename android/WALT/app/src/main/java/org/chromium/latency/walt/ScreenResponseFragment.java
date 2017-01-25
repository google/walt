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
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import static org.chromium.latency.walt.Utils.getIntPreference;

/**
 * Measurement of screen response time when switching between black and white.
 */
public class ScreenResponseFragment extends Fragment implements View.OnClickListener {

    private static final int curveTimeout = 1000;  // milliseconds
    private static final int curveBlinkTime = 250;  // milliseconds
    private Activity activity;
    private SimpleLogger logger;
    private WaltDevice waltDevice;
    private Handler handler = new Handler();
    TextView blackBox;
    private int timesToBlink;
    int initiatedBlinks = 0;
    int detectedBlinks = 0;
    boolean isBoxWhite = false;
    long lastFrameStartTime;
    long lastFrameCallbackTime;
    long lastSetBackgroundTime;
    ArrayList<Double> deltas = new ArrayList<>();
    private static final int color_gray = Color.argb(0xFF, 0xBB, 0xBB, 0xBB);
    private StringBuilder brightnessCurveData = new StringBuilder();

    public ScreenResponseFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        timesToBlink = getIntPreference(getContext(), R.string.preference_screen_blinks, 20);
        activity = getActivity();
        waltDevice = WaltDevice.getInstance(getContext());
        logger = SimpleLogger.getInstance(getContext());
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_screen_response, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        // restartMeasurement();
        blackBox = (TextView) activity.findViewById(R.id.txt_black_box_screen);


        // Register this fragment class as the listener for some button clicks
        activity.findViewById(R.id.button_restart_screen_response).setOnClickListener(this);
        activity.findViewById(R.id.button_start_screen_response).setOnClickListener(this);
        activity.findViewById(R.id.button_brightness_curve).setOnClickListener(this);
    }


    void startMeasurement() {
        // TODO: Add a stop button to interrupt the measurement
        deltas.clear();

        initiatedBlinks = 0;
        detectedBlinks = 0;

        blackBox.setText("");
        blackBox.setBackgroundColor(Color.WHITE);
        isBoxWhite = true;

        handler.postDelayed(startBlinking, 300);
    }

    Runnable startBlinking = new Runnable() {
        @Override
        public void run() {
            try {
                // Check for PWM
                WaltDevice.TriggerMessage tmsg = waltDevice.readTriggerMessage(WaltDevice.CMD_SEND_LAST_SCREEN);
                logger.log("Blink count was: "+ tmsg.count);

                waltDevice.softReset();
                waltDevice.syncClock(); // Note, sync also sends CMD_RESET (but not simpleSync).
                waltDevice.command(WaltDevice.CMD_AUTO_SCREEN_ON);
                waltDevice.startListener();
            } catch (IOException e) {
                logger.log("Error: " + e.getMessage());
            }

            // Register a callback for triggers
            waltDevice.setTriggerHandler(triggerHandler);

            // post doBlink runnable
            handler.postDelayed(doBlinkRunnable, 100);
        }
    };

    Runnable doBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            logger.log("======\ndoBlink.run(), initiatedBlinks = " + initiatedBlinks + " detectedBlinks = " + detectedBlinks);
            // Check if we saw some transitions without blinking, this would usually mean
            // the screen has PWM enabled, warn and ask the user to turn it off.
            if (initiatedBlinks == 0 && detectedBlinks > 1) {
                logger.log("Unexpected blinks detected, probably PWM, turn it off");
                // TODO: show a dialog here instructing to turn off PWM and finish this properly
                return;
            }

            if (initiatedBlinks >= timesToBlink) {
                finishAndShowStats();
                return;
            }

            // * 2 flip the screen, save time as last flip time (last flip direction?)

            isBoxWhite = !isBoxWhite;
            int nextColor = isBoxWhite ? Color.WHITE : Color.BLACK;
            initiatedBlinks++;
            blackBox.setBackgroundColor(nextColor);
            lastSetBackgroundTime = waltDevice.clock.micros(); // TODO: is this the right time to save?

            // Set up a callback to run on next frame render to collect the timestamp
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    // frameTimeNanos is he time in nanoseconds when the frame started being
                    // rendered, in the nanoTime() timebase.
                    lastFrameStartTime = frameTimeNanos / 1000 - waltDevice.clock.baseTime;
                    lastFrameCallbackTime = System.nanoTime() / 1000 - waltDevice.clock.baseTime;
                }
            });


            // Repost doBlink to some far away time to blink again even if nothing arrives from
            // Teensy. This callback will almost always get cancelled by onIncomingTimestamp()
            handler.postDelayed(doBlinkRunnable, 600); // TODO: config and or randomiz the delay,

        }
    };

    private WaltDevice.TriggerHandler triggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
            // Remove the far away doBlink callback
            handler.removeCallbacks(doBlinkRunnable);

            detectedBlinks++;
            logger.log("blink counts " + initiatedBlinks + " " + detectedBlinks);
            if (initiatedBlinks == 0) {
                if (detectedBlinks < 5) {
                    logger.log("got incoming but initiatedBlinks = 0");
                    return;
                } else {
                    logger.log("Looks like PWM is used for this screen, turn auto brightness off and set it to max brightness");
                    // TODO: show a modal dialog here saying the same as the log msg above

                    return;
                }
            }


            double dt = (tmsg.t - lastFrameStartTime) / 1000.;
            deltas.add(dt);

            logger.log(String.format(Locale.US,
                    "Times [ms]: setBG:%.3f callback:%.3f physical:%.3f black2white:%d",
                    (lastSetBackgroundTime - lastFrameStartTime) / 1000.0 ,
                    (lastFrameCallbackTime - lastFrameStartTime) / 1000.0,
                    dt,
                    isBoxWhite ? 1: 0
            ));

            // Schedule another blink soon-ish
            handler.postDelayed(doBlinkRunnable, 50); // TODO: randomize the delay

        }
    };


    void finishAndShowStats() {
        // Stop the USB listener
        waltDevice.stopListener();

        // Unregister trigger handler
        waltDevice.clearTriggerHandler();

        waltDevice.sendAndFlush(WaltDevice.CMD_AUTO_SCREEN_OFF);

        waltDevice.checkDrift();

        // Show deltas and the median
        logger.log("deltas: " + deltas.toString());
        logger.log(String.format(Locale.US,
                "Median latency %.1f ms",
                Utils.median(deltas)
        ));

        blackBox.setText(logger.getLogText());
        blackBox.setMovementMethod(new ScrollingMovementMethod());
        blackBox.setBackgroundColor(color_gray);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_restart_screen_response) {
            // TODO: change to "Stop measurement?"
            blackBox.setBackgroundColor(Color.BLACK);
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

    private WaltDevice.TriggerHandler brightnessTriggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
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
            waltDevice.syncClock();
            waltDevice.startListener();
        } catch (IOException e) {
            logger.log("Error starting test: " + e.getMessage());
            return;
        }

        waltDevice.setTriggerHandler(brightnessTriggerHandler);

        blackBox.setText("");

        long tStart = waltDevice.clock.micros();

        try {
            waltDevice.command(WaltDevice.CMD_BRIGHTNESS_CURVE);
        } catch (IOException e) {
            logger.log("Error sending command CMD_BRIGHTNESS_CURVE: " + e.getMessage());
            return;
        }

        blackBox.setBackgroundColor(Color.WHITE);

        logger.log("=== Screen brightness curve: ===\nt_start: " + tStart);

        handler.postDelayed(finishBrightnessCurve, curveTimeout);

        // Schedule the screen to flip back to black in curveBlinkTime ms
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long tBack = waltDevice.clock.micros();
                blackBox.setBackgroundColor(Color.BLACK);
                logger.log("t_back: " + tBack);

            }
        }, curveBlinkTime);

    }

    Runnable finishBrightnessCurve = new Runnable() {
        @Override
        public void run() {
            waltDevice.stopListener();
            waltDevice.clearTriggerHandler();

            // TODO: Add option to save this data into a separate file rather than the main log.
            logger.log(brightnessCurveData.toString());
            logger.log("=== End of screen brightness data ===");

            blackBox.setText(logger.getLogText());
            blackBox.setMovementMethod(new ScrollingMovementMethod());
            blackBox.setBackgroundColor(color_gray);
        }
    };
}
