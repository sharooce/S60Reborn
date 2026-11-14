package com.s60reborn.app.core;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MbmConverter {

    public static final int MBM_UID1 = 0x10000037;
    public static final int MBM_UID2 = 0x10000042;

    /**
     * Memuat seluruh gambar dari file .mbm menjadi List<Bitmap> untuk ditampilkan di UI
     */
    public static List<Bitmap> loadBitmapsFromMbm(File mbmFile) throws IOException {
        byte[] data = readAll(mbmFile);
        if (data.length < 24) throw new IOException("File MBM terlalu kecil.");

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt(0) != MBM_UID1 || buf.getInt(4) != MBM_UID2) {
            throw new IOException("Header bukan file MBM Symbian yang valid.");
        }

        int trailerOffset = buf.getInt(16);
        if (trailerOffset <= 0 || trailerOffset + 4 > data.length) {
            throw new IOException("Trailer offset MBM tidak valid.");
        }

        int numBitmaps = buf.getInt(trailerOffset);
        List<Bitmap> bitmaps = new ArrayList<>();

        for (int i = 0; i < numBitmaps; i++) {
            int ptrPos = trailerOffset + 4 + (i * 4);
            if (ptrPos + 4 > data.length) break;
            int bmpOffset = buf.getInt(ptrPos);
            if (bmpOffset <= 0 || bmpOffset + 40 > data.length) {
                bitmaps.add(null);
                continue;
            }
            bitmaps.add(decodeSymbianBitmap(data, bmpOffset));
        }
        return bitmaps;
    }

    /**
     * Mengganti satu gambar pada indeks tertentu di dalam .mbm dengan Bitmap baru dari Android,
     * otomatis menyesuaikan resolusi asli agar pas dengan layout tema Symbian, lalu menyimpan ulang .mbm!
     */
    public static void replaceBitmapInMbm(File mbmFile, int targetIndex, Bitmap replacementBmp) throws IOException {
        List<Bitmap> allBitmaps = loadBitmapsFromMbm(mbmFile);
        if (targetIndex < 0 || targetIndex >= allBitmaps.size()) {
            throw new IOException("Indeks gambar tidak valid: " + targetIndex);
        }

        Bitmap oldBmp = allBitmaps.get(targetIndex);
        Bitmap scaledNew = replacementBmp;
        if (oldBmp != null && (oldBmp.getWidth() != replacementBmp.getWidth() || oldBmp.getHeight() != replacementBmp.getHeight())) {
            scaledNew = Bitmap.createScaledBitmap(replacementBmp, oldBmp.getWidth(), oldBmp.getHeight(), true);
        }
        allBitmaps.set(targetIndex, scaledNew);

        packBitmapsToMbm(allBitmaps, mbmFile);

        // Bersihkan memori bitmap
        for (Bitmap b : allBitmaps) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    /**
     * Mengemas daftar Bitmap Android menjadi file .mbm Symbian (24-bit RGB888 uncompressed)
     */
    public static void packBitmapsToMbm(List<Bitmap> bitmaps, File outMbmFile) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int currentOffset = 20; // Header MBM 20 byte

        for (Bitmap bmp : bitmaps) {
            if (bmp == null) {
                offsets.add(0);
                continue;
            }
            offsets.add(currentOffset);
            byte[] encodedEntry = encodeSymbianBitmap24(bmp);
            payload.write(encodedEntry);
            currentOffset += encodedEntry.length;
        }

        int trailerOffset = currentOffset;

        // Susun Trailer Table
        ByteBuffer trailer = ByteBuffer.allocate(4 + offsets.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putInt(offsets.size());
        for (int off : offsets) {
            trailer.putInt(off);
        }

        // Susun Header 20 Byte
        int uidChecksum = SisxPacker.calculateSymbianUidChecksum(MBM_UID1, MBM_UID2, 0);
        ByteBuffer header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MBM_UID1);
        header.putInt(MBM_UID2);
        header.putInt(0);
        header.putInt(uidChecksum);
        header.putInt(trailerOffset);

        try (FileOutputStream fos = new FileOutputStream(outMbmFile)) {
            fos.write(header.array());
            fos.write(payload.toByteArray());
            fos.write(trailer.array());
        }
    }

    private static byte[] encodeSymbianBitmap24(Bitmap bmp) throws IOException {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int stride = ((w * 3 + 3) / 4) * 4;
        int pixelDataSize = stride * h;
        int headerSize = 40;
        int totalSize = headerSize + pixelDataSize;

        ByteBuffer hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(totalSize);
        hdr.putInt(headerSize);
        hdr.putInt(w);
        hdr.putInt(h);
        hdr.putInt(w * 15);        // Twips width
        hdr.putInt(h * 15);        // Twips height
        hdr.putInt(24);            // 24-bit color
        hdr.putInt(1);             // isColor = 1
        hdr.putInt(0);             // paletteSize = 0
        hdr.putInt(0);             // compression = 0 (Uncompressed)

        byte[] rawPixels = new byte[pixelDataSize];
        int[] argb = new int[w * h];
        bmp.getPixels(argb, 0, w, 0, 0, w, h);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                int idx = y * stride + x * 3;
                rawPixels[idx] = (byte) Color.blue(c);
                rawPixels[idx + 1] = (byte) Color.green(c);
                rawPixels[idx + 2] = (byte) Color.red(c);
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(totalSize);
        bos.write(hdr.array());
        bos.write(rawPixels);
        return bos.toByteArray();
    }

    private static Bitmap decodeSymbianBitmap(byte[] data, int offset) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int totalSize = b.getInt(offset);
        int headerSize = b.getInt(offset + 4);
        int width = b.getInt(offset + 8);
        int height = b.getInt(offset + 12);
        int bitsPerPixel = b.getInt(offset + 24);
        int compression = b.getInt(offset + 36);

        if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return null;

        int pixelStart = offset + headerSize;
        int rawDataLen = totalSize - headerSize;
        if (pixelStart + rawDataLen > data.length || rawDataLen <= 0) return null;

        byte[] rawPixels;
        if (compression == 0) {
            rawPixels = new byte[rawDataLen];
            System.arraycopy(data, pixelStart, rawPixels, 0, rawDataLen);
        } else if (compression == 1 && bitsPerPixel == 16) {
            rawPixels = decodeRle16(data, pixelStart, rawDataLen, width * height * 2);
        } else if (compression == 2 && bitsPerPixel == 24) {
            rawPixels = decodeRle24(data, pixelStart, rawDataLen, width * height * 3);
        } else {
            return null;
        }

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        if (bitsPerPixel == 16) {
            int stride = ((width * 2 + 3) / 4) * 4;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = y * stride + x * 2;
                    if (idx + 1 < rawPixels.length) {
                        int val = (rawPixels[idx] & 0xFF) | ((rawPixels[idx + 1] & 0xFF) << 8);
                        int r = ((val >> 11) & 0x1F) * 255 / 31;
                        int g = ((val >> 5) & 0x3F) * 255 / 63;
                        int bl = (val & 0x1F) * 255 / 31;
                        pixels[y * width + x] = Color.rgb(r, g, bl);
                    }
                }
            }
        } else if (bitsPerPixel == 24) {
            int stride = ((width * 3 + 3) / 4) * 4;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = y * stride + x * 3;
                    if (idx + 2 < rawPixels.length) {
                        int bl = rawPixels[idx] & 0xFF;
                        int g = rawPixels[idx + 1] & 0xFF;
                        int r = rawPixels[idx + 2] & 0xFF;
                        pixels[y * width + x] = Color.rgb(r, g, bl);
                    }
                }
            }
        } else if (bitsPerPixel == 8 || bitsPerPixel == 1) {
            int stride = (bitsPerPixel == 8) ? ((width + 3) / 4) * 4 : (((width + 31) / 32) * 4);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (bitsPerPixel == 8) {
                        int idx = y * stride + x;
                        if (idx < rawPixels.length) {
                            int v = rawPixels[idx] & 0xFF;
                            pixels[y * width + x] = Color.rgb(v, v, v);
                        }
                    } else {
                        int idx = y * stride + (x / 8);
                        if (idx < rawPixels.length) {
                            int bit = (rawPixels[idx] >> (x % 8)) & 1;
                            int v = (bit == 1) ? 255 : 0;
                            pixels[y * width + x] = Color.rgb(v, v, v);
                        }
                    }
                }
            }
        }

        bmp.setPixels(pixels, 0, width, 0, 0, width, height);
        return bmp;
    }

    private static byte[] decodeRle16(byte[] src, int off, int len, int expectedBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expectedBytes);
        int end = off + len;
        while (off < end) {
            int ctrl = src[off++];
            if (ctrl >= 0) {
                int count = ctrl + 1;
                if (off + 1 >= end) break;
                byte b0 = src[off++], b1 = src[off++];
                for (int i = 0; i < count; i++) {
                    out.write(b0);
                    out.write(b1);
                }
            } else {
                int count = -ctrl;
                for (int i = 0; i < count && off + 1 < end; i++) {
                    out.write(src[off++]);
                    out.write(src[off++]);
                }
            }
        }
        return out.toByteArray();
    }

    private static byte[] decodeRle24(byte[] src, int off, int len, int expectedBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expectedBytes);
        int end = off + len;
        while (off < end) {
            int ctrl = src[off++];
            if (ctrl >= 0) {
                int count = ctrl + 1;
                if (off + 2 >= end) break;
                byte b0 = src[off++], b1 = src[off++], b2 = src[off++];
                for (int i = 0; i < count; i++) {
                    out.write(b0);
                    out.write(b1);
                    out.write(b2);
                }
            } else {
                int count = -ctrl;
                for (int i = 0; i < count && off + 2 < end; i++) {
                    out.write(src[off++]);
                    out.write(src[off++]);
                    out.write(src[off++]);
                }
            }
        }
        return out.toByteArray();
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

