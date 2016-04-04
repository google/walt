## Hardware assembly ##
Note, this is not a precise recipe. Depending on your specific needs, you may want to introduce some variations.
In most cases it's possible to assemble a device with partial functionality (e.g. only for audio latency) on a solderless breadboard.

### List of parts ###

 * Microcontroller board - [Teensy LC](https://www.pjrc.com/teensy/teensyLC.html)
 * Photodiodes - [BPW34](http://www.digikey.com/catalog/en/partgroup/bpw34/12351) (3 units)
 * Laser - any laser pointer will do, ~1 mW is just fine (5 mW is ok, but avoid stronger)
 * Accelerometer board [Adafruit ADXL335](https://www.adafruit.com/product/163),
   Alternatively use the ADXL335 chip directly without the board
 * Resistors: 10K, 820K, 3.3K, 100Ω (2 units)
 * TRRS connector or wire for audio measurements
 * Clipboard, like [this one](https://upload.wikimedia.org/wikipedia/commons/c/c0/Wood-clipboard.jpg) -
   it’s important to use one where you push to open it.
 * Foam to hold the whole thing


### Microcontroller code ###

Important pin numbers from the code listed below, defined in [walt.ino](../arduino/walt/walt.ino)

 * PD_LASER_PIN 14 - Photodiode that looks at the laser
 * G_PIN 15 // Same as A1 - Accelerometer for detecting when touch probe hits the screen
 * PD_SCREEN_PIN 20 // Same as A6 - Photodiode that looks at the screen
 * AUDIO_PIN 22 // Same as A8 - Detects audio signal from headphones output
 * MIC_PIN 23 // Same as A9 - uses PWM to generate a tone for measuring microphone latency.

Optional pins for LEDs used for extra debugging signals
If LEDs take more than 5mA those should be the hight current pins on TeensyLC (bold on the Teensy diagram, can provide 20mA vs the usual 5mA).

 * LED_PIN_GREEN 16
 * LED_PIN_RED 17


### Schematic ###
![WALT Schematic](WALT_schematic_20160404.png)


### Notes ###

 * If using Teensy 3.1 instead of LC, it won’t be able to directly read
   the photodiode with 820k resistor. Something like a follower opamp will be
   necessary. The screen photodiodes are bundled as a couple to produce more
   current, a single photodiode with 1.5M resistor should provide the same
   result, but might be at the limit of what the teensy can measure, and again an
   opamp will be needed.
 * Photodiode cathode has a small protrusion on the leg (cathode marker).
   Anode has a white dot near it.
 * Note the different setup of the laser and screen photodiode. The laser one
   uses internal pullup resistor (about 20k), enabled by pinMode(PD_LASER_PIN,
   INPUT_PULLUP);
