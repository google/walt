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
import android.os.Handler;
import android.util.Log;

import java.io.IOException;

/**
 * A singleton used as an interface for the physical WALT device.
 */
public class WaltDevice implements WaltConnection.ConnectionStateListener {

    private static final int DEFAULT_DRIFT_LIMIT_US = 1500;
    private static final String TAG = "WaltDevice";
    public static final String PROTOCOL_VERSION = "6";

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
    static final char CMD_ACCELEROMETER    = 'O'; // Generate a MIDI NoteOn message

    private static final int BYTE_BUFFER_SIZE = 1024 * 4;
    private byte[] buffer = new byte[BYTE_BUFFER_SIZE];

    private Context context;
    protected SimpleLogger logger;
    private WaltConnection connection;
    public RemoteClockInfo clock;
    private WaltConnection.ConnectionStateListener connectionStateListener;

    private static final Object LOCK = new Object();
    private static WaltDevice instance;

    public static WaltDevice getInstance(Context context) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new WaltDevice(context.getApplicationContext());
            }
            return instance;
        }
    }

    private WaltDevice(Context context) {
        this.context = context;
        triggerListener = new TriggerListener();
        logger = SimpleLogger.getInstance(context);
    }

    public void onConnect() {
        try {
            // TODO: restore
            softReset();
            checkVersion();
            syncClock();
        } catch (IOException e) {
            logger.log("Unable to communicate with WALT: " + e.getMessage());
        }

        if (connectionStateListener != null) {
            connectionStateListener.onConnect();
        }
    }

    // Called when disconnecting from WALT
    // TODO: restore this, not called from anywhere
    public void onDisconnect() {
        if (!isListenerStopped()) {
            stopListener();
        }

        if (connectionStateListener != null) {
            connectionStateListener.onDisconnect();
        }
    }

    public void connect() {
        if (WaltTcpConnection.probe()) {
            logger.log("Using TCP bridge for ChromeOS");
            connection = WaltTcpConnection.getInstance(context);
        } else {
            // USB connection
            logger.log("No TCP bridge detected, using direct USB connection");
            connection = WaltUsbConnection.getInstance(context);
        }
        connection.setConnectionStateListener(this);
        connection.connect();
    }

    public void connect(UsbDevice usbDevice) {
        // This happens when apps starts as a result of plugging WALT into USB. In this case we
        // receive an intent with a usbDevice
        WaltUsbConnection usbConnection = WaltUsbConnection.getInstance(context);
        connection = usbConnection;
        connection.setConnectionStateListener(this);
        usbConnection.connect(usbDevice);
    }

    public boolean isConnected() {
        return connection.isConnected();
    }


    public String readOne() throws IOException {
        if (!isListenerStopped()) {
            throw new IOException("Can't do blocking read while listener is running");
        }

        byte[] buff = new byte[64];
        int ret = connection.blockingRead(buff);

        if (ret < 0) {
            throw new IOException("Timed out reading from WALT");
        }
        String s = new String(buff, 0, ret);
        Log.i(TAG, "readOne() received data: " + s);
        return s;
    }


    private String sendReceive(char c) throws IOException {
        connection.sendByte(c);
        return readOne();
    }

    public void sendAndFlush(char c) {

        try {
            connection.sendByte(c);
            while(connection.blockingRead(buffer) > 0) {
                // flushing all incoming data
            }
        } catch (Exception e) {
            logger.log("Exception in sendAndFlush: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void softReset() {
        sendAndFlush(CMD_RESET);
    }

    String command(char cmd, char ack) throws IOException {
        if (!isListenerStopped()) {
            connection.sendByte(cmd); // TODO: check response even if the listener is running
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

    public void checkVersion() throws IOException {
        if (!isConnected()) throw new IOException("Not connected to WALT");
        if (!isListenerStopped()) throw new IOException("Listener is running");

        String s = command(CMD_VERSION);
        if (!PROTOCOL_VERSION.equals(s)) {
            Resources res = context.getResources();
            throw new IOException(String.format(res.getString(R.string.protocol_version_mismatch),
                    s, PROTOCOL_VERSION));
        }
    }

    public void syncClock() throws IOException {
        clock = connection.syncClock();
    }

    // Simple way of syncing clocks. Used for diagnostics. Accuracy of several ms.
    public void simpleSyncClock() throws IOException {
        byte[] buffer = new byte[1024];
        clock = new RemoteClockInfo();
        clock.baseTime = RemoteClockInfo.microTime();
        String reply = sendReceive(CMD_SYNC_ZERO);
        logger.log("Simple sync reply: " + reply);
        clock.maxLag = (int) clock.micros();
        logger.log("Synced clocks, the simple way:\n" + clock);
    }

    public void checkDrift() {
        if (! isConnected()) {
            logger.log("ERROR: Not connected, aborting checkDrift()");
            return;
        }
        connection.updateLag();
        int drift = Math.abs(clock.getMeanLag());
        String msg = String.format("Remote clock delayed between %d and %d us",
                clock.minLag, clock.maxLag);
        // TODO: Convert the limit to user editable preference
        if (drift > DEFAULT_DRIFT_LIMIT_US) {
            msg = "WARNING: High clock drift. " + msg;
        }
        logger.log(msg);
    }

    public long readLastShockTime_mock() {
        return clock.micros() - 15000;
    }

    public long readLastShockTime() {
        String s;
        try {
            s = sendReceive(CMD_GSHOCK);
        } catch (IOException e) {
            logger.log("Error sending GSHOCK command: " + e.getMessage());
            return -1;
        }
        Log.i(TAG, "Received S reply: " + s);
        long t = 0;
        try {
            t = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            logger.log("Bad reply for shock time: " + e.getMessage());
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

    private TriggerListener triggerListener;
    private Thread triggerListenerThread;

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

    private TriggerHandler triggerHandler;

    void setTriggerHandler(TriggerHandler triggerHandler) {
        this.triggerHandler = triggerHandler;
    }

    void clearTriggerHandler() {
        triggerHandler = null;
    }

    private class TriggerListener implements Runnable {
        static final int BUFF_SIZE = 1024 * 4;
        public Utils.ListenerState state = Utils.ListenerState.STOPPED;
        private byte[] buffer = new byte[BUFF_SIZE];

        @Override
        public void run() {
            state = Utils.ListenerState.RUNNING;
            while(isRunning()) {
                int ret = connection.blockingRead(buffer);
                if (ret > 0 && triggerHandler != null) {
                    String s = new String(buffer, 0, ret);
                    Log.i(TAG, "Listener received data: " + s);
                    if (s.length() > 0) {
                        triggerHandler.go(s);
                    }
                }
            }
            state = Utils.ListenerState.STOPPED;
        }

        public synchronized boolean isRunning() {
            return state == Utils.ListenerState.RUNNING;
        }

        public synchronized boolean isStopped() {
            return state == Utils.ListenerState.STOPPED;
        }

        public synchronized void stop() {
            state = Utils.ListenerState.STOPPING;
        }
    }

    public boolean isListenerStopped() {
        return triggerListener.isStopped();
    }

    public void startListener() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to WALT");
        }
        triggerListenerThread = new Thread(triggerListener);
        logger.log("Starting Listener");
        triggerListener.state = Utils.ListenerState.STARTING;
        triggerListenerThread.start();
    }

    public void stopListener() {
        logger.log("Stopping Listener");
        triggerListener.stop();
        try {
            triggerListenerThread.join();
        } catch (Exception e) {
            logger.log("Error while stopping Listener: " + e.getMessage());
        }
        logger.log("Listener stopped");
    }

    public void setConnectionStateListener(WaltConnection.ConnectionStateListener connectionStateListener) {
        this.connectionStateListener = connectionStateListener;
        if (isConnected()) {
            this.connectionStateListener.onConnect();
        }
    }

}
