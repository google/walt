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
Module for computing drag latency given logs of touchpad positions and
QuickStep laser crossing timestamps
"""

import numpy
import evparser

debug_mode = False


def load_laser_data(fname_laser):
    laser_data = numpy.loadtxt(fname_laser)
    t = laser_data[:, 0]
    transition = laser_data[:, 1].astype(int)
    if transition[0] != 0:
        print('WARNING: First laser transition should be from light to dark')
    return t, transition


def calc_ssr(x, y):
    """Return sum of squared residuals (SSR) of a linear least square fit"""
    p = numpy.polyfit(x, y, 1, full=True)
    r = p[1][0]
    return r


def minimize_lsq(tx, x, ty, y, tl, min_shift, max_shift, step):
    """Find best time shift so that the shifted laser crossing events fit nicely
    on a straight line. Upper and lower side are treated separately.

    """

    # generate an array of all shifts to try
    shifts = numpy.arange(min_shift, max_shift, step)

    # side = [0, 1, 1, 0, 0, 1, 1 ...
    # this is an indicator of which side of the beam the crossing belongs to
    side = ((numpy.arange(len(tl)) + 1) / 2) % 2

    residuals0 = []
    residuals1 = []
    for shift in shifts:
        # Find the locations of the finger at the shifted laser timestamps
        yl = numpy.interp(tl + shift, ty, y)
        xl = numpy.interp(tl + shift, tx, x)
        # Fit a line to each side separately and save the SSR for this fit
        residuals0.append(calc_ssr(xl[side == 0], yl[side == 0]))
        residuals1.append(calc_ssr(xl[side == 1], yl[side == 1]))

    # Find the shift with lower SSR for each side
    best_shift0 = shifts[numpy.argmin(residuals0)]
    best_shift1 = shifts[numpy.argmin(residuals1)]

    # Use average of the two sides
    best_shift = (best_shift0 + best_shift1) / 2
    return best_shift


def minimize(fname_evtest, fname_laser):

    # Load all the data
    tl, transition = load_laser_data(fname_laser)
    (tx, x, ty, y) = evparser.load_xy(fname_evtest)

    # Shift time so that first time point is 0
    t0 = min(tx[0], ty[0])
    tx = tx - t0
    ty = ty - t0
    tl = tl - t0

    # Sanity checks
    if numpy.std(x)*2 < numpy.std(y):
        print('WARNING: Not enough motion in X axis')

    # Search for minimum with coarse step of 1 ms in range of 0 to 200 ms
    coarse_step = 1e-3  # Seconds
    best_shift_coarse = minimize_lsq(tx, x, ty, y, tl, 0, 0.2, coarse_step)
    # Run another search with 0.02 ms step within +-3 ms of the previous result
    lmts = numpy.array([-1, 1]) * 3 * coarse_step + best_shift_coarse
    fine_step = 2e-5  # seconds
    best_shift_fine = minimize_lsq(tx, x, ty, y, tl, lmts[0], lmts[1], fine_step)

    print("Drag latency (min method) = %.2f ms" % (best_shift_fine*1000))
    if debug_mode:
        debug_plot(tx, x, ty, y, tl, best_shift_fine)

    return best_shift_fine


def debug_plot(tx, x, ty, y, tl, shift):
    """Plot the XY data with time-shifted laser events

    Note: this is a utility function used for offline debugging. It needs
    matplotlib which is not installed on CrOS images.

    """
    import matplotlib.pyplot as plt
    xx = numpy.interp(ty, tx, x)
    plt.plot(xx, y, '.b')

    yl = numpy.interp(tl + shift, ty, y)
    xl = numpy.interp(tl + shift, tx, x)
    sides = (((numpy.arange(len(tl)) + 1) / 2) % 2)
    colors = ['g', 'm']
    x_linear = numpy.array([min(x), max(x)])
    for side in [0, 1]:
        xls = xl[sides == side]
        yls = yl[sides == side]
        plt.plot(xls, yls, 'o' + colors[side])
        a, c = numpy.polyfit(xls, yls, 1)
        plt.plot(x_linear, a * x_linear + c, colors[side])
    plt.xlabel('X')
    plt.ylabel('Y')
    plt.title('Laser events shifted %.2f ms' % (shift*1000))
    plt.show()

# Debug & test
if __name__ == '__main__':

    fname = '/tmp/WALT_2016_06_22__1739_21_'
    fname_evtest = fname + 'evtest.log'
    fname_laser = fname + 'laser.log'

    minimize(fname_evtest, fname_laser)

