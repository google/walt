## Setting up for WALT Android app development

In general WALT Android app has no special requirements but since WALT usually takes up the only available USB connector on the phone, the typical development using ADB via USB becomes difficult. Below are some options to overcome this problem.


#### Using a USB hub and a USB Ethernet adapter

ADB can work over TCP connections. The [official documentation](https://developer.android.com/studio/command-line/adb.html#wireless) assumes that the TCP connection is established over WiFi, but a wired Ethernet connection can be used in the same way and is reported to work much better with ADB. Android will recognize and use most USB-Ethernet adapters out of the box.

1. With the phone connected you computer via USB, run `adb tcpip 5555` (you can replace 5555 with any port)
1. Disconnect the phone from USB
1. Connect a USB hub (preferably powered) to the phone using a [USB-C to USB-A-female adapter](https://store.google.com/product/usb_type_c_to_usb_standard_a_adapter)
   or a USB-OTG adapter.
1. Use the hub to connect WALT, Ethernet adapter and whatever other USB peripherals you might want to test using WALT

Note, this setup is sensitive to the order in which you connect the different components and adapters, experiments with it.


####  Using a ChromeOS device with Android

A ChromeOS device with Android (e.g. Asus Flip) is another convenient option.

Since Android on ChromeOS has no access to USB, it requires a TCP bridge script implemented in [walt.py](/pywalt/walt.py). Detailed instructions in [Chromeos.md](Chromeos.md).

In order to set up your  for Android development, use either the
[official guide for Android development on ChromeOS](https://developer.android.com/topic/arc/index.html#setup).
Or the following short list:

1. Log in to Chromebook via a non-guest account, also avoid restricted corporate/managed accounts
1. Go to settings, scroll down to Google Play Store and click enable
1. Click Manage your Android preferences and enable ADB debugging like on a regular Android device:
  1. Click About device
  1. Keep tapping the build number until it says “You are now a developer” (7 taps)
  1. Go back and click on Developer options
  1. Enable ADB debugging
1. Get your Chromebook connected to a network so it would be accessible from you workstation
1. Switch Chromebook to terminal via ctrl+alt+f2 and log in as root
1. Run `ifconfig` to get the ip address of eth0 (or wlan0 if using wireless)
1. On your workstation, run `ssh root@ip_addr` to verify that Chromebook is accessible
1. Run `adb connect ip_addr:22`. From this point on Android studio and adb on your workstation should treat the Chromebook just like a regular Android device
1. Run the WALT TCP bridge (more details [here](ChromeOS.md))
  1. scp the pywalt folder from your workstation and run the following on the Chromebook:
  1. `iptables -A INPUT -p tcp --dport 50007 -j ACCEPT`
  1. `python walt.py -t bridge`
