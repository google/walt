# PyWALT
Python scripts for [WALT Latency Timer](https://github.com/google/walt) on Linux and ChromeOS.

 * Based on [ChromeOS scroll test implementation](https://chromium.googlesource.com/chromiumos/platform/touchbot/+/master/quickstep/)
 * Currently supprots tap and drag (scroll) latency measurements
 * For tests using evetest or drm (all touch and screen tests) pywalt needs to run as root
 * In order to find the name/number of your touch device run `evtest`. It will list available input devices and once you enter a device number it will show incoming events when you touch that touchpad / touchscreen.


Synopsis:
```
$ walt --help  
usage: walt [-h] [-i INPUT] [-s SERIAL] [-t TYPE] [-l LOGDIR] [-n N] [-p PORT]
            [-d]

Run a latency test using WALT Latency Timer

optional arguments:
  -h, --help            show this help message and exit
  -i INPUT, --input INPUT
                        input device, e.g: 6 or /dev/input/event6 (default:
                        None)
  -s SERIAL, --serial SERIAL
                        WALT serial port (default: /dev/ttyACM0)
  -t TYPE, --type TYPE  Test type:
                        drag|tap|screen|sanity|curve|bridge|tapaudio|tapblink
                        (default: None)
  -l LOGDIR, --logdir LOGDIR
                        where to store logs (default: /tmp)
  -n N                  Number of laser toggles to read (default: 40)
  -p PORT, --port PORT  port to listen on for the TCP bridge (default: 50007)
  -d, --debug           talk more (default: False)
 ```


## Tap Latency ##
See the [tap latency section](../docs/usage/WALT_usage.md#tap-latency) in Android app usage doc.

Below is output from an example run of a tap latency test that reads touch events from `/dev/input/event4` (in this case a touchpad). After 40 events (20 down and 20 up) are detected, the script prints median delays and exits.

The input device option is mandatory since several touch devices might be preset (e.g. touchpad and touch screen). You can use a shorthand notation `-i 4` which is expanded to `-i /dev/input/event4`.

The following must be run as root.

```
$ ./walt.py -t tap -n 40 -i /dev/input/event4

Starting tap latency test
Clock zeroed at 1487105210 (rt 0.250 ms)
Event: time 1487105212.048997, type 1 (EV_KEY), code 330 (BTN_TOUCH), value 1

shock t 1990338, tap t 1487105212.048997, tap val 1. dt=63738.9
Event: time 1487105212.262449, type 1 (EV_KEY), code 330 (BTN_TOUCH), value 0

shock t 2219992, tap t 1487105212.262449, tap val 0. dt=47537.0
Event: time 1487105212.702711, type 1 (EV_KEY), code 330 (BTN_TOUCH), value 1

...

Processing data...
dt_down = [63.74, 26.96, 27.14 ...
dt_up = [47.54, 47.03, 41.52...

Median latency, down: 23.9, up: 47.1
```

## Drag / Scroll Latency ##
See the [drag latency section](../docs/usage/WALT_usage.md#dragscroll-latency) in Android app usage doc.

Below is a drag latency measurement of the trackpad on Asus Flip. The trackpad input device is `/dev/input/event4` which was selected using the `-i 4` argument.

The `-n 20` option tells the script to record 20 laser events. Any change in laser sensor reading counts as one event, therefore one crossing of the beam counts as two events (laser goes off and back on), and a full cycle of the finger going up and down counts as 4 events. This measurement recorded 20/4 = 5 full cycles of the finger moving up and down.

In addition to moving your finger up and down please also move it slowly along the beam. The calculation used by pywalt needs some spread of the x coordinates for better precision.

Drag latency uses evtest and must therefore be run as root.

```
#./walt.py -t drag -i 4 -n 20
Starting drag latency test
Input device   : /dev/input/event4
Serial device  : /dev/ttyACM1
Laser log file : /tmp/WALT_2017_03_07__1532_12_laser.log
evtest log file: /tmp/WALT_2017_03_07__1532_12_evtest.log
Clock zeroed at 1488918733 (rt 0.306 ms)
....................
Processing data, may take a minute or two...
Drag latency (min method) = 21.07 ms
```
