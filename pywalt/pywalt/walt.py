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
import contextlib
import glob
import os
import random
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time

import serial
import numpy

from . import evparser
from . import minimization
from . import screen_stats


# Time units
MS = 1e-3  # MS = 0.001 seconds
US = 1e-6  # US = 10^-6 seconds

# Globals
debug_mode = True


def log(msg):
    if debug_mode:
        print(msg)


class Walt(object):
    """ A class for communicating with Walt device

    Usage:
    with Walt('/dev/ttyUSB0') as walt:
        body....


    """

    # Teensy commands, always singe char. Defined in WALT.ino
    # github.com/google/walt/blob/master/arduino/walt/walt.ino
    CMD_RESET = 'F'
    CMD_PING = 'P'
    CMD_SYNC_ZERO = 'Z'
    CMD_SYNC_SEND = 'I'
    CMD_SYNC_READOUT = 'R'
    CMD_TIME_NOW = 'T'
    CMD_AUTO_LASER_ON = 'L'
    CMD_AUTO_LASER_OFF = 'l'
    CMD_AUTO_SCREEN_ON = 'C'
    CMD_AUTO_SCREEN_OFF = 'c'
    CMD_GSHOCK = 'G'
    CMD_VERSION = 'V'
    CMD_SAMPLE_ALL = 'Q'
    CMD_BRIGHTNESS_CURVE = 'U'
    CMD_AUDIO = 'A'


    def __init__(self, serial_dev, timeout=None, encoding='utf-8'):
        self.encoding = encoding
        self.serial_dev = serial_dev
        self.ser = serial.Serial(serial_dev, baudrate=115200, timeout=timeout)
        self.base_time = None
        self.min_lag = None
        self.max_lag = None
        self.median_latency = None

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
        return self.ser.readline().decode(self.encoding)

    def sndrcv(self, data):
        """ Send a 1-char command.
        Return the reply and how long it took.

        """
        t0 = time.time()
        self.ser.write(data.encode(self.encoding))
        reply = self.ser.readline()
        reply = reply.decode(self.encoding)
        t1 = time.time()
        dt = (t1 - t0)
        log('sndrcv(): round trip %.3fms, reply=%s' % (dt / MS, reply.strip()))
        return dt, reply

    def read_shock_time(self):
        dt, s = self.sndrcv(Walt.CMD_GSHOCK)
        t_us = int(s.strip())
        return t_us


    def run_comm_stats(self, N=100):
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
        t_total = time.time() - tstart

        median = numpy.median(times)
        stats = (times.min() / MS, median / MS, times.max() / MS, N)
        self.median_latency = median
        log('USB comm round trip stats:')
        log('min=%.2fms, median=%.2fms, max=%.2fms N=%d' % stats)
        if (median > 2):
            print('ERROR: the median round trip is too high: %.2f ms' % (median / MS) )
            sys.exit(2)

    def zero_clock(self, max_delay=0.001, retries=10):
        """
        Tell the TeensyUSB to zero its clock (CMD_SYNC_ZERO).
        Returns the time when the command was sent.
        Verify that the response arrived within max_delay seconds.

        This is the simple zeroing used when the round trip is fast.
        It does not employ the same method as Android clock sync.
        """

        # Check that we get reasonable ping time with Teensy
        # this also 'warms up' the comms, first msg is often slower
        self.run_comm_stats(N=10)

        self.ser.flushInput()

        for i in range(retries):
            t0 = time.time()
            dt, _ = self.sndrcv(Walt.CMD_SYNC_ZERO)
            if dt < max_delay:
                print('Clock zeroed at %.0f (rt %.3f ms)' % (t0, dt / MS))
                self.base_time = t0
                self.max_lag = dt
                self.min_lag = 0
                return t0
        print('Error, failed to zero the clock after %d retries')
        return -1

    def read_remote_times(self):
        """ Helper func, see doc string in estimate_lage()
        Read out the timestamps taken recorded by the Teensy.
        """
        times = numpy.zeros(9)
        for i in range(9):
            dt, reply = self.sndrcv(Walt.CMD_SYNC_READOUT)
            num, tstamp = reply.strip().split(':')
            # TODO: verify that num is what we expect it to be
            log('read_remote_times() CMD_SYNC_READOUT > w >  = %s' % reply)
            t = float(tstamp) * US  # WALT sends timestamps in microseconds
            times[i] = t
        return times

    def estimate_lag(self):
        """ Estimate the difference between local and remote clocks

        This is based on:
        github.com/google/walt/blob/master/android/WALT/app/src/main/jni/README.md

        self.base_time needs to be set using self.zero_clock() before running
        this function.

        The result is saved as self.min_lag and self.max_lag. Assume that the
        remote clock lags behind the local by `lag` That is, at a given moment
        local_time = remote_time + lag
        where local_time = time.time() - self.base_time

        Immediately after this function completes the lag is guaranteed to be
        between min_lag and max_lag. But the lag change (drift) away with time.
        """
        self.ser.flushInput()

        # remote -> local
        times_local_received = numpy.zeros(9)
        self.ser.write(Walt.CMD_SYNC_SEND)
        for i in range(9):
            reply = self.ser.readline()
            times_local_received[i] = time.time() - self.base_time

        times_remote_sent = self.read_remote_times()
        max_lag = (times_local_received - times_remote_sent).min()

        # local -> remote
        times_local_sent = numpy.zeros(9)
        for i in range(9):
            s = '%d' % (i + 1)
            # Sleep between the messages to combat buffering
            t_sleep = US * random.randint(70, 700)
            time.sleep(t_sleep)
            times_local_sent[i] = time.time() - self.base_time
            self.ser.write(s)

        times_remote_received = self.read_remote_times()
        min_lag = (times_local_sent - times_remote_received).max()

        self.min_lag = min_lag
        self.max_lag = max_lag

    def parse_trigger(self, trigger_line):
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


def parse_args(argv):
    temp_dir = tempfile.gettempdir()
    serial = '/dev/ttyACM0'

    # Try to autodetect the WALT serial port
    ls_ttyACM = glob.glob('/dev/ttyACM*')
    if len(ls_ttyACM) > 0:
        serial = ls_ttyACM[0]

    description = "Run a latency test using WALT Latency Timer"
    parser = argparse.ArgumentParser(
        description=description,
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)

    parser.add_argument('-i', '--input',
                        help='input device, e.g: 6 or /dev/input/event6')
    parser.add_argument('-s', '--serial', default=serial,
                        help='WALT serial port')
    parser.add_argument('-t', '--type',
                        help='Test type: drag|tap|screen|sanity|curve|bridge|'
                             'tapaudio|tapblink')
    parser.add_argument('-l', '--logdir', default=temp_dir,
                        help='where to store logs')
    parser.add_argument('-n', default=40, type=int,
                        help='Number of laser toggles to read')
    parser.add_argument('-p', '--port', default=50007, type=int,
                        help='port to listen on for the TCP bridge')
    parser.add_argument('-d', '--debug', action='store_true',
                        help='talk more')
    args = parser.parse_args(argv)

    if not args.type:
        parser.print_usage()
        sys.exit(0)

    global debug_mode
    debug_mode = args.debug

    if args.input and args.input.isalnum():
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
        t_zero = walt.zero_clock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exiting')
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

            t, val = walt.parse_trigger(trigger_line)
            t += t_zero
            with open(laser_file_name, 'at') as flaser:
                flaser.write('%.3f %d\n' % (t, val))
        walt.sndrcv(Walt.CMD_AUTO_LASER_OFF)

    # Send SIGTERM to evtest process
    evtest.terminate()

    print("\nProcessing data, may take a minute or two...")
    # lm.main(evtest_file_name, laser_file_name)
    minimization.minimize(evtest_file_name, laser_file_name)


def run_screen_curve(args):

    with Walt(args.serial, timeout=1) as walt:
        walt.sndrcv(Walt.CMD_RESET)

        t_zero = walt.zero_clock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exiting')
            sys.exit(1)

        # Fire up the walt_blinker process
        cmd = 'blink_test 1'
        blinker = subprocess.Popen(cmd, shell=True)

        # Request screen brightness data
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

    print('Starting screen latency test')
    print('Serial device  : ' + args.serial)
    print('Sensor log file : ' + sensor_file_name)
    print('Blinker log file: ' + blinker_file_name)

    with Walt(args.serial, timeout=1) as walt:
        walt.sndrcv(Walt.CMD_RESET)

        t_zero = walt.zero_clock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exiting')
            sys.exit(1)

        # Fire up the walt_blinker process
        cmd = 'blink_test %d > %s' % (args.n, blinker_file_name, )
        blinker = subprocess.Popen(cmd, shell=True)

        # Turn on screen trigger auto-sending
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

            t, val = walt.parse_trigger(trigger_line)
            t += t_zero
            with open(sensor_file_name, 'at') as flaser:
                flaser.write('%.3f %d\n' % (t, val))
        walt.sndrcv(Walt.CMD_AUTO_SCREEN_OFF)
    screen_stats.screen_stats(blinker_file_name, sensor_file_name)


def run_tap_audio_test(args):
    print('Starting tap-to-audio latency test')
    with Walt(args.serial) as walt:
        walt.sndrcv(Walt.CMD_RESET)
        t_zero = walt.zero_clock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exiting')
            sys.exit(1)

        walt.sndrcv(Walt.CMD_GSHOCK)
        deltas = []
        while len(deltas) < args.n:
            sys.stdout.write('\rWAIT   ')
            sys.stdout.flush()
            time.sleep(1)  # Wait for previous beep to stop playing
            while walt.read_shock_time() != 0:
                pass  # skip shocks during sleep
            sys.stdout.write('\rTAP NOW')
            sys.stdout.flush()
            walt.sndrcv(Walt.CMD_AUDIO)
            trigger_line = walt.readline()
            beep_time_seconds, val = walt.parse_trigger(trigger_line)
            beep_time_ms = beep_time_seconds * 1e3
            shock_time_ms = walt.read_shock_time() / 1e3
            if shock_time_ms == 0:
                print("\rNo shock detected, skipping this event")
                continue
            dt = beep_time_ms - shock_time_ms
            deltas.append(dt)
            print("\rdt=%0.1f ms" % dt)
        print('Median tap-to-audio latency: %0.1f ms' % numpy.median(deltas))


def run_tap_blink_test(args):
    print('Starting tap-to-blink latency test')
    with Walt(args.serial) as walt:
        walt.sndrcv(Walt.CMD_RESET)
        t_zero = walt.zero_clock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exiting')
            sys.exit(1)

        walt.sndrcv(Walt.CMD_GSHOCK)
        walt.sndrcv(Walt.CMD_AUTO_SCREEN_ON)
        deltas = []
        while len(deltas) < args.n:
            trigger_line = walt.readline()
            blink_time_seconds, val = walt.parse_trigger(trigger_line)
            blink_time_ms = blink_time_seconds * 1e3
            shock_time_ms = walt.read_shock_time() / 1e3
            if shock_time_ms == 0:
                print("No shock detected, skipping this event")
                continue
            dt = blink_time_ms - shock_time_ms
            deltas.append(dt)
            print("dt=%0.1f ms" % dt)
        print('Median tap-to-blink latency: %0.1f ms' % numpy.median(deltas))


def run_tap_latency_test(args):

    if not args.input:
        print('Error: --input argument is required for tap latency test')
        sys.exit(1)

    print('Starting tap latency test')

    with Walt(args.serial) as walt:
        walt.sndrcv(Walt.CMD_RESET)
        t_zero = walt.zero_clock()
        if t_zero < 0:
            print('Error: Couldn\'t zero clock, exiting')
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
            shock_time_us = walt.read_shock_time()
            dt_tap_us = 1e6 * (t_tap_epoch - t_zero) - shock_time_us

            print(ev_line.strip())
            print("shock t %d, tap t %f, tap val %d. dt=%0.1f" % (shock_time_us, t_tap_epoch, direction, dt_tap_us))

            if shock_time_us == 0:
                print("No shock detected, skipping this event")
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
    median_up_ms = numpy.median(dt_up)

    print('Median latency, down: %0.1f, up: %0.1f' % (median_down_ms, median_up_ms))


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


class TcpServer:
    """


    """
    def __init__(self, walt, port=50007, host=''):
        self.running = threading.Event()
        self.paused = threading.Event()
        self.net = None
        self.walt = walt
        self.port = port
        self.host = host
        self.last_zero = 0.

    def ser2net(self, data):
        print('w>: ' + repr(data))
        return data

    def net2ser(self, data):
        print('w<: ' + repr(data))
        # Discard any empty data
        if not data or len(data) == 0:
            print('o<: discarded empty data')
            return None

        # Get a string version of the data for checking longer commands
        s = data.decode(self.walt.encoding)
        if s.startswith('bridge'):
            log('bridge command: %s, pausing ser2net thread...' % s)
            self.pause()
            is_sync = 'sync' in s
            if is_sync:
                self.walt.zero_clock()

            self.walt.estimate_lag()
            if is_sync:
                # shift the base so that min_lag is 0
                self.walt.base_time += self.walt.min_lag
                self.walt.max_lag -= self.walt.min_lag
                self.walt.min_lag = 0

            t0 = self.walt.base_time * 1e6
            min_lag = self.walt.min_lag * 1e6
            max_lag = self.walt.max_lag * 1e6
            reply = 'clock %d %d %d\n' % (t0, min_lag, max_lag)
            print('|custom-reply>: ' + repr(reply))
            self.net.sendall(reply)
            self.resume()
            return None

        return data

    def connections_loop(self):
        with contextlib.closing(socket.socket(
                socket.AF_INET, socket.SOCK_STREAM)) as sock:
            self.sock = sock
            # SO_REUSEADDR is supposed to prevent the "Address already in use" error
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((self.host, self.port))
            sock.listen(1)
            while True:
                print('Listening on port %d' % self.port)
                net, addr = sock.accept()
                self.net = net
                try:
                    print('Connected by: ' + str(addr))
                    self.net2ser_loop()
                except socket.error as e:
                    # IO errors with the socket, not sure what they are
                    print('Error: %s' % e)
                    break
                finally:
                    net.close()
                    self.net = None

    def net2ser_loop(self):
        while True:
            data = self.net.recv(1024)
            if not data:
                break  # got disconnected

            data = self.net2ser(data)
            if(data):
                self.walt.ser.write(data)

    def ser2net_loop(self):
        while True:
            self.running.wait()
            data = self.walt.readline()
            if self.net and self.running.is_set():
                data = self.ser2net(data)
                data = data.encode(self.walt.encoding)
                self.net.sendall(data)
            if not self.running.is_set():
                self.paused.set()

    def serve(self):
        t = self.ser2net_thread = threading.Thread(
            target=self.ser2net_loop,
            name='ser2net_thread'
        )
        t.daemon = True
        t.start()
        self.paused.clear()
        self.running.set()
        self.connections_loop()

    def pause(self):
        """ Pause serial -> net forwarding

        The ser2net_thread stays running, but won't read any incoming data
        from the serial port.
        """

        self.running.clear()
        # Send a ping to break out of the blocking read on serial port and get
        # blocked on running.wait() instead. The ping response is discarded.
        self.walt.ser.write(Walt.CMD_PING)
        # Wait until the ping response comes in and we are sure we are no longer
        # blocked on ser.read()
        self.paused.wait()
        print("Paused ser2net thread")

    def resume(self):
        self.running.set()
        self.paused.clear()
        print("Resuming ser2net thread")

    def close(self):
        try:
            self.sock.close()
        except:
            pass

        try:
            self.walt.close()
        except:
            pass

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def __enter__(self):
        return self


def run_tcp_bridge(args):

    print('Starting TCP bridge')
    print('You may need to run the following to allow traffic from the android container:')
    print('iptables -A INPUT -p tcp --dport %d -j ACCEPT' % args.port)

    try:
        with Walt(args.serial) as walt:
            with TcpServer(walt, port=args.port) as srv:
                walt.sndrcv(Walt.CMD_RESET)
                srv.serve()
    except KeyboardInterrupt:
        print(' KeyboardInterrupt, exiting...')


def main(argv=sys.argv[1:]):
    args = parse_args(argv)
    if args.type == 'drag':
        run_drag_latency_test(args)
    if args.type == 'tap':
        run_tap_latency_test(args)
    elif args.type == 'screen':
        run_screen_latency_test(args)
    elif args.type == 'sanity':
        run_walt_sanity_test(args)
    elif args.type == 'curve':
        run_screen_curve(args)
    elif args.type == 'bridge':
        run_tcp_bridge(args)
    elif args.type == 'tapaudio':
        run_tap_audio_test(args)
    elif args.type == 'tapblink':
        run_tap_blink_test(args)
    else:
        print('Unknown test type: "%s"' % args.type)


if __name__ == '__main__':
    main()
