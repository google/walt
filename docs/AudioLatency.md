## Audio Latency Measurement

Audio output latency as measured by WALT is the time that passes from the moment an application
decides to output a tone until it can be detected via the headphone jack. Microphone latency is
defined similarly.

Low latency audio IO on Android can be achieved via JNI C/C++ code.
Documentation and sample code can be found on the
 [High Performance Audio website](http://googlesamples.github.io/android-audio-high-performance/).


### Reported values

We are trying to stick to the following (overlapping) principles
1. Timestamp events as close to hardware as possible. Most events up the stack can be easily timed with software alone.
1. Measure time intervals that are likely to have low variability.

##### Playback

In order to avoid warm up latency during audio playback it is
[recommended to constantly enqueue buffers containing silence](http://googlesamples.github.io/android-audio-high-performance/guides/audio-output-latency.html#avoid-warm-up-latency).
WALT app follows this pattern.

The audio data buffers are enqueued in the
[player callback](https://github.com/google/walt/blob/v0.1.6/android/WALT/app/src/main/jni/player.c#L107)
and the latency reported by WALT app is the time from the
[Enqueue() call](https://github.com/google/walt/blob/v0.1.6/android/WALT/app/src/main/jni/player.c#L123)
until there is a detectable signal on the wire. Note that this does not include the time between the moment the app decided to output a tone until the Enqueue() call. This is somewhat counterintuitive but this time is deliberately omitted. In case of the WALT app code this time is likely be uniformly distributed between 0 and the length of the buffer (5 ms in case of Nexus 5) and therefore would contribute considerable variance but little interesting information if included in the reported latency.

##### Recording
The reported latency is the time from the moment the last frame in a buffer was recorded until the
[recorder callback](https://github.com/google/walt/blob/v0.1.6/android/WALT/app/src/main/jni/player.c#L345)
receiving that buffer is executed.

TODO: Is the round trip latency expected to be Recording latency + Playback latency + one buffer length?

### Sample measurements

| Device       | OS version     |  Buffer                      | Playback [ms] | Recording* [ms] |
| :---         | :---           | :---                         |          ---: |            ---: |
| Nexus 5      | M4B30Z (6.0.1) | 240 frames @ 48 kHz = 5 ms   |          27.6 |             2.5 |
| Nexus 5X     | NRD91P (7.0)   | 192 frames @ 48 kHz = 4 ms   |          14.9 |             3.5 |
| Nexus 7      | LMY47Q (5.1)   | 240 frames @ 48 kHz = 5 ms   |          32.1 |            16.3 |
| Nexus 9      | MMB29K (6.0.1) | 128 frames @ 48 kHz = 2.6 ms |           9.8 |             1.0 |
| Nexus 6P     | MHC19I (6.0.1) | 192 frames @ 48 kHz = 4 ms   |          15.3 |             1.6 |
| Pixel        | NDE63P (7.1)   | 192 frames @ 48 kHz = 4 ms   |           8.9 |             1.7 |
| Pixel XL     | NDE63H (7.1)   | 192 frames @ 48 kHz = 4 ms   |           9.1 |             1.6 |

\* WALT clock synchronization accuracy is about 1 ms hence the relative error for recording latency can be fairly high.

#### Published round trip measurements
Superpowered Inc. maintains an open source app for measuring round trip audio latency -
[Superpowered Latency App](https://github.com/superpoweredSDK/SuperpoweredLatency).

* [Audio round trip measurements published by Android group](https://source.android.com/devices/audio/latency_measurements.html#measurements)
* [Audio round trip measurements published by Superpowered Inc.](http://superpowered.com/latency)


### Hardware

Audio signal for measuring microphone latency is generated as a square wave using the Teensy tone()
function ([currently at 5 kHz](https://github.com/google/walt/blob/v0.1.6/arduino/walt/walt.ino#L310)).
The signal is attenuated by a simple circuit similar to the
[ChromeOS/Android audio loopback dongle](https://source.android.com/devices/audio/loopback.html).

Audio output signal from the phone is detected when audio line voltage crosses a predefined
threshold (currently about 65 mV).
