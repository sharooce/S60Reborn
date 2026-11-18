package com.s60reborn.app.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

public class MifConverter {

    private static final int MIF_MAGIC = 0x34232342;      // "B##4"
    private static final int MIF_ICON_MAGIC = 0x34232343; // "C##4"

    public static class MifIconEntry {
        public int index;
        public byte[] data;
        public String typeLabel;
    }

    public static List<MifIconEntry> parseMif(File mifFile) throws IOException {
        byte[] raw = readAll(mifFile);
        if (raw.length < 16) throw new IOException("File MIF terlalu kecil.");

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt(0);
        if (magic != MIF_MAGIC) {
            throw new IOException(String.format("Header MIF tidak valid: 0x%08X", magic));
        }

        int arrayOffset = buf.getInt(8);
        int arrayCount = buf.getInt(12);

        List<MifIconEntry> list = new ArrayList<>();
        for (int i = 0; i < arrayCount; i++) {
            int pos = arrayOffset + (i * 8);
            if (pos + 8 > raw.length) break;
            int dataOff = buf.getInt(pos);
            int dataLen = buf.getInt(pos + 4);

            MifIconEntry entry = new MifIconEntry();
            entry.index = i;
            if (dataOff > 0 && dataLen > 0 && dataOff + dataLen <= raw.length) {
                entry.data = new byte[dataLen];
                System.arraycopy(raw, dataOff, entry.data, 0, dataLen);
                entry.typeLabel = detectMifEntryType(entry.data);
            } else {
                entry.data = new byte[0];
                entry.typeLabel = "Empty / Mask";
            }
            list.add(entry);
        }
        return list;
    }

    public static void replaceMifEntry(File mifFile, int targetIndex, byte[] newIconData) throws IOException {
        List<MifIconEntry> entries = parseMif(mifFile);
        if (targetIndex < 0 || targetIndex >= entries.size()) {
            throw new IOException("Indeks MIF tidak valid.");
        }
        entries.get(targetIndex).data = newIconData;
        entries.get(targetIndex).typeLabel = detectMifEntryType(newIconData);
        packMif(entries, mifFile);
    }

    public static void packMif(List<MifIconEntry> entries, File outMifFile) throws IOException {
        int headerSize = 16;
        int tableSize = entries.size() * 8;
        int currentDataOffset = headerSize + tableSize;

        ByteBuffer tableBuf = ByteBuffer.allocate(tableSize).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream dataPayload = new ByteArrayOutputStream();

        for (MifIconEntry e : entries) {
            if (e.data == null || e.data.length == 0) {
                tableBuf.putInt(0);
                tableBuf.putInt(0);
            } else {
                byte[] chunk = wrapWithMifIconHeaderIfNeeded(e.data);
                tableBuf.putInt(currentDataOffset);
                tableBuf.putInt(chunk.length);
                dataPayload.write(chunk);
                currentDataOffset += chunk.length;
            }
        }

        ByteBuffer hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(MIF_MAGIC);
        hdr.putInt(2);
        hdr.putInt(headerSize);
        hdr.putInt(entries.size());

        try (FileOutputStream fos = new FileOutputStream(outMifFile)) {
            fos.write(hdr.array());
            fos.write(tableBuf.array());
            fos.write(dataPayload.toByteArray());
        }
    }

    private static byte[] wrapWithMifIconHeaderIfNeeded(byte[] data) throws IOException {
        if (data.length >= 32) {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) == MIF_ICON_MAGIC) {
                return data;
            }
        }
        ByteBuffer iconHdr = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        iconHdr.putInt(MIF_ICON_MAGIC);
        iconHdr.putInt(1);
        iconHdr.putInt(32);
        iconHdr.putInt(data.length);
        iconHdr.putInt(1); // Type 1 = SVG/SVGB
        iconHdr.putInt(0);
        iconHdr.putInt(0);
        iconHdr.putInt(0);

        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + data.length);
        out.write(iconHdr.array());
        out.write(data);
        return out.toByteArray();
    }

    /**
     * Mengekstrak payload murni dari MifIconHeader ("C##4") dan mendekode zlib jika terkompresi
     */
    public static byte[] extractRawIconPayload(byte[] chunkData) {
        if (chunkData == null || chunkData.length == 0) return new byte[0];
        byte[] payload = chunkData;

        if (chunkData.length >= 16) {
            ByteBuffer buf = ByteBuffer.wrap(chunkData).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getInt(0) == MIF_ICON_MAGIC) {
                int dataOff = buf.getInt(8);
                int dataLen = buf.getInt(12);
                if (dataOff >= 16 && dataOff < chunkData.length) {
                    int actualLen = (dataLen > 0 && dataOff + dataLen <= chunkData.length)
                        ? dataLen : (chunkData.length - dataOff);
                    payload = new byte[actualLen];
                    System.arraycopy(chunkData, dataOff, payload, 0, actualLen);
                }
            }
        }

        // Cek jika payload terkompresi Zlib (0x78 0x9C / 0x78 0xDA / 0x78 0x01)
        if (payload.length > 6 && (payload[0] & 0xFF) == 0x78 &&
            (((payload[1] & 0xFF) == 0x9C) || ((payload[1] & 0xFF) == 0xDA) || ((payload[1] & 0xFF) == 0x01))) {
            try {
                Inflater inf = new Inflater();
                inf.setInput(payload);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(payload.length * 3);
                byte[] tmp = new byte[4096];
                while (!inf.finished()) {
                    int n = inf.inflate(tmp);
                    if (n > 0) bos.write(tmp, 0, n);
                    else if (inf.needsInput() || inf.needsDictionary()) break;
                }
                inf.end();
                if (bos.size() > 0) return bos.toByteArray();
            } catch (Exception ignored) {}
        }
        return payload;
    }

    private static String detectMifEntryType(byte[] data) {
        byte[] p = extractRawIconPayload(data);
        if (p.length >= 4) {
            int magic = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (magic == 0x03FA56CC || magic == 0x03FA56CD || magic == 0x03FA56CE) {
                return "SVGB Vector Icon";
            }
            if ((p[0] & 0xFF) == 'n' && (p[1] & 0xFF) == 'v' && (p[2] & 0xFF) == 'g') {
                return "NVG Vector Icon";
            }
            if ((p[0] & 0xFF) == 0x89 && (p[1] & 0xFF) == 0x50) {
                return "PNG Raster Icon";
            }
        }
        String head = new String(p, 0, Math.min(p.length, 64));
        if (head.contains("<svg")) return "XML SVG Vector";
        return "Icon (" + p.length + " B)";
    }

    public static Bitmap renderMifIconToBitmap(byte[] chunkData, int sizePx) {
        byte[] payload = extractRawIconPayload(chunkData);
        if (payload == null || payload.length < 4) return null;

        // 1. Jika PNG / JPG
        if ((payload[0] & 0xFF) == 0x89 && (payload[1] & 0xFF) == 0x50) {
            return BitmapFactory.decodeByteArray(payload, 0, payload.length);
        }

        // 2. Render SVGB / NVG / SVG XML melalui SymbianSvgRenderer
        return SymbianSvgRenderer.renderBytesToBitmap(payload, sizePx);
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

