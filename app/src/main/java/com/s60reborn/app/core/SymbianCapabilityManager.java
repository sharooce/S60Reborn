package com.s60reborn.app.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class SymbianCapabilityManager {

    public static final String[] CAPABILITY_NAMES = {
        "TCB",              // 0
        "CommDD",           // 1
        "PowerMgmt",        // 2
        "MultimediaDD",     // 3
        "ReadDeviceData",   // 4
        "WriteDeviceData",  // 5
        "DRM",              // 6
        "TrustedUI",        // 7
        "ProtServ",         // 8
        "DiskAdmin",        // 9
        "NetworkControl",   // 10
        "AllFiles",         // 11
        "SwEvent",          // 12
        "NetworkServices",  // 13
        "LocalServices",    // 14
        "ReadUserData",     // 15
        "WriteUserData",    // 16
        "Location",         // 17
        "SurroundingsDD",   // 18
        "UserEnvironment"   // 19
    };

    // Preset umum yang sering dipakai modder Symbian
    public static final long PRESET_NONE = 0x00000000L;
    public static final long PRESET_SELF_SIGNED_MAX =
	(1L << 13) | (1L << 14) | (1L << 15) | (1L << 16) | (1L << 19); // 0x0009E000 (Lolos Self-Sign tanpa hack)
    public static final long PRESET_ALL_20_CAPS = 0x000FFFFFL;               // Semua 20 capability aktif (Butuh HP Hacked)

    /**
     * Membaca bitmask 64-bit capability (offset 0x8C) dari file .exe / .dll Symbian OS 9.x
     */
    public static long readCapabilitiesFromE32(File e32File) {
        if (e32File == null || !e32File.exists() || e32File.length() < 0x9C) return 0L;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(e32File);
            byte[] hdr = new byte[0x9C];
            int total = 0;
            while (total < hdr.length) {
                int r = fis.read(hdr, total, hdr.length - total);
                if (r == -1) break;
                total += r;
            }
            if (total < 0x9C) return 0L;
            return readCapabilitiesFromBytes(hdr);
        } catch (Exception e) {
            return 0L;
        } finally {
            if (fis != null) try { fis.close(); } catch (Exception ignored) {}
        }
    }

	public static long readCapabilitiesFromBytes(byte[] data) {
        if (data == null || data.length < 0x90) return 0L;
        if (data[16] != 'E' || data[17] != 'P' || data[18] != 'O' || data[19] != 'C') {
            return 0L;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return buf.getLong(0x88);
    }
	

    /**
     * Mengubah bitmask capability di file .exe / .dll (offset 0x8C) dan menghitung ulang Header CRC32 (offset 0x18)
     */
    public static void writeCapabilitiesToE32(File e32File, long newCapMask) throws IOException {
        byte[] data = readAllBytes(e32File);
        if (data.length < 0x9C || data[16] != 'E' || data[17] != 'P' || data[18] != 'O' || data[19] != 'C') {
            throw new IOException("Bukan file E32Image Symbian OS 9.x yang valid.");
        }

		ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // 1. Tulis bitmask capability baru ke offset 0x88 (8 bytes: iCaps[0] & iCaps[1])
        buf.putLong(0x88, newCapMask);

        // 2. Hitung ulang Header CRC32 (offset 0x18) menggunakan iCodeOffset (0x6C)
        int codeOffset = buf.getInt(0x6C);
        if (codeOffset < 0x9C || codeOffset > data.length) codeOffset = 0x9C;

        buf.putInt(0x18, 0);
        int newHeaderCrc = computeSymbianHeaderCrc32(data, 0, codeOffset);
        buf.putInt(0x18, newHeaderCrc);
		

        FileOutputStream fos = new FileOutputStream(e32File);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }

    /**
     * Mengubah bitmask menjadi daftar nama capability yang mudah dibaca UI
     */
    public static List<String> getCapabilityNamesList(long capMask) {
        List<String> list = new ArrayList<String>();
        for (int i = 0; i < CAPABILITY_NAMES.length; i++) {
            if ((capMask & (1L << i)) != 0) {
                list.add(CAPABILITY_NAMES[i]);
            }
        }
        return list;
    }

    // CRC-32 standar IEEE 802.3 untuk validasi header E32Image Symbian
    private static int computeSymbianHeaderCrc32(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xFF;
            crc ^= b;
            for (int k = 0; k < 8; k++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xEDB88320;
                } else {
                    crc = (crc >>> 1);
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

