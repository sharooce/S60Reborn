package com.s60reborn.app.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MifCacheManager {

    private static final int MIF_MAGIC_1 = 0x34232342;
    private static final int MIF_MAGIC_2 = 0x00000002;
    private static final int ICON_MAGIC  = 0x34232343;

    /**
     * 1. EXTRACT: Membongkar file .MIF ke folder cache (0.svgb, 1.svg, 2.png, dst.)
     */
    public static int extractMifToCache(File mifFile, File cacheDir) throws IOException {
        if (!cacheDir.exists()) cacheDir.mkdirs();

        // Bersihkan cache lama sebelum ekstraksi baru
        cleanMifCache(cacheDir);
        cacheDir.mkdirs();

        byte[] raw = readAll(mifFile);
        if (raw.length < 16) throw new IOException("File MIF terlalu kecil atau rusak.");

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int magic1 = buf.getInt(0);
        if (magic1 != MIF_MAGIC_1) {
            throw new IOException("Header MIF tidak valid (bukan format Symbian MIF).");
        }

        int headerSize = buf.getInt(8);
        int entryCount = buf.getInt(12);
        int extractedCount = 0;

        int tableStart = headerSize;
        int iconCount = entryCount / 2;

        for (int i = 0; i < iconCount; i++) {
            int entryPos = tableStart + (i * 16);
            if (entryPos + 8 > raw.length) break;

            int dataOffset = buf.getInt(entryPos);
            int dataLength = buf.getInt(entryPos + 4);

            if (dataOffset > 0 && dataLength > 32 && (dataOffset + dataLength) <= raw.length) {
                int payloadStart = dataOffset + 32;
                int payloadLen = dataLength - 32;

                if (payloadLen > 0 && payloadStart + payloadLen <= raw.length) {
                    String ext = detectAssetExtension(raw, payloadStart, payloadLen);
                    File outFile = new File(cacheDir, i + "." + ext);

                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(outFile);
                        fos.write(raw, payloadStart, payloadLen);
                        extractedCount++;
                    } finally {
                        if (fos != null) {
                            try { fos.close(); } catch (IOException ignored) {}
                        }
                    }
                }
            }
        }

        return extractedCount;
    }

    /**
     * 2. PACK: Membaca seluruh file di folder cache sesuai urutan nomor indeks (0, 1, 2, ...)
     *    dan membungkusnya kembali menjadi file .MIF yang valid.
     */
    public static int packCacheToMif(File cacheDir, File outMifFile) throws IOException {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            throw new IOException("Folder cache MIF kosong.");
        }

        Arrays.sort(files, new Comparator<File>() {
				@Override
				public int compare(File f1, File f2) {
					return Integer.compare(extractIndexFromName(f1.getName()), extractIndexFromName(f2.getName()));
				}
			});

        List<byte[]> payloads = new ArrayList<byte[]>();
        List<Integer> types = new ArrayList<Integer>();

        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.isFile()) continue;
            byte[] data = readAll(f);
            payloads.add(data);

            String low = f.getName().toLowerCase();
            int assetType = (low.endsWith(".svg") || low.endsWith(".svgb")) ? 1 : 0;
            types.add(assetType);
        }

        int iconCount = payloads.size();
        int entryCount = iconCount * 2;
        int headerSize = 16;
        int tableSize = entryCount * 8;
        int currentDataOffset = headerSize + tableSize;

        ByteBuffer tableBuf = ByteBuffer.allocate(tableSize).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();

        for (int i = 0; i < iconCount; i++) {
            byte[] payload = payloads.get(i);
            int totalItemLen = 32 + payload.length;

            tableBuf.putInt(currentDataOffset);
            tableBuf.putInt(totalItemLen);

            tableBuf.putInt(0);
            tableBuf.putInt(0);

            ByteBuffer itemHeader = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            itemHeader.putInt(ICON_MAGIC);
            itemHeader.putInt(1);
            itemHeader.putInt(32);
            itemHeader.putInt(payload.length);
            itemHeader.putInt(types.get(i));
            itemHeader.putInt(0);
            itemHeader.putInt(0);
            itemHeader.putInt(0);

            dataStream.write(itemHeader.array());
            dataStream.write(payload);

            int pad = (4 - (totalItemLen % 4)) % 4;
            for (int p = 0; p < pad; p++) {
                dataStream.write(0);
            }

            currentDataOffset += totalItemLen + pad;
        }

        ByteBuffer mainHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        mainHeader.putInt(MIF_MAGIC_1);
        mainHeader.putInt(MIF_MAGIC_2);
        mainHeader.putInt(headerSize);
        mainHeader.putInt(entryCount);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outMifFile);
            fos.write(mainHeader.array());
            fos.write(tableBuf.array());
            fos.write(dataStream.toByteArray());
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }

        return iconCount;
    }

    /**
     * 3. ADD NEW: Menambahkan file gambar baru ke indeks berikutnya di folder cache
     */
    public static int addNewAssetToCache(File cacheDir, File sourceImageFile) throws IOException {
        if (!cacheDir.exists()) cacheDir.mkdirs();

        int maxIdx = -1;
        File[] existing = cacheDir.listFiles();
        if (existing != null) {
            for (int i = 0; i < existing.length; i++) {
                int idx = extractIndexFromName(existing[i].getName());
                if (idx > maxIdx) maxIdx = idx;
            }
        }

        int nextIndex = maxIdx + 1;
        String name = sourceImageFile.getName();
        String ext = "svgb";
        int dot = name.lastIndexOf('.');
        if (dot != -1) {
            ext = name.substring(dot + 1).toLowerCase();
        }

        File destFile = new File(cacheDir, nextIndex + "." + ext);
        byte[] data = readAll(sourceImageFile);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(destFile);
            fos.write(data);
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }

        return nextIndex;
    }

    /**
     * 4. CLEAN: Menghapus seluruh isi folder cache beserta subfoldernya secara rekursif
     */
    public static boolean cleanMifCache(File cacheDir) {
        if (cacheDir == null || !cacheDir.exists()) {
            return true;
        }
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    cleanMifCache(files[i]);
                } else {
                    files[i].delete();
                }
            }
        }
        return cacheDir.delete();
    }

    /**
     * 5. PACK & CLEAN: Mengemas ulang cache ke file .MIF, lalu menghapus folder cache jika sukses
     */
    public static int packAndCleanMif(File cacheDir, File outMifFile) throws IOException {
        int packedCount = packCacheToMif(cacheDir, outMifFile);
        if (packedCount > 0 && outMifFile.exists() && outMifFile.length() > 16) {
            cleanMifCache(cacheDir);
        }
        return packedCount;
    }
	
	

    private static int extractIndexFromName(String fileName) {
        try {
            int dot = fileName.indexOf('.');
            String numPart = (dot != -1) ? fileName.substring(0, dot) : fileName;
            String digits = numPart.replaceAll("[^0-9].*$", "");
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String detectAssetExtension(byte[] data, int off, int len) {
        if (len >= 4) {
            int b0 = data[off] & 0xFF;
            int b1 = data[off + 1] & 0xFF;
            if ((b0 == 0xCC && b1 == 0x56) || (b0 == 0xCD && b1 == 0x56) || (b0 == 0xCE && b1 == 0x56)) {
                return "svgb";
            }
            if (b0 == 0x89 && b1 == 0x50 && (data[off + 2] & 0xFF) == 0x4E) {
                return "png";
            }
        }
        String head = new String(data, off, Math.min(len, 64));
        if (head.contains("<svg") || head.contains("<?xml")) {
            return "svg";
        }
        return "svgb";
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream fis = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            fis = new FileInputStream(f);
            byte[] b = new byte[8192];
            int n;
            while ((n = fis.read(b)) != -1) bos.write(b, 0, n);
            return bos.toByteArray();
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }
}

