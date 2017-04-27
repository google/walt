/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.ScatterChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.ScatterData;
import com.github.mikephil.charting.data.ScatterDataSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.chromium.latency.walt.Utils.argmax;
import static org.chromium.latency.walt.Utils.interp;
import static org.chromium.latency.walt.Utils.max;
import static org.chromium.latency.walt.Utils.mean;
import static org.chromium.latency.walt.Utils.min;

public class AccelerometerFragment extends Fragment implements
        View.OnClickListener, SensorEventListener {

    private static final int MAX_TEST_LENGTH_MS = 10000;
    private SimpleLogger logger;
    private WaltDevice waltDevice;
    private TextView logTextView;
    private View startButton;
    private ScatterChart latencyChart;
    private View latencyChartLayout;
    private StringBuilder accelerometerData;
    private List<AccelerometerEvent> phoneAccelerometerData = new ArrayList<>();
    private Handler handler = new Handler();
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private double realTimeOffsetMs;
    private boolean isTestRunning = false;

    Runnable finishAccelerometer = new Runnable() {
        @Override
        public void run() {
            isTestRunning = false;
            waltDevice.stopListener();
            waltDevice.clearTriggerHandler();
            calculateAndDrawLatencyChart(accelerometerData.toString());
            startButton.setEnabled(true);
            accelerometerData = new StringBuilder();
            LogUploader.uploadIfAutoEnabled(getContext());
        }
    };

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            AccelerometerFragment.this.appendLogText(msg);
        }
    };

    private WaltDevice.TriggerHandler triggerHandler = new WaltDevice.TriggerHandler() {
        @Override
        public void onReceive(WaltDevice.TriggerMessage tmsg) {
            logger.log("ERROR: Accelerometer trigger got a trigger message, " +
                    "this should never happen.");
        }

        @Override
        public void onReceiveRaw(String s) {
            if (s.trim().equals("end")) {
                // Remove the delayed callback and run it now
                handler.removeCallbacks(finishAccelerometer);
                handler.post(finishAccelerometer);
            } else {
                accelerometerData.append(s);
            }
        }
    };

    Runnable startAccelerometer = new Runnable() {
        @Override
        public void run() {
            waltDevice.setTriggerHandler(triggerHandler);
            try {
                waltDevice.command(WaltDevice.CMD_ACCELEROMETER);
            } catch (IOException e) {
                logger.log("Error sending command CMD_ACCELEROMETER: " + e.getMessage());
                startButton.setEnabled(true);
                return;
            }

            logger.log("=== Accelerometer Test ===\n");
            isTestRunning = true;
            handler.postDelayed(finishAccelerometer, MAX_TEST_LENGTH_MS);
        }
    };

    public AccelerometerFragment() {
        // Required empty public constructor
    }

    static List<Entry> getEntriesFromString(final String latencyString) {
        List<Entry> entries = new ArrayList<>();
        // "o" marks the start of the accelerometer data
        int startIndex = latencyString.indexOf("o") + 1;

        String[] brightnessStrings =
                latencyString.substring(startIndex).trim().split("\n");
        for (String str : brightnessStrings) {
            String[] arr = str.split(" ");
            final float timestampMs = Integer.parseInt(arr[0]) / 1000f;
            final float value = Integer.parseInt(arr[1]);
            entries.add(new Entry(timestampMs, value));
        }
        return entries;
    }

    static List<Entry> smoothEntries(List<Entry> entries, int windowSize) {
        List<Entry> smoothEntries = new ArrayList<>();
        for (int i = windowSize; i < entries.size() - windowSize; i++) {
            final float time = entries.get(i).getX();
            float avg = 0;
            for (int j = i - windowSize; j <= i + windowSize; j++) {
                avg += entries.get(j).getY() / (2 * windowSize + 1);
            }
            smoothEntries.add(new Entry(time, avg));
        }
        return smoothEntries;
    }

    static double[] findShifts(List<Entry> phoneEntries, List<Entry> waltEntries) {
        double[] phoneTimes = new double[phoneEntries.size()];
        double[] phoneValues = new double[phoneEntries.size()];
        double[] waltTimes = new double[waltEntries.size()];
        double[] waltValues = new double[waltEntries.size()];

        for (int i = 0; i < phoneTimes.length; i++) {
            phoneTimes[i] = phoneEntries.get(i).getX();
            phoneValues[i] = phoneEntries.get(i).getY();
        }

        for (int i = 0; i < waltTimes.length; i++) {
            waltTimes[i] = waltEntries.get(i).getX();
            waltValues[i] = waltEntries.get(i).getY();
        }

        double[] shiftCorrelations = new double[401];
        for (int i = 0; i < shiftCorrelations.length; i++) {
            double shift = i / 10.;
            final double[] shiftedPhoneTimes = new double[phoneTimes.length];
            for (int j = 0; j < phoneTimes.length; j++) {
                shiftedPhoneTimes[j] = phoneTimes[j] - shift;
            }
            final double[] interpolatedValues = interp(shiftedPhoneTimes, waltTimes, waltValues);
            double sum = 0;
            for (int j = 0; j < shiftedPhoneTimes.length; j++) {
                // Calculate square dot product of phone and walt values
                sum += Math.pow(phoneValues[j] * interpolatedValues[j], 2);
            }
            shiftCorrelations[i] = sum;
        }
        return shiftCorrelations;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        logger = SimpleLogger.getInstance(getContext());
        waltDevice = WaltDevice.getInstance(getContext());

        // Inflate the layout for this fragment
        final View view = inflater.inflate(R.layout.fragment_accelerometer, container, false);
        logTextView = (TextView) view.findViewById(R.id.txt_log);
        startButton = view.findViewById(R.id.button_start);
        latencyChart = (ScatterChart) view.findViewById(R.id.latency_chart);
        latencyChartLayout = view.findViewById(R.id.latency_chart_layout);
        logTextView.setMovementMethod(new ScrollingMovementMethod());
        view.findViewById(R.id.button_close_chart).setOnClickListener(this);
        sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            logger.log("ERROR! Accelerometer sensor not found");
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        logTextView.setText(logger.getLogText());
        logger.registerReceiver(logReceiver);
        startButton.setOnClickListener(this);
        sensorManager.registerListener(
                AccelerometerFragment.this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(logReceiver);
        sensorManager.unregisterListener(AccelerometerFragment.this, accelerometer);
        super.onPause();
    }

    public void appendLogText(String msg) {
        logTextView.append(msg + "\n");
    }

    void startMeasurement() {
        logger.log("Starting accelerometer latency measurement");
        try {
            accelerometerData = new StringBuilder();
            phoneAccelerometerData.clear();
            waltDevice.syncClock();
            waltDevice.startListener();
            realTimeOffsetMs =
                    SystemClock.elapsedRealtimeNanos() / 1e6 - waltDevice.clock.micros() / 1e3;
        } catch (IOException e) {
            logger.log("Error syncing clocks: " + e.getMessage());
            startButton.setEnabled(true);
            return;
        }
        Toast.makeText(getContext(), "Start shaking the phone and WALT!", Toast.LENGTH_LONG).show();
        handler.postDelayed(startAccelerometer, 500);
    }

    /**
     * Handler for all the button clicks on this screen.
     */
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button_start) {
            latencyChartLayout.setVisibility(View.GONE);
            startButton.setEnabled(false);
            startMeasurement();
            return;
        }

        if (v.getId() == R.id.button_close_chart) {
            latencyChartLayout.setVisibility(View.GONE);
        }
    }

    private void calculateAndDrawLatencyChart(final String latencyString) {
        List<Entry> phoneEntries = new ArrayList<>();
        List<Entry> waltEntries = getEntriesFromString(latencyString);
        List<Entry> waltSmoothEntries = smoothEntries(waltEntries, 4);

        for (AccelerometerEvent e : phoneAccelerometerData) {
            phoneEntries.add(new Entry(e.callbackTimeMs, e.value));
        }

        while (phoneEntries.get(0).getX() < waltSmoothEntries.get(0).getX()) {
            // This event is earlier than any walt event, so discard it
            phoneEntries.remove(0);
        }

        while (phoneEntries.get(phoneEntries.size() - 1).getX() >
                waltSmoothEntries.get(waltSmoothEntries.size() - 1).getX()) {
            // This event is later than any walt event, so discard it
            phoneEntries.remove(phoneEntries.size() - 1);
        }

        // Adjust waltEntries so min and max is the same as phoneEntries
        float phoneMean = mean(phoneEntries);
        float phoneMax = max(phoneEntries);
        float phoneMin = min(phoneEntries);
        float waltMin = min(waltSmoothEntries);
        float phoneRange = phoneMax - phoneMin;
        float waltRange = max(waltSmoothEntries) - waltMin;
        for (Entry e : waltSmoothEntries) {
            e.setY((e.getY() - waltMin) * (phoneRange / waltRange) + phoneMin - phoneMean);
        }

        // Adjust phoneEntries so mean=0
        for (Entry e : phoneEntries) {
            e.setY(e.getY() - phoneMean);
        }

        double[] shifts = findShifts(phoneEntries, waltSmoothEntries);
        double bestShift = argmax(shifts) / 10d;
        logger.log(String.format("Accelerometer latency: %.1fms", bestShift));

        double[] deltasKernelToCallback = new double[phoneAccelerometerData.size()];
        for (int i = 0; i < deltasKernelToCallback.length; i++) {
            deltasKernelToCallback[i] = phoneAccelerometerData.get(i).callbackTimeMs -
                    phoneAccelerometerData.get(i).kernelTimeMs;
        }

        logger.log(String.format(
                "Mean kernel-to-callback latency: %.1fms", mean(deltasKernelToCallback)));

        List<Entry> phoneEntriesShifted = new ArrayList<>();
        for (Entry e : phoneEntries) {
            phoneEntriesShifted.add(new Entry((float) (e.getX() - bestShift), e.getY()));
        }

        drawLatencyChart(phoneEntriesShifted, waltSmoothEntries);
    }

    private void drawLatencyChart(List<Entry> phoneEntriesShifted, List<Entry> waltEntries) {
        final ScatterDataSet dataSetWalt =
                new ScatterDataSet(waltEntries, "WALT Events");
        dataSetWalt.setColor(Color.BLUE);
        dataSetWalt.setScatterShape(ScatterChart.ScatterShape.CIRCLE);
        dataSetWalt.setScatterShapeSize(8f);

        final ScatterDataSet dataSetPhoneShifted =
                new ScatterDataSet(phoneEntriesShifted, "Phone Events Shifted");
        dataSetPhoneShifted.setColor(Color.RED);
        dataSetPhoneShifted.setScatterShapeSize(10f);
        dataSetPhoneShifted.setScatterShape(ScatterChart.ScatterShape.X);

        final ScatterData scatterData = new ScatterData(dataSetWalt, dataSetPhoneShifted);
        final Description desc = new Description();
        desc.setText("");
        desc.setTextSize(12f);
        latencyChart.setDescription(desc);
        latencyChart.setData(scatterData);
        latencyChart.invalidate();
        latencyChartLayout.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isTestRunning) {
            phoneAccelerometerData.add(new AccelerometerEvent(event));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private class AccelerometerEvent {
        float callbackTimeMs;
        float kernelTimeMs;
        float value;

        AccelerometerEvent(SensorEvent event) {
            callbackTimeMs = waltDevice.clock.micros() / 1e3f;
            kernelTimeMs = (float) (event.timestamp / 1e6f - realTimeOffsetMs);
            value = event.values[2];
        }
    }
}
