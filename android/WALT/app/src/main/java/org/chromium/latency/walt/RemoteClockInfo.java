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

    public int getMeanLag() {
        return (minLag + maxLag) / 2;
    }

    public String toString(){
        return "Remote clock [us]: current time = " + micros() + " baseTime = " + baseTime +
                " lagBounds = (" + minLag + ", " + maxLag + ")";
    }
}
