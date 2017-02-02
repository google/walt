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

    protected SimpleLogger logger;
    protected Context context;
    private LocalBroadcastManager broadcastManager;
    private BroadcastReceiver currentConnectReceiver;
    private WaltConnection.ConnectionStateListener connectionStateListener;

    private UsbManager usbManager;
    protected UsbDevice usbDevice = null;
    protected UsbDeviceConnection usbConnection;

    public BaseUsbConnection(Context context) {
        this.context = context;
        usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        logger = SimpleLogger.getInstance(context);
        broadcastManager = LocalBroadcastManager.getInstance(context);
    }

    public abstract int getVid();
    public abstract int getPid();

    // Used to distinguish between bootloader and normal mode that differ by PID
    // TODO: change intent strings to reduce dependence on PID
    protected abstract boolean isCompatibleUsbDevice(UsbDevice usbDevice);

    public void onDisconnect() {
        if (connectionStateListener != null) {
            connectionStateListener.onDisconnect();
        }
    }

    public void onConnect() {
        if (connectionStateListener != null) {
            connectionStateListener.onConnect();
        }
    }


    private String getConnectIntent() {
        return CONNECT_INTENT + getVid() + ":" + getPid();
    }

    private String getUsbPermissionResponseIntent() {
        return USB_PERMISSION_RESPONSE_INTENT + getVid() + ":" + getPid();
    }

    public boolean isConnected() {
        return usbConnection != null;
    }

    public void registerConnectCallback(final Runnable r) {
        if (currentConnectReceiver != null) {
            broadcastManager.unregisterReceiver(currentConnectReceiver);
            currentConnectReceiver = null;
        }

        if (isConnected()) {
            r.run();
            return;
        }

        currentConnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                broadcastManager.unregisterReceiver(this);
                r.run();
            }
        };
        broadcastManager.registerReceiver(currentConnectReceiver,
                new IntentFilter(getConnectIntent()));
    }

    public void connect() {
        UsbDevice usbDevice = findUsbDevice();
        connect(usbDevice);
    }

    public void connect(UsbDevice usbDevice) {
        if (usbDevice == null) {
            logger.log("Device not found.");
            return;
        }

        if (!isCompatibleUsbDevice(usbDevice)) {
            logger.log("Not a valid device");
            return;
        }

        this.usbDevice = usbDevice;

        // Request permission
        // This displays a dialog asking user for permission to use the device.
        // No dialog is displayed if the permission was already given before or the app started as a
        // result of intent filter when the device was plugged in.

        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(getUsbPermissionResponseIntent()), 0);
        context.registerReceiver(respondToUsbPermission,
                new IntentFilter(getUsbPermissionResponseIntent()));
        logger.log("Requesting permission for USB device.");
        usbManager.requestPermission(this.usbDevice, permissionIntent);
    }

    public void disconnect() {
        onDisconnect();

        usbConnection.close();
        usbConnection = null;
        usbDevice = null;

        context.unregisterReceiver(disconnectReceiver);
    }

    private BroadcastReceiver disconnectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (isConnected() && BaseUsbConnection.this.usbDevice.equals(usbDevice)) {
                logger.log("WALT was detached");
                disconnect();
            }
        }
    };

    private BroadcastReceiver respondToUsbPermission = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (usbDevice == null) {
                logger.log("USB device was not properly opened");
                return;
            }

            if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                    usbDevice.equals(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE))){
                usbConnection = usbManager.openDevice(usbDevice);

                BaseUsbConnection.this.context.registerReceiver(disconnectReceiver,
                        new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));

                onConnect();

                broadcastManager.sendBroadcast(new Intent(getConnectIntent()));
            } else {
                logger.log("Could not get permission to open the USB device");
            }
            BaseUsbConnection.this.context.unregisterReceiver(respondToUsbPermission);
        }
    };

    public UsbDevice findUsbDevice() {

        logger.log(String.format("Looking for TeensyUSB VID=0x%x PID=0x%x", getVid(), getPid()));

        HashMap<String, UsbDevice> deviceHash = usbManager.getDeviceList();
        if (deviceHash.size() == 0) {
            logger.log("No connected USB devices found");
            return null;
        }

        logger.log("Found " + deviceHash.size() + " connected USB devices:");

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

            logger.log(msg);
        }
        return usbDevice;
    }

    public void setConnectionStateListener(WaltConnection.ConnectionStateListener connectionStateListener) {
        this.connectionStateListener = connectionStateListener;
    }
}
