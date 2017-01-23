## Setting up for WALT Android app development

Since the WALT device usually takes up the only available USB connector the usual development via USB is difficult and annoying. There are several ways around it:


#### Use a USB hub and a USB Ethernet adapter

Set up ADB to work via TCP using instructions [here](https://developer.android.com/studio/command-line/adb.html#wireless). Note, the WiFi option was reported to work poorly for this, so an Ethernet adapter is recommended. You will need to know the IP address of your phone's ethernet interface.

1. Connect a USB OTG or USB-C to USB-A-female adapter to the phone
1. Connect the hub (preferably powered) to the adapter
1. Use the hub to connect WALT, Ethernet adapter and whatever USB peripherals you might want to test using WALT


####  Use a ChromeOS device with Android

 A ChromeOS device with Android (e.g. Asus Flip) is a convenient option. Use the official guide for Android development for ChromeOS. Since Android on ChromeOS has no access to USB, it requires the use of a TCP bridge script implemented in [walt.py](../pywalt/walt.py)  (TODO: more on how to use the bridge)