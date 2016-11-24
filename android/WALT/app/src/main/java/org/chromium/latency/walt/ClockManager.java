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
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;

public class ClockManager extends BaseUsbConnection {

    private static final int TEENSY_VID = 0x16c0;
    // TODO: refactor to demystify PID. See BaseUsbConnection.isCompatibleUsbDevice()
    private static final int TEENSY_PID = 0;
    private static final int HALFKAY_PID = 0x0478;
    private static final int USB_READ_TIMEOUT_MS = 200;
    private static final int DEFAULT_DRIFT_LIMIT_US = 1500;
    private static final String TAG = "WaltClockManager";
    public static final String PROTOCOL_VERSION = "4";

    private UsbEndpoint mEndpointIn = null;
    private UsbEndpoint mEndpointOut = null;

    // Teensy side commands. Each command is a single char
    // Based on #defines section in walt.ino
    static final char CMD_PING_DELAYED     = 'D'; // Ping with a delay
    static final char CMD_RESET            = 'F'; // Reset all vars
    static final char CMD_SYNC_SEND        = 'I'; // Send some digits for clock sync
    static final char CMD_PING             = 'P'; // Ping with a single byte
    static final char CMD_VERSION          = 'V'; // Determine WALT's firmware version
    static final char CMD_SYNC_READOUT     = 'R'; // Read out sync times
    static final char CMD_GSHOCK           = 'G'; // Send last shock time and watch for another shock.
    static final char CMD_TIME_NOW         = 'T'; // Current time
    static final char CMD_SYNC_ZERO        = 'Z'; // Initial zero
    static final char CMD_AUTO_SCREEN_ON   = 'C'; // Send a message on screen color change
    static final char CMD_AUTO_SCREEN_OFF  = 'c';
    static final char CMD_SEND_LAST_SCREEN = 'E'; // Send info about last screen color change
    static final char CMD_BRIGHTNESS_CURVE = 'U'; // Probe screen for brightness vs time curve
    static final char CMD_AUTO_LASER_ON    = 'L'; // Send messages on state change of the laser
    static final char CMD_AUTO_LASER_OFF   = 'l';
    static final char CMD_SEND_LAST_LASER  = 'J';
    static final char CMD_AUDIO            = 'A'; // Start watching for signal on audio out line
    static final char CMD_BEEP             = 'B'; // Generate a tone into the mic and send timestamp
    static final char CMD_BEEP_STOP        = 'S'; // Stop generating tone
    static final char CMD_MIDI             = 'M'; // Start listening for a MIDI message
    static final char CMD_NOTE             = 'N'; // Generate a MIDI NoteOn message

    public long baseTime = 0;

    public long lastSync = 0;

    private static final Object mLock = new Object();
    private static ClockManager mInstance;

    public static ClockManager getInstance(Context context) {
        synchronized (mLock) {
            if (mInstance == null) {
                mInstance = new ClockManager(context.getApplicationContext());
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

    @Override
    public void onDisconnect() {
        if (!isListenerStopped()) {
            stopListener();
        }
        mEndpointIn = null;
        mEndpointOut = null;
    }

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
            checkVersion();
            syncClock();
        } catch (IOException e) {
            mLogger.log("Unable to communicate with WALT: " + e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && (mEndpointIn != null) && (mEndpointOut != null);
    }

    public static long microTime() {
        return System.nanoTime() / 1000;
    }

    public long micros() {
        return microTime() - baseTime;
    }

    private ClockManager(Context context) {
        super(context);
        mTriggerListener = new TriggerListener();
    }


    private byte[] char2byte(char c) {
        byte[] buff = new byte[1];
        buff[0] = (byte) c;
        return buff;
    }

    private void sendByte(char c) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }
        // mLogger.log("Sending char " + c);
        mUsbConnection.bulkTransfer(mEndpointOut, char2byte(c), 1, 100);
    }

    private String readOne() throws IOException {
        if (!isListenerStopped()) {
            throw new IOException("Listener is running");
        }

        byte[] buff = new byte[64];
        int ret = mUsbConnection.bulkTransfer(mEndpointIn, buff, 64, USB_READ_TIMEOUT_MS);

        if (ret < 0) {
            throw new IOException("Timed out reading from WALT");
        }
        String s = new String(buff, 0, ret);
        Log.i(TAG, "readOne() received byte: " + s);
        return s;
    }


    private String sendReceive(char c) throws IOException {
        sendByte(c);
        return readOne();
    }

    String command(char cmd, char ack) throws IOException {
        if (!isListenerStopped()) {
            sendByte(cmd); // TODO: check response even if the listener is running
            return "";
        }
        String response = sendReceive(cmd);
        if (!response.startsWith(String.valueOf(ack))) {
            throw new IOException("Unexpected response from WALT. Expected \"" + ack
                    + "\", got \"" + response + "\"");
        }
        return response.substring(1).trim();
    }

    String command(char cmd) throws IOException {
        return command(cmd, flipCase(cmd));
    }

    private char flipCase(char c) {
        if (Character.isUpperCase(c)) {
            return Character.toLowerCase(c);
        } else if (Character.isLowerCase(c)) {
            return Character.toUpperCase(c);
        } else {
            return c;
        }
    }

    public String readAll() throws IOException {
        if (!isListenerStopped()) {
            throw new IOException("Listener is running");
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

    public void checkVersion() throws IOException {
        if (!isConnected()) throw new IOException("Not connected to WALT");
        if (!isListenerStopped()) throw new IOException("Listener is running");

        String s = command(CMD_VERSION);
        if (!PROTOCOL_VERSION.equals(s)) {
            Resources res = mContext.getResources();
            throw new IOException(String.format(res.getString(R.string.protocol_version_mismatch),
                    s, PROTOCOL_VERSION));
        }
    }

    public void syncClock() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }

        if (!isListenerStopped()) {
            throw new IOException("Listener is running");
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

    public void checkDrift() {
        if (! isConnected()) {
            mLogger.log("ERROR: Not connected, aborting checkDrift()");
            return;
        }
        updateBounds();
        int minE = getMinE();
        int maxE = getMaxE();
        int drift = Math.abs(minE + maxE) / 2;
        String msg = String.format("Remote clock delayed between %d and %d us", minE, maxE);
        // TODO: Convert the limit to user editable preference
        if (drift > DEFAULT_DRIFT_LIMIT_US) {
            msg = "WARNING: High clock drift. " + msg;
        }
        mLogger.log(msg);
    }

    public long readLastShockTime_mock() {
        return micros() - 15000;
    }

    public long readLastShockTime() {
        String s;
        try {
            s = sendReceive(CMD_GSHOCK);
        } catch (IOException e) {
            mLogger.log("Error sending GSHOCK command: " + e.getMessage());
            return -1;
        }
        mLogger.log("Received S reply: " + s);
        long t = 0;
        try {
            t = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            mLogger.log("Bad reply for shock time: " + e.getMessage());
        }

        return t;
    }

    static class TriggerMessage {
        public char tag;
        public long t;
        public int value;
        public int count;
        // TODO: verify the format of the message while parsing it
        TriggerMessage(String s) {
            String[] parts = s.trim().split("\\s+");
            tag = parts[0].charAt(0);
            t = Integer.parseInt(parts[1]);
            value = Integer.parseInt(parts[2]);
            count = Integer.parseInt(parts[3]);
        }

        static boolean isTriggerString(String s) {
            return s.trim().matches("G\\s+[A-Z]\\s+\\d+\\s+\\d+.*");
        }
    }

    TriggerMessage readTriggerMessage(char cmd) throws IOException {
        String response = command(cmd, 'G');
        return new TriggerMessage(response);
    }


    /***********************************************************************************************
     Trigger Listener
     A thread that constantly polls the interface for incoming triggers and passes them to the handler

     */

    private TriggerListener mTriggerListener;
    private Thread mTriggerListenerThread;

    public enum ListenerState {
        RUNNING,
        STARTING,
        STOPPED,
        STOPPING
    }

    abstract static class TriggerHandler {
        private Handler handler;

        TriggerHandler() {
            handler = new Handler();
        }

        private void go(final String s) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onReceiveRaw(s);
                }
            });
        }

        void onReceiveRaw(String s) {
            if (TriggerMessage.isTriggerString(s)) {
                TriggerMessage tmsg = new TriggerMessage(s.substring(1).trim());
                onReceive(tmsg);
            } else {
                Log.i(TAG, "Malformed trigger data: " + s);
            }
        }

        abstract void onReceive(TriggerMessage tmsg);
    }

    private TriggerHandler mTriggerHandler;

    void setTriggerHandler(TriggerHandler triggerHandler) {
        mTriggerHandler = triggerHandler;
    }

    void clearTriggerHandler() {
        mTriggerHandler = null;
    }

    private class TriggerListener implements Runnable {
        static final int BUFF_SIZE = 1024 * 4;
        public ListenerState state = ListenerState.STOPPED;
        private byte[] buffer = new byte[BUFF_SIZE];

        @Override
        public void run() {
            state = ListenerState.RUNNING;
            while(isRunning()) {
                int ret = mUsbConnection.bulkTransfer(mEndpointIn, buffer, BUFF_SIZE, USB_READ_TIMEOUT_MS);
                if (ret > 0 && mTriggerHandler != null) {
                    String s = new String(buffer, 0, ret);
                    Log.i(TAG, "Listener received data: " + s);
                    if (s.length() > 0) {
                        mTriggerHandler.go(s);
                    }
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
        return mTriggerListener.isStopped();
    }

    public void startListener() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }
        mTriggerListenerThread = new Thread(mTriggerListener);
        mLogger.log("Starting Listener");
        mTriggerListener.state = ListenerState.STARTING;
        mTriggerListenerThread.start();
    }

    public void stopListener() {
        mLogger.log("Stopping Listener");
        mTriggerListener.stop();
        try {
            mTriggerListenerThread.join();
        } catch (InterruptedException e) {
            mLogger.log("Error while stopping Listener: " + e.getMessage());
        }
        mLogger.log("Listener stopped");
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
