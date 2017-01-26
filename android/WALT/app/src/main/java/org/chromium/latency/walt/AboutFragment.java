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


import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;


/**
 * A screen that shows information about WALT.
 */
public class AboutFragment extends Fragment {

    public AboutFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        TextView textView = (TextView) getActivity().findViewById(R.id.txt_build_info);
        String text = String.format("WALT v%s  (versionCode=%d)\n",
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        text += "WALT protocol version: " + WaltDevice.PROTOCOL_VERSION + "\n";
        text += "Android Build ID: " + Build.DISPLAY + "\n";
        text += "Android API Level: " + Build.VERSION.SDK_INT + "\n";
        text += "Android OS Version: " + System.getProperty("os.version");
        textView.setText(text);
    }
}
