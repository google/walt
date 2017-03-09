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

import org.junit.Test;

import static java.lang.Double.NaN;
import static junit.framework.Assert.assertEquals;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class UtilsTest {

    @Test
    public void testMedian_singleNumber() {
        assertThat(Utils.mean(new double[]{0}), is(0d));
    }

    @Test
    public void testMedian_evenSize() {
        assertThat(Utils.mean(new double[]{1,2,3,4}), is(2.5d));
    }

    @Test
    public void testMean() {
        assertThat(Utils.mean(new double[]{-1,1,2,3}), is(1.25d));
    }

    @Test
    public void testMedian_oddSize() {
        assertThat(Utils.mean(new double[]{1,2,3,4,5}), is(3d));
    }

    @Test
    public void testMean_singleNumber() {
        assertThat(Utils.mean(new double[]{0}), is(0d));
    }

    @Test
    public void testMean_empty() {
        assertThat(Utils.mean(new double[]{}), is(NaN));
    }

    @Test
    public void testMean_repeatedNumbers() {
        assertThat(Utils.mean(new double[]{5,5,5,5}), is(5d));
    }

    @Test
    public void testInterp() {
        assertThat(Utils.interp(new double[]{5,6,16,17}, new double[]{0, 10, 12, 18},
                new double[]{35, 50, 75, 93}), is(new double[]{42.5, 44, 87, 90}));
    }

    @Test
    public void testInterp_singleNumber() {
        assertThat(Utils.interp(new double[]{5}, new double[]{0, 10},
                new double[]{35, 50}), is(new double[]{42.5}));
    }

    @Test
    public void testInterp_twoNumbers() {
        assertThat(Utils.interp(new double[]{0}, new double[]{0, 10},
                new double[]{35, 50}), is(new double[]{35}));
    }

    @Test
    public void testInterp_numberContained() {
        assertThat(Utils.interp(new double[]{5, 10}, new double[]{0, 5, 10},
                new double[]{35, 19, 50}), is(new double[]{19, 50}));
    }

    @Test
    public void testStdev() {
        assertThat(Utils.stdev(new double[]{10,12,14,18}), is(Math.sqrt(8.75)));
    }

    @Test
    public void testStdev_empty() {
        assertThat(Utils.stdev(new double[]{}), is(NaN));
    }

    @Test
    public void testStdev_singleNumber() {
        assertThat(Utils.stdev(new double[]{42}), is(0d));
    }

    @Test
    public void testStdev_manyNumbers() {
        assertThat(Utils.stdev(new double[]{-1,0,1}), is(Math.sqrt(2d/3d)));
    }

    @Test
    public void testExtract() {
        assertThat(Utils.extract(new int[]{1, 2, 2, 1, 2, 2, 1, 2, 2}, 1,
                new double[]{1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 9.5}),
                is(new double[]{1.5, 4.5, 7.5}));
    }

    @Test
    public void testExtract_empty() {
        assertThat(Utils.extract(new int[]{}, 1, new double[]{}), is(new double[]{}));
    }

    @Test
    public void testArgmin() {
        assertThat(Utils.argmin(new double[]{5, 2, 1, -10, -20, 5, 19, 100}), is(4));
    }

    @Test
    public void testArgmin_empty() {
        assertThat(Utils.argmin(new double[]{}), is(0));
    }

    @Test
    public void testFindBestShift() {
        double[] touchTimes = new double[1000];
        for (int i = 0; i < touchTimes.length; i++) {
            touchTimes[i] = i;
        }
        double[] touchY = new double[touchTimes.length];
        for (int i = 0; i < touchY.length; i++) {
            // sine wave will oscillate 1000/200 times
            touchY[i] = 100*Math.sin(i * Math.PI/100);
        }
        double[] laserTimes = new double[4*5];
        int i = 0;
        for (int root = 0; root < 1000; root+=200) {
            laserTimes[i++] = root + 100 - 10;
            laserTimes[i++] = root + 100 + 10;
            laserTimes[i++] = root + 200 - 10;
            laserTimes[i++] = root + 200 + 10;
        }
        assertEquals(100, Utils.findBestShift(laserTimes, touchTimes, touchY), 0.7);
    }
}
