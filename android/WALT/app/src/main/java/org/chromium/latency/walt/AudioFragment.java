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
import android.media.AudioManager;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * A simple {@link Fragment} subclass.
 */
public class AudioFragment extends Fragment implements View.OnClickListener,
        AdapterView.OnItemSelectedListener, BaseTest.TestStateListener {

    private SimpleLogger logger;
    private TextView textView;
    private AudioTest audioTest;
    private View startPlaybackButton;
    private View startRecordingButton;
    private Spinner modeSpinner;

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
        startPlaybackButton = view.findViewById(R.id.button_start_audio_play);
        startRecordingButton = view.findViewById(R.id.button_start_audio_rec);

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
        startPlaybackButton.setOnClickListener(this);
        startRecordingButton.setOnClickListener(this);

        modeSpinner.setOnItemSelectedListener(this);

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
    public void onDestroy() {
        super.onDestroy();
        audioTest.teardown();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_start_audio_rec:
                attemptRecordingTest();
                break;
            case R.id.button_start_audio_play:
                disableButtons();

                // Set media volume to max
                AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
                audioTest.startMeasurement();
                break;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() == R.id.spinner_audio_mode) {
            switch (position) {
                case 0:
                    audioTest.setAudioMode(AudioTest.AudioMode.CONTINUOUS);
                    audioTest.setPeriod(AudioTest.CONTINUOUS_TEST_PERIOD);
                    break;
                case 1:
                    audioTest.setAudioMode(AudioTest.AudioMode.COLD);
                    audioTest.setPeriod(AudioTest.COLD_TEST_PERIOD);
                    break;
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        if (parent.getId() == R.id.spinner_audio_mode) {
            audioTest.setAudioMode(AudioTest.AudioMode.CONTINUOUS); // set the default
        }
    }

    private BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
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
            audioTest.beginRecordingTest();
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
                    audioTest.beginRecordingTest();
                } else {
                    logger.log("Could not get permission to record audio");
                }
                return;
        }
    }

    private void disableButtons() {
        startRecordingButton.setEnabled(false);
        startPlaybackButton.setEnabled(false);
    }

    @Override
    public void onTestStopped() {
        startRecordingButton.setEnabled(true);
        startPlaybackButton.setEnabled(true);
    }
}
