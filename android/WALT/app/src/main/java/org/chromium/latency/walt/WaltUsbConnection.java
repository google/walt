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
public class WaltUsbConnection extends BaseUsbConnection {

    private static final int TEENSY_VID = 0x16c0;
    // TODO: refactor to demystify PID. See BaseUsbConnection.isCompatibleUsbDevice()
    private static final int TEENSY_PID = 0;
    private static final int HALFKAY_PID = 0x0478;
    private static final int USB_READ_TIMEOUT_MS = 200;
    private static final String TAG = "WaltUsbConnection";

    private UsbEndpoint mEndpointIn = null;
    private UsbEndpoint mEndpointOut = null;

    public RemoteClockInfo remoteClock = new RemoteClockInfo();

    private static final Object mLock = new Object();

    private static WaltUsbConnection mInstance;

    private WaltUsbConnection(Context context) {
        super(context);
    }


    public static WaltUsbConnection getInstance(Context context) {
        synchronized (mLock) {
            if (mInstance == null) {
                mInstance = new WaltUsbConnection(context.getApplicationContext());
            }
            return mInstance;
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
        mEndpointIn = null;
        mEndpointOut = null;
    }


    // Called when WALT is physically plugged into USB
    @Override
    public void onConnect() {
        // Serial mode only
        // TODO: find the interface and endpoint indexes no matter what mode it is
        int ifIdx = 1;
        int epInIdx = 1;
        int epOutIdx = 0;

        UsbInterface iface = mUsbDevice.getInterface(ifIdx);

        if (mUsbConnection.claimInterface(iface, true)) {
            mLogger.log("Interface claimed successfully\n");
        } else {
            mLogger.log("ERROR - can't claim interface\n");
            return;
        }

        mEndpointIn = iface.getEndpoint(epInIdx);
        mEndpointOut = iface.getEndpoint(epOutIdx);

        try {
            // TODO: restore checkVersion somehow.
            // checkVersion();
            syncClock();
        } catch (IOException e) {
            mLogger.log("Unable to communicate with WALT: " + e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && (mEndpointIn != null) && (mEndpointOut != null);
    }


    private byte[] char2byte(char c) {
        byte[] buff = new byte[1];
        buff[0] = (byte) c;
        return buff;
    }

    public void sendByte(char c) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }
        // mLogger.log("Sending char " + c);
        mUsbConnection.bulkTransfer(mEndpointOut, char2byte(c), 1, 100);
    }

    public int blockingRead(byte[] buffer) {
        return mUsbConnection.bulkTransfer(mEndpointIn, buffer, buffer.length, USB_READ_TIMEOUT_MS);
    }


    public void syncClock() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }

        try {
            int fd = mUsbConnection.getFileDescriptor();
            int ep_out = mEndpointOut.getAddress();
            int ep_in = mEndpointIn.getAddress();

            remoteClock.baseTime = syncClock(fd, ep_out, ep_in);
            remoteClock.minLag = 0;
            remoteClock.maxLag = getMaxE();
        } catch (Exception e) {
            mLogger.log("Exception while syncing clocks: " + e.getStackTrace());
        }
        mLogger.log("Synced clocks, maxE=" + remoteClock.maxLag + "us");
        Log.i(TAG, remoteClock.toString());
    }

    public void updateLag() {
        if (! isConnected()) {
            mLogger.log("ERROR: Not connected, aborting checkDrift()");
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
