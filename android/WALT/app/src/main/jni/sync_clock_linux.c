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

#include <errno.h>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <unistd.h>


int main(int argc, char ** argv) {
    if(argc < 2) {
        printf("Usage %s <device_path>\n"
               "Try `lsusb | grep eensy` and use /dev/bus/usb/<Bus>/<Device>\n",
               argv[0]);
        return 1;
    }

    printf("Opening %s\n", argv[1]);
    int fd = open(argv[1], O_RDWR);
    printf("open() fd=%d, errno=%d, %s\n", fd, errno, strerror(errno));

    // The interface and endpoint numbers are defined by the TeensyUSB. They may
    // be different depending on the mode (Serial vs HID) the Teensy code is
    // compiled in. A real app would employ some discovery logic here. To list
    // the interfaces and endpoints use `lsusb --verbose` or an app like USB
    // Host Viewer on Android. Look for a "CDC Data" interface (class 0x0a).
    int interface = 1;
    int ep_out = 0x03;
    int ep_in = 0x84;

    int ret = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &interface);
    printf("Interface claimed retval=%d, errno=%d, %s\n", ret, errno, strerror(errno));
    if (errno == EBUSY) {
        printf("You may need to run 'sudo rmmod cdc_acm' to release the "
               "interface claimed by the kernel serial driver.");
        return 1;
    }

    struct clock_connection clk;
    clk.fd = fd;
    clk.endpoint_in = ep_in;
    clk.endpoint_out = ep_out;

    sync_clocks(&clk);

    printf("===========================\n"
           "sync_clocks base_t=%lld, minE=%d, maxE=%d\n",
           (long long int)clk.t_base, clk.minE, clk.maxE);

    // Check for clock drift. Try sleeping here to let it actually drift away.
    update_bounds(&clk);

    printf("*** UPDATE ****************\n"
           "Update_bounds base_t=%lld, minE=%d, maxE=%d\n",
           (long long int)(clk.t_base), clk.minE, clk.maxE
    );


    close(fd);
    return 0;
}