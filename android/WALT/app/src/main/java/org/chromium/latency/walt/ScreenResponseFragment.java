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
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.chromium.latency.walt.Utils.getBooleanPreference;
import static org.chromium.latency.walt.Utils.getIntPreference;

/**
 * Measurement of screen response time when switching between black and white.
 */
public class ScreenResponseFragment extends Fragment implements View.OnClickListener {

    private static final int CURVE_TIMEOUT = 1000;  // milliseconds
    private static final int CURVE_BLINK_TIME = 250;  // milliseconds
    private static final int CURVE_BACKLIGHT_STEP_TIMEOUT = 2000;  // milliseconds
    private static final int CURVE_BACKLIGHT_STEP_TIME = 350;  // milliseconds
    private static final int MAX_BACKLIGHT = 255;
    private static final int BACKLIGHT_STEP_LEVEL = 5;

    private static final int W2B_INDEX = 0;
    private static final int B2W_INDEX = 1;
    private SimpleLogger logger;
    private WaltDevice waltDevice;
    private Handler handler = new Handler();
    private TextView blackBox;
    private View startButton;
    private View stopButton;
    private Spinner spinner;
    private LineChart brightnessChart;
    private LineChart backlightChart;
    private HistogramChart latencyChart;
    private View brightnessChartLayout;
    private int timesToBlink;
    private boolean shouldShowLatencyChart = false;
    private boolean isTestRunning = false;
    int initiatedBlinks = 0;
    int detectedBlinks = 0;
    boolean isBoxWhite = false;
    long lastFrameStartTime;
    long lastFrameCallbackTime;
    long lastSetBackgroundTime;
    ArrayList<Double> deltas_w2b = new ArrayList<>();
    ArrayList<Double> deltas_b2w = new ArrayList<>();
    ArrayList<Double> deltas = new ArrayList<>();
    private static final int color_gray = Color.argb(0xFF, 0xBB, 0xBB, 0xBB);
    private StringBuilder brightnessCurveData;
    private StringBuilder backlightCurveData;
    private float currentbacklight = 0;
    private float savebacklight;
    private List<Entry> B_entries;

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isTestRunning) {
                String msg = intent.getStringExtra("message");
                blackBox.append(msg + "\n");
            }
        }
    };

    public ScreenResponseFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        timesToBlink = getIntPreference(getContext(), R.string.preference_screen_blinks, 20);
        shouldShowLatencyChart = getBooleanPreference(getContext(), R.string.preference_show_blink_histogram, true);
        waltDevice = WaltDevice.getInstance(getContext());
        logger = SimpleLogger.getInstance(getContext());

        // Inflate the layout for this fragment
        final View view = inflater.inflate(R.layout.fragment_screen_response, container, false);
        stopButton = view.findViewById(R.id.button_stop_screen_response);
        startButton = view.findViewById(R.id.button_start_screen_response);
        blackBox = (TextView) view.findViewById(R.id.txt_black_box_screen);
        spinner = (Spinner) view.findViewById(R.id.spinner_screen_response);
        ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(getContext(),
                R.array.screen_response_mode_array, android.R.layout.simple_spinner_item);
        modeAdapter.setDropDownViewResource(R.layout.support_simple_spinner_dropdown_item);
        spinner.setAdapter(modeAdapter);
        stopButton.setEnabled(false);
        blackBox.setMovementMethod(new ScrollingMovementMethod());
        brightnessChartLayout = view.findViewById(R.id.brightness_chart_layout);
        view.findViewById(R.id.button_close_chart).setOnClickListener(this);
        brightnessChart = (LineChart) view.findViewById(R.id.chart);
        backlightChart = (LineChart) view.findViewById(R.id.chart);
        latencyChart = (HistogramChart) view.findViewById(R.id.latency_chart);

        if (getBooleanPreference(getContext(), R.string.preference_auto_increase_brightness, true)) {
            increaseScreenBrightness();
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        logger.registerReceiver(logReceiver);
        // Register this fragment class as the listener for some button clicks
        startButton.setOnClickListener(this);
        stopButton.setOnClickListener(this);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(logReceiver);
        super.onPause();
    }

    void startBlinkLatency() {
        deltas.clear();
        deltas_b2w.clear();
        deltas_w2b.clear();
        if (shouldShowLatencyChart) {
            latencyChart.clearData();
            latencyChart.setVisibility(View.VISIBLE);
            latencyChart.setLabel(W2B_INDEX, "White-to-black");
            latencyChart.setLabel(B2W_INDEX, "Black-to-white");
        }
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
                logger.log("Blink count was: " + tmsg.count);

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
            if (!isTestRunning) return;
            logger.log("======\ndoBlink.run(), initiatedBlinks = " + initiatedBlinks + " detectedBlinks = " + detectedBlinks);
            // Check if we saw some transitions without blinking, this would usually mean
            // the screen has PWM enabled, warn and ask the user to turn it off.
            if (initiatedBlinks == 0 && detectedBlinks > 1) {
                logger.log("Unexpected blinks detected, probably PWM, turn it off");
                // TODO: show a dialog here instructing to turn off PWM and finish this properly
                isTestRunning = false;
                stopButton.setEnabled(false);
                startButton.setEnabled(true);
                return;
            }

            if (initiatedBlinks >= timesToBlink) {
                isTestRunning = false;
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
            if (isBoxWhite) {  // Current color is the color we transitioned to
                deltas_b2w.add(dt);
            } else {
                deltas_w2b.add(dt);
            }
            if (shouldShowLatencyChart) latencyChart.addEntry(isBoxWhite ? B2W_INDEX : W2B_INDEX, dt);

            // Other times can be important, logging them to allow more detailed analysis
            logger.log(String.format(Locale.US,
                    "Times [ms]: setBG:%.3f callback:%.3f physical:%.3f black2white:%d",
                    (lastSetBackgroundTime - lastFrameStartTime) / 1000.0,
                    (lastFrameCallbackTime - lastFrameStartTime) / 1000.0,
                    dt,
                    isBoxWhite ? 1 : 0
            ));

            // Schedule another blink soon-ish
            handler.postDelayed(doBlinkRunnable, 50); // TODO: randomize the delay and allow config

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
        /* // Debug printouts
        logger.log("deltas = array(" + deltas.toString() + ")");
        logger.log("deltas_w2b = array(" + deltas_w2b.toString() + ")");
        logger.log("deltas_b2w = array(" + deltas_b2w.toString() + ")");
        */

        double median_b2w = Utils.median(deltas_b2w);
        double median_w2b = Utils.median(deltas_w2b);
        logger.log(String.format(Locale.US,
                "\n-------------------------------\n" +
                        "Median screen response latencies (N=%d):\n" +
                        "Black to white: %.1f ms (N=%d)\n" +
                        "White to black: %.1f ms (N=%d)\n" +
                        "Average: %.1f ms\n" +
                        "-------------------------------\n",
                deltas.size(),
                median_b2w, deltas_b2w.size(),
                median_w2b, deltas_w2b.size(),
                (median_b2w + median_w2b) / 2
        ));

        blackBox.setText(logger.getLogText());
        blackBox.setMovementMethod(new ScrollingMovementMethod());
        blackBox.setBackgroundColor(color_gray);
        stopButton.setEnabled(false);
        startButton.setEnabled(true);
        if (shouldShowLatencyChart) {
            latencyChart.setLabel(W2B_INDEX, String.format(Locale.US, "White-to-black m=%.1f ms", median_w2b));
            latencyChart.setLabel(B2W_INDEX, String.format(Locale.US, "Black-to-white m=%.1f ms", median_b2w));
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_stop_screen_response) {
            isTestRunning = false;
            handler.removeCallbacks(doBlinkRunnable);
            handler.removeCallbacks(startBlinking);
            handler.removeCallbacks(stepBacklight);
            handler.removeCallbacks(runBacklight);
            if (spinner.getSelectedItemPosition() == 2) {
		if (getBooleanPreference(getContext(), R.string.preference_auto_increase_brightness, true))
			increaseScreenBrightness();
	    }
            finishAndShowStats();
            return;
        }

        if (v.getId() == R.id.button_start_screen_response) {
            brightnessChartLayout.setVisibility(View.GONE);
            latencyChart.setVisibility(View.GONE);
            if (!waltDevice.isConnected()) {
                logger.log("Error starting test: WALT is not connected");
                return;
            }

            isTestRunning = true;
            startButton.setEnabled(false);
            blackBox.setBackgroundColor(Color.BLACK);
            blackBox.setText("");
            if (spinner.getSelectedItemPosition() == 0) {
                logger.log("Starting screen response measurement");
                stopButton.setEnabled(true);
                startBlinkLatency();
            } else if (spinner.getSelectedItemPosition() == 1) {
                logger.log("Starting screen brightness curve measurement");
                startBrightnessCurve();
            } else {
                logger.log("Starting screen backlight measurement");
                stopButton.setEnabled(true);
                startBacklightCurve();
            }
            return;
        }

        if (v.getId() == R.id.button_close_chart) {
            brightnessChartLayout.setVisibility(View.GONE);
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
                // Remove the delayed callback and run it now
                handler.removeCallbacks(finishBrightnessCurve);
                handler.post(finishBrightnessCurve);
            }
        }
    };

    void startBrightnessCurve() {
        try {
            brightnessCurveData = new StringBuilder();
            waltDevice.syncClock();
            waltDevice.startListener();
        } catch (IOException e) {
            logger.log("Error starting test: " + e.getMessage());
            isTestRunning = false;
            startButton.setEnabled(true);
            return;
        }
        blackBox.setText("");
        blackBox.setBackgroundColor(Color.BLACK);
        handler.postDelayed(startBrightness, CURVE_BLINK_TIME);
    }

    Runnable startBrightness = new Runnable() {
        @Override
        public void run() {
            waltDevice.setTriggerHandler(brightnessTriggerHandler);
            long tStart = waltDevice.clock.micros();

            try {
                waltDevice.command(WaltDevice.CMD_BRIGHTNESS_CURVE);
            } catch (IOException e) {
                logger.log("Error sending command CMD_BRIGHTNESS_CURVE: " + e.getMessage());
                isTestRunning = false;
                startButton.setEnabled(true);
                return;
            }

            blackBox.setBackgroundColor(Color.WHITE);

            logger.log("=== Screen brightness curve: ===\nt_start: " + tStart);

            handler.postDelayed(finishBrightnessCurve, CURVE_TIMEOUT);

            // Schedule the screen to flip back to black in CURVE_BLINK_TIME ms
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    long tBack = waltDevice.clock.micros();
                    blackBox.setBackgroundColor(Color.BLACK);
                    logger.log("t_back: " + tBack);

                }
            }, CURVE_BLINK_TIME);
        }
    };

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
            isTestRunning = false;
            startButton.setEnabled(true);
            drawBrightnessChart();
        }
    };

    private void drawBrightnessChart() {
        final String brightnessCurveString = brightnessCurveData.toString();
        List<Entry> entries = new ArrayList<>();

        // "u" marks the start of the brightness curve data
        int startIndex = brightnessCurveString.indexOf("u") + 1;
        int endIndex = brightnessCurveString.indexOf("end");
        if (endIndex == -1) endIndex = brightnessCurveString.length();

        String[] brightnessStrings =
                brightnessCurveString.substring(startIndex, endIndex).trim().split("\n");
        for (String str : brightnessStrings) {
            String[] arr = str.split(" ");
            final float timestampMs = Integer.parseInt(arr[0]) / 1000f;
            final float brightness = Integer.parseInt(arr[1]);
            entries.add(new Entry(timestampMs, brightness));
        }
        LineDataSet dataSet = new LineDataSet(entries, "Brightness");
        dataSet.setColor(Color.BLACK);
        dataSet.setValueTextColor(Color.BLACK);
        dataSet.setCircleColor(Color.BLACK);
        dataSet.setCircleRadius(1.5f);
        dataSet.setCircleColorHole(Color.DKGRAY);
        LineData lineData = new LineData(dataSet);
        brightnessChart.setData(lineData);
        final Description desc = new Description();
        desc.setText("Screen Brightness [digital level 0-1023] vs. Time [ms]");
        desc.setTextSize(12f);
        brightnessChart.setDescription(desc);
        brightnessChart.getLegend().setEnabled(false);
        brightnessChart.invalidate();
        brightnessChartLayout.setVisibility(View.VISIBLE);
    }

    private void increaseScreenBrightness() {
        final WindowManager.LayoutParams layoutParams = getActivity().getWindow().getAttributes();
        layoutParams.screenBrightness = 1f;
        getActivity().getWindow().setAttributes(layoutParams);
    }

/**
 * Measurement of full range screen brightness variation linearity
 */
    private void setScreenBrightness(float value) {
        final WindowManager.LayoutParams layoutParams = getActivity().getWindow().getAttributes();
        layoutParams.screenBrightness = value;
        getActivity().getWindow().setAttributes(layoutParams);
    }

    private float getScreenBrightness() {
        final WindowManager.LayoutParams layoutParams = getActivity().getWindow().getAttributes();
        return (layoutParams.screenBrightness);
    }

    private WaltDevice.TriggerHandler BacklightTriggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
            logger.log("ERROR: Backlight curve trigger got a trigger message, " +
                    "this should never happen."
            );
        }

        @Override
        public void onReceiveRaw(String s) {

            backlightCurveData.append(s);

            if (s.trim().equals("end")) {
                // Remove the delayed callback and run it now
                handler.removeCallbacks(runBacklight);
                handler.post(runBacklight);
            }
        }
    };

    void startBacklightCurve() {
        logger.log("=== Screen startBacklightCurve: === ");

        try {
            backlightCurveData = new StringBuilder();
            waltDevice.syncClock();
            waltDevice.startListener();
        } catch (IOException e) {
            logger.log("Error starting test: " + e.getMessage());
            isTestRunning = false;
            startButton.setEnabled(true);
            return;
        }
        blackBox.setText("");
        blackBox.setBackgroundColor(Color.WHITE);
	// TODO: restore initial brightness value
	// savebacklight = getScreenBrightness();
        // logger.log("== Backlight saved value: " + savebacklight);

	B_entries = new ArrayList<>();
	currentbacklight = 0;
	setScreenBrightness(currentbacklight);

	// wait long time before first measure for stable low backlight initial state
        handler.postDelayed(stepBacklight, CURVE_BACKLIGHT_STEP_TIME*10);
    }

    Runnable stepBacklight = new Runnable() {
        @Override
        public void run() {
            waltDevice.setTriggerHandler(BacklightTriggerHandler);
            long tStart = waltDevice.clock.micros();

            try {
                waltDevice.command(WaltDevice.CMD_BRIGHTNESS_CURVE);
            } catch (IOException e) {
                logger.log("Error sending command CMD_BRIGHTNESS_CURVE: " + e.getMessage());
                isTestRunning = false;
                startButton.setEnabled(true);
                return;
            }

            logger.log("=== Backlight step: ===\nt_start: " + tStart);

            handler.postDelayed(runBacklight, CURVE_BACKLIGHT_STEP_TIMEOUT);
        }
    };

    Runnable runBacklight = new Runnable() {
        @Override
        public void run() {

            waltDevice.clearTriggerHandler();
	    getBacklightLevel();
            backlightCurveData.setLength(0);

	    currentbacklight += BACKLIGHT_STEP_LEVEL;
	    if (currentbacklight > MAX_BACKLIGHT){
		handler.post(finishBacklightCurve);

	    } else {
		setScreenBrightness(currentbacklight/255);
		handler.postDelayed(stepBacklight, CURVE_BACKLIGHT_STEP_TIME);
	    }
        }
    };


    Runnable finishBacklightCurve = new Runnable() {
        @Override
        public void run() {
            waltDevice.stopListener();

            // TODO: Add option to save this data into a separate file rather than the main log.
            logger.log(backlightCurveData.toString());
            logger.log("=== End of screen backlight data ===");

            blackBox.setText(logger.getLogText());
            blackBox.setMovementMethod(new ScrollingMovementMethod());
            blackBox.setBackgroundColor(color_gray);
            isTestRunning = false;
            startButton.setEnabled(true);
            drawBacklightChart();
	    // TODO: restore initial brightness value
	    // setScreenBrightness(savebacklight);

        }
    };

    private void getBacklightLevel() {
        final String backlightCurveString = backlightCurveData.toString();

        float backlight_max = 0;
        // "u" marks the start of the brightness curve data
        int startIndex = backlightCurveString.indexOf("u") + 1;
        int endIndex = backlightCurveString.indexOf("end");
        if (endIndex == -1) endIndex = backlightCurveString.length();

        String[] backlightStrings =
                backlightCurveString.substring(startIndex, endIndex).trim().split("\n");

        for (String str : backlightStrings) {
            String[] arr = str.split(" ");
            final float backlight = Integer.parseInt(arr[1]);
	    if (backlight > backlight_max)
		backlight_max = backlight;
        }
        B_entries.add(new Entry(currentbacklight, backlight_max));
        logger.log("=== Set entries backlight data "+ currentbacklight+" => " + backlight_max);

    }

    private void drawBacklightChart() {

        LineDataSet dataSet = new LineDataSet(B_entries, "Backlight");
        dataSet.setColor(Color.BLACK);
        dataSet.setValueTextColor(Color.BLACK);
        dataSet.setCircleColor(Color.BLACK);
        dataSet.setCircleRadius(1.5f);
        dataSet.setCircleColorHole(Color.DKGRAY);
        LineData lineData = new LineData(dataSet);
        backlightChart.setData(lineData);
        final Description desc = new Description();
        desc.setText("Screen Backlight [level 0-255]");
        desc.setTextSize(12f);
        backlightChart.setDescription(desc);
        backlightChart.getLegend().setEnabled(false);
        backlightChart.invalidate();
        brightnessChartLayout.setVisibility(View.VISIBLE);
    }

}
