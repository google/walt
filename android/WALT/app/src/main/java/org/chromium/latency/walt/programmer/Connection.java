/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chromium.latency.walt.programmer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import org.chromium.latency.walt.SimpleLogger;

import java.util.HashMap;
import java.util.Locale;

class Connection {
    private static final String USB_PERMISSION_RESPONSE_INTENT =
            "usb-permission-response-programmer";
    private static final int HALFKAY_VID = 0x16C0;
    private static final int HALFKAY_PID = 0x0478;

    private SimpleLogger mLogger;
    private Context mContext;

    private UsbManager mUsbManager;
    private UsbDevice mUsbDevice = null;
    private UsbDeviceConnection mUsbConnection;

    Connection(Context context) {
        mContext = context;
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        mLogger = SimpleLogger.getInstance(context);
    }

    public void write(byte[] buf, int timeout) {
        write(buf, 0, buf.length, timeout);
    }

    public void write(byte[] buf, int index, int len, int timeout) {
        if (!isConnected()) return;

        while (timeout > 0) {
            // USB HID Set_Report message
            int result = mUsbConnection.controlTransfer(0x21, 9, 0x0200, index, buf, len, timeout);

            if (result >= 0) break;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            timeout -= 10;
        }

    }

    public boolean isConnected() {
        return mUsbConnection != null;
    }

    public void connect() {
        UsbDevice usbDevice = findUsbDevice();
        connect(usbDevice);
    }

    public void connect(UsbDevice usbDevice) {
        if (usbDevice == null) {
            mLogger.log("TeensyUSB not found. Try pressing the button on the Teensy first.");
            return;
        }

        mUsbDevice = usbDevice;

        // Request permission
        // This displays a dialog asking user for permission to use the device.
        // No dialog is displayed if the permission was already given before or the app started as a
        // result of intent filter when the device was plugged in.

        PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(USB_PERMISSION_RESPONSE_INTENT), 0);
        mContext.registerReceiver(respondToUsbPermission,
                new IntentFilter(USB_PERMISSION_RESPONSE_INTENT));
        mLogger.log("Requesting permission for USB device.");
        mUsbManager.requestPermission(mUsbDevice, permissionIntent);
    }

    private BroadcastReceiver respondToUsbPermission = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (mUsbDevice == null) {
                mLogger.log("USB device was not properly opened");
                return;
            }

            if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)){
                mUsbConnection = mUsbManager.openDevice(mUsbDevice);

                int ifIdx = 0;

                UsbInterface iface = mUsbDevice.getInterface(ifIdx);

                if (mUsbConnection.claimInterface(iface, true)) {
                    mLogger.log("Interface claimed successfully\n");
                } else {
                    mLogger.log("ERROR - can't claim interface\n");
                    return;
                }
            } else {
                mLogger.log("Could not get permission to open the USB device");
            }
            mContext.unregisterReceiver(respondToUsbPermission);
        }
    };

    public UsbDevice findUsbDevice() {

        mLogger.log(String.format("Looking for TeensyUSB VID=0x%x", HALFKAY_VID));

        HashMap<String, UsbDevice> deviceHash = mUsbManager.getDeviceList();
        if (deviceHash.size() == 0) {
            mLogger.log("No connected USB devices found");
            return null;
        }

        mLogger.log("Found " + deviceHash.size() + " connected USB devices:");

        UsbDevice usbDevice = null;

        for (String key : deviceHash.keySet()) {

            UsbDevice dev = deviceHash.get(key);

            String msg = String.format(Locale.US,
                    "USB Device: %s, VID:PID - %x:%x, %d interfaces, class 0x%x",
                    key, dev.getVendorId(), dev.getProductId(), dev.getInterfaceCount(),
                    dev.getDeviceClass()
            );

            if (dev.getVendorId() == HALFKAY_VID && dev.getProductId() == HALFKAY_PID) {
                usbDevice = dev;
                msg += " <- using this one as HalfKay";
            }

            mLogger.log(msg);
        }
        return usbDevice;
    }
}
