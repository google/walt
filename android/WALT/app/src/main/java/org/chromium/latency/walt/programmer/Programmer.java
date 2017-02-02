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

package org.chromium.latency.walt.programmer;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.chromium.latency.walt.R;
import org.chromium.latency.walt.SimpleLogger;
import org.chromium.latency.walt.WaltConnection;

import java.io.InputStream;
import java.text.ParseException;
import java.util.Arrays;

public class Programmer {
    private static final String TAG = "Programmer";
    private SimpleLogger logger;

    private FirmwareImage image;
    private BootloaderConnection conn;

    private Context context;
    private Handler handler = new Handler();

    public Programmer(Context context) {
        this.context = context;
    }

    public void program() {
        logger = SimpleLogger.getInstance(context);
        InputStream in = context.getResources().openRawResource(R.raw.walt);
        image = new FirmwareImage();
        try {
            image.parseHex(in);
        } catch (ParseException e) {
            Log.e(TAG, "Parsing input file: ", e);
        }

        conn = BootloaderConnection.getInstance(context);
        // TODO: automatically reboot into the bootloader
        logger.log("\nRemember to press the button on the Teensy first\n");
        conn.setConnectionStateListener(new WaltConnection.ConnectionStateListener() {
            @Override
            public void onConnect() {
                handler.post(programRunnable);
            }

            @Override
            public void onDisconnect() {}
        });
        if (!conn.isConnected()) {
            conn.connect();
        }
    }

    private Runnable programRunnable = new Runnable() {
        @Override
        public void run() {
            logger.log("Programming...");

            // The logic for this is ported from
            // https://github.com/PaulStoffregen/teensy_loader_cli
            byte[] buf = new byte[DeviceConstants.BLOCK_SIZE + 64];
            for (int addr = 0; addr < DeviceConstants.FIRMWARE_SIZE;
                 addr += DeviceConstants.BLOCK_SIZE) {
                if (!image.shouldWrite(addr, DeviceConstants.BLOCK_SIZE) && addr != 0)
                    continue; // don't need to flash this block

                buf[0] = (byte) (addr & 255);
                buf[1] = (byte) ((addr >>> 8) & 255);
                buf[2] = (byte) ((addr >>> 16) & 255);
                Arrays.fill(buf, 3, 64, (byte) 0);
                image.getData(buf, 64, addr, DeviceConstants.BLOCK_SIZE);

                conn.write(buf, (addr == 0) ? 3000 : 250);
            }

            logger.log("Programming complete. Rebooting.");

            // reboot the device
            buf[0] = (byte) 0xFF;
            buf[1] = (byte) 0xFF;
            buf[2] = (byte) 0xFF;
            Arrays.fill(buf, 3, DeviceConstants.BLOCK_SIZE + 64, (byte) 0);
            conn.write(buf, 250);
        }
    };
}
