package org.chromium.latency.walt.programmer;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;

import org.chromium.latency.walt.BaseUsbConnection;

class BootloaderConnection extends BaseUsbConnection {
    private static final int HALFKAY_VID = 0x16C0;
    private static final int HALFKAY_PID = 0x0478;

    private static final Object LOCK = new Object();
    private static BootloaderConnection instance;

    public static BootloaderConnection getInstance(Context context) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new BootloaderConnection(context.getApplicationContext());
            }
            return instance;
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

        UsbInterface iface = usbDevice.getInterface(ifIdx);

        if (usbConnection.claimInterface(iface, true)) {
            logger.log("Interface claimed successfully\n");
        } else {
            logger.log("ERROR - can't claim interface\n");
        }

        super.onConnect();
    }

    public void write(byte[] buf, int timeout) {
        write(buf, 0, buf.length, timeout);
    }

    public void write(byte[] buf, int index, int len, int timeout) {
        if (!isConnected()) return;

        while (timeout > 0) {
            // USB HID Set_Report message
            int result = usbConnection.controlTransfer(0x21, 9, 0x0200, index, buf, len, timeout);

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
