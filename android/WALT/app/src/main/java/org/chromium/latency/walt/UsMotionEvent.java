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

import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * A convenient representation of MotionEvent events
 * - microsecond accuracy
 * - no bundling of ACTION_MOVE events
 */

public class UsMotionEvent {

    public long physicalTime, kernelTime, createTime;
    public float x, y;
    public int slot;
    public int action;
    public int num;
    public String metadata;
    public long baseTime;

    public boolean isOk = false;

    /**
     *
     * @param event - MotionEvent as received by the handler.
     * @param baseTime - base time of the last clock sync.
     */
    public UsMotionEvent(MotionEvent event, long baseTime) {
        createTime = RemoteClockInfo.microTime() - baseTime;
        this.baseTime = baseTime;
        slot = -1;
        kernelTime = event.getEventTimeNanos() / 1000 - baseTime;
        x = event.getX();
        y = event.getY();
        action = event.getAction();
    }

    public UsMotionEvent(MotionEvent event, long baseTime, int pos) {
        createTime = RemoteClockInfo.microTime() - baseTime;
        this.baseTime = baseTime;
        slot = pos;
        action = MotionEvent.ACTION_MOVE; // Only MOVE events get bundled with history

        kernelTime = event.getHistoricalEventTimeNanos(pos) / 1000 - baseTime;
        x = event.getHistoricalX(pos);
        y = event.getHistoricalY(pos);
    }

    public String getActionString() {
        return actionToString(action);
    }


    public String toString() {
        return String.format("%d %f %f",
                kernelTime, x, y);

    }

    public String toStringLong() {
        return String.format("Event: t=%d x=%.1f y=%.1f slot=%d num=%d %s",
                kernelTime, x, y, slot, num, actionToString(action));

    }

    // The MotionEvent.actionToString is not present before API 19
    public static String actionToString(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "ACTION_DOWN";
            case MotionEvent.ACTION_UP:
                return "ACTION_UP";
            case MotionEvent.ACTION_CANCEL:
                return "ACTION_CANCEL";
            case MotionEvent.ACTION_OUTSIDE:
                return "ACTION_OUTSIDE";
            case MotionEvent.ACTION_MOVE:
                return "ACTION_MOVE";
            case MotionEvent.ACTION_HOVER_MOVE:
                return "ACTION_HOVER_MOVE";
            case MotionEvent.ACTION_SCROLL:
                return "ACTION_SCROLL";
            case MotionEvent.ACTION_HOVER_ENTER:
                return "ACTION_HOVER_ENTER";
            case MotionEvent.ACTION_HOVER_EXIT:
                return "ACTION_HOVER_EXIT";
        }
        return "UNKNOWN_ACTION";
    }

}
