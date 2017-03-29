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
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import static org.chromium.latency.walt.Utils.getBooleanPreference;

public class TapLatencyFragment extends Fragment
    implements View.OnClickListener {

    private static final int ACTION_DOWN_INDEX = 0;
    private static final int ACTION_UP_INDEX = 1;
    private SimpleLogger logger;
    private TraceLogger traceLogger;
    private WaltDevice waltDevice;
    private TextView logTextView;
    private TextView tapCatcherView;
    private TextView tapCountsView;
    private TextView moveCountsView;
    private ImageButton finishButton;
    private ImageButton restartButton;
    private HistogramChart latencyChart;
    private int moveCount = 0;
    private int allDownCount = 0;
    private int allUpCount = 0;
    private int okDownCount = 0;
    private int okUpCount = 0;
    private boolean shouldShowLatencyChart = false;

    ArrayList<UsMotionEvent> eventList = new ArrayList<>();
    ArrayList<Double> p2kDown = new ArrayList<>();
    ArrayList<Double> p2kUp = new ArrayList<>();
    ArrayList<Double> k2cDown = new ArrayList<>();
    ArrayList<Double> k2cUp = new ArrayList<>();

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            TapLatencyFragment.this.appendLogText(msg);
        }
    };

    private View.OnTouchListener touchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            UsMotionEvent tapEvent = new UsMotionEvent(event, waltDevice.clock.baseTime);

            if(tapEvent.action != MotionEvent.ACTION_UP && tapEvent.action != MotionEvent.ACTION_DOWN) {
                moveCount++;
                updateCountsDisplay();
                return true;
            }

            // Debug: logger.log("\n"+ action + " event received: " + tapEvent.toStringLong());
            tapEvent.physicalTime = waltDevice.readLastShockTime();

            tapEvent.isOk = checkTapSanity(tapEvent);
            // Save it in any case so we can do stats on bad events later
            eventList.add(tapEvent);

            final double physicalToKernelTime = (tapEvent.kernelTime - tapEvent.physicalTime) / 1000.;
            final double kernelToCallbackTime = (tapEvent.createTime - tapEvent.kernelTime) / 1000.;
            if (tapEvent.action == MotionEvent.ACTION_DOWN) {
                allDownCount++;
                if (tapEvent.isOk) {
                    okDownCount++;
                    p2kDown.add(physicalToKernelTime);
                    k2cDown.add(kernelToCallbackTime);
                    if (shouldShowLatencyChart) latencyChart.addEntry(ACTION_DOWN_INDEX, physicalToKernelTime);
                    logger.log(String.format(Locale.US,
                            "ACTION_DOWN:\ntouch2kernel: %.1f ms\nkernel2java: %.1f ms",
                            physicalToKernelTime, kernelToCallbackTime));
                }
            } else if (tapEvent.action == MotionEvent.ACTION_UP) {
                allUpCount++;
                if (tapEvent.isOk) {
                    okUpCount++;
                    p2kUp.add(physicalToKernelTime);
                    k2cUp.add(kernelToCallbackTime);
                    if (shouldShowLatencyChart) latencyChart.addEntry(ACTION_UP_INDEX, physicalToKernelTime);
                    logger.log(String.format(Locale.US,
                            "ACTION_UP:\ntouch2kernel: %.1f ms\nkernel2java: %.1f ms",
                            physicalToKernelTime, kernelToCallbackTime));
                }
            }
            traceLogEvent(tapEvent);

            updateCountsDisplay();
            return true;
        }
    };

    private void traceLogEvent(UsMotionEvent tapEvent) {
        if (!tapEvent.isOk) return;
        if (traceLogger == null) return;
        if (tapEvent.action != MotionEvent.ACTION_DOWN && tapEvent.action != MotionEvent.ACTION_UP) return;
        final String title = tapEvent.action == MotionEvent.ACTION_UP ? "Tap-Up" : "Tap-Down";
        traceLogger.log(tapEvent.physicalTime + waltDevice.clock.baseTime,
                tapEvent.kernelTime + waltDevice.clock.baseTime, title + " Physical",
                "Bar starts at accelerometer shock and ends at kernel time of tap event");
        traceLogger.log(tapEvent.kernelTime + waltDevice.clock.baseTime,
                tapEvent.createTime + waltDevice.clock.baseTime, title + " App Callback",
                "Bar starts at kernel time of tap event and ends at app callback time");
    }

    public TapLatencyFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        shouldShowLatencyChart = getBooleanPreference(getContext(), R.string.preference_show_tap_histogram, true);
        if (getBooleanPreference(getContext(), R.string.preference_systrace, true)) {
            traceLogger = TraceLogger.getInstance();
        }
        waltDevice = WaltDevice.getInstance(getContext());
        logger = SimpleLogger.getInstance(getContext());
        // Inflate the layout for this fragment
        final View view = inflater.inflate(R.layout.fragment_tap_latency, container, false);
        restartButton = (ImageButton) view.findViewById(R.id.button_restart_tap);
        finishButton = (ImageButton) view.findViewById(R.id.button_finish_tap);
        tapCatcherView = (TextView) view.findViewById(R.id.tap_catcher);
        logTextView = (TextView) view.findViewById(R.id.txt_log_tap_latency);
        tapCountsView = (TextView) view.findViewById(R.id.txt_tap_counts);
        moveCountsView = (TextView) view.findViewById(R.id.txt_move_count);
        latencyChart = (HistogramChart) view.findViewById(R.id.latency_chart);
        logTextView.setMovementMethod(new ScrollingMovementMethod());
        finishButton.setEnabled(false);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        logTextView.setText(logger.getLogText());
        logger.registerReceiver(logReceiver);

        // Register this fragment class as the listener for some button clicks
        restartButton.setOnClickListener(this);
        finishButton.setOnClickListener(this);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(logReceiver);
        super.onPause();
    }

    public void appendLogText(String msg) {
        logTextView.append(msg + "\n");
    }

    public boolean checkTapSanity(UsMotionEvent e) {
        String action = e.getActionString();
        double dt = (e.kernelTime - e.physicalTime) / 1000.0;

        if (e.physicalTime == 0) {
            logger.log(action + " no shock found");
            return false;
        }

        if (dt < 0 || dt > 200) {
            logger.log(action + " bogus kernelTime, ignored, dt=" + dt);
            return  false;
        }
        return true;
    }

    void updateCountsDisplay() {
        String tpl = "N ↓%d (%d)  ↑%d (%d)";
        tapCountsView.setText(String.format(Locale.US,
                tpl,
                okDownCount,
                allDownCount,
                okUpCount,
                allUpCount
                ));

        moveCountsView.setText(String.format(Locale.US, "⇄ %d", moveCount));
    }

    void restartMeasurement() {
        logger.log("\n## Restarting tap latency measurement. Re-sync clocks ...");
        try {
            waltDevice.softReset();
            waltDevice.syncClock();
        } catch (IOException e) {
            logger.log("Error syncing clocks: " + e.getMessage());
            restartButton.setImageResource(R.drawable.ic_play_arrow_black_24dp);
            finishButton.setEnabled(false);
            latencyChart.setVisibility(View.GONE);
            return;
        }

        eventList.clear();
        p2kDown.clear();
        p2kUp.clear();
        k2cDown.clear();
        k2cUp.clear();

        moveCount = 0;
        allDownCount = 0;
        allUpCount = 0;
        okDownCount = 0;
        okUpCount = 0;

        updateCountsDisplay();
        tapCatcherView.setOnTouchListener(touchListener);
    }

    void finishAndShowStats() {
        tapCatcherView.setOnTouchListener(null);
        waltDevice.checkDrift();
        logger.log("\n-------------------------------");
        logger.log(String.format(Locale.US,
                "Tap latency results:\n" +
                        "Number of events recorded:\n" +
                        "   ACTION_DOWN %d (bad %d)\n" +
                        "   ACTION_UP %d (bad %d)\n" +
                        "   ACTION_MOVE %d",
                okDownCount,
                allDownCount - okDownCount,
                okUpCount,
                allUpCount - okUpCount,
                moveCount
        ));

        logger.log("ACTION_DOWN median times:");
        logger.log(String.format(Locale.US,
                "   Touch to kernel: %.1f ms\n   Kernel to Java: %.1f ms",
                Utils.median(p2kDown),
                Utils.median(k2cDown)
        ));
        logger.log("ACTION_UP median times:");
        logger.log(String.format(Locale.US,
                "   Touch to kernel: %.1f ms\n   Kernel to Java: %.1f ms",
                Utils.median(p2kUp),
                Utils.median(k2cUp)
        ));
        logger.log("-------------------------------");
        if (traceLogger != null) traceLogger.flush(getContext());

        if (shouldShowLatencyChart) {
            latencyChart.setLabel(ACTION_DOWN_INDEX, String.format(Locale.US, "ACTION_DOWN median=%.1f ms", Utils.median(p2kDown)));
            latencyChart.setLabel(ACTION_UP_INDEX, String.format(Locale.US, "ACTION_UP median=%.1f ms", Utils.median(p2kUp)));
        }
        LogUploader.uploadIfAutoEnabled(getContext());
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_restart_tap) {
            restartButton.setImageResource(R.drawable.ic_refresh_black_24dp);
            finishButton.setEnabled(true);
            if (shouldShowLatencyChart) {
                latencyChart.setVisibility(View.VISIBLE);
                latencyChart.clearData();
                latencyChart.setLabel(ACTION_DOWN_INDEX, "ACTION_DOWN");
                latencyChart.setLabel(ACTION_UP_INDEX, "ACTION_UP");
            }
            restartMeasurement();
            return;
        }

        if (v.getId() == R.id.button_finish_tap) {
            finishButton.setEnabled(false);
            finishAndShowStats();
            restartButton.setImageResource(R.drawable.ic_play_arrow_black_24dp);
            return;
        }

    }
}
