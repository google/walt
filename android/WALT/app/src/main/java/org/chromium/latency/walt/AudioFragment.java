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

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.chromium.latency.walt.Utils.getIntPreference;

/**
 * A simple {@link Fragment} subclass.
 */
public class AudioFragment extends Fragment implements View.OnClickListener,
        BaseTest.TestStateListener {

    enum AudioTestType {
        CONTINUOUS_PLAYBACK,
        CONTINUOUS_RECORDING,
        COLD_PLAYBACK,
        COLD_RECORDING,
        DISPLAY_WAVEFORM
    }

    private SimpleLogger logger;
    private TextView textView;
    private AudioTest audioTest;
    private View startButton;
    private View stopButton;
    private Spinner modeSpinner;
    private LineChart chart;
    private HistogramChart latencyChart;
    private View chartLayout;

    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 1;

    public AudioFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        logger = SimpleLogger.getInstance(getContext());

        audioTest = new AudioTest(getActivity());
        audioTest.setTestStateListener(this);

        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_audio, container, false);
        textView = (TextView) view.findViewById(R.id.txt_box_audio);
        textView.setMovementMethod(new ScrollingMovementMethod());
        startButton = view.findViewById(R.id.button_start_audio);
        stopButton = view.findViewById(R.id.button_stop_audio);
        chartLayout = view.findViewById(R.id.chart_layout);
        chart = (LineChart) view.findViewById(R.id.chart);
        latencyChart = (HistogramChart) view.findViewById(R.id.latency_chart);

        view.findViewById(R.id.button_close_chart).setOnClickListener(this);
        enableButtons();

        // Configure the audio mode spinner
        modeSpinner = (Spinner) view.findViewById(R.id.spinner_audio_mode);
        ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(getContext(),
                R.array.audio_mode_array, android.R.layout.simple_spinner_item);
        modeAdapter.setDropDownViewResource(R.layout.support_simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Register this fragment class as the listener for some button clicks
        startButton.setOnClickListener(this);
        stopButton.setOnClickListener(this);

        textView.setText(logger.getLogText());
        logger.registerReceiver(logReceiver);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(logReceiver);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        audioTest.teardown();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_start_audio:
                chartLayout.setVisibility(View.GONE);
                disableButtons();
                AudioTestType testType = getSelectedTestType();
                switch (testType) {
                    case CONTINUOUS_PLAYBACK:
                    case CONTINUOUS_RECORDING:
                    case DISPLAY_WAVEFORM:
                        audioTest.setAudioMode(AudioTest.AudioMode.CONTINUOUS);
                        audioTest.setPeriod(AudioTest.CONTINUOUS_TEST_PERIOD);
                        break;
                    case COLD_PLAYBACK:
                    case COLD_RECORDING:
                        audioTest.setAudioMode(AudioTest.AudioMode.CONTINUOUS);
                        audioTest.setPeriod(AudioTest.COLD_TEST_PERIOD);
                        break;
                }
                if (testType == AudioTestType.DISPLAY_WAVEFORM) {
                    // Only need to record 1 beep to display wave
                    audioTest.setRecordingRepetitions(1);
                } else {
                    audioTest.setRecordingRepetitions(
                            getIntPreference(getContext(), R.string.preference_audio_in_reps, 5));
                }
                if (testType == AudioTestType.CONTINUOUS_PLAYBACK ||
                        testType == AudioTestType.COLD_PLAYBACK ||
                        testType == AudioTestType.CONTINUOUS_RECORDING ||
                        testType == AudioTestType.COLD_RECORDING) {
                    latencyChart.setVisibility(View.VISIBLE);
                    latencyChart.clearData();
                    latencyChart.setLegendEnabled(false);
                    final String description =
                            getResources().getStringArray(R.array.audio_mode_array)[
                                    modeSpinner.getSelectedItemPosition()] + " [ms]";
                    latencyChart.setDescription(description);
                }
                switch (testType) {
                    case CONTINUOUS_RECORDING:
                    case COLD_RECORDING:
                    case DISPLAY_WAVEFORM:
                        attemptRecordingTest();
                        break;
                    case CONTINUOUS_PLAYBACK:
                    case COLD_PLAYBACK:
                        // Set media volume to max
                        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
                        audioTest.beginPlaybackMeasurement();
                        break;
                }
                break;
            case R.id.button_stop_audio:
                audioTest.stopTest();
                break;
            case R.id.button_close_chart:
                chartLayout.setVisibility(View.GONE);
                break;
        }
    }

    private AudioTestType getSelectedTestType() {
        return AudioTestType.values()[modeSpinner.getSelectedItemPosition()];
    }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            textView.append(msg + "\n");
        }
    };

    private void attemptRecordingTest() {
        // first see if we already have permission to record audio
        int currentPermission = ContextCompat.checkSelfPermission(this.getContext(),
                Manifest.permission.RECORD_AUDIO);
        if (currentPermission == PackageManager.PERMISSION_GRANTED) {
            disableButtons();
            audioTest.beginRecordingMeasurement();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_RECORD_AUDIO:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    disableButtons();
                    audioTest.beginRecordingMeasurement();
                } else {
                    logger.log("Could not get permission to record audio");
                }
                return;
        }
    }

    @Override
    public void onTestStopped() {
        if (getSelectedTestType() == AudioTestType.DISPLAY_WAVEFORM) {
            drawWaveformChart();
        } else {
            if (!audioTest.deltas_mic.isEmpty()) {
                latencyChart.setLegendEnabled(true);
                latencyChart.setLabel(String.format(Locale.US, "Median=%.1f ms", Utils.median(audioTest.deltas_mic)));
            } else if (!audioTest.deltas_queue2wire.isEmpty()) {
                latencyChart.setLegendEnabled(true);
                latencyChart.setLabel(String.format(Locale.US, "Median=%.1f ms", Utils.median(audioTest.deltas_queue2wire)));
            }
        }
        LogUploader.uploadIfAutoEnabled(getContext());
        enableButtons();
    }

    @Override
    public void onTestStoppedWithError() {
        enableButtons();
        latencyChart.setVisibility(View.GONE);
    }

    @Override
    public void onTestPartialResult(double value) {
        latencyChart.addEntry(value);
    }

    private void drawWaveformChart() {
        final short[] wave = AudioTest.getRecordedWave();
        List<Entry> entries = new ArrayList<>();
        int frameRate = audioTest.getOptimalFrameRate();
        for (int i = 0; i < wave.length; i++) {
            float timeStamp = (float) i / frameRate * 1000f;
            entries.add(new Entry(timeStamp, (float) wave[i]));
        }
        LineDataSet dataSet = new LineDataSet(entries, "Waveform");
        dataSet.setColor(Color.BLACK);
        dataSet.setValueTextColor(Color.BLACK);
        dataSet.setCircleColor(ContextCompat.getColor(getContext(), R.color.DarkGreen));
        dataSet.setCircleRadius(1.5f);
        dataSet.setCircleColorHole(Color.DKGRAY);
        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);

        LimitLine line = new LimitLine(audioTest.getThreshold(), "Threshold");
        line.setLineColor(Color.RED);
        line.setLabelPosition(LimitLine.LimitLabelPosition.LEFT_TOP);
        line.setLineWidth(2f);
        line.setTextColor(Color.DKGRAY);
        line.setTextSize(10f);
        chart.getAxisLeft().addLimitLine(line);

        final Description desc = new Description();
        desc.setText("Wave [digital level -32768 to +32767] vs. Time [ms]");
        desc.setTextSize(12f);
        chart.setDescription(desc);
        chart.getLegend().setEnabled(false);
        chart.invalidate();
        chartLayout.setVisibility(View.VISIBLE);
    }

    private void disableButtons() {
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
    }

    private void enableButtons() {
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }
}
