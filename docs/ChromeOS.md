## Using WALT on ChromeOS

WALT can be used on ChromeOS in two different modes:
 1. Via the [pywalt/walt.py](/pywalt/walt.py) command line script - for this mode refer to [pywalt/README.md](/pywalt/README.md)
 1. Using the [WALT Android app](https://play.google.com/store/apps/details?id=org.kamrik.latency.walt) and walt.py script as a bridge


For either mode you will need to use a ChromeOS test image -
[some pointers on how to get it installed](https://www.chromium.org/chromium-os/testing/autotest-developer-faq/ssh-test-keys-setup)

Copy the [pywalt/](/pywalt) directory from WALT repo to the Chromebook. One option is to download a repo tarball directly from GitHub:

```
wget https://github.com/google/walt/archive/master.tar.gz
tar -xzf master.tar.gz
cd walt-master/pywalt
./walt.py --help
```



Connect WALT to Chromebook's USB port and test the setup by running: `$ ./walt.py -t sanity` on the Chromebook.
This continuously displays readings from WALT's sensors (press Ctrl-C to stop):
```
Starting sanity test
q G:480 PD_screen:3 PD_laser:910	min-max: 480-480 3-3 910-910
q G:514 PD_screen:3 PD_laser:896	min-max: 480-514 3-3 896-910
q G:486 PD_screen:4 PD_laser:894	min-max: 480-514 3-4 894-910
q G:509 PD_screen:4 PD_laser:891	min-max: 480-514 3-4 891-910
...
```
The first reading `G` is the accelerometer, it should change when WALT is rotated.
`PD_screen` and `PD_laser` are the light sensors (photodiodes), shading them or exposing to light should change their readings.


### Using WALT Android app

If you intend to run the android app, run walt.py in TCP bridge mode. This is needed because Android container on ChromeOS has no access to USB.
 - `iptables -A INPUT -p tcp --dport 50007 -j ACCEPT`
 - `./walt.py -t bridge`

The script will respond with `Listening on port 50007`. It can be stopped by pressing Ctrl-C. At this point you should be able to use the WALT Android app as if it's running on a regular Android device. If you reset or reconnect the WALT device, you'll need to re-run the script (no need to re-run the iptables command).

If you need to deploy your own version of WALT Android app, follow instruction in [Development.md](Development.md)
