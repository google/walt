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
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;

public class TapLatencyFragment extends Fragment
    implements View.OnClickListener {
    TextView mLogTextView;
    MainActivity activity;
    TextView mTapCatcher;
    int moveCount = 0;
    int allDownConunt = 0;
    int allUpConunt = 0;
    int okDownCount = 0;
    int okUpCount = 0;


    ArrayList<UsMotionEvent> eventList = new ArrayList<UsMotionEvent>();
    HashMap<Integer, Integer> tapCounts = new HashMap<>();


    private BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            TapLatencyFragment.this.appendLogText(msg);
        }
    };

    private View.OnTouchListener mTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            UsMotionEvent tapEvent = new UsMotionEvent(event, activity.clockManager.baseTime);
            activity.logger.log("\nTouch event received: " + tapEvent.toStringLong());
            tapEvent.physicalTime = activity.clockManager.readLastShockTime();
            String action = tapEvent.getActionString();

            if(tapEvent.action != MotionEvent.ACTION_UP && tapEvent.action != MotionEvent.ACTION_DOWN) {
                moveCount++;
                activity.logger.log(action + " " + moveCount);
                updateCountsDisplay();
                return true;
            }

            tapEvent.isOk = checkTapSanity(tapEvent);
            // Save it in any case so we can do stats on bad events later
            eventList.add(tapEvent);

            if (tapEvent.action == MotionEvent.ACTION_DOWN) {
                allDownConunt++;
                if (tapEvent.isOk) {
                    okDownCount++;
                }
            } else if (tapEvent.action == MotionEvent.ACTION_UP) {
                allUpConunt++;
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
        // Inflate the layout for this fragment
        activity = (MainActivity) getActivity();
        View view =  inflater.inflate(R.layout.fragment_tap_latency, container, false);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        restartMeasurement();
        mLogTextView = (TextView) activity.findViewById(R.id.txt_log_tap_latency);

        mLogTextView.setText(activity.logger.getLogText());
        activity.logger.broadcastManager.registerReceiver(mLogReceiver,
                new IntentFilter(activity.logger.LOG_INTENT));

        // Register this fregment class as the listener for some button clicks
        ((ImageButton)activity.findViewById(R.id.button_restart_tap)).setOnClickListener(this);
        ((ImageButton)activity.findViewById(R.id.button_finish_tap)).setOnClickListener(this);

        mTapCatcher = (TextView) activity.findViewById(R.id.tap_catcher);
        mTapCatcher.setOnTouchListener(mTouchListener);
    }

    @Override
    public void onPause() {
        activity.logger.broadcastManager.unregisterReceiver(mLogReceiver);
        super.onPause();
    }

    public void appendLogText(String msg) {
        mLogTextView.append(msg + "\n");
    }

    public boolean checkTapSanity(UsMotionEvent e) {
        String action = e.getActionString();
        double dt = (e.kernelTime - e.physicalTime) / 1000.0;

        if (e.physicalTime == 0) {
            activity.logger.log(action + " no shock found");
            return false;
        }

        if (dt < 0 || dt > 200) {
            activity.logger.log(action + " bogus kernelTime, ignored, dt=" + dt);
            return  false;
        }

        activity.logger.log(String.format("%s: dt_p2k = %.1f ms", action, dt));
        return true;
    }

    void updateCountsDisplay() {
        TextView tv = (TextView) activity.findViewById(R.id.txt_tap_counts);
        String tpl = "N ↓%d (%d)  ↑%d (%d)";
        tv.setText(String.format(tpl,
                okDownCount,
                allDownConunt,
                okUpCount,
                allUpConunt
                ));

        TextView tvMove = (TextView) activity.findViewById(R.id.txt_move_count);
        tvMove.setText(String.format("⇄ %d", moveCount));
    }

    void restartMeasurement() {
        activity.logger.log("\n## Restarting tap latency  measurement. Re-sync clocks ...");
        activity.clockManager.syncClock();

        eventList.clear();

        moveCount = 0;
        allDownConunt = 0;
        allUpConunt = 0;
        okDownCount = 0;
        okUpCount = 0;

        updateCountsDisplay();
    }

    void finishAndShowStats() {
        activity.logger.log("\n\n## Processing tap latency data");
        activity.logger.log(String.format(
                "Counts: ACTION_DOWN %d (bad %d), ACTION_UP %d (bad %d), ACTION_MOVE %d",
                okDownCount,
                allDownConunt - okDownCount,
                okUpCount,
                allUpConunt - okUpCount,
                moveCount
        ));


        // Check drift
        activity.clockManager.updateBounds();
        int minE = activity.clockManager.getMinE();
        int maxE = activity.clockManager.getMaxE();
        activity.logger.log(String.format("Remote clock delayed between %d and %d us", minE, maxE));
        // TODO: check the drift and display warning if too high. Optionally interpolate drift as linear.

        // TODO: Here we should fire up a new fragment with histogram(s)
        // For now do the stats here and save them to log

        activity.logger.log("\nACTION_DOWN:");
        printStats(MotionEvent.ACTION_DOWN);
        activity.logger.log("\nACTION_UP:");
        printStats(MotionEvent.ACTION_UP);

        restartMeasurement();
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

        activity.logger.log(p2k.toString());
        activity.logger.log(k2c.toString());

        activity.logger.log(String.format(
                "Medians, p2k & k2c [ms]: %.1f    %.1f",
                Utils.median(p2k),
                Utils.median(k2c)
        ));
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_restart_tap) {
            restartMeasurement();
            return;
        }

        if (v.getId() == R.id.button_finish_tap) {
            finishAndShowStats();
            return;
        }

    }
}
