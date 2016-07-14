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


import android.app.Activity;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.mikephil.charting.charts.BarChart;


/**
 * Shows a histogram.
 */
public class HistogramFragment extends Fragment {

    private Activity activity;
    private SimpleLogger logger;
    TextView mTextView;
    protected BarChart mChart;



    public HistogramFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        activity = getActivity();
        logger = SimpleLogger.getInstance(getContext());
        return inflater.inflate(R.layout.fragment_histogram, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        mChart = (BarChart) activity.findViewById(R.id.chart_histogram);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

}
