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

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.Arrays;

class FirmwareImage {
    private static final String TAG = "FirmwareImage";

    private boolean atEOF = false;
    private byte[] image = new byte[DeviceConstants.FIRMWARE_SIZE];
    private boolean[] mask = new boolean[DeviceConstants.FIRMWARE_SIZE];

    boolean shouldWrite(int addr, int len) {
        if (addr < 0 || addr + len > DeviceConstants.FIRMWARE_SIZE) return false;
        for (int i = 0; i < len; i++) {
            if (mask[addr + i]) return true;
        }
        return false;
    }

    void getData(byte[] dest, int index, int addr, int count) {
        System.arraycopy(image, addr, dest, index, count);
    }

    void parseHex(InputStream stream) throws ParseException {
        Arrays.fill(image, (byte) 0xFF);
        Arrays.fill(mask, false);
        BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                parseLine(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Reading input file: " + e);
        }

        if (!atEOF) throw new ParseException("No EOF marker", -1);
        Log.d(TAG, "Done parsing file");
    }

    private void parseLine(String line) throws ParseException {
        if (atEOF) throw new ParseException("Line after EOF marker", -1);
        int cur = 0;
        final int length = line.length();
        if (length < 1 || line.charAt(cur) != ':') {
            throw new ParseException("Expected ':', got '" + line.charAt(cur), cur);
        }
        cur++;

        int count = parseByte(line, cur);
        cur += 2;
        int addr = parseInt(line, cur);
        cur += 4;
        byte code = parseByte(line, cur);
        cur += 2;

        switch (code) {
            case 0x00: {
                parseData(line, cur, count, image, mask, addr);
                // TODO: verify checksum
                break;
            }
            case 0x01: {
                Log.d(TAG, "Got EOF marker");
                atEOF = true;
                return;
            }
            default: {
                throw new ParseException(String.format("Unknown code '%x'", code), cur);
            }
        }
    }

    private static byte parseByte(String line, int pos) throws ParseException {
        if (line.length() < pos + 2) throw new ParseException("Unexpected EOL", pos);
        try {
            return (byte) Integer.parseInt(line.substring(pos, pos + 2), 16);
        } catch (NumberFormatException e) {
            throw new ParseException("Malformed file: " + e.getMessage(), pos);
        }
    }

    private static int parseInt(String line, int pos) throws ParseException {
        if (line.length() < pos + 4) throw new ParseException("Unexpected EOL", pos);
        try {
            return Integer.parseInt(line.substring(pos, pos + 4), 16);
        } catch (NumberFormatException e) {
            throw new ParseException("Malformed file: " + e.getMessage(), pos);
        }
    }

    private static void parseData(String line, int pos, int count,
                                  byte[] dest, boolean[] mask, int addr) throws ParseException {
        for (int i = 0; i < count; i++) {
            try {
                dest[addr + i] = parseByte(line, pos + i * 2);
                mask[addr + i] = true;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new ParseException(String.format("Address '%x' out of range", addr + i),
                        pos + i * 2);
            }
        }
    }
}
