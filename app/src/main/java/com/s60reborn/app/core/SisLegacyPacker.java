package com.s60reborn.app.core;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;
import com.s60reborn.app.model.SisProjectConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

public class SisLegacyPacker {

    private static class PackedItem {
        SisFileEntry entry;
        byte[] rawBytes;
        byte[] compBytes;
        byte[] srcPathUtf16;
        byte[] dstPathUtf16;
        int srcPathPtr;
        int dstPathPtr;
        int dataPtr;
    }

    public static void pack(File projectDir, SisPackageInfo info, SisProjectConfig cfg, File outputSisFile) throws Exception {
        if (info == null) throw new IOException("SisPackageInfo kosong.");

        String appName = (info.appName != null && !info.appName.trim().isEmpty())
            ? info.appName.trim() : "LegacyApp";
        byte[] appNameUtf16 = appName.getBytes(StandardCharsets.UTF_16LE);

        // Target Device default S60 2nd Edition (0x101F7960) atau dari config/info
        int targetProductUid = 0x101F7960;
        String targetProductName = "Series60ProductID";
        if (info.targetDevices != null && !info.targetDevices.isEmpty()) {
            targetProductUid = info.targetDevices.get(0).uid;
            if (info.targetDevices.get(0).name != null && !info.targetDevices.get(0).name.isEmpty()) {
                targetProductName = info.targetDevices.get(0).name;
            }
        }
        byte[] prodNameUtf16 = targetProductName.getBytes(StandardCharsets.UTF_16LE);

        // 1. Siapkan seluruh file dari folder proyek
        List<PackedItem> items = new ArrayList<>();
        if (info.files != null) {
            for (SisFileEntry entry : info.files) {
                File localFile = new File(projectDir, entry.localRelPath);
                byte[] raw = localFile.exists() ? readAllBytes(localFile)
                    : (entry.rawData != null ? entry.rawData : new byte[0]);
                if (raw.length == 0 && entry.operation != LegacySisField.FILE_TYPE_NULL) continue;

                PackedItem pi = new PackedItem();
                pi.entry = entry;
                pi.rawBytes = raw;
                pi.compBytes = deflateZlib(raw);

                String fileNameOnly = new File(entry.localRelPath).getName();
                String fakeWinSrc = "C:\\S60Reborn\\build\\" + fileNameOnly;
                pi.srcPathUtf16 = fakeWinSrc.getBytes(StandardCharsets.UTF_16LE);

                String dst = (entry.targetPath != null) ? entry.targetPath.trim() : "";
                if (entry.operation == LegacySisField.FILE_TYPE_TEXT) {
                    dst = ""; // Sesuai format EPOC untuk teks pop-up (FT)
                } else if (dst.isEmpty()) {
                    String cleanApp = appName.replaceAll("[^a-zA-Z0-9_]", "");
                    if (cleanApp.isEmpty()) cleanApp = "LegacyApp";
                    dst = "!:\\System\\Apps\\" + cleanApp + "\\" + fileNameOnly;
                }
                pi.dstPathUtf16 = dst.getBytes(StandardCharsets.UTF_16LE);
                items.add(pi);
            }
        }

        int numLangs = 1;
        int numFiles = items.size();
        int numReqs  = 1;

        // 2. Hitung seluruh offset tata letak biner S60v2 (0x10201ED3)
        int headerSize = LegacySisField.HEADER_SIZE_V2; // 0x60 (96 byte)
        int ptrLangs   = headerSize;                    // 0x60 (2 byte: uint16 lang)
        int ptrFiles   = ptrLangs + 2;                  // 0x62
        int filesSize  = numFiles * LegacySisField.REC_V2_SINGLE_LANG_SIZE; // 48 byte per record
        int ptrReqs    = ptrFiles + filesSize;
        int reqsSize   = 20;                            // 12B header req + 4B len + 4B ptr
        int ptrComp    = ptrReqs + reqsSize;
        int compSize   = 8;                             // 4B len + 4B ptr

        int cursor = ptrComp + compSize;

        // String Pool offsets
        int prodNamePtr = cursor;
        cursor += prodNameUtf16.length;

        int appNamePtr = cursor;
        cursor += appNameUtf16.length;

        for (PackedItem pi : items) {
            pi.srcPathPtr = cursor;
            cursor += pi.srcPathUtf16.length;
            if (pi.dstPathUtf16.length > 0) {
                pi.dstPathPtr = cursor;
                cursor += pi.dstPathUtf16.length;
            } else {
                pi.dstPathPtr = 0;
            }
        }

        // Data Pool offsets
        for (PackedItem pi : items) {
            pi.dataPtr = cursor;
            cursor += pi.compBytes.length;
        }

        int totalSize = cursor;

        // 3. Tulis seluruh biner ke ByteBuffer
        ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        // --- Header (0x00 .. 0x5F) ---
        int uid1 = LegacySisField.UID1_EPOC_V2; // 0x10201ED3
        int uid2 = LegacySisField.UID1_EPOC_V1; // 0x10003A12
        int uid3Comp = LegacySisField.UID2_SIS_LEGACY; // 0x10000419
        int uidChk = computeSymbianUidChecksum(uid1, uid2, uid3Comp);

        out.putInt(0x00, uid1);
        out.putInt(0x04, uid2);
        out.putInt(0x08, uid3Comp);
        out.putInt(0x0C, uidChk);
        out.putInt(0x10, info.uid3 != 0 ? info.uid3 : 0x10005678);

        out.putShort(0x12, (short) numLangs);
        out.putShort(0x14, (short) numFiles);
        out.putShort(0x16, (short) numReqs);
        out.putShort(0x18, (short) 1); // Selected language: English (1)
        out.putShort(0x1A, (short) 0); // Install drive
        out.putShort(0x1C, (short) 0); // Num caps

        byte legacyInstallType = (byte) LegacySisField.TYPE_SA;
        if (info.installType == 2) legacyInstallType = (byte) LegacySisField.TYPE_PU;
        else if (info.installType == 3) legacyInstallType = (byte) LegacySisField.TYPE_PA;
        out.put(0x1E, legacyInstallType);

        int options = LegacySisField.OPT_UNICODE; // 0x0001
        if ((info.installFlags & 0x01) != 0) options |= LegacySisField.OPT_SHUTDOWN_APPS;
        if ((info.installFlags & 0x02) != 0) options |= LegacySisField.OPT_NON_REMOVABLE;
        out.putShort(0x20, (short) options);
        out.putShort(0x22, (short) 0);

        out.putShort(0x24, (short) Math.max(0, info.majorVersion));
        out.putShort(0x26, (short) Math.max(0, info.minorVersion));
        out.putShort(0x28, (short) Math.max(0, info.buildVersion));
        out.putShort(0x2A, (short) 0);

        // Pointer table (shifted +4 untuk 0x10201ED3)
        out.putInt(0x30, ptrLangs);
        out.putInt(0x34, ptrFiles);
        out.putInt(0x38, ptrReqs);
        out.putInt(0x3C, 0); // ptrCerts (unsigned)
        out.putInt(0x40, ptrComp);

        // Extended header S60v2 (0x44..0x5F)
        out.putInt(0x44, totalSize);
        out.putInt(0x48, ptrComp);

        // --- Languages (0x60) ---
        out.putShort(ptrLangs, (short) 1); // ELangEnglish

        // --- File Records (mulai di ptrFiles = 0x62, masing-masing 48 byte) ---
        int rPos = ptrFiles;
        for (PackedItem pi : items) {
            int fOp = pi.entry.operation;
            if (pi.entry.isEmbeddedSis) fOp = LegacySisField.FILE_TYPE_COMPONENT;
            else if (fOp < 0 || fOp > 5) fOp = LegacySisField.FILE_TYPE_STANDARD;

            out.putShort(rPos, (short) LegacySisField.REC_SINGLE_LANG_FILE);
            out.putShort(rPos + 2, (short) 0);
            out.putInt(rPos + 4, fOp);
            out.putInt(rPos + 8, pi.entry.operationOptions);
            out.putInt(rPos + 12, pi.srcPathUtf16.length);
            out.putInt(rPos + 16, pi.srcPathPtr);
            out.putInt(rPos + 20, pi.dstPathUtf16.length);
            out.putInt(rPos + 24, pi.dstPathPtr);
            out.putInt(rPos + 28, pi.compBytes.length);
            out.putInt(rPos + 32, pi.dataPtr);
            out.putInt(rPos + 36, pi.rawBytes.length);
            out.putInt(rPos + 40, 0); // mimeLen
            out.putInt(rPos + 44, 0); // mimePtr
            rPos += LegacySisField.REC_V2_SINGLE_LANG_SIZE;
        }

        // --- Prerequisites (ptrReqs) ---
        out.putInt(ptrReqs, targetProductUid);
        out.putShort(ptrReqs + 4, (short) 0);
        out.putShort(ptrReqs + 6, (short) 0);
        out.putInt(ptrReqs + 8, 0);
        out.putInt(ptrReqs + 12, prodNameUtf16.length);
        out.putInt(ptrReqs + 16, prodNamePtr);

        // --- Component Names (ptrComp) ---
        out.putInt(ptrComp, appNameUtf16.length);
        out.putInt(ptrComp + 4, appNamePtr);

        // --- String Pool ---
        out.position(prodNamePtr);
        out.put(prodNameUtf16);
        out.put(appNameUtf16);
        for (PackedItem pi : items) {
            out.put(pi.srcPathUtf16);
            if (pi.dstPathUtf16.length > 0) {
                out.put(pi.dstPathUtf16);
            }
        }

        // --- Data Pool ---
        for (PackedItem pi : items) {
            out.position(pi.dataPtr);
            out.put(pi.compBytes);
        }

        if (outputSisFile.getParentFile() != null && !outputSisFile.getParentFile().exists()) {
            outputSisFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outputSisFile)) {
            fos.write(out.array());
        }
    }

    private static byte[] deflateZlib(byte[] input) {
        if (input == null || input.length == 0) return new byte[0];
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            bos.write(buf, 0, n);
        }
        deflater.end();
        return bos.toByteArray();
    }

    private static int computeSymbianUidChecksum(int uid1, int uid2, int uid3) {
        byte[] b12 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(uid1).putInt(uid2).putInt(uid3).array();
        int evenCrc = crc16Ccitt(b12, 0);
        int oddCrc  = crc16Ccitt(b12, 1);
        return (oddCrc << 16) | (evenCrc & 0xFFFF);
    }

    private static int crc16Ccitt(byte[] data, int startOffset) {
        int crc = 0;
        for (int i = startOffset; i < data.length; i += 2) {
            crc ^= ((data[i] & 0xFF) << 8);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                else crc = (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}

