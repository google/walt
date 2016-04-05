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


Superpowered Inc. maintains an open source app for measuring round trip audio latency - 
[Superpowered Latency App](https://github.com/superpoweredSDK/SuperpoweredLatency).


## Published round trip measurements ##
* [Audio round trip measurements published by Android group](https://source.android.com/devices/audio/latency_measurements.html#measurements)
* [Audio round trip measurements published by Superpowered Inc.](http://superpowered.com/latency)
