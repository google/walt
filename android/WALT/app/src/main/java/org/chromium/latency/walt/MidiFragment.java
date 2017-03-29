/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

public class MidiFragment extends Fragment
        implements View.OnClickListener, BaseTest.TestStateListener {

    private SimpleLogger logger;
    private TextView textView;
    private View startMidiInButton;
    private View startMidiOutButton;
    private HistogramChart latencyChart;
    private MidiTest midiTest;

    public MidiFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        logger = SimpleLogger.getInstance(getContext());
        midiTest = new MidiTest(getActivity());
        midiTest.setTestStateListener(this);

        final View view = inflater.inflate(R.layout.fragment_midi, container, false);
        textView = (TextView) view.findViewById(R.id.txt_box_midi);
        startMidiInButton = view.findViewById(R.id.button_start_midi_in);
        startMidiOutButton = view.findViewById(R.id.button_start_midi_out);
        latencyChart = (HistogramChart) view.findViewById(R.id.latency_chart);
        textView.setMovementMethod(new ScrollingMovementMethod());
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Register this fragment class as the listener for some button clicks
        startMidiInButton.setOnClickListener(this);
        startMidiOutButton.setOnClickListener(this);

        textView.setText(logger.getLogText());
        logger.registerReceiver(logReceiver);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(logReceiver);
        super.onPause();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_start_midi_in:
                disableButtons();
                latencyChart.setVisibility(View.VISIBLE);
                latencyChart.clearData();
                latencyChart.setLegendEnabled(false);
                latencyChart.getBarChart().getDescription().setText("MIDI Input Latency [ms]");
                midiTest.testMidiIn();
                break;
            case R.id.button_start_midi_out:
                disableButtons();
                latencyChart.setVisibility(View.VISIBLE);
                latencyChart.clearData();
                latencyChart.setLegendEnabled(false);
                latencyChart.getBarChart().getDescription().setText("MIDI Output Latency [ms]");
                midiTest.testMidiOut();
                break;
        }
    }

    private void disableButtons() {
        startMidiInButton.setEnabled(false);
        startMidiOutButton.setEnabled(false);
    }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            textView.append(msg + "\n");
        }
    };

    @Override
    public void onTestStopped() {
        if (!midiTest.deltasOutputTotal.isEmpty()) {
            latencyChart.setLegendEnabled(true);
            latencyChart.setLabel(String.format(
                    Locale.US, "Median=%.1f ms", Utils.median(midiTest.deltasOutputTotal)));
        } else if (!midiTest.deltasInputTotal.isEmpty()) {
            latencyChart.setLegendEnabled(true);
            latencyChart.setLabel(String.format(
                    Locale.US, "Median=%.1f ms", Utils.median(midiTest.deltasInputTotal)));
        }
        LogUploader.uploadIfAutoEnabled(getContext());
        startMidiInButton.setEnabled(true);
        startMidiOutButton.setEnabled(true);
    }

    @Override
    public void onTestStoppedWithError() {
        onTestStopped();
        latencyChart.setVisibility(View.GONE);
    }

    @Override
    public void onTestPartialResult(double value) {
        latencyChart.addEntry(value);
    }

    public static boolean hasMidi(Context context) {
        return context.getPackageManager().
                hasSystemFeature("android.software.midi");
    }
}
