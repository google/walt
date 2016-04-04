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

#include "sync_clock.h"

#include <android/log.h>
#include <jni.h>


#define APPNAME "ClockSyncJNI"

// This is global so that we don't have to pass it aroundbetween Java and here.
// TODO: come up with some more elegant solution.
struct clock_connection clk;

jlong
Java_org_chromium_latency_walt_ClockManager_syncClock(
    JNIEnv* env,
    jobject thiz,
    jint fd,
    jint endpoint_out,
    jint endpoint_in
){
    clk.fd = (int)fd;
    clk.endpoint_in = (int)endpoint_in;
    clk.endpoint_out = (int)endpoint_out;
    clk.t_base = 0;
    sync_clocks(&clk);
    // __android_log_print(ANDROID_LOG_VERBOSE, APPNAME, "Returned from sync_clocks\n");

    int64_t t_base = clk.t_base;
    return (jlong) t_base;
}

void
Java_org_chromium_latency_walt_ClockManager_updateBounds() {
    update_bounds(&clk);
}

jint
Java_org_chromium_latency_walt_ClockManager_getMinE() {
    return clk.minE;
}


jint
Java_org_chromium_latency_walt_ClockManager_getMaxE() {
    return clk.maxE;
}
