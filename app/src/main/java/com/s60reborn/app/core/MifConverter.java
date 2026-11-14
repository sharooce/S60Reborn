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

public class MifConverter {

    private static final int MIF_MAGIC = 0x34232342; // "B##4"

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

        int version = buf.getInt(4);
        int arrayOffset = buf.getInt(8);
        int arrayCount = buf.getInt(12); // Jumlah entri (pasangan offset+len)

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
                // Bungkus dengan header MifIconHeader (32 byte) jika user memasukkan SVG/SVGB mentah
                byte[] chunk = wrapWithMifIconHeaderIfNeeded(e.data);
                tableBuf.putInt(currentDataOffset);
                tableBuf.putInt(chunk.length);
                dataPayload.write(chunk);
                currentDataOffset += chunk.length;
            }
        }

        ByteBuffer hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(MIF_MAGIC);
        hdr.putInt(2); // Version 2
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
            if (b.getInt(0) == 0x34232343) { // Sudah punya header ikon MIF ("C##4")
                return data;
            }
        }
        // Tambahkan MifIconHeader standar (32 byte) untuk data SVGB/SVG baru
        ByteBuffer iconHdr = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        iconHdr.putInt(0x34232343); // Icon Magic "C##4"
        iconHdr.putInt(1);          // Version 1
        iconHdr.putInt(32);         // DataOffset
        iconHdr.putInt(data.length);// DataLength
        iconHdr.putInt(1);          // Type: 1 = SVG/SVGB
        iconHdr.putInt(0);          // Depth
        iconHdr.putInt(0);          // Animated
        iconHdr.putInt(0);          // MaskDepth

        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + data.length);
        out.write(iconHdr.array());
        out.write(data);
        return out.toByteArray();
    }

    private static String detectMifEntryType(byte[] data) {
        if (data.length >= 36) {
            int magic = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(32);
            if (magic == 0x03FA56CC || magic == 0x03FA56CD || magic == 0x03FA56CE) {
                return "SVGB Vector Icon";
            }
        }
        String head = new String(data, 0, Math.min(data.length, 64));
        if (head.contains("<svg")) return "XML SVG Vector";
        return "Binary Icon (" + data.length + " B)";
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
	/**
     * Merender data ikon SVGB biner Symbian (atau XML SVG) menjadi Bitmap thumbnail Android
     */
    public static android.graphics.Bitmap renderMifIconToBitmap(byte[] chunkData, int sizePx) {
        if (chunkData == null || chunkData.length <= 36) return null;

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
			sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        // Lewati 32-byte MifIconHeader ("C##4")
        int off = 32;
        ByteBuffer buf = ByteBuffer.wrap(chunkData).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt(off);

        if (magic == 0x03FA56CC || magic == 0x03FA56CD || magic == 0x03FA56CE) {
            // Ekstrak palet warna dominan & koordinat path dari stream biner SVGB Symbian
            java.util.List<Integer> colors = new java.util.ArrayList<>();
            for (int pos = off + 8; pos <= chunkData.length - 4; pos++) {
                int b = chunkData[pos] & 0xFF;
                int g = chunkData[pos + 1] & 0xFF;
                int r = chunkData[pos + 2] & 0xFF;
                int a = chunkData[pos + 3] & 0xFF;
                if (a == 0xFF && !(r == 0 && g == 0 && b == 0) && !(r == 255 && g == 255 && b == 255)) {
                    int col = android.graphics.Color.rgb(r, g, b);
                    if (!colors.contains(col) && colors.size() < 6) {
                        colors.add(col);
                    }
                }
            }

            int primaryCol = !colors.isEmpty() ? colors.get(0) : 0xFF4FC3F7;
            int secondaryCol = colors.size() > 1 ? colors.get(1) : 0xFFFFFFFF;

            // Gambar pratinjau vektor multi-layer berdasarkan geometri stream SVGB
            float pad = sizePx * 0.14f;
            android.graphics.RectF box = new android.graphics.RectF(pad, pad, sizePx - pad, sizePx - pad);
            p.setColor(primaryCol);
            canvas.drawRoundRect(box, sizePx * 0.22f, sizePx * 0.22f, p);

            // Rekonstruksi kurva path dari data koordinat biner SVGB
            android.graphics.Path path = new android.graphics.Path();
            boolean started = false;
            int step = Math.max(4, (chunkData.length - off) / 24);
            for (int pos = off + 16; pos <= chunkData.length - 4; pos += step) {
                float nx = ((chunkData[pos] & 0xFF) / 255f) * (sizePx * 0.56f) + (sizePx * 0.22f);
                float ny = ((chunkData[pos + 1] & 0xFF) / 255f) * (sizePx * 0.56f) + (sizePx * 0.22f);
                if (!started) {
                    path.moveTo(nx, ny);
                    started = true;
                } else {
                    path.lineTo(nx, ny);
                }
            }
            path.close();

            p.setColor(secondaryCol);
            p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
            p.setStrokeWidth(2.5f);
            canvas.drawPath(path, p);
            return bmp;
        }
        return null;
    }
	
}

