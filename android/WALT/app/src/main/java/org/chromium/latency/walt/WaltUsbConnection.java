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

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.IOException;

/**
 * A singleton used as an interface for the physical WALT device.
 */
public class WaltUsbConnection extends BaseUsbConnection implements WaltConnection {

    private static final int TEENSY_VID = 0x16c0;
    // TODO: refactor to demystify PID. See BaseUsbConnection.isCompatibleUsbDevice()
    private static final int TEENSY_PID = 0;
    private static final int HALFKAY_PID = 0x0478;
    private static final int USB_READ_TIMEOUT_MS = 200;
    private static final String TAG = "WaltUsbConnection";

    private UsbEndpoint endpointIn = null;
    private UsbEndpoint endpointOut = null;

    private RemoteClockInfo remoteClock = new RemoteClockInfo();

    private static final Object LOCK = new Object();

    private static WaltUsbConnection instance;

    private WaltUsbConnection(Context context) {
        super(context);
    }

    public static WaltUsbConnection getInstance(Context context) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new WaltUsbConnection(context.getApplicationContext());
            }
            return instance;
        }
    }

    @Override
    public int getPid() {
        return TEENSY_PID;
    }

    @Override
    public int getVid() {
        return TEENSY_VID;
    }

    @Override
    protected boolean isCompatibleUsbDevice(UsbDevice usbDevice) {
        // Allow any Teensy, but not in HalfKay bootloader mode
        // Teensy PID depends on mode (e.g: Serail + MIDI) and also changed in TeensyDuino 1.31
        return ((usbDevice.getProductId() != HALFKAY_PID) &&
                (usbDevice.getVendorId() == TEENSY_VID));
    }


    // Called when WALT is physically unplugged from USB
    @Override
    public void onDisconnect() {
        endpointIn = null;
        endpointOut = null;
        super.onDisconnect();
    }


    // Called when WALT is physically plugged into USB
    @Override
    public void onConnect() {
        // Serial mode only
        // TODO: find the interface and endpoint indexes no matter what mode it is
        int ifIdx = 1;
        int epInIdx = 1;
        int epOutIdx = 0;

        UsbInterface iface = usbDevice.getInterface(ifIdx);

        if (usbConnection.claimInterface(iface, true)) {
            logger.log("Interface claimed successfully\n");
        } else {
            logger.log("ERROR - can't claim interface\n");
            return;
        }

        endpointIn = iface.getEndpoint(epInIdx);
        endpointOut = iface.getEndpoint(epOutIdx);

        super.onConnect();
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && (endpointIn != null) && (endpointOut != null);
    }


    @Override
    public void sendByte(char c) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }
        // logger.log("Sending char " + c);
        usbConnection.bulkTransfer(endpointOut, Utils.char2byte(c), 1, 100);
    }

    @Override
    public int blockingRead(byte[] buffer) {
        return usbConnection.bulkTransfer(endpointIn, buffer, buffer.length, USB_READ_TIMEOUT_MS);
    }


    @Override
    public RemoteClockInfo syncClock() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }

        try {
            int fd = usbConnection.getFileDescriptor();
            int ep_out = endpointOut.getAddress();
            int ep_in = endpointIn.getAddress();

            remoteClock.baseTime = syncClock(fd, ep_out, ep_in);
            remoteClock.minLag = 0;
            remoteClock.maxLag = getMaxE();
        } catch (Exception e) {
            logger.log("Exception while syncing clocks: " + e.getStackTrace());
        }
        logger.log("Synced clocks, maxE=" + remoteClock.maxLag + "us");
        Log.i(TAG, remoteClock.toString());
        return remoteClock;
    }

    @Override
    public void updateLag() {
        if (! isConnected()) {
            logger.log("ERROR: Not connected, aborting checkDrift()");
            return;
        }
        updateBounds();
        remoteClock.minLag = getMinE();
        remoteClock.maxLag = getMaxE();
    }



    // NDK / JNI stuff
    // TODO: add guards to avoid calls to updateBounds and getter when listener is running.
    private native long syncClock(int fd, int endpoint_out, int endpoint_in);

    private native void updateBounds();

    private native int getMinE();

    private native int getMaxE();

    static {
        System.loadLibrary("sync_clock_jni");
    }
}
