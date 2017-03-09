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

import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;

import static junit.framework.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(android.graphics.Color.class)
public class HistogramChartTest {

    private BarData barData;
    private HistogramChart.HistogramData data;

    @Before
    public void setUp() {
        mockStatic(android.graphics.Color.class);
        when(android.graphics.Color.rgb(anyInt(), anyInt(), anyInt())).thenReturn(0);
        barData = new BarData();
        barData.setBarWidth((1f - HistogramChart.GROUP_SPACE)/1);
        barData.addDataSet(new BarDataSet(new ArrayList<BarEntry>(), "SomeLabel"));
        data = new HistogramChart.HistogramData(1, 5f);
        data.addEntry(barData, 0, 12);
        data.addEntry(barData, 0, 14);
        data.addEntry(barData, 0, 16);
        data.addEntry(barData, 0, 21);
    }

    @Test
    public void testBinHeights() {
        final IBarDataSet barDataSet = barData.getDataSetByIndex(0);
        assertEquals(3, barDataSet.getEntryCount());
        assertEquals(2d, barDataSet.getEntryForIndex(0).getY(), 0.000001);
        assertEquals(1d, barDataSet.getEntryForIndex(1).getY(), 0.000001);
        assertEquals(1d, barDataSet.getEntryForIndex(2).getY(), 0.000001);
    }

    @Test
    public void testBinXPositions() {
        final IBarDataSet barDataSet = barData.getDataSetByIndex(0);
        assertEquals(3, barDataSet.getEntryCount());
        assertEquals(0d + 0.05d + 0.45d, barDataSet.getEntryForIndex(0).getX(), 0.000001);
        assertEquals(1d + 0.05d + 0.45d, barDataSet.getEntryForIndex(1).getX(), 0.000001);
        assertEquals(2d + 0.05d + 0.45d, barDataSet.getEntryForIndex(2).getX(), 0.000001);
    }

    @Test
    public void testDisplayValue() {
        assertEquals(10d, data.getMinBin(), 0.000001);
        assertEquals(15d, data.getDisplayValue(1), 0.000001);
    }
}
