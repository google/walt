# PyWALT
Python scripts for [WALT Latency Timer](https://github.com/google/walt) on Linux and ChromeOS.

 * Based on [ChromeOS scroll test implementation](https://chromium.googlesource.com/chromiumos/platform/touchbot/+/master/quickstep/)
 * Currently supprots tap and drag (scroll) latency measurements


Synopsis:
```
$ ./walt.py --help
usage: walt.py [-h] [-i INPUT] [-s SERIAL] [-t TYPE] [-l LOGDIR] [-n N]
               [-p PORT] [-d]

Run the touchpad drag latency test using WALT Latency Timer

optional arguments:
  -h, --help            show this help message and exit
  -i INPUT, --input INPUT
                        input device, e.g: 6 or /dev/input/event6 (default: )
  -s SERIAL, --serial SERIAL
                        WALT serial port (default: /dev/ttyACM0)
  -t TYPE, --type TYPE  Test type: drag|tap|screen|sanity|curve|bridge
                        (default: drag)
  -l LOGDIR, --logdir LOGDIR
                        where to store logs (default: /tmp)
  -n N                  Number of laser toggles to read (default: 40)
  -p PORT, --port PORT  Port to listen on for the TCP bridge (default: 50007)
  -d, --debug           talk more (default: False)
 ```


## Tap Latency ##
See the [tap latency section](../docs/usage/WALT_usage.md#tap-latency) in Android app usage doc.

Below is output from an example run of a tap latency test that reads touch events from `/dev/input/event4` (in this case a touchpad). After 40 events (20 down and 20 up) are detected, the script prints median delays and exits.

The input device option is mandatory since several touch devices might be preset (e.g. touchpad and touch screen). You can use a shorthand notation `-i 4` which is expanded to `-i /dev/input/event4`.

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
