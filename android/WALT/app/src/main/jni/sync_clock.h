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

#include <inttypes.h>

#define CLOCK_BUFFER_LENGTH 512

// Commands, original definitions in TensyUSB side code.
#define CMD_RESET               'F' // Reset all vars
#define CMD_SYNC_SEND           'I' // Send some digits for clock sync
#define CMD_SYNC_READOUT        'R' // Read out sync times
#define CMD_SYNC_ZERO           'Z' // Initial zero


struct clock_connection {
    int fd;
    int endpoint_in;
    int endpoint_out;
    int64_t t_base;
    char buffer[CLOCK_BUFFER_LENGTH];
    int minE;
    int maxE;
};


// Returns microseconds elapsed since boot
int64_t uptimeMicros();

// Returns microseconds elapsed since last clock sync
int micros(struct clock_connection *clk);

// Runs clock synchronization logic
void sync_clocks(struct clock_connection *clk);

// Run the sync logic without changing clocks, used for estimating clock drift
void update_bounds(struct clock_connection *clk);

