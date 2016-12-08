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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;


public class WaltTcpConnection implements WaltConnection {

    // The local ip on ARC++ to connect to underlying ChromeOS
    private static final String SERVER_IP = "192.168.254.1";
    private static final int SERVER_PORT = 50007;

    private final SimpleLogger mLogger;
    private HandlerThread networkThread;
    private Handler networkHandler;
    private Object mReadLock = new Object();
    private boolean messageReceived = false;
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
                    mOutputStream = socket.getOutputStream();
                    mInputStream = socket.getInputStream();
                    mLogger.log("TCP connection established");

                } catch (Exception e) {
                    e.printStackTrace();
                    mLogger.log("Can't connect to TCP bridge: " + e.getMessage());
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
        if (mConnectionStateListener != null) {
            mConnectionStateListener.onConnect();
        }
    }

    public synchronized boolean isConnected() {
        return false;
    }

    public void sendByte(char c) throws IOException {
        mOutputStream.write(Utils.char2byte('p'));
    }

    public synchronized int blockingRead(byte[] buff) {

        messageReceived = false;

        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (mReadLock) {
                        lastRetVal = mInputStream.read(buffer);
                        messageReceived = true;
                        mReadLock.notifyAll();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    // TODO: better messaging / error handling here
                }
            }
        });

        // TODO: make sure length is ok
        // This blocks on mReadLock which is taken by the blocking read operation
        try {
            synchronized (mReadLock) {
                while (!messageReceived) mReadLock.wait();
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

    }

    public void setConnectionStateListener(ConnectionStateListener connectionStateListener) {
        mConnectionStateListener = connectionStateListener;
    }
}
