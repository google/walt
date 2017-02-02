## Drag / scroll latency

For detailed instructions on how to perform the measurement refer to the [usage doc](usage/WALT_usage.md#dragscroll-latency).

For drag (or scroll) latency WALT uses a laser that shines across the touch device and hits a detector on the
other side. The microcontroller monitors the state of the laser detector and reports (over usb) when
the laser beam is broken. A finger dragged back and forth on a touchpad or touch screen
and interrupts a laser beam. Touch events from the pad and laser events are then processed together
to deduce the delay.

A [video](https://plus.google.com/+FrancoisBeaufort/posts/XctAif2nv4U) showing the measurement
performed using a robotic stylus.


![Drag/scroll latency measurement](usage/images/drag.png)


