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
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public class TapLatencyFragment extends Fragment
    implements View.OnClickListener {

    private SimpleLogger logger;
    private WaltDevice waltDevice;
    private TextView logTextView;
    private TextView tapCatcherView;
    private TextView tapCountsView;
    private TextView moveCountsView;
    private ImageButton finishButton;
    private ImageButton restartButton;
    private int moveCount = 0;
    private int allDownCount = 0;
    private int allUpCount = 0;
    private int okDownCount = 0;
    private int okUpCount = 0;

    ArrayList<UsMotionEvent> eventList = new ArrayList<>();

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
            String action = tapEvent.getActionString();

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

            if (tapEvent.action == MotionEvent.ACTION_DOWN) {
                allDownCount++;
                if (tapEvent.isOk) {
                    okDownCount++;
                }
            } else if (tapEvent.action == MotionEvent.ACTION_UP) {
                allUpCount++;
                if (tapEvent.isOk) {
                    okUpCount++;
                }
            }

            updateCountsDisplay();
            return true;
        }
    };

    public TapLatencyFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
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
        double dt_k2c = (e.createTime - e.kernelTime) / 1000.0;

        if (e.physicalTime == 0) {
            logger.log(action + " no shock found");
            return false;
        }

        if (dt < 0 || dt > 200) {
            logger.log(action + " bogus kernelTime, ignored, dt=" + dt);
            return  false;
        }

        logger.log(String.format(Locale.US,
                "%s:\ntouch2kernel: %.1f ms\nkernel2java: %.1f ms",
                action, dt, dt_k2c
        ));
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
            return;
        }

        eventList.clear();

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

        // TODO: Here we should fire up a new fragment with histogram(s)
        // For now do the stats here and save them to log

        logger.log("ACTION_DOWN median times:");
        printStats(MotionEvent.ACTION_DOWN);
        logger.log("ACTION_UP median times:");
        printStats(MotionEvent.ACTION_UP);
        logger.log("-------------------------------");
    }

    private void printStats(int action) {
        ArrayList<Double> p2k = new ArrayList<>();
        ArrayList<Double> k2c = new ArrayList<>();

        for (UsMotionEvent event : eventList) {
            if (event == null || event.action != action || !event.isOk) continue;

            // physical to kernel
            p2k.add((event.kernelTime - event.physicalTime) / 1000.);

            // kernel to callback
            k2c.add((event.createTime - event.kernelTime) / 1000.);
        }

        logger.log(String.format(Locale.US,
                "   Touch to kernel: %.1f ms\n" +
                "   Kernel to Java: %.1f ms",
                Utils.median(p2k),
                Utils.median(k2c)
        ));
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_restart_tap) {
            restartButton.setImageResource(R.drawable.ic_refresh_black_24dp);
            finishButton.setEnabled(true);
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
