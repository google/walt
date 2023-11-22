# Copyright 2014 The Chromium OS Authors. All rights reserved.
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
#
# Compute the touchpad drag latency
#
# This script computed the touch drag-latency given an evtest log and a
# Quickstep log that were collected while moving a finger at a fixed speed
# back and forth over the laser beam on the pad.  It generates the numeric
# calculations (in seconds) as well as an image that visualizes the data
# for debugging purposes.  This image is useful if the results are surprising,
# as it will often make it very obvious what the issue was.
#
# Usage:
#   1.) Align the Quickstep laser horizontally across the device.  Make sure
#       that the beam is low across the surface and squarely centered on the
#       receiever.
#   2.) Begin evtest log collection while nothing is touching the device.
#           evtest > evtest_log.txt
#   3.) Move a single finger at a constant speed back and forth over the laser
#       on the pad at least 20 times.  A robot will be able to produce more
#       consistent results than a hand.
#   4.) Save a copy of the Quickstep log by accessing its "laser" sysfs entry
#           find / -name 'laser'
#           cat $the_file_you_found_above > quickstep_log.txt
#   5.) Crunch the numbers by passing both file names to this script
#           python latency_measurement.py evtest_log.txt quickstep_log.txt

import subprocess
import json
import numpy as np
import re
import sys
from collections import namedtuple


FingerPosition = namedtuple('FingerPosition', ['timestamp', 'x', 'y'])
LaserCrossing = namedtuple('LaserCrossing', ['timestamp', 'direction'])

def get_evtest_timestamp(line):
    return float(line.split(',')[0].split(' ')[-1])

def get_evtest_value(line):
    return int(line.split(' ')[-1])

def get_finger_positions(filename):
    """ Parse the finger positions out of an evtest log
    This only works with a single contact.
    Returns a list of FingerPosition objects
    """
    log = open(filename, 'r')
    if not log:
        return None

    points = []
    fingers = {}
    curr_id = -1
    fingers[curr_id] = {}
    last_x = last_y = 0

    for line in log:
        if not line.startswith('Event: '):
            continue

        if 'ABS_MT_TRACKING_ID' in line:
            curr_id = get_evtest_value(line)
            if not fingers.get(curr_id, None):
                fingers[curr_id] = {}
        elif 'ABS_X' in line:
            fingers[curr_id]['x'] = get_evtest_value(line)
        elif 'ABS_Y' in line:
            fingers[curr_id]['y'] = get_evtest_value(line)
        elif 'SYN' in line and curr_id in fingers:
            t = get_evtest_timestamp(line)
            last_x = x = fingers[curr_id].get('x', last_x)
            last_y = y = fingers[curr_id].get('y', last_y)
            points.append(FingerPosition(t, x, y))

    log.close()
    return points


def get_laser_crossings(filename):
    """ Parse out the laser crossing events from a Quickstep log
    Returns a list of LaserCrossing events
    """
    QUICKSTEP_LOG_REGEX = '^\s*([\d.]+)\s+(\d)\s*$'

    status, raw_log = subprocess.getstatusoutput('cat %s' % filename)
    if status:
        return None

    laser_crossings = []
    for line in raw_log.splitlines():
        matches = re.match(QUICKSTEP_LOG_REGEX, line)
        if matches and len(matches.groups()) == 2:
            timestamp = float(matches.group(1))
            direction = int(matches.group(2))
            laser_crossings.append(LaserCrossing(timestamp, direction))

    return laser_crossings


def clip_timeframes(positions, laser_crossings):
    """ Clip both lists of events so they only contain events that occurred
    during the period of time that *both* logs were being generated.
    Returns the clipped lists
    """
    if not positions or not laser_crossings:
        return [], []

    touchpad_start = positions[0].timestamp
    touchpad_end = positions[-1].timestamp
    laser_start = laser_crossings[0].timestamp
    laser_end = laser_crossings[-1].timestamp

    positions = [p for p in positions
                 if (p.timestamp <= laser_end and p.timestamp >= laser_start)]
    laser_crossings = [q for q in laser_crossings
                       if (q.timestamp <= touchpad_end and
                           q.timestamp >= touchpad_start)]

    return positions, laser_crossings


def get_laser_crossing_points(positions, laser_crossings):
    """ Find the touchpad readings at the moment the laser was crossed for each
    laser crossing event.  Since the timestamps will not likely line up
    perfectly, the exact position is interpolated between the two positions
    read before and after the laser was crossed.
    Returns a list of TouchpadPosition events
    """
    crossing_points = []

    for crossing in laser_crossings:
        for i, position in enumerate(positions):
            if i == 0:
                continue

            if position.timestamp > crossing.timestamp:
                # interpolate, since they won't line up perfectly

                time_gap = (position.timestamp - positions[i - 1].timestamp)
                before_weight = ((position.timestamp - crossing.timestamp) /
                                 time_gap)
                after_weight = 1.0 - before_weight

                crossing_points.append(
                    FingerPosition(crossing.timestamp,
                                   (positions[i - 1].x * before_weight +
                                                     position.x * after_weight),
                                    (positions[i - 1].y * before_weight +
                                                     position.y * after_weight)
                                   ))
                break

    return crossing_points


def estimate_laser_line(laser_crossings):
    """ Estimate the position of the line defined by the laser on the pad
    Given the touchpad readings on either side of the line that were generated
    at the moment the laser was crossed, you can fit a line to them and
    discover where the line is inbetween then.
    Returns a np.poly1d representation of the line
    """
    line = np.poly1d(np.polyfit([x for t, x, y in laser_crossings],
                                [y for t, x, y in laser_crossings],
                                deg=1))
    return line


def which_side(line, coords):
    """ Indicates which side of a line a given point lies on """
    distance = line(coords.x) - coords.y
    if abs(distance) <= 2: return 0
    elif distance > 0: return 1
    else: return -1


def get_touchpad_crossing_points(positions, line):
    """ Find the touchpad readings of where it finally crossed the line formed
    by the laser.  These are the points that the user would see, and are likely
    delayed somewhat, which is what we will try to measure.
    Returns a list of TouchpadPosition events along the line
    """
    last_side = which_side(line, positions[0])
    points = []
    for i, position in enumerate(positions):
        current_side = which_side(line, position)
        if current_side != last_side and last_side != 0:
            points.append(position)
        last_side = current_side
    return points


def compute_latency(line_positions, laser_positions):
    """ Measure how much time passed between the laser being triggered and the
    touchpad reporting an event passed the line.
    Returns a list of floats corresponding to the latency for each crossing
    """
    latencies = []
    for (probe_timestamp, _, _), (laser_timestamp, _, _) \
                                in zip(line_positions, laser_positions):
        latencies.append(probe_timestamp - laser_timestamp)
    return latencies

def measure_latencies(finger_positions, laser_crossings):
    positions, laser_crossings = \
                        clip_timeframes(finger_positions, laser_crossings)
    if not positions or not laser_crossings:
        print('ERROR: There are no overlapping events in the input')
        return []

    # Find the points where the laser was crossed
    laser_crossing_points = \
                get_laser_crossing_points(positions, laser_crossings)

    # Separate those points of two "lines" (the laser essentially defines two)
    laser_crossing_points0 = [p for i, p in enumerate(laser_crossing_points)
                              if int((i + 1) / 2) % 2 == 0]
    laser_crossing_points1 = [p for i, p in enumerate(laser_crossing_points)
                              if int((i + 1) / 2) % 2 == 1]

    # Fit a line to them to estimate where the actual laser is
    line0 = estimate_laser_line(laser_crossing_points0)
    line1 = estimate_laser_line(laser_crossing_points1)

    # Find the touchpad positions where the probe actually crossed the "line"
    touchpad_crossing_points0 = get_touchpad_crossing_points(positions, line0)
    touchpad_crossing_points1 = get_touchpad_crossing_points(positions, line1)

    # Actually do the timings against the ideal line
    latencies0 = compute_latency(touchpad_crossing_points0,
                                 laser_crossing_points0)
    latencies1 = compute_latency(touchpad_crossing_points1,
                                 laser_crossing_points1)

    return latencies0 + latencies1


def main(evtest_log_filename, quickstep_log_filename):
    positions = get_finger_positions(evtest_log_filename)
    laser_crossings = get_laser_crossings(quickstep_log_filename)

    latencies = measure_latencies(positions, laser_crossings)
    if not latencies:
        print('ERROR: Unable to compute any latencies')
        return

    # Display the results
    print('Average', 'Maximum', 'Minimum')
    print(np.average(latencies), max(latencies), min(latencies))
    print()
    print('Latency result: %f ms' % (np.average(latencies) * 1000.0))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print('Usage: %s evtest_log.txt quickstep_log.txt' % sys.argv[0])
        sys.exit(1)
    evtest_log_filename = sys.argv[1]
    quickstep_log_filename = sys.argv[2]
    main(evtest_log_filename, quickstep_log_filename)
