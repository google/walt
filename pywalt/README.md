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
