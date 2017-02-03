## Audio Latency Measurement ##

Audio output latency as measured by WALT is the time that passes from the moment an application
decides to output a tone until it can be detected via the headphone jack. Microphone latency is
defined similarly.

Audio signal for measuring microphone latency is generated as a square wave using the Teensy tone()
function (currently at 5 kHz). The signal is attenuated by a simple circuit similar to the
[ChromeOS/Android audio loopback dongle](https://source.android.com/devices/audio/loopback.html).

Audio output signal from the phone is detected when audio line voltage crosses a predefined
threshold (currently about 65 mV).

Low latency audio IO on Android can be achieved via JNI C/C++ code.
Documentation and sample code can be found on the
[High Performance Audio website](http://googlesamples.github.io/android-audio-high-performance/)


Sample measurements:

| Device       | OS version     |  Buffer                      | Playback [ms] | Recording* [ms] |
| :---         | :---           | :---                         |          ---: |            ---: |
| Nexus 5      | M4B30Z (6.0.1) | 240 frames @ 48 kHz = 5 ms   |          27.6 |             2.5 |
| Nexus 5X     | NRD91P (7.0)   | 192 frames @ 48 kHz = 4 ms   |          14.9 |             3.5 |
| Nexus 7      | LMY47Q (5.1)   | 240 frames @ 48 kHz = 5 ms   |          32.1 |            16.3 |
| Nexus 9      | MMB29K (6.0.1) | 128 frames @ 48 kHz = 2.6 ms |           9.8 |             1.0 |

\* WALT clock synchronization accuracy is about 1 ms hence the relative error for recording latency can be fairly high.

#### Published round trip measurements
Superpowered Inc. maintains an open source app for measuring round trip audio latency -
[Superpowered Latency App](https://github.com/superpoweredSDK/SuperpoweredLatency).

* [Audio round trip measurements published by Android group](https://source.android.com/devices/audio/latency_measurements.html#measurements)
* [Audio round trip measurements published by Superpowered Inc.](http://superpowered.com/latency)
