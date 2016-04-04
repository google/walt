WALT uses a “stylus” equipped with an accelerometer. The finger is imitated by a flat circular metal
tip that is grounded, pretty much any rigid tip can be used as long as it triggers the touch sensor.
When the stylus “collides” with touch screen the accelerometer senses a shock (above 3g) which is
timestamped by the Teensy. In order to generate a similar shock when picking the stylus up from the
screen, the conductive surface and the accelerometer are mounted on a button of a retractable pen.
On the way up, the spring keeps the button in contact with the touch screen for the first few mm of
motion. This allows the hand holding the main part of the pen gain some speed to which the button is
then abruptly accelerated generating an easily detectable shock.

Linux [Multi Touch (MT)](https://www.kernel.org/doc/Documentation/input/multi-touch-protocol.txt)
implementation timestamps touch events in the kernel as they arrive from the hardware. On Android
the MT events are then exposed in Java as
[MotionEvent](http://developer.android.com/reference/android/view/MotionEvent.html) 
and include the kernel timestamp. For tap, the relevant MotionEvent types are
ACTION_DOWN and ACTION_UP.

