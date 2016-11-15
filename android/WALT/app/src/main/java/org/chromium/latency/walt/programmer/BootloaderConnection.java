package org.chromium.latency.walt.programmer;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;

import org.chromium.latency.walt.Connection;

class BootloaderConnection extends Connection {
    private static final int HALFKAY_VID = 0x16C0;
    private static final int HALFKAY_PID = 0x0478;

    private static final Object mLock = new Object();
    private static BootloaderConnection mInstance;

    public static BootloaderConnection getInstance(Context context) {
        synchronized (mLock) {
            if (mInstance == null) {
                mInstance = new BootloaderConnection(context.getApplicationContext());
            }
            return mInstance;
        }
    }

    @Override
    public int getPid() {
        return HALFKAY_PID;
    }

    @Override
    public int getVid() {
        return HALFKAY_VID;
    }

    @Override
    protected boolean isCompatibleUsbDevice(UsbDevice usbDevice) {
        return ((usbDevice.getProductId() == HALFKAY_PID) &&
                (usbDevice.getVendorId() == HALFKAY_VID));
    }

    @Override
    public void onConnect() {
        int ifIdx = 0;

        UsbInterface iface = mUsbDevice.getInterface(ifIdx);

        if (mUsbConnection.claimInterface(iface, true)) {
            mLogger.log("Interface claimed successfully\n");
        } else {
            mLogger.log("ERROR - can't claim interface\n");
        }
    }

    @Override
    public void onDisconnect() {}

    public void write(byte[] buf, int timeout) {
        write(buf, 0, buf.length, timeout);
    }

    public void write(byte[] buf, int index, int len, int timeout) {
        if (!isConnected()) return;

        while (timeout > 0) {
            // USB HID Set_Report message
            int result = mUsbConnection.controlTransfer(0x21, 9, 0x0200, index, buf, len, timeout);

            if (result >= 0) break;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            timeout -= 10;
        }
    }

    private BootloaderConnection(Context context) {
        super(context);
    }
}
