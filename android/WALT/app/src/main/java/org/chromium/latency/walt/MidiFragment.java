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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class MidiFragment extends Fragment
        implements View.OnClickListener, BaseTest.TestStateListener {

    private SimpleLogger logger;
    private TextView textView;
    private View startMidiInButton;
    private View startMidiOutButton;
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
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Register this fragment class as the listener for some button clicks
        startMidiInButton.setOnClickListener(this);
        startMidiOutButton.setOnClickListener(this);

        // mLogTextView.setMovementMethod(new ScrollingMovementMethod());
        textView.setText(logger.getLogText());
        logger.registerReceiver(mLogReceiver);
    }

    @Override
    public void onPause() {
        logger.unregisterReceiver(mLogReceiver);
        super.onPause();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_start_midi_in:
                disableButtons();
                midiTest.testMidiIn();
                break;
            case R.id.button_start_midi_out:
                disableButtons();
                midiTest.testMidiOut();
                break;
        }
    }

    private void disableButtons() {
        startMidiInButton.setEnabled(false);
        startMidiOutButton.setEnabled(false);
    }

    private BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            textView.append(msg + "\n");
        }
    };

    @Override
    public void onTestStopped() {
        startMidiInButton.setEnabled(true);
        startMidiOutButton.setEnabled(true);
    }
}
