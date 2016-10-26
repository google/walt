#!/usr/bin/env python
#
# Copyright 2016 The Chromium OS Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Runs the touchpad drag latency test using WALT Latency Timer
Usage example:
    $ python walt.py 11
    Input device   : /dev/input/event11
    Serial device  : /dev/ttyACM1
    Laser log file : /tmp/WALT_2016_06_23__1714_51_laser.log
    evtest log file: /tmp/WALT_2016_06_23__1714_51_evtest.log
    Clock zeroed at 1466716492 (rt 0.284ms)
    ........................................
    Processing data, may take a minute or two...
    Drag latency (min method) = 15.37 ms

Note, before running this script, check that evtest can grab the device.
On some systems it requires running as root.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile
import time

import serial
import numpy

import evparser
import minimization
import screen_stats


# Globals
debug_mode = False


def log(msg):
    if debug_mode:
        print(msg)


class Walt(object):
    """ A class for communicating with Wlat device

    Usage:
    with Walt('/dev/ttyUSB0') as walt:
        body....


    """
    # Teensy commands (always singe char). Defined in WALT.ino
    # TODO(kamrik): link to WALT.ino once it's opensourced.
    CMD_RESET = 'F'
    CMD_SYNC_ZERO = 'Z'
    CMD_TIME_NOW = 'T'
    CMD_AUTO_LASER_ON = 'L'
    CMD_AUTO_LASER_OFF = 'l'
    CMD_AUTO_SCREEN_ON = 'C'
    CMD_AUTO_SCREEN_OFF = 'c'
    CMD_GSHOCK = 'G'
    CMD_VERSION = 'V'
    CMD_SAMPLE_ALL = 'Q'
    CMD_BRIGHTNESS_CURVE = 'U'


    def __init__(self, serial_dev, timeout=None):
        self.serial_dev = serial_dev
        self.ser = serial.Serial(serial_dev, baudrate=115200, timeout=timeout)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        try:
            self.ser.close()
        except:
            pass

    def close(self):
        self.ser.close()

    def readline(self):
        return self.ser.readline()

    def sndrcv(self, data):
        """ Send a 1-char command.
        Return the reply and how long it took.

        """
        t0 = time.time()
        self.ser.write(data)
        reply = self.ser.readline()
        t1 = time.time()
        dt = (t1 - t0) * 1000
        log('sndrcv(): round trip %.3fms, reply=%s' % (dt, reply.strip()))
        return dt, reply

    def readShockTime(self):
        dt, s = self.sndrcv(Walt.CMD_GSHOCK)
        t_us = int(s.strip())
        return t_us


    def runCommStats(self, N=100):
        """
        Measure the USB serial round trip time.
        Send CMD_TIME_NOW to the Teensy N times measuring the round trip each time.
        Prints out stats (min, median, max).

        """
        log('Running USB comm stats...')
        self.ser.flushInput()
        self.sndrcv(Walt.CMD_SYNC_ZERO)
        tstart = time.time()
        times = numpy.zeros((N, 1))
        for i in range(N):
            dt, _ = self.sndrcv(Walt.CMD_TIME_NOW)
            times[i] = dt
        t_tot = (time.time() - tstart)*1000

        median = numpy.median(times)
        stats = (times.min(), median, times.max(), N)
        log('USB comm round trip stats:')
        log('min=%.2fms, median=%.2fms, max=%.2fms N=%d' % stats)
        if (median > 2):
            print('ERROR: the median round trip is too high: %.2f' % median)
            sys.exit(2)

    def zeroClock(self, max_delay_ms=1.0, retries=10):
        """
        Tell the TeensyUSB to zero its clock (CMD_SYNC_ZERO).
        Returns the time when the command was sent.
        Verify that the response arrived within max_delay_ms.

        This is the simple zeroing used when the round trip is fast.
        It does not employ the same method as Android clock sync.
        """

        # Check that we get reasonable ping time with Teensy
        # this also 'warms up' the comms, first msg is often slower
        self.runCommStats(N=10)

        self.ser.flushInput()

        for i in range(retries):
            t0 = time.time()
            dt, _ = self.sndrcv(Walt.CMD_SYNC_ZERO)
            if dt < max_delay_ms:
                print('Clock zeroed at %.0f (rt %.3fms)' % (t0, dt))
                return t0
        print('Error, failed to zero the clock after %d retries')
        return -1

    def parseTrigger(self, trigger_line):
        """ Parse a trigger line from WALT.

        Trigger events look like this: "G L 12902345 1 1"
        The parts:
         * G - common for all trigger events
         * L - means laser
         * 12902345 is timestamp in us since zeroed
         * 1st 1 or 0 is trigger value. 0 = changed to dark, 1 = changed to light,
         * 2nd 1 is counter of how many times this trigger happened since last
           readout, should always be 1 in our case

        """

        parts = trigger_line.strip().split()
        if len(parts) != 5:
            raise Exception('Malformed trigger line: "%s"\n' % trigger_line)
        t_us = int(parts[2])
        val = int(parts[3])
        return (t_us / 1e6, val)


def array2str(a):
    a_strs = ['%0.2f' % x for x in a]
    s = ', '.join(a_strs)
    return '[' + s + ']'


def parse_args():
    temp_dir = tempfile.gettempdir()
    serial = '/dev/ttyACM0'

    # Try to autodetect the WALT serial port
    ls_ttyACM = glob.glob('/dev/ttyACM*')
    if len(ls_ttyACM) > 0:
        serial = ls_ttyACM[0]

    description = "Run the touchpad drag latency test using WALT Latency Timer"
    parser = argparse.ArgumentParser(
        description=description,
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)

    parser.add_argument('-i', '--input', default='',
                        help='input device, e.g: 6 or /dev/input/event6')
    parser.add_argument('-s', '--serial', default=serial,
                        help='WALT serial port')
    parser.add_argument('-t', '--type', default='drag',
                        help='Test type: drag|tap|screen|sanity|curve')
    parser.add_argument('-l', '--logdir', default=temp_dir,
                        help='where to store logs')
    parser.add_argument('-n', default=40, type=int,
                        help='Number of laser toggles to read')
    parser.add_argument('-d', '--debug', action='store_true',
                        help='talk more')
    args = parser.parse_args()

    global debug_mode
    debug_mode = args.debug

    if args.input.isalnum():
        args.input = '/dev/input/event' + args.input

    return args


def run_drag_latency_test(args):

    if not args.input:
        print('Error: --input argument is required for drag latency test')
        sys.exit(1)

    # Create names for log files
    prefix = time.strftime('WALT_%Y_%m_%d__%H%M_%S')
    laser_file_name = os.path.join(args.logdir,  prefix + '_laser.log')
    evtest_file_name = os.path.join(args.logdir,  prefix + '_evtest.log')

    print('Starting drag latency test')
    print('Input device   : ' + args.input)
    print('Serial device  : ' + args.serial)
    print('Laser log file : ' + laser_file_name)
    print('evtest log file: ' + evtest_file_name)

    with Walt(args.serial) as walt:
        walt.sndrcv(Walt.CMD_RESET)
        tstart = time.time()
        t_zero = walt.zeroClock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exitting')
            sys.exit(1)

        # Fire up the evtest process
        cmd = 'evtest %s > %s' % (args.input, evtest_file_name)
        evtest = subprocess.Popen(cmd, shell=True)

        # Turn on laser trigger auto-sending
        walt.sndrcv(Walt.CMD_AUTO_LASER_ON)
        trigger_count = 0
        while trigger_count < args.n:
            # The following line blocks until a message from WALT arrives
            trigger_line = walt.readline()
            trigger_count += 1
            log('#%d/%d - ' % (trigger_count, args.n) +
                trigger_line.strip())

            if not debug_mode:
                sys.stdout.write('.')
                sys.stdout.flush()

            t, val = walt.parseTrigger(trigger_line)
            t += t_zero
            with open(laser_file_name, 'at') as flaser:
                flaser.write('%.3f %d\n' % (t, val))
        walt.sndrcv(Walt.CMD_AUTO_LASER_OFF)

    # Send SIGTERM to evtest process
    evtest.terminate()

    print "\nProcessing data, may take a minute or two..."
    # lm.main(evtest_file_name, laser_file_name)
    minimization.minimize(evtest_file_name, laser_file_name)

def run_screen_curve(args):

    with Walt(args.serial, timeout=1) as walt:
        walt.sndrcv(Walt.CMD_RESET)

        t_zero = walt.zeroClock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exitting')
            sys.exit(1)

        # Fire up the walt_blinker process
        cmd = 'walt_blink 1'
        blinker = subprocess.Popen(cmd, shell=True)

        # Turn on laser trigger auto-sending
        walt.sndrcv(Walt.CMD_BRIGHTNESS_CURVE)
        s = 'dummy'
        while s:
            s = walt.readline()
            print(s.strip())


def run_screen_latency_test(args):

    # Create names for log files
    prefix = time.strftime('WALT_%Y_%m_%d__%H%M_%S')
    sensor_file_name = os.path.join(args.logdir,  prefix + '_screen_sensor.log')
    blinker_file_name = os.path.join(args.logdir,  prefix + '_blinker.log')

    print('Starting drag latency test')
    print('Input device   : ' + args.input)
    print('Serial device  : ' + args.serial)
    print('Laser log file : ' + sensor_file_name)
    print('evtest log file: ' + blinker_file_name)

    with Walt(args.serial, timeout=1) as walt:
        walt.sndrcv(Walt.CMD_RESET)

        t_zero = walt.zeroClock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exitting')
            sys.exit(1)

        # Fire up the walt_blinker process
        cmd = 'walt_blink %d > %s' % (args.n, blinker_file_name, )
        blinker = subprocess.Popen(cmd, shell=True)

        # Turn on laser trigger auto-sending
        walt.sndrcv(Walt.CMD_AUTO_SCREEN_ON)
        trigger_count = 0

        # Iterate while the blinker process is alive
        # TODO: re-sync clocks every once in a while
        while blinker.poll() is None:
            # The following line blocks until a message from WALT arrives
            trigger_line = walt.readline()
            if not trigger_line:
                # This usually happens when readline timeouts on last iteration
                continue
            trigger_count += 1
            log('#%d/%d - ' % (trigger_count, args.n) +
                trigger_line.strip())

            if not debug_mode:
                sys.stdout.write('.')
                sys.stdout.flush()

            t, val = walt.parseTrigger(trigger_line)
            t += t_zero
            with open(sensor_file_name, 'at') as flaser:
                flaser.write('%.3f %d\n' % (t, val))
        walt.sndrcv(Walt.CMD_AUTO_SCREEN_OFF)

    print("\nProcessing data ...")
    screen_stats.screen_stats(blinker_file_name, sensor_file_name)


def run_tap_latency_test(args):

    if not args.input:
        print('Error: --input argument is required for tap latency test')
        sys.exit(1)

    print('Starting tap latency test')

    with Walt(args.serial) as walt:
        walt.sndrcv(Walt.CMD_RESET)
        tstart = time.time()
        t_zero = walt.zeroClock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exitting')
            sys.exit(1)

        # Fire up the evtest process
        cmd = 'evtest ' + args.input
        evtest = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, bufsize=1, universal_newlines=True)
        walt.sndrcv(Walt.CMD_GSHOCK)

        taps_detected = 0
        taps = []
        while taps_detected < args.n:
            ev_line = evtest.stdout.readline()
            tap_info = evparser.parse_tap_line(ev_line)
            if not tap_info:
                continue

            # Just received a tap event from evtest
            taps_detected += 1

            t_tap_epoch, direction = tap_info
            shock_time_us = walt.readShockTime()
            dt_tap_us = 1e6 * (t_tap_epoch - t_zero) - shock_time_us

            print ev_line
            print("shock t %d, tap t %f, tap val %d. dt=%0.1f" % (shock_time_us, t_tap_epoch, direction, dt_tap_us))

            if shock_time_us == 0:
                print "No shock detected, skipping this event"
                continue

            taps.append((dt_tap_us, direction))

    evtest.terminate()

    # Process data
    print("\nProcessing data...")
    dt_down = numpy.array([t[0] for t in taps if t[1] == 1]) / 1e3
    dt_up = numpy.array([t[0] for t in taps if t[1] == 0]) / 1e3

    print('dt_down = ' + array2str(dt_down))
    print('dt_up = ' + array2str(dt_up))

    median_down_ms = numpy.median(dt_down)
    meidan_up_ms = numpy.median(dt_up)

    print('Median latency, down: %0.1f, up: %0.1f' % (median_down_ms, meidan_up_ms))


def run_walt_sanity_test(args):
    print('Starting sanity test')

    with Walt(args.serial) as walt:
        walt.sndrcv(Walt.CMD_RESET)

        not_digit = re.compile('\D+')
        lows = numpy.zeros(3) + 1024
        highs = numpy.zeros(3)
        while True:
            t, s = walt.sndrcv(Walt.CMD_SAMPLE_ALL)
            nums = not_digit.sub(' ', s).strip().split()
            if not nums:
                continue
            ints = numpy.array([int(x) for x in nums])
            lows = numpy.array([lows, ints]).min(axis=0)
            highs = numpy.array([highs, ints]).max(axis=0)

            minmax = ' '.join(['%d-%d' % (lows[i], highs[i]) for i in range(3)])
            print(s.strip() + '\tmin-max: ' + minmax)
            time.sleep(0.1)


if __name__ == '__main__':
    args = parse_args()
    if args.type == 'tap':
        run_tap_latency_test(args)
    elif args.type == 'screen':
        run_screen_latency_test(args)
    elif args.type == 'sanity':
        run_walt_sanity_test(args)
    elif args.type == 'curve':
        run_screen_curve(args)
    else:
        run_drag_latency_test(args)

