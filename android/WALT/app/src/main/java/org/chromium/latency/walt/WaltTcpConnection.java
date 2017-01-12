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
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;

import static android.os.SystemClock.uptimeMillis;


public class WaltTcpConnection implements WaltConnection {

    // The local ip on ARC++ to connect to underlying ChromeOS
    private static final String SERVER_IP = "192.168.254.1";
    private static final int SERVER_PORT = 50007;
    private static final int TCP_READ_TIMEOUT_MS = 200;

    private final SimpleLogger mLogger;
    private HandlerThread networkThread;
    private Handler networkHandler;
    private Object mReadLock = new Object();
    private boolean messageReceived = false;
    private Utils.ListenerState mConnectionState = Utils.ListenerState.STOPPED;
    private int lastRetVal;
    static final int BUFF_SIZE = 1024 * 4;
    private byte[] buffer = new byte[BUFF_SIZE];



    private final Handler mainHandler = new Handler();
    private RemoteClockInfo remoteClock = new RemoteClockInfo();

    private Socket socket;
    private OutputStream mOutputStream = null;
    private InputStream mInputStream = null;


    private WaltConnection.ConnectionStateListener mConnectionStateListener;

    // Singleton stuff
    private static WaltTcpConnection mInstance;
    private static final Object mLock = new Object();


    public static WaltTcpConnection getInstance(Context context) {
        synchronized (mLock) {
            if (mInstance == null) {
                mInstance = new WaltTcpConnection(context.getApplicationContext());
            }
            return mInstance;
        }
    }

    private WaltTcpConnection(Context context) {
        mLogger = SimpleLogger.getInstance(context);
    }

    public void connect() {
        mConnectionState = Utils.ListenerState.STARTING;
        networkThread = new HandlerThread("NetworkThread");
        networkThread.start();
        networkHandler = new Handler(networkThread.getLooper());
        mLogger.log("Started network thread for TCP bridge");
        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
                    socket = new Socket(serverAddr, SERVER_PORT);
                    socket.setSoTimeout(TCP_READ_TIMEOUT_MS);
                    mOutputStream = socket.getOutputStream();
                    mInputStream = socket.getInputStream();
                    mLogger.log("TCP connection established");
                    mConnectionState = Utils.ListenerState.RUNNING;
                } catch (Exception e) {
                    e.printStackTrace();
                    mLogger.log("Can't connect to TCP bridge: " + e.getMessage());
                    mConnectionState = Utils.ListenerState.STOPPED;
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

    /**
     * With TCP bridge, the connection is super slow for the first few bytes. This is some arbitrary
     * communication to get past this initial state.
     */
    private void connectionWarmup() {
        try {
            sendByte(WaltDevice.CMD_SYNC_SEND);
        } catch (Exception e) {}

        long startTime = uptimeMillis();

        final int READ_COUNT = 30;
        String allReplies = "";
        for (int i = 0; i < READ_COUNT; i++) {
            int retval = blockingRead(buffer);
            String msg = String.format(Locale.US,
                    "Connection warm up: ret=%d, T=%d",
                    retval,
                    uptimeMillis() - startTime
            );
            if (retval > 0) {
                String reply = new String(buffer, 0, retval);
                msg = msg + " Reply=" + reply;

                // This is some hackish logic to break out once we got all the expected data.
                // Otherwise this loop takes TCP_READ_TIMEOUT_MS * remaining iterations
                // which is too long.
                allReplies = allReplies + reply.trim().replace("\n", "");
                if (allReplies.equals("123456789")) {
                    i = READ_COUNT - 3;
                }
            }
            mLogger.log(msg);
        }

    }

    public void onConnect() {
        connectionWarmup();
        if (mConnectionStateListener != null) {
            mConnectionStateListener.onConnect();
        }
    }

    public synchronized boolean isConnected() {
        return mConnectionState == Utils.ListenerState.RUNNING;
    }

    public void sendByte(char c) throws IOException {
        mOutputStream.write(Utils.char2byte(c));
    }

    public synchronized int blockingRead(byte[] buff) {

        messageReceived = false;

        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                lastRetVal = -1;
                try {
                    synchronized (mReadLock) {
                        lastRetVal = mInputStream.read(buffer);
                        messageReceived = true;
                        mReadLock.notifyAll();
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
        // This blocks on mReadLock which is taken by the blocking read operation
        try {
            synchronized (mReadLock) {
                while (!messageReceived) mReadLock.wait(TCP_READ_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            return -1;
        }

        if (lastRetVal > 0) {
            System.arraycopy(buffer, 0, buff, 0, lastRetVal);
        }

        return lastRetVal;
    }


    public RemoteClockInfo syncClock() throws IOException {
        remoteClock.baseTime = RemoteClockInfo.microTime();
        return remoteClock;
    }

    public void updateLag() {
        // TODO: needs implementation
    }

    public void setConnectionStateListener(ConnectionStateListener connectionStateListener) {
        mConnectionStateListener = connectionStateListener;
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
            Socket socket = new Socket();;
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
