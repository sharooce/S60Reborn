package com.s60reborn.app.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class E32ImagePatcher {

    // 20 Standard Symbian OS 9.x Capabilities Bitmask
    public static final String[] CAP_NAMES = {
        "TCB", "CommDD", "PowerMgmt", "MultimediaDD", "ReadDeviceData",
        "WriteDeviceData", "DRM", "TrustedUI", "ProtServ", "DiskAdmin",
        "NetworkControl", "AllFiles", "SwEvent", "NetworkServices", "LocalServices",
        "ReadUserData", "WriteUserData", "Location", "SurroundingsDD", "UserEnvironment"
    };

    public static final int MASK_SELF_SIGNED_SAFE =
	(1 << 13) | (1 << 14) | (1 << 15) | (1 << 16) | (1 << 17) | (1 << 19); // 6 User Caps
    public static final int MASK_ALL_20_CAPS = 0x000FFFFF; // All - TCB (20 Caps)

    public static class E32Info {
        public boolean isValidE32;
        public int uid1, uid2, uid3;
        public int secureId;
        public int vendorId;
        public int capabilities;
        public int heapMin, heapMax;
        public int stackSize;
    }

    public static E32Info inspectE32(File exeFile) throws IOException {
        byte[] data = readAllBytes(exeFile);
        return inspectE32Bytes(data);
    }

    public static E32Info inspectE32Bytes(byte[] data) {
        E32Info info = new E32Info();
        if (data == null || data.length < 0x9C) return info;
        if (data[16] != 'E' || data[17] != 'P' || data[18] != 'O' || data[19] != 'C') return info;

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        info.isValidE32 = true;
        info.uid1 = b.getInt(0x00);
        info.uid2 = b.getInt(0x04);
        info.uid3 = b.getInt(0x08);
        info.stackSize = b.getInt(0x34);
        info.heapMin = b.getInt(0x38);
        info.heapMax = b.getInt(0x3C);
        info.secureId = b.getInt(0x78);
        info.vendorId = b.getInt(0x7C);
        info.capabilities = b.getInt(0x80);
        return info;
    }

    /**
     * Memodifikasi Capabilities, SecureID, dan HeapMax pada file .exe / .dll Symbian OS 9.x
     * serta memperbarui Header CRC32 (offset 0x14) agar executable valid.
     */
    public static void patchE32File(File exeFile, Integer newCaps, Integer newSid, Integer newHeapMax) throws IOException {
        byte[] data = readAllBytes(exeFile);
        if (data.length < 0x9C || data[16] != 'E' || data[17] != 'P' || data[18] != 'O' || data[19] != 'C') {
            throw new IOException("Bukan file E32Image Symbian OS 9.x yang valid.");
        }

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (newHeapMax != null && newHeapMax > 0) {
            b.putInt(0x3C, newHeapMax);
        }
        if (newSid != null && newSid != 0) {
            b.putInt(0x78, newSid);
        }
        if (newCaps != null) {
            b.putInt(0x80, newCaps);
        }

        // Hitung ulang Header CRC32 E32Image (dengan field CRC di 0x14 dinolkan sementara)
        int codeOffset = b.getInt(0x28);
        if (codeOffset >= 0x9C && codeOffset <= data.length) {
            b.putInt(0x14, 0);
            int crc = computeSymbianHeaderCrc(data, codeOffset);
            b.putInt(0x14, crc);
        }

        FileOutputStream fos = new FileOutputStream(exeFile);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }

    private static int computeSymbianHeaderCrc(byte[] header, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (header[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xEDB88320;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return ~crc;
    }

    private static byte[] readAllBytes(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }
}

