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

#include <asm/byteorder.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/usbdevice_fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>


#ifdef __ANDROID__
    #include <android/log.h>
    #define  LOGD(...)  __android_log_print(ANDROID_LOG_VERBOSE, "ClockSyncNative", __VA_ARGS__)
#else
    #define  LOGD(...)  printf(__VA_ARGS__)
#endif


// How many times to repeat the 1..9 digit sequence it's a tradeoff between
// precision and how long it takes.
// TODO: investigate better combination of constants for repeats and wait times
const int kSyncRepeats = 7;
const int kMillion = 1000000;


/**
uptimeMicros() - returns microseconds elapsed since boot.
Same time as Android's SystemClock.uptimeMillis() but in microseconds.

Adapted from Android:
platform/system/core/libutils/Timers.cpp
platform/system/core/include/utils/Timers.h

See:
http://developer.android.com/reference/android/os/SystemClock.html
https://android.googlesource.com/platform/system/core/+/master/libutils/Timers.cpp
*/
int64_t uptimeMicros() {
    struct timespec ts = {0};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((int64_t)ts.tv_sec) * kMillion + ts.tv_nsec / 1000;
}


// Sleeps us microseconds
int microsleep(int us) {
    struct timespec ts = {0};
    ts.tv_sec = us / kMillion;
    us %= kMillion;
    ts.tv_nsec = us*1000;
    nanosleep(&ts, NULL);
    return 0;
}


// *********************** Generic USB functions *******************************

static int send_char_async(int fd, int endpoint, char msg, char * label) {
    // TODO: Do we really need a buffer longer than 1 char here?
    char buffer[256] = {0};
    buffer[0] = msg;
    int length = 1;

    // TODO: free() the memory used for URBs.
    // Circular buffer of URBs? Cleanup at the end of clock sync?
    // Several may be used simultaneously, no signal when done.
    struct usbdevfs_urb *urb = calloc(1, sizeof(struct usbdevfs_urb));
    memset(urb, 0, sizeof(struct usbdevfs_urb));

    int res;
    urb->status = -1;
    urb->buffer = buffer;
    urb->buffer_length = length;
    urb->endpoint = endpoint;
    urb->type = USBDEVFS_URB_TYPE_BULK;
    urb->usercontext = label; // This is hackish
    do {
        res = ioctl(fd, USBDEVFS_SUBMITURB, urb);
    } while((res < 0) && (errno == EINTR));
    return res;
}


// Send or read using USBDEVFS_BULK. Allows to set a timeout.
static int bulk_talk(int fd, int endpoint, char * buffer, int length) {
    // Set some reasonable timeout. 20ms is plenty time for most transfers but
    // short enough to fail quickly if all transfers and retries fail with
    // timeout.
    const int kTimeoutMs = 20;
    struct usbdevfs_bulktransfer ctrl = {0};
    // TODO: need to limit request size to avoid EINVAL

    ctrl.ep = endpoint;
    ctrl.len = length;
    ctrl.data = buffer;
    ctrl.timeout = kTimeoutMs;
    int ret = ioctl(fd, USBDEVFS_BULK, &ctrl);
    return ret;
}


/*******************************************************************************
* Clock sync specific stuff below.
* Most data is stored in the clock_connection struct variable.
*/

// Send a single character to the remote in a blocking mode
int send_cmd(struct clock_connection *clk, char cmd) {
    return bulk_talk(clk->fd, clk->endpoint_out, &cmd, 1);
}

// Schedule a single character to be sent to the remote - async.
int send_async(struct clock_connection *clk, char cmd) {
    return send_char_async(clk->fd, clk->endpoint_out, cmd, NULL);
}


int bulk_read(struct clock_connection *clk) {
    memset(clk->buffer, 0, sizeof(clk->buffer));
    int ret = bulk_talk(clk->fd, clk->endpoint_in, clk->buffer, sizeof(clk->buffer));
    return ret;
}

// microseconds elapsed since clk->t_base
int micros(struct clock_connection *clk) {
    return uptimeMicros() - clk->t_base;
}

// Clear all incoming data that's already waiting somewhere in kernel buffers
// and discard it.
void flush_incoming(struct clock_connection *clk) {
    // When bulk_read times out errno = ETIMEDOUT=110, retval =-1
    // should we check for this?

    while(bulk_read(clk) >= 0) {
        // TODO: fail nicely if waiting too long to avoid hangs
    }
}

// Ask the remote to send its timestamps
// for the digits previously sent to it.
void read_remote_timestamps(struct clock_connection *clk, int * times_remote) {
    int i;
    int t_remote;
    // Go over the digits [1, 2 ... 9]
    for (i = 0; i < 9; i++) {
        char digit = i + '1';
        send_cmd(clk, CMD_SYNC_READOUT);
        bulk_read(clk);
        if (clk->buffer[0] != digit) {
            LOGD("Error, bad reply for R%d: %s", i+1, clk->buffer);
        }
        // The reply string looks like digit + space + timestamp
        // Offset by 2 to ignore the digit and the space
        t_remote = atoi(clk->buffer + 2);
        times_remote[i] = t_remote;
    }
}


// Preliminary rough sync with a single message - CMD_SYNC_ZERO = 'Z'.
// This is not strictly necessary but greatly simplifies debugging
// by removing the need to look at very long numbers.
void zero_remote(struct clock_connection *clk) {
    flush_incoming(clk);
    clk->t_base = uptimeMicros();
    send_cmd(clk, CMD_SYNC_ZERO);
    bulk_read(clk); // TODO, make sure we got 'z'
    clk->maxE = micros(clk);
    clk->minE = 0;

    LOGD("Sent a 'Z', reply '%c' in %d us\n", clk->buffer[0], clk->maxE);
}



void improve_minE(struct clock_connection *clk) {
    int times_local_sent[9] = {0};
    int times_remote_received[9] = {0};

    // Set sleep time as 1/kSleepTimeDivider of the current bounds interval,
    // but never less or more than k(Min/Max)SleepUs. All pretty random
    // numbers that could use some tuning and may behave differently on
    // different devices.
    const int kMaxSleepUs = 700;
    const int kMinSleepUs = 70;
    const int kSleepTimeDivider = 10;
    int minE = clk->minE;
    int sleep_time = (clk->maxE - minE) / kSleepTimeDivider;
    if(sleep_time > kMaxSleepUs) sleep_time = kMaxSleepUs;
    if(sleep_time < kMinSleepUs) sleep_time = kMinSleepUs;

    flush_incoming(clk);
    // Send digits to remote side
    int i;
    for (i = 0; i < 9; i++) {
        char c = i + '1';
        times_local_sent[i] = micros(clk);
        send_async(clk, c);
        microsleep(sleep_time);
    }

    // Read out receive times from the other side
    read_remote_timestamps(clk, times_remote_received);

    // Do stats
    for (i = 0; i < 9; i++) {
        int tls = times_local_sent[i];
        int trr = times_remote_received[i];

        int dt;

        // Look at outgoing digits
        dt = tls - trr;
        if (tls != 0 && trr != 0 && dt > minE) {
            minE = dt;
        }

    }

    clk->minE = minE;

    LOGD("E is between %d and %d us, sleep_time=%d\n", clk->minE, clk->maxE, sleep_time);
}

void improve_maxE(struct clock_connection *clk) {
    int times_remote_sent[9] = {0};
    int times_local_received[9] = {0};

    // Tell the remote to send us digits with delays
    // TODO: try tuning / configuring the delay time on remote side
    send_async(clk, CMD_SYNC_SEND);

    // Read and timestamp the incoming digits, they may arrive out of order.
    // TODO: Try he same with USBDEVFS_REAPURB, it might be faster
    int i;
    for (i = 0; i < 9; ++i) {
        int retval = bulk_read(clk);
        // TODO: deal with retval = (bytes returned) > 1. shouldn't happen.
        // Can it happen on some devices?
        int t_local = micros(clk);
        int digit = atoi(clk->buffer);
        if (digit <=0 || digit > 9) {
            LOGD("Error, bad incoming digit: %s\n", clk->buffer);
        }
        times_local_received[digit-1] = t_local;
    }

    // Flush whatever came after the digits. As of this writing, it's usually
    // a single linefeed character.
    flush_incoming(clk);
    // Read out the remote timestamps of when the digits were sent
    read_remote_timestamps(clk, times_remote_sent);

    // Do stats
    int maxE = clk->maxE;
    for (i = 0; i < 9; i++) {
        int trs = times_remote_sent[i];
        int tlr = times_local_received[i];
        int dt = tlr - trs;
        if (tlr != 0 && trs != 0 && dt < maxE) {
            maxE = dt;
        }
    }

    clk->maxE = maxE;

    LOGD("E is between %d and %d us\n", clk->minE, clk->maxE);
}


void improve_bounds(struct clock_connection *clk) {
    improve_minE(clk);
    improve_maxE(clk);
}

// get minE and maxE again after some time to check for clock drift
void update_bounds(struct clock_connection *clk) {
    // Reset the bounds to some unrealistically large numbers
    int i;
    clk->minE = -1e7;
    clk->maxE =  1e7;
    // Talk to remote to get bounds on minE and maxE
    for (i=0; i < kSyncRepeats; i++) {
        improve_bounds(clk);
    }
}

void sync_clocks(struct clock_connection *clk) {
    // Send CMD_SYNC_ZERO to remote for rough initial sync
    zero_remote(clk);

    int rep;
    for (rep=0; rep < kSyncRepeats; rep++) {
        improve_bounds(clk);
    }

    // Shift the base time to set minE = 0
    clk->t_base += clk->minE;
    clk->maxE -= clk->minE;
    clk->minE = 0;
    LOGD("Base time shifted for zero minE\n");
}


