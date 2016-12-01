package org.chromium.latency.walt;

import java.io.IOException;

/**
 * Created by kamrik on 12/1/16.
 */
public interface WaltConnection {
    boolean isConnected();

    void sendByte(char c) throws IOException;

    int blockingRead(byte[] buffer);

    RemoteClockInfo syncClock() throws IOException;

    void updateLag();
}
