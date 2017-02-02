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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;


public class WaltTcpConnection implements WaltConnection {

    // The local ip on ARC++ to connect to underlying ChromeOS
    private static final String SERVER_IP = "192.168.254.1";
    private static final int SERVER_PORT = 50007;
    private static final int TCP_READ_TIMEOUT_MS = 200;

    private final SimpleLogger logger;
    private HandlerThread networkThread;
    private Handler networkHandler;
    private final Object readLock = new Object();
    private boolean messageReceived = false;
    private Utils.ListenerState connectionState = Utils.ListenerState.STOPPED;
    private int lastRetVal;
    static final int BUFF_SIZE = 1024 * 4;
    private byte[] buffer = new byte[BUFF_SIZE];

    private final Handler mainHandler = new Handler();
    private RemoteClockInfo remoteClock = new RemoteClockInfo();

    private Socket socket;
    private OutputStream outputStream = null;
    private InputStream inputStream = null;

    private WaltConnection.ConnectionStateListener connectionStateListener;

    // Singleton stuff
    private static WaltTcpConnection instance;
    private static final Object LOCK = new Object();

    public static WaltTcpConnection getInstance(Context context) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new WaltTcpConnection(context.getApplicationContext());
            }
            return instance;
        }
    }

    private WaltTcpConnection(Context context) {
        logger = SimpleLogger.getInstance(context);
    }

    public void connect() {
        connectionState = Utils.ListenerState.STARTING;
        networkThread = new HandlerThread("NetworkThread");
        networkThread.start();
        networkHandler = new Handler(networkThread.getLooper());
        logger.log("Started network thread for TCP bridge");
        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
                    socket = new Socket(serverAddr, SERVER_PORT);
                    socket.setSoTimeout(TCP_READ_TIMEOUT_MS);
                    outputStream = socket.getOutputStream();
                    inputStream = socket.getInputStream();
                    logger.log("TCP connection established");
                    connectionState = Utils.ListenerState.RUNNING;
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.log("Can't connect to TCP bridge: " + e.getMessage());
                    connectionState = Utils.ListenerState.STOPPED;
                    return;
                }

                // Run the onConnect callback, but on main thread.
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        WaltTcpConnection.this.onConnect();
                    }
                });
            }
        });

    }

    public void onConnect() {
        if (connectionStateListener != null) {
            connectionStateListener.onConnect();
        }
    }

    public synchronized boolean isConnected() {
        return connectionState == Utils.ListenerState.RUNNING;
    }

    public void sendByte(char c) throws IOException {
        outputStream.write(Utils.char2byte(c));
    }

    public void sendString(String s) throws IOException {
        outputStream.write(s.getBytes("UTF-8"));
    }

    public synchronized int blockingRead(byte[] buff) {

        messageReceived = false;

        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                lastRetVal = -1;
                try {
                    synchronized (readLock) {
                        lastRetVal = inputStream.read(buffer);
                        messageReceived = true;
                        readLock.notifyAll();
                    }
                } catch (SocketTimeoutException e) {
                    messageReceived = true;
                    lastRetVal = -2;
                }
                catch (Exception e) {
                    e.printStackTrace();
                    messageReceived = true;
                    lastRetVal = -1;
                    // TODO: better messaging / error handling here
                }
            }
        });

        // TODO: make sure length is ok
        // This blocks on readLock which is taken by the blocking read operation
        try {
            synchronized (readLock) {
                while (!messageReceived) readLock.wait(TCP_READ_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            return -1;
        }

        if (lastRetVal > 0) {
            System.arraycopy(buffer, 0, buff, 0, lastRetVal);
        }

        return lastRetVal;
    }


    private void updateClock(String cmd) throws IOException {
        sendString(cmd);
        int retval = blockingRead(buffer);
        if (retval <= 0) {
            throw new IOException("WaltTcpConnection, can't sync clocks");
        }
        String s = new String(buffer, 0, retval);
        String[] parts = s.trim().split("\\s+");
        // TODO: make sure reply starts with "clock"
        long wallBaseTime = Long.parseLong(parts[1]);
        remoteClock.baseTime = wallBaseTime - RemoteClockInfo.uptimeZero();
        remoteClock.minLag = Integer.parseInt(parts[2]);
        remoteClock.maxLag = Integer.parseInt(parts[3]);
    }

    public RemoteClockInfo syncClock() throws IOException {
        updateClock("bridge sync");
        logger.log("Synced clocks via TCP bridge:\n" + remoteClock);
        return remoteClock;
    }

    public void updateLag() {
        try {
            updateClock("bridge update");
        } catch (IOException e) {
            logger.log("Failed to update clock lag: " + e.getMessage());
        }
    }

    public void setConnectionStateListener(ConnectionStateListener connectionStateListener) {
        this.connectionStateListener = connectionStateListener;
    }

    // A way to test if there is a TCP bridge to decide whether to use it.
    // Some thread dancing to get around the Android strict policy for no network on main thread.
    public static boolean probe() {
        ProbeThread probeThread = new ProbeThread();
        probeThread.start();
        try {
            probeThread.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return probeThread.isReachable;
    }

    private static class ProbeThread extends Thread {
        public boolean isReachable = false;
        private final String TAG = "ProbeThread";

        @Override
        public void run() {
            Socket socket = new Socket();
            try {
                InetSocketAddress remoteAddr = new InetSocketAddress(SERVER_IP, SERVER_PORT);
                socket.connect(remoteAddr, 50 /* timeout in milliseconds */);
                isReachable = true;
                socket.close();
            } catch (Exception e) {
                Log.i(TAG, "Probing TCP connection failed: " + e.getMessage());
            }
        }
    }
}
