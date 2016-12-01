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

package org.chromium.latency.walt;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.support.v4.content.LocalBroadcastManager;

import java.util.HashMap;
import java.util.Locale;

public abstract class BaseUsbConnection {
    private static final String USB_PERMISSION_RESPONSE_INTENT = "usb-permission-response";
    private static final String CONNECT_INTENT = "org.chromium.latency.walt.CONNECT";

    protected SimpleLogger mLogger;
    protected Context mContext;
    private LocalBroadcastManager mBroadcastManager;
    private BroadcastReceiver mCurrentConnectReceiver;

    private UsbManager mUsbManager;
    protected UsbDevice mUsbDevice = null;
    protected UsbDeviceConnection mUsbConnection;

    public BaseUsbConnection(Context context) {
        mContext = context;
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        mLogger = SimpleLogger.getInstance(context);
        mBroadcastManager = LocalBroadcastManager.getInstance(context);
    }

    public abstract void onConnect();
    public abstract void onDisconnect();
    public abstract int getVid();
    public abstract int getPid();

    // Used to distinguish between bootloader and normal mode that differ by PID
    // TODO: change intent strings to reduce dependence on PID
    protected abstract boolean isCompatibleUsbDevice(UsbDevice usbDevice);


    private String getConnectIntent() {
        return CONNECT_INTENT + getVid() + ":" + getPid();
    }

    private String getUsbPermissionResponseIntent() {
        return USB_PERMISSION_RESPONSE_INTENT + getVid() + ":" + getPid();
    }

    public boolean isConnected() {
        return mUsbConnection != null;
    }

    public void registerConnectCallback(final Runnable r) {
        if (mCurrentConnectReceiver != null) {
            mBroadcastManager.unregisterReceiver(mCurrentConnectReceiver);
            mCurrentConnectReceiver = null;
        }

        if (isConnected()) {
            r.run();
            return;
        }

        mCurrentConnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mBroadcastManager.unregisterReceiver(this);
                r.run();
            }
        };
        mBroadcastManager.registerReceiver(mCurrentConnectReceiver,
                new IntentFilter(getConnectIntent()));
    }

    public void connect() {
        UsbDevice usbDevice = findUsbDevice();
        connect(usbDevice);
    }

    public void connect(UsbDevice usbDevice) {
        if (usbDevice == null) {
            mLogger.log("Device not found.");
            return;
        }

        if (!isCompatibleUsbDevice(usbDevice)) {
            mLogger.log("Not a valid device");
            return;
        }

        mUsbDevice = usbDevice;

        // Request permission
        // This displays a dialog asking user for permission to use the device.
        // No dialog is displayed if the permission was already given before or the app started as a
        // result of intent filter when the device was plugged in.

        PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(getUsbPermissionResponseIntent()), 0);
        mContext.registerReceiver(respondToUsbPermission,
                new IntentFilter(getUsbPermissionResponseIntent()));
        mLogger.log("Requesting permission for USB device.");
        mUsbManager.requestPermission(mUsbDevice, permissionIntent);
    }

    public void disconnect() {
        onDisconnect();

        mUsbConnection.close();
        mUsbConnection = null;
        mUsbDevice = null;

        mContext.unregisterReceiver(disconnectReceiver);
    }

    private BroadcastReceiver disconnectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (isConnected() && mUsbDevice.equals(usbDevice)) {
                mLogger.log("WALT was detached");
                disconnect();
            }
        }
    };

    private BroadcastReceiver respondToUsbPermission = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (mUsbDevice == null) {
                mLogger.log("USB device was not properly opened");
                return;
            }

            if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                    mUsbDevice.equals(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE))){
                mUsbConnection = mUsbManager.openDevice(mUsbDevice);

                mContext.registerReceiver(disconnectReceiver,
                        new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));

                onConnect();

                mBroadcastManager.sendBroadcast(new Intent(getConnectIntent()));
            } else {
                mLogger.log("Could not get permission to open the USB device");
            }
            mContext.unregisterReceiver(respondToUsbPermission);
        }
    };

    public UsbDevice findUsbDevice() {

        mLogger.log(String.format("Looking for TeensyUSB VID=0x%x PID=0x%x", getVid(), getPid()));

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
                    "USB Device: %s, VID:PID - %x:%x, %d interfaces",
                    key, dev.getVendorId(), dev.getProductId(), dev.getInterfaceCount()
            );

            if (isCompatibleUsbDevice(dev)) {
                usbDevice = dev;
                msg = "Using " + msg;
            } else {
                msg = "Skipping " + msg;
            }

            mLogger.log(msg);
        }
        return usbDevice;
    }
}
