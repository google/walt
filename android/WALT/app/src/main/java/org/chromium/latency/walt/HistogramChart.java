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

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.text.DecimalFormat;
import java.util.ArrayList;

public class HistogramChart extends RelativeLayout implements View.OnClickListener {

    private float binWidth;
    private static final float GROUP_SPACE = 0.1f;
    private final ArrayList<ArrayList<Double>> rawData;

    private double minBin = 0;
    private double maxBin = 100;
    private double min = 0;
    private double max = 100;

    private BarChart barChart;

    public HistogramChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(getContext(), R.layout.histogram, this);

        barChart = (BarChart) findViewById(R.id.bar_chart);
        findViewById(R.id.button_close_bar_chart).setOnClickListener(this);

        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.HistogramChart);
        final String descString;
        final int numDataSets;
        try {
            descString = a.getString(R.styleable.HistogramChart_description);
            numDataSets = a.getInteger(R.styleable.HistogramChart_numDataSets, 1);
            binWidth = a.getFloat(R.styleable.HistogramChart_binWidth, 5f);
        } finally {
            a.recycle();
        }


        rawData = new ArrayList<>(numDataSets);
        ArrayList<IBarDataSet> dataSets = new ArrayList<>(numDataSets);
        for (int i = 0; i < numDataSets; i++) {
            rawData.add(new ArrayList<Double>());
            final BarDataSet dataSet = new BarDataSet(new ArrayList<BarEntry>(), "");
            dataSet.setColor(ColorTemplate.MATERIAL_COLORS[i]);
            dataSets.add(dataSet);
        }

        BarData barData = new BarData(dataSets);
        barData.setBarWidth((1f - GROUP_SPACE)/numDataSets);
        barChart.setData(barData);
        groupBars();
        final Description desc = new Description();
        desc.setText(descString);
        desc.setTextSize(12f);
        barChart.setDescription(desc);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setGranularityEnabled(true);
        xAxis.setGranularity(1);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setValueFormatter(new IAxisValueFormatter() {
            DecimalFormat df = new DecimalFormat("#.##");

            @Override
            public String getFormattedValue(float value, AxisBase axis) {
                return df.format(value * binWidth + minBin);
            }
        });

        barChart.setFitBars(true);
        barChart.invalidate();
    }

    BarChart getBarChart() {
        return barChart;
    }

    /**
     * Re-implementation of BarData.groupBars(), but allows grouping with only 1 BarDataSet
     * This adjusts the x-coordinates of entries, which centers the bars between axis labels
     */
    private void groupBars() {
        final BarData barData = barChart.getBarData();
        IBarDataSet max = barData.getMaxEntryCountSet();
        int maxEntryCount = max.getEntryCount();
        float groupSpaceWidthHalf = GROUP_SPACE / 2f;
        float barWidthHalf = barData.getBarWidth() / 2f;
        float interval = barData.getGroupWidth(GROUP_SPACE, 0);
        float fromX = 0;

        for (int i = 0; i < maxEntryCount; i++) {
            float start = fromX;
            fromX += groupSpaceWidthHalf;

            for (IBarDataSet set : barData.getDataSets()) {
                fromX += barWidthHalf;
                if (i < set.getEntryCount()) {
                    BarEntry entry = set.getEntryForIndex(i);
                    if (entry != null) {
                        entry.setX(fromX);
                    }
                }
                fromX += barWidthHalf;
            }

            fromX += groupSpaceWidthHalf;
            float end = fromX;
            float innerInterval = end - start;
            float diff = interval - innerInterval;

            // correct rounding errors
            if (diff > 0 || diff < 0) {
                fromX += diff;
            }
        }

        barData.notifyDataChanged();
    }

    public void clearData() {
        for (int i = 0; i < rawData.size(); i++) {
            rawData.get(i).clear();
            barChart.getBarData().getDataSetByIndex(i).clear();
        }
        barChart.getBarData().notifyDataChanged();
        barChart.invalidate();
    }

    public void addEntry(int dataSetIndex, double value) {
        if (isRawDataEmpty()) {
            min = value;
            max = value;
        } else {
            if (value < min) min = value;
            if (value > max) max = value;
        }

        rawData.get(dataSetIndex).add(value);
        recalculateDataSet();
    }

    public void addEntry(double value) {
        addEntry(0, value);
    }

    private void recalculateDataSet() {
        minBin = Math.floor(min / binWidth) * binWidth;
        maxBin = Math.floor(max / binWidth) * binWidth;

        final int numBins = (int) ((maxBin - minBin) / binWidth) + 1;
        int[][] bins = new int[rawData.size()][numBins];

        for (int setNum = 0; setNum < rawData.size(); setNum++) {
            for (Double d : rawData.get(setNum)) {
                ++bins[setNum][(int) (Math.floor((d - minBin) / binWidth))];
            }
        }

        for (int setNum = 0; setNum < barChart.getBarData().getDataSetCount(); setNum++) {
            final IBarDataSet dataSet = barChart.getBarData().getDataSetByIndex(setNum);
            dataSet.clear();
            for (int i = 0; i < bins[setNum].length; i++) {
                dataSet.addEntry(new BarEntry(i, bins[setNum][i]));
            }
        }

        final XAxis xAxis = barChart.getXAxis();
        xAxis.setAxisMinimum(0);
        xAxis.setAxisMaximum(numBins);
        groupBars();
        barChart.getBarData().notifyDataChanged();
        barChart.notifyDataSetChanged();
        barChart.invalidate();
    }

    private boolean isRawDataEmpty() {
        for (ArrayList<Double> data : rawData) {
            if (!data.isEmpty()) return false;
        }
        return true;
    }

    public void setLabel(int dataSetIndex, String label) {
        barChart.getBarData().getDataSetByIndex(dataSetIndex).setLabel(label);
        barChart.getLegendRenderer().computeLegend(barChart.getBarData());
        barChart.invalidate();
    }

    public void setLabel(String label) {
        setLabel(0, label);
    }

    public void setDescription(String description) {
        getBarChart().getDescription().setText(description);
    }

    public void setLegendEnabled(boolean enabled) {
        barChart.getLegend().setEnabled(enabled);
        barChart.notifyDataSetChanged();
        barChart.invalidate();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_close_bar_chart:
                this.setVisibility(GONE);
        }
    }
}
