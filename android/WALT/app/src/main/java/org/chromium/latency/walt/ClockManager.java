/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.util.HashMap;

/**
 *
 */
public class ClockManager {
    static final int TEENSY_VID = 0x16c0;
    static final int USB_READ_TIMEOUT_MS = 200;
    public static final String TAG = "WaltClockManager";

    // Teensy side commands. Each command is a single char
    // Based on #defines section in walt.ino
    static final char CMD_PING_DELAYED     = 'D'; // Ping with a delay
    static final char CMD_RESET            = 'F'; // Reset all vars
    static final char CMD_SYNC_SEND        = 'I'; // Send some digits for clock sync
    static final char CMD_PING             = 'P'; // Ping with a single byte
    static final char CMD_SYNC_READOUT     = 'R'; // Read out sync times
    static final char CMD_GSHOCK           = 'G'; // Send last shock time and watch for another shock.
    static final char CMD_TIME_NOW         = 'T'; // Current time
    static final char CMD_SYNC_ZERO        = 'Z'; // Initial zero
    static final char CMD_AUTO_SCREEN_ON   = 'C'; // Send a message on screen color change
    static final char CMD_AUTO_SCREEN_OFF  = 'c';
    static final char CMD_SEND_LAST_SCREEN = 'E'; // Send info about last screen color change
    static final char CMD_AUTO_LASER_ON    = 'L'; // Send messages on state change of the laser
    static final char CMD_AUTO_LASER_OFF   = 'l';
    static final char CMD_SEND_LAST_LASER  = 'J';
    static final char CMD_AUDIO            = 'A'; // Start watching for signal on audio out line
    static final char CMD_BEEP             = 'B'; // Generate a tone into the mic and send timestamp


    // TODO: any more elegant solution rather than static?
    // On the other had we can declare this as a singleton
    public long baseTime = 0;

    private StringBuilder mTheLog = new StringBuilder();
    public long lastSync = 0;

    SimpleLogger mLogger;
    Context mContext;

    UsbManager mUsbManager;
    UsbDevice mUsbDevice = null;
    UsbDeviceConnection mUsbConnection;
    UsbEndpoint mEndpointIn = null;
    UsbEndpoint mEndpointOut = null;

    public static long microTime() {
        return System.nanoTime() / 1000;
    }

    public long micros() {
        return microTime() - baseTime;

    }

    public ClockManager(Context context, SimpleLogger logger) {
        mContext = context;
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        this.mLogger = logger;
    }

    public boolean isConnected() {
        return ((mEndpointIn != null) && (mEndpointOut != null));
    }


    public void connect() {
        findUsbDevice();

        if (mUsbDevice == null) {
            mLogger.log("TeensyUSB not found.");
            return;
        }

        // Request permission
        // This displays a dialog aking user for permission to use the device.
        // No dialog is displayed if the permission was already given before or the app started as a
        // result of intent filter when the device was plugged in.
        // TODO: Figure out how to check for permission here and respond to result of the dialog

        //PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent("com.google.android.HID.action.USB_PERMISSION"), 0);
        //mLogger.log("Requesting permission for USB device. Click OK then CONNECT.");
        //mUsbManager.requestPermission(mUsbDevice, permissionIntent);

        mUsbConnection = mUsbManager.openDevice(mUsbDevice);

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
    }

    public void findUsbDevice() {

        mLogger.log(String.format("Looking for TeensyUSB VID=0x%x", TEENSY_VID));

        HashMap<String, UsbDevice> deviceHash = mUsbManager.getDeviceList();
        if (deviceHash.size() == 0) {
            mLogger.log("No connected USB devices found");
            return;
        }

        mLogger.log("Found " + deviceHash.size() + " connected USB devices:");

        for (String key : deviceHash.keySet()) {

            UsbDevice dev = deviceHash.get(key);

            String msg = String.format(
                    "USB Device: %s, VID:PID - %x:%x, %d interfaces",
                    key, dev.getVendorId(), dev.getProductId(), dev.getInterfaceCount()
            );


            if (dev.getVendorId() == TEENSY_VID) {
                mUsbDevice = dev;
                msg += " <- using this one.";
            }

            mLogger.log(msg);
        }
    }


    byte[] char2byte(char c) {
        byte[] buff = new byte[1];
        buff[0] = (byte) c;
        return buff;
    }

    public void sendByte(char c) {
        if (!isConnected()) {
            mLogger.log("ERROR: Not connected - aborting sendByte()");
            return;
        }
        // mLogger.log("Sending char " + c);
        mUsbConnection.bulkTransfer(mEndpointOut, char2byte(c), 1, 100);
    }

    public String readOne() {
        if (!isListenerStopped()) {
            mLogger.log("ERROR: readOne(), listener is running - aborting read.");
            return "";
        }

        byte[] buff = new byte[64];
        int ret = mUsbConnection.bulkTransfer(mEndpointIn, buff, 64, USB_READ_TIMEOUT_MS);

        if (ret < 0) return "";  // Timed out
        String s = new String(buff, 0, ret);
        Log.i(TAG, "readOne() received byte: " + s);
        return s;
    }


    public String sendReceive(char c) {
        if (!isListenerStopped()) {
            mLogger.log("ERROR: listener is running - aborting sendReceive()");
            return "";
        }
        if (!isConnected()) {
            mLogger.log("ERROR: Not connected, aborting sendReceive().");
            return "";
        }
        sendByte(c);
        return readOne();
    }

    public String readAll() {

        if (!isListenerStopped()) {
            mLogger.log("ERROR: listener is running - aborting readAll().");
            return "";
        }

        // Things that were sent deliberately as separate packets using
        // Serial.send_now() on Teensy side will come out on separate
        // invocations of bulkTransfer() but just a bunch of text
        // can come out as a single text, at least up to 4K.
        int USB_BUFFER_LENGTH = 1024 * 4;
        byte[] buff = new byte[USB_BUFFER_LENGTH];
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int ret;
        while (true) {
            i++;
            // long t_pre = microTime();
            ret = mUsbConnection.bulkTransfer(mEndpointIn,
                    buff, USB_BUFFER_LENGTH, USB_READ_TIMEOUT_MS);
            // long dt = microTime() - t_pre;
            // mLog(String.format("Iteration %d, ret=%d, dt=%d", i, ret, dt));
            if (ret < 0) break;
            String s = new String(buff, 0, ret);
            // mLog("str=" + s);
            sb.append(new String(buff, 0, ret));
        }
        String s = sb.toString();
        Log.i(TAG, "readAll() received data: " + s);
        return s;
    }

    public void syncClock() {
        if (! isConnected()) {
            mLogger.log("ERROR: Not connected, aborting syncClock()");
            return;
        }

        if (!isListenerStopped()) {
            mLogger.log("ERROR: listener is running - aborting syncClock().");
            return;
        }

        int maxE = 0;
        try {
            int fd = mUsbConnection.getFileDescriptor();
            int ep_out = mEndpointOut.getAddress();
            int ep_in = mEndpointIn.getAddress();

            baseTime = syncClock(fd, ep_out, ep_in);
            maxE = getMaxE();
        } catch (Exception e) {
            mLogger.log("Exception while syncing clocks: " + e.getStackTrace());
        }

        lastSync = SystemClock.uptimeMillis();
        mLogger.log("Synced clocks, maxE=" + maxE + "us");
    }

    public void logDrift() {
        if (! isConnected()) {
            mLogger.log("ERROR: Not connected, aborting logDrift()");
            return;
        }
        updateBounds();
        int minE = getMinE();
        int maxE = getMaxE();
        mLogger.log(String.format("Remote clock delayed between %d and %d us", minE, maxE));
    }

    public long readLastShockTime_mock() {
        return micros() - 15000;
    }

    public long readLastShockTime() {
        String s = sendReceive(CMD_GSHOCK);
        mLogger.log("Received S reply: " + s);
        long t = 0;
        try {
            t = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            mLogger.log("Bad reply for shock time: " + e.getMessage());
        }

        return t;
    }

    public void watchForAcclerometerShock() {
        mLogger.log("Tap the phone screen with accelerometer probe");
        sendByte('F');
        sendByte(CMD_GSHOCK);
        readAll(); // Flush the input stream.
    }

    class TriggerMessage {
        public char tag;
        public long t;
        public int value;
        public int count;
        TriggerMessage(String s) {
            String[] parts = s.split("\\s+");
            tag = parts[1].charAt(0);
            t = Integer.parseInt(parts[2]);
            value = Integer.parseInt(parts[3]);
            count = Integer.parseInt(parts[4]);
        }
    }

    public TriggerMessage readTriggerMessage(char cmd) {
        String s = sendReceive(cmd);
        TriggerMessage msg = new TriggerMessage(s);
        return msg;
    }

    public TriggerMessage parseTriggerMessage(String s) {
        return new TriggerMessage(s);
    }


    /***********************************************************************************************
     USB Listener
     A thread that constantly polls the interface for incoming data and sends it as a LocalBroadcast

     */

    private UsbListener mUsbListener = new UsbListener();
    private Thread mUsbListenerThread;

    public final String INCOMING_DATA_INTENT = "incoming-usb-message";

    public enum ListenerState {
        RUNNING,
        STOPPED,
        STOPPING
    }



    class UsbListener implements Runnable {
        static final int BUFF_SIZE = 1024 * 4;
        public ListenerState state = ListenerState.STOPPED;
        private byte[] buffer = new byte[BUFF_SIZE];

        @Override
        public void run() {
            state = ListenerState.RUNNING;
            while(isRunning()) {
                int ret = mUsbConnection.bulkTransfer(mEndpointIn, buffer, BUFF_SIZE, USB_READ_TIMEOUT_MS);
                if (ret > 0) {
                    String s = new String(buffer, 0, ret);
                    Log.i(TAG, "Listener received data: " + s);
                    Intent intent = new Intent(INCOMING_DATA_INTENT);
                    intent.putExtra("message", s);
                    LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
                }
            }
            state = ListenerState.STOPPED;
        }

        public synchronized boolean isRunning() {
            return state == ListenerState.RUNNING;
        }

        public synchronized boolean isStopped() {
            return state == ListenerState.STOPPED;
        }

        public synchronized void stop() {
            state = ListenerState.STOPPING;
        }
    };

    public boolean isListenerStopped() {
        return mUsbListener.isStopped();
    }

    public void startUsbListener() {
        if (!isConnected()) {
            mLogger.log("ERROR: Not connected - aborting startUsbListener()");
            return;
        }
        mUsbListenerThread = new Thread(mUsbListener);
        mLogger.log("Starting USB Listener");
        mUsbListenerThread.start();
    }

    public void stopUsbListener() {
        mLogger.log("Stopping USB Listener");
        mUsbListener.stop();
        try {
            mUsbListenerThread.join();
        } catch (InterruptedException e) {
            mLogger.log("Error while stopping USB Listener: " + e.getMessage());
        }
        mLogger.log("USB Listener stopped");
    }
    //

    // NDK / JNI stuff
    // TODO: add guards to avoid calls to updateBounds and getter when listener is running.
    private native long syncClock(int fd, int endpoint_out, int endpoint_in);

    public native void updateBounds();

    public native int getMinE();

    public native int getMaxE();

    static {
        System.loadLibrary("sync_clock_jni");
    }

}
