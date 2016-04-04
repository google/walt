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

public class DragLatencyFragment extends Fragment
    implements View.OnClickListener {

    SimpleLogger logger;

    TextView mLogTextView;
    MainActivity activity;
    TextView mTouchCatcher;
    int moveCount = 0;
    int allDownConunt = 0;
    int allUpConunt = 0;
    int okDownCount = 0;
    int okUpCount = 0;


    ArrayList<UsMotionEvent> touchEventList = new ArrayList<>();
    ArrayList<ClockManager.TriggerMessage> laserEventList = new ArrayList<>();


    private BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            DragLatencyFragment.this.appendLogText(msg);
        }
    };

    private View.OnTouchListener mTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {

            int action = event.getAction();

            int histLen = event.getHistorySize();
            for (int i = 0; i < histLen; i++){
                UsMotionEvent eh = new UsMotionEvent(event, activity.clockManager.baseTime, i);
                touchEventList.add(eh);
            }
            UsMotionEvent e = new UsMotionEvent(event, activity.clockManager.baseTime);
            touchEventList.add(e);
            moveCount += histLen + 1;

            updateCountsDisplay();
            return true;
        }
    };

    public DragLatencyFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        activity = (MainActivity) getActivity();
        View view =  inflater.inflate(R.layout.fragment_drag_latency, container, false);
        logger = activity.logger;

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        mLogTextView = (TextView) activity.findViewById(R.id.txt_log_drag_latency);
        mLogTextView.setText(activity.logger.getLogText());
        activity.logger.broadcastManager.registerReceiver(mLogReceiver,
                new IntentFilter(activity.logger.LOG_INTENT));

        // Register this fregment class as the listener for some button clicks
        ((ImageButton)activity.findViewById(R.id.button_restart_drag)).setOnClickListener(this);
        ((ImageButton)activity.findViewById(R.id.button_start_drag)).setOnClickListener(this);
        ((ImageButton)activity.findViewById(R.id.button_finish_drag)).setOnClickListener(this);

        mTouchCatcher = (TextView) activity.findViewById(R.id.tap_catcher);
    }

    @Override
    public void onPause() {
        activity.logger.broadcastManager.unregisterReceiver(mLogReceiver);
        super.onPause();
    }

    public void appendLogText(String msg) {
        mLogTextView.append(msg + "\n");
    }


    void updateCountsDisplay() {
        TextView tv = (TextView) activity.findViewById(R.id.txt_cross_counts);
        tv.setText(String.format("⤯ %d", laserEventList.size()));

        TextView tvMove = (TextView) activity.findViewById(R.id.txt_drag_counts);
        tvMove.setText(String.format("⇄ %d", moveCount));
    }


    void startMeasurement() {
        logger.log("Starting drag latency test");
        activity.clockManager.syncClock();
        mTouchCatcher.setOnTouchListener(mTouchListener);
        activity.clockManager.sendReceive(ClockManager.CMD_AUTO_LASER_ON);
        // Register a callback for broadcasts
        activity.broadcastManager.registerReceiver(
                onIncomingTimestamp,
                new IntentFilter(activity.clockManager.INCOMING_DATA_INTENT)
        );
        activity.clockManager.startUsbListener();
    }


    void restartMeasurement() {
        activity.logger.log("\n## Restarting tap latency  measurement. Re-sync clocks ...");
        activity.clockManager.syncClock();

        touchEventList.clear();

        moveCount = 0;
        allDownConunt = 0;
        allUpConunt = 0;
        okDownCount = 0;
        okUpCount = 0;

        updateCountsDisplay();
    }


    void finishAndShowStats() {
        activity.clockManager.stopUsbListener();
        activity.clockManager.sendReceive(ClockManager.CMD_AUTO_LASER_OFF);
        mTouchCatcher.setOnTouchListener(null);
        activity.broadcastManager.unregisterReceiver(onIncomingTimestamp);

        logger.log(String.format(
                "Recorded %d laser events and %d touch events. ",
                laserEventList.size(),
                touchEventList.size()
        ));

        if (touchEventList.size() < 100) {
            logger.log("Insufficient number of touch events (<100), aborting.");
            return;
        }

        if (laserEventList.size() < 8) {
            logger.log("Insufficient number of laser events (<8), aborting.");
            return;
        }

        // TODO: Log raw data if enabled in settings, touch events add lots of text to the log.
        // logRawData();
        reshapeAndCalculate();
    }

    // Data formatted for processing with python script, y.py
    void logRawData() {
        logger.log("#####> LASER EVENTS #####");
        for (int i = 0; i < laserEventList.size(); i++){
            logger.log(laserEventList.get(i).t + " " + laserEventList.get(i).value);
        }
        logger.log("#####< END OF LASER EVENTS #####");

        logger.log("=====> TOUCH EVENTS =====");
        for (UsMotionEvent e: touchEventList) {
            logger.log(String.format(
                    "%d %.3f %.3f",
                    e.kernelTime,
                    e.x, e.y
            ));
        }
        logger.log("=====< END OF TOUCH EVENTS =====");
    }


    void reshapeAndCalculate() {
        double[] ft, lt; // All time arrays are in _milliseconds_
        double[] fy;
        int[] ldir;

        // Use the time of the first touch event as time = 0 for debugging convenience
        long t0_us = touchEventList.get(0).kernelTime;
        long tLast_us = touchEventList.get(touchEventList.size() - 1).kernelTime;

        int fN = touchEventList.size();
        ft = new double[fN];
        fy = new double[fN];

        for (int i = 0; i < fN; i++){
            ft[i] = (touchEventList.get(i).kernelTime - t0_us) / 1000.;
            fy[i] = touchEventList.get(i).y;
        }

        // Remove all laser events that are outside the time span of the touch events
        // they are not usable and would result in errors downstream
        int j = laserEventList.size() - 1;
        while (j >= 0 && laserEventList.get(j).t > tLast_us) {
            laserEventList.remove(j);
            j--;
        }

        while (laserEventList.size() > 0 && laserEventList.get(0).t < t0_us) {
            laserEventList.remove(0);
        }

        int lN = laserEventList.size();

        if (lN < 8) {
            logger.log("ERROR: Insufficient number of laser events overlapping with touch events," +
                            "aborting."
            );
            return;
        }

        lt = new double[lN];
        ldir = new int[lN];
        for (int i = 0; i < lN; i++){
            lt[i] = (laserEventList.get(i).t - t0_us) / 1000.;
            ldir[i] = laserEventList.get(i).value;
        }

        calculateDragLatency(ft,fy, lt, ldir);
    }


    /**
     * Handler for all the button clicks on this screen.
     */
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_restart_drag) {
            restartMeasurement();
            return;
        }

        if (v.getId() == R.id.button_start_drag) {

            startMeasurement();
            return;
        }

        if (v.getId() == R.id.button_finish_drag) {

            finishAndShowStats();
            return;
        }

    }


    private BroadcastReceiver onIncomingTimestamp = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String msg = intent.getStringExtra("message");
            logger.log("Incoming timestamp received: " + msg.trim());


            ClockManager.TriggerMessage tmsg = activity.clockManager.parseTriggerMessage(msg);

            laserEventList.add(tmsg);
            updateCountsDisplay();

        }
    };

    public void calculateDragLatency(double[] ft, double[] fy, double[] lt, int[] ldir) {
        // TODO: throw away several first laser crossings (if not already)
        double[] ly = Utils.interp(lt, ft, fy);
        double lmid = Utils.mean(ly);
        // Assume first crossing is into the beam = light-off = 0
        if (ldir[0] != 0) {
            // TODO: add more sanity checks here.
            logger.log("First laser crossing is not into the beam, aborting");
            return;
        }

        // label sides, one simple label is i starts from 1, then side = (i mod 4) / 2  same as the 2nd LSB bit or i.
        int[] sideIdx = new int[lt.length];

        // This is one way of deciding what laser events were on which side
        // It should go above, below, below, above, above
        // The other option is to mirror the python code that uses position and velocity for this
        for (int i = 0; i<lt.length; i++) {
            sideIdx[i] = ((i+1) / 2) % 2;
        }
        /*
        logger.log("ft = " + Utils.array2string(ft, "%.2f"));
        logger.log("fy = " + Utils.array2string(fy, "%.2f"));
        logger.log("lt = " + Utils.array2string(lt, "%.2f"));
        logger.log("sideIdx = " + Arrays.toString(sideIdx));*/

        double averageBestShift = 0;
        for(int side = 0; side < 2; side++) {
            double[] lts = Utils.extract(sideIdx, side, lt);
            // TODO: time this call
            double bestShift = Utils.findBestShift(lts, ft, fy);
            logger.log("bestShift = " + bestShift);
            averageBestShift += bestShift / 2;
        }

        logger.log(String.format("Drag latency is %.1f [ms]", averageBestShift));
    }


}
