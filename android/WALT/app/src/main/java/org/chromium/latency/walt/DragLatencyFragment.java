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
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.mikephil.charting.charts.ScatterChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.ScatterData;
import com.github.mikephil.charting.data.ScatterDataSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public class DragLatencyFragment extends Fragment implements View.OnClickListener {

    private SimpleLogger logger;
    private WaltDevice waltDevice;
    private TextView logTextView;
    private TouchCatcherView touchCatcher;
    private TextView crossCountsView;
    private TextView dragCountsView;
    private View startButton;
    private View restartButton;
    private View finishButton;
    private ScatterChart latencyChart;
    private View latencyChartLayout;
    int moveCount = 0;

    ArrayList<UsMotionEvent> touchEventList = new ArrayList<>();
    ArrayList<WaltDevice.TriggerMessage> laserEventList = new ArrayList<>();


    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            DragLatencyFragment.this.appendLogText(msg);
        }
    };

    private View.OnTouchListener touchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int histLen = event.getHistorySize();
            for (int i = 0; i < histLen; i++){
                UsMotionEvent eh = new UsMotionEvent(event, waltDevice.clock.baseTime, i);
                touchEventList.add(eh);
            }
            UsMotionEvent e = new UsMotionEvent(event, waltDevice.clock.baseTime);
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
        logger = SimpleLogger.getInstance(getContext());
        waltDevice = WaltDevice.getInstance(getContext());

        // Inflate the layout for this fragment
        final View view = inflater.inflate(R.layout.fragment_drag_latency, container, false);
        logTextView = (TextView) view.findViewById(R.id.txt_log_drag_latency);
        startButton = view.findViewById(R.id.button_start_drag);
        restartButton = view.findViewById(R.id.button_restart_drag);
        finishButton = view.findViewById(R.id.button_finish_drag);
        touchCatcher = (TouchCatcherView) view.findViewById(R.id.tap_catcher);
        crossCountsView = (TextView) view.findViewById(R.id.txt_cross_counts);
        dragCountsView = (TextView) view.findViewById(R.id.txt_drag_counts);
        latencyChart = (ScatterChart) view.findViewById(R.id.latency_chart);
        latencyChartLayout = view.findViewById(R.id.latency_chart_layout);
        logTextView.setMovementMethod(new ScrollingMovementMethod());
        view.findViewById(R.id.button_close_chart).setOnClickListener(this);
        restartButton.setEnabled(false);
        finishButton.setEnabled(false);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        logTextView.setText(logger.getLogText());
        logger.registerReceiver(logReceiver);

        // Register this fragment class as the listener for some button clicks
        startButton.setOnClickListener(this);
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

    void updateCountsDisplay() {
        crossCountsView.setText(String.format(Locale.US, "↕ %d", laserEventList.size()));
        dragCountsView.setText(String.format(Locale.US, "⇄ %d", moveCount));
    }

    /**
     * @return true if measurement was successfully started
     */
    boolean startMeasurement() {
        logger.log("Starting drag latency test");
        try {
            waltDevice.syncClock();
        } catch (IOException e) {
            logger.log("Error syncing clocks: " + e.getMessage());
            return false;
        }
        // Register a callback for triggers
        waltDevice.setTriggerHandler(triggerHandler);
        try {
            waltDevice.command(WaltDevice.CMD_AUTO_LASER_ON);
            waltDevice.startListener();
        } catch (IOException e) {
            logger.log("Error: " + e.getMessage());
            waltDevice.clearTriggerHandler();
            return false;
        }
        touchCatcher.setOnTouchListener(touchListener);
        touchCatcher.startAnimation();
        touchEventList.clear();
        laserEventList.clear();
        moveCount = 0;
        updateCountsDisplay();
        return true;
    }

    void restartMeasurement() {
        logger.log("\n## Restarting drag latency test. Re-sync clocks ...");
        try {
            waltDevice.syncClock();
        } catch (IOException e) {
            logger.log("Error syncing clocks: " + e.getMessage());
        }

        touchCatcher.startAnimation();
        touchEventList.clear();
        laserEventList.clear();
        moveCount = 0;
        updateCountsDisplay();
    }

    void finishAndShowStats() {
        touchCatcher.stopAnimation();
        waltDevice.stopListener();
        try {
            waltDevice.command(WaltDevice.CMD_AUTO_LASER_OFF);
        } catch (IOException e) {
            logger.log("Error: " + e.getMessage());
        }
        touchCatcher.setOnTouchListener(null);
        waltDevice.clearTriggerHandler();

        waltDevice.checkDrift();

        logger.log(String.format(Locale.US,
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
        LogUploader.uploadIfAutoEnabled(getContext());
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
            logger.log(String.format(Locale.US,
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

        // Calculation assumes that the first event is generated by the finger obstructing the beam.
        // Remove the first event if it was generated by finger going out of the beam (value==1).
        while (laserEventList.size() > 0 && laserEventList.get(0).value == 1) {
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
            latencyChartLayout.setVisibility(View.GONE);
            restartButton.setEnabled(false);
            restartMeasurement();
            restartButton.setEnabled(true);
            return;
        }

        if (v.getId() == R.id.button_start_drag) {
            latencyChartLayout.setVisibility(View.GONE);
            startButton.setEnabled(false);
            boolean startSuccess = startMeasurement();
            if (startSuccess) {
                finishButton.setEnabled(true);
                restartButton.setEnabled(true);
            } else {
                startButton.setEnabled(true);
            }
            return;
        }

        if (v.getId() == R.id.button_finish_drag) {
            finishButton.setEnabled(false);
            restartButton.setEnabled(false);
            finishAndShowStats();
            startButton.setEnabled(true);
            return;
        }

        if (v.getId() == R.id.button_close_chart) {
            latencyChartLayout.setVisibility(View.GONE);
        }
    }

    private WaltDevice.TriggerHandler triggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
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
            logger.log(String.format(Locale.US, "bestShift = %.2f", bestShift));
            averageBestShift += bestShift / 2;
        }

        drawLatencyGraph(ft, fy, lt, averageBestShift);
        logger.log(String.format(Locale.US, "Drag latency is %.1f [ms]", averageBestShift));
    }

    private void drawLatencyGraph(double[] ft, double[] fy, double[] lt, double averageBestShift) {
        final ArrayList<Entry> touchEntries = new ArrayList<>();
        final ArrayList<Entry> laserEntries = new ArrayList<>();
        final double[] laserT = new double[lt.length];
        for (int i = 0; i < ft.length; i++) {
            touchEntries.add(new Entry((float) ft[i], (float) fy[i]));
        }
        for (int i = 0; i < lt.length; i++) {
            laserT[i] = lt[i] + averageBestShift;
        }
        final double[] laserY = Utils.interp(laserT, ft, fy);
        for (int i = 0; i < laserY.length; i++) {
            laserEntries.add(new Entry((float) laserT[i], (float) laserY[i]));
        }

        final ScatterDataSet dataSetTouch = new ScatterDataSet(touchEntries, "Touch Events");
        dataSetTouch.setScatterShape(ScatterChart.ScatterShape.CIRCLE);
        dataSetTouch.setScatterShapeSize(8f);

        final ScatterDataSet dataSetLaser = new ScatterDataSet(laserEntries,
                String.format(Locale.US, "Laser Events  Latency=%.1f ms", averageBestShift));
        dataSetLaser.setColor(Color.RED);
        dataSetLaser.setScatterShapeSize(10f);
        dataSetLaser.setScatterShape(ScatterChart.ScatterShape.X);

        final ScatterData scatterData = new ScatterData(dataSetTouch, dataSetLaser);
        final Description desc = new Description();
        desc.setText("Y-Position [pixels] vs. Time [ms]");
        desc.setTextSize(12f);
        latencyChart.setDescription(desc);
        latencyChart.setData(scatterData);
        latencyChartLayout.setVisibility(View.VISIBLE);
    }
}
