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

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.DefaultValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.util.ArrayList;



/**
 * Shows a histogram.
 */
public class HistogramFragment extends Fragment {

    private Activity activity;
    private SimpleLogger logger;
    protected BarChart mChart;
    int[] barColors = ColorTemplate.MATERIAL_COLORS;

    private ArrayList<int[]> mHists = new ArrayList<>();
    private ArrayList<String> mLables = new ArrayList<>();

    private float minX, maxX;

    public String chartDescription = "Latency [ms]";


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
        setupBarChart();
    }

    public void clearData() {
        mHists.clear();
        mLables.clear();
    }

    public void addHist(int[] hist, String lable) {
        if (mHists.size() > 0 && mHists.get(0).length != hist.length) {
            throw new IllegalArgumentException("Histograms must be of same length");
        }
        mHists.add(hist);
        mLables.add(lable);
    }

    private BarData generateBarData() {


        ArrayList<IBarDataSet> dataSetList = new ArrayList<IBarDataSet>();
        minX = Float.MAX_VALUE;
        maxX = Float.MIN_VALUE;

        for (int histNum = 0; histNum < mHists.size(); histNum++) {
            ArrayList<BarEntry> entries = new ArrayList<BarEntry>();
            int [] hist = mHists.get(histNum);

            // Shifting the bar x positions for showing bars of two histograms side by side.
            // xShift = 0 for one histogram and +-0.2 for two. Won't look good for more than two.
            float xShift = 0.2f * (mHists.size() - 1) * (histNum * 2 - 1);

            // Copy histogram vectors into BarEntry objects
            for (int i = 0; i < hist.length; i++) {
                if (hist[i] != 0) {
                    float x = i + xShift;
                    entries.add(new BarEntry(x, hist[i]));
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                }
            }

            BarDataSet dataSet = new BarDataSet(entries, mLables.get(histNum));
            dataSet.setColor(barColors[histNum % barColors.length]);
            dataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            dataSetList.add(dataSet);
        }


        BarData d = new BarData(dataSetList);
        float barWidth = 0.7f / mHists.size();
        d.setBarWidth(barWidth);

        d.setValueFormatter(new DefaultValueFormatter(0)); // 0 decimal digits

        return d;
    }


    void setupBarChart() {

        BarData data = generateBarData();

        YAxis rightAxis = mChart.getAxisRight();
        rightAxis.setEnabled(false);

        Legend l = mChart.getLegend();
        l.setPosition(Legend.LegendPosition.ABOVE_CHART_LEFT);

        XAxis xAxis = mChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularityEnabled(true);
        xAxis.setGranularity(1.0f);

        // Some black magic with min/max limits of the graph to get better look & feel.
        float xSpan = (maxX - minX);
        float midX = (minX + maxX) / 2;
        float zoomMargin = 0.1f;
        xAxis.setAxisMinValue(midX - xSpan * (1 + zoomMargin));
        xAxis.setAxisMaxValue(midX + xSpan * (1 + zoomMargin));
        mChart.zoomAndCenter( 2f, 1f, midX, 0, YAxis.AxisDependency.LEFT);

        mChart.setDescription(chartDescription);

        mChart.setData(data);

    }

    @Override
    public void onPause() {
        super.onPause();
    }

}
