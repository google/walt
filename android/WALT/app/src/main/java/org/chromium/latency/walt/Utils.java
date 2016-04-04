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

import java.util.ArrayList;
import java.util.InputMismatchException;

/**
 * Kitchen sink for small utility functions
 */
public class Utils {
    public static double median(ArrayList<Double> lst) {
        int len = lst.size();
        if (len == 0) {
            return Double.NaN;
        }

        if (len % 2 == 1) {
            return lst.get(len / 2);
        } else {
            return 0.5 * (lst.get(len / 2) + lst.get(len / 2 - 1));
        }
    }

    public static double mean(double[] x) {
        double s = 0;
        for (double v: x) s += v;
        return s / x.length;
    }

    /**
     * Linear interpolation styled after numpy.interp()
     * returns values at points x interpolated using xp, yp data points
     * Both x and xp must be monotonically increasing.
     */
    public static double[] interp(double[] x, double[] xp, double[] yp) {
        // assuming that x and xp are already sorted.
        // go over x and xp as if we are merging them
        double[] y = new double[x.length];
        int i = 0;
        int ip = 0;

        // skip x points that are outside the data
        while (i < x.length && x[i] < xp[0]) i++;

        while (ip < xp.length && i < x.length) { // TODO: x
            // skip until we see an xp larger than current x
            while (ip < xp.length && xp[ip] <= x[i]) ip++;
            if (ip >= xp.length) break;
            double dy = yp[ip] - yp[ip-1];
            double dx = xp[ip] - xp[ip-1];
            y[i] = yp[ip-1] + dy/dx * (x[i] - xp[ip-1]);
            i++;
        }
        return y;
    }

    public static double stdev(double[] a) {
        double sum = 0;
        for (double v : a) sum += v;
        double m = sum/a.length;
        double sumsq = 0;
        for (double v : a) sumsq += (v-m)*(v-m);
        return Math.sqrt(sumsq / a.length);
    }

    /**
     * Similar to numpy.extract()
     * returns a shorter array with values taken from x at indices where indicator == value
     */
    public static double[] extract(int[] indicator, int value, double[] arr) {
        if (arr.length != indicator.length) {
            throw new InputMismatchException("Length of x and indicator must be the same.");
        }
        int newLen = 0;
        for (int v: indicator) if (v == value) newLen++;
        double[] newx = new double[newLen];

        int j = 0;
        for (int i=0; i<arr.length; i++) {
            if (indicator[i] == value) {
                newx[j] = arr[i];
                j++;
            }
        }
        return newx;
    }

    public static String array2string(double[] a, String format) {
        StringBuilder sb = new StringBuilder();
        sb.append("array([");
        for (double x: a) {
            sb.append(String.format(format, x));
            sb.append(", ");
        }
        sb.append("])");
        return sb.toString();
    }


    public static int argmin(double[] a) {
        int imin = 0;
        for (int i=1; i<a.length; i++) if (a[i] < a[imin]) imin = i;
        return imin;
    }


    /**
     * Simplified Java re-implementation or py/qslog/minimization.py.
     * This is very specific to the drag latency algorithm.
     *
     * tl;dr: Shift laser events by some time delta and see how well they fit on a horizontal line.
     * Delta that results in the best looking straight line is the latency.
     */
    public static double findBestShift(double[] laserT, double[] touchT, double[] touchY) {
        // TODO: reimplement as multiple passes with decreasing steps
        int steps = 1500;
        double shiftStep = 0.1;  // milliseconds

        double[] T = new double[laserT.length];
        double[] devs = new double[steps];

        for (int i=0; i < steps; i++) {
            for (int j=0; j<T.length; j++) {
                T[j] = laserT[j] + shiftStep * i;
            }

            double [] laserY = Utils.interp(T, touchT, touchY);
            // TODO: Think about throwing away a percentile of most distanced points for noise reduction
            devs[i] = Utils.stdev(laserY);
        }

        double bestShift = argmin(devs) * shiftStep;
        return bestShift;
    }

}
