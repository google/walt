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

import com.github.mikephil.charting.data.Entry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static junit.framework.Assert.assertEquals;

public class AccelerometerFragmentTest {

    @Test
    public void testSmoothEntries() {
        Random rand = new Random(1234);
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            entries.add(new Entry(i, i + rand.nextFloat()*0.01f));
        }
        final List<Entry> smoothEntries = AccelerometerFragment.smoothEntries(entries, 4);
        for (Entry e : smoothEntries) {
            assertEquals(e.getX(), e.getY(), 1e-2);
        }
    }

    @Test
    public void testFindShifts() {
        Random rand = new Random(5678);
        List<Entry> phoneEntries = new ArrayList<>();
        List<Entry> waltEntries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            if (i % 3 == 0)
                phoneEntries.add(new Entry(i, (float) Math.sin((i - 12)*Math.PI/100)));
            waltEntries.add(new Entry(i, (float) Math.sin(i*Math.PI/100)*rand.nextFloat() + rand.nextFloat()*0.2f - 0.1f));
        }
        final double[] shifts = AccelerometerFragment.findShifts(phoneEntries, waltEntries);
        for (double d : shifts) {
            System.out.println(d);
        }
        assertEquals(12, Utils.argmax(shifts)/10d, 1e-9);
    }
}
