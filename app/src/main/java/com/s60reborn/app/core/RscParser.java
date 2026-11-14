package com.s60reborn.app.core;

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

public class RscParser {

    public static class RscStringEntry {
        public int index;
        public int offset;
        public int originalByteLen;
        public boolean isUnicode;
        public String text;
    }

    /**
     * Memindai dan mengekstrak seluruh string bahasa (UTF-16LE & Latin-1 L-String) dari file .RSC
     */
    public static List<RscStringEntry> parseRscStrings(File rscFile) throws IOException {
        byte[] raw = readAll(rscFile);
        if (raw.length < 24) throw new IOException("File RSC terlalu kecil.");

        // Header RSC Symbian:
        // [0..15] UID1/UID2/UID3/Checksum (atau 4 byte header pada RSC generasi baru)
        // Di akhir file terdapat Index Table (jumlah resource * 2 byte offset)
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int indexTableOffset = buf.getInt(16) & 0xFFFF;
        int dataEnd = (indexTableOffset > 20 && indexTableOffset < raw.length) ? indexTableOffset : raw.length;

        List<RscStringEntry> results = new ArrayList<>();
        int pos = 20;

        while (pos < dataEnd - 2) {
            int charCount = raw[pos] & 0xFF;

            // Cek pola L-String UTF-16LE Symbian (panjang > 1, karakter ASCII dalam UTF-16LE: [char, 0x00])
            if (charCount >= 2 && charCount <= 120) {
                int alignedStart = ((pos + 1) % 2 == 0) ? (pos + 1) : (pos + 2);
                int byteLen = charCount * 2;
                if (alignedStart + byteLen <= dataEnd && isValidUtf16LeText(raw, alignedStart, charCount)) {
                    String s = new String(raw, alignedStart, byteLen, StandardCharsets.UTF_16LE);
                    RscStringEntry entry = new RscStringEntry();
                    entry.index = results.size();
                    entry.offset = pos;
                    entry.originalByteLen = (alignedStart - pos) + byteLen;
                    entry.isUnicode = true;
                    entry.text = s;
                    results.add(entry);
                    pos = alignedStart + byteLen;
                    continue;
                }

                // Cek pola L-String 8-bit (Latin-1 / ASCII)
                if (pos + 1 + charCount <= dataEnd && isValidAsciiText(raw, pos + 1, charCount)) {
                    String s = new String(raw, pos + 1, charCount, StandardCharsets.ISO_8859_1);
                    RscStringEntry entry = new RscStringEntry();
                    entry.index = results.size();
                    entry.offset = pos;
                    entry.originalByteLen = 1 + charCount;
                    entry.isUnicode = false;
                    entry.text = s;
                    results.add(entry);
                    pos += 1 + charCount;
                    continue;
                }
            }
            pos++;
        }

        return results;
    }

    /**
     * Menyimpan perubahan teks ke dalam file .RSC dan memperbarui offset Index Table di akhir file
     */
    public static void updateRscString(File rscFile, RscStringEntry target, String newText) throws IOException {
        byte[] raw = readAll(rscFile);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int indexTableOffset = buf.getInt(16) & 0xFFFF;
        boolean hasValidIndexTable = (indexTableOffset > 20 && indexTableOffset < raw.length);

        byte[] newStrBlock;
        if (target.isUnicode) {
            byte[] utf16 = newText.getBytes(StandardCharsets.UTF_16LE);
            int charLen = Math.min(255, newText.length());
            boolean needPad = ((target.offset + 1) % 2 != 0);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(charLen);
            if (needPad) bos.write(0x00);
            bos.write(utf16, 0, charLen * 2);
            newStrBlock = bos.toByteArray();
        } else {
            byte[] ascii = newText.getBytes(StandardCharsets.ISO_8859_1);
            int charLen = Math.min(255, ascii.length);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(charLen);
            bos.write(ascii, 0, charLen);
            newStrBlock = bos.toByteArray();
        }

        int delta = newStrBlock.length - target.originalByteLen;
        int cutStart = target.offset;
        int cutEnd = target.offset + target.originalByteLen;

        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream(raw.length + delta);
        rebuilt.write(raw, 0, cutStart);
        rebuilt.write(newStrBlock);
        rebuilt.write(raw, cutEnd, raw.length - cutEnd);

        byte[] outBytes = rebuilt.toByteArray();

        // Jika panjang teks berubah (delta != 0), geser seluruh pointer di Index Table akhir file .RSC!
        if (delta != 0 && hasValidIndexTable) {
            ByteBuffer outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);
            int newIndexTableOffset = indexTableOffset + delta;
            outBuf.putShort(16, (short) newIndexTableOffset);

            for (int ptr = newIndexTableOffset; ptr <= outBytes.length - 2; ptr += 2) {
                int oldResOff = outBuf.getShort(ptr) & 0xFFFF;
                if (oldResOff > cutStart) {
                    outBuf.putShort(ptr, (short) (oldResOff + delta));
                }
            }
        }

        try (FileOutputStream fos = new FileOutputStream(rscFile)) {
            fos.write(outBytes);
        }
    }

    private static boolean isValidUtf16LeText(byte[] data, int start, int charCount) {
        int printable = 0;
        for (int i = 0; i < charCount; i++) {
            int lo = data[start + i * 2] & 0xFF;
            int hi = data[start + i * 2 + 1] & 0xFF;
            if (hi == 0x00 && lo >= 32 && lo <= 126) {
                printable++;
            } else if (hi >= 0x01 && hi <= 0x06) {
                printable++; // Mendukung karakter Arab/Cyrillic/aksen
            } else {
                return false;
            }
        }
        return printable >= 2;
    }

    private static boolean isValidAsciiText(byte[] data, int start, int charCount) {
        for (int i = 0; i < charCount; i++) {
            int c = data[start + i] & 0xFF;
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = fis.read(b)) != -1) bos.write(b, 0, n);
            return bos.toByteArray();
        }
    }
}

