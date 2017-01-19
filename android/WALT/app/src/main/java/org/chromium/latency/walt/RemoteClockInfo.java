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

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Representation of our best knowledge of the remote clock.
 * All time variables here are stored in microseconds.
 *
 * Which time reporting function is used locally on Android:
 * This app uses SystemClock.uptimeMillis() for keeping local time which, up to
 * units, is the same time reported by System.nanoTime() and by
 * clock_gettime(CLOCK_MONOTONIC, &ts) from time.h and is, roughly, the time
 * elapsed since last boot, excluding sleep time.
 *
 * base_time is the local Android time when remote clock was zeroed.
 *
 * micros() is our best available approximation of the current reading of the remote clock.
 *
 * Immediately after synchronization minLag is set to zero and the remote clock guaranteed to lag
 * behind what micros() reports by at most maxLag.
 *
 * Immediately after synchronization or an update of the bounds (minLag, maxLag) the following holds
 * t_remote + minLag < micros() < t_rmote + maxLag
 *
 * For more details about clock synchronization refer to
 * https://github.com/google/walt/blob/master/android/WALT/app/src/main/jni/README.md
 * and sync_clock.c
 */

public class RemoteClockInfo {
    public int minLag;
    public int maxLag;
    public long baseTime;


    public long micros() {
        return microTime() - baseTime;
    }

    public static long microTime() {
        return System.nanoTime() / 1000;
    }


    /**
     Find the wall time when uptime was zero = CLOCK_REALTIME - CLOCK_MONOTONIC

     Needed for TCP bridge because Python prior to 3.3 has no direct access to CLOCK_MONOTONIC
     so the bridge returns timestamps as wall time and we need to convert them to CLOCK_MONOTONIC.

     See:
     [1] https://docs.python.org/3/library/time.html#time.CLOCK_MONOTONIC
     [2] http://stackoverflow.com/questions/14270300/what-is-the-difference-between-clock-monotonic-clock-monotonic-raw
     [3] http://stackoverflow.com/questions/1205722/how-do-i-get-monotonic-time-durations-in-python

     android.os.SystemClock.currentTimeMicros() is hidden by @hide which means it can't be called
     directly - calling it via reflection.

     See:
     http://stackoverflow.com/questions/17035271/what-does-hide-mean-in-the-android-source-code
     */
    public static long uptimeZero() {
        long t = -1;
        long dt = Long.MAX_VALUE;
        try {
            Class cls = Class.forName("android.os.SystemClock");
            Method myTimeGetter = cls.getMethod("currentTimeMicro");
            t = (long) myTimeGetter.invoke(null);
            dt = t - microTime();
        } catch (Exception e) {
            Log.i("WALT.uptimeZero", e.getMessage());
        }

        return dt;
    }

    public static long currentTimeMicro() {

        long t = -1;
        try {
            Class cls = Class.forName("android.os.SystemClock");
            Method myTimeGetter = cls.getMethod("currentTimeMicro");
            t = (long) myTimeGetter.invoke(null);
        } catch (Exception e) {
            Log.i("WALT.currentTimeMicro", e.getMessage());
        }

        return t;
    }

    public int getMeanLag() {
        return (minLag + maxLag) / 2;
    }

    public String toString(){
        return "Remote clock [us]: current time = " + micros() + " baseTime = " + baseTime +
                " lagBounds = (" + minLag + ", " + maxLag + ")";
    }
}
