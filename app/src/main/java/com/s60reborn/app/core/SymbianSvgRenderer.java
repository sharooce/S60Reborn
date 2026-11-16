package com.s60reborn.app.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import android.graphics.RectF;
import java.util.ArrayList;

public class SymbianSvgRenderer {

    /**
     * Merender file PNG, SVG XML, atau SVGB biner Symbian menjadi Bitmap pratinjau Android
     */
    public static Bitmap renderToBitmap(File file, int targetSize) {
        if (file == null || !file.exists()) return null;
        try {
            byte[] raw = readAll(file);
            if (raw.length < 4) return null;

            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;

            // 1. Jika PNG / JPG asli
            if (b0 == 0x89 && b1 == 0x50) {
                return BitmapFactory.decodeByteArray(raw, 0, raw.length);
            }

            // 2. Jika SVGB Biner Symbian (Magic: 0xCC 0x56, 0xCD 0x56, 0xCE 0x56)
            if ((b0 == 0xCC || b0 == 0xCD || b0 == 0xCE) && b1 == 0x56) {
                return renderBinarySvgb(raw, targetSize);
            }

            // 3. Jika SVG XML Teks
            String head = new String(raw, 0, Math.min(raw.length, 128), "UTF-8");
            if (head.contains("<svg") || head.contains("<?xml")) {
                return renderXmlSvg(new String(raw, "UTF-8"), targetSize);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Merender SVG XML (mendukung embedded base64 PNG maupun tag <path> / <rect> / <circle>)
     */
    private static Bitmap renderXmlSvg(String xml, int size) {
        // Cek apakah SVG membungkus gambar PNG Base64 (hasil konversi manual)
        int b64Idx = xml.indexOf("base64,");
        if (b64Idx != -1) {
            int endQuote = xml.indexOf('"', b64Idx);
            if (endQuote > b64Idx + 7) {
                String b64Data = xml.substring(b64Idx + 7, endQuote);
                byte[] imgBytes = Base64.decode(b64Data, Base64.DEFAULT);
                return BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
            }
        }

        // Render bentuk vektor dasar dari SVG XML
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF4FC3F7);

        // Ambil ukuran viewBox jika ada untuk skala presisi
        float vbW = 100f, vbH = 100f;
        Matcher vbMatcher = Pattern.compile("viewBox\\s*=\\s*\"[0-9.-]+\\s+[0-9.-]+\\s+([0-9.]+)\\s+([0-9.]+)\"").matcher(xml);
        if (vbMatcher.find()) {
            vbW = Float.parseFloat(vbMatcher.group(1));
            vbH = Float.parseFloat(vbMatcher.group(2));
        }
        float scale = Math.min(size / vbW, size / vbH);
        canvas.scale(scale, scale);

        // Render setiap tag <path d="..." fill="#..."/>
        Matcher pathMatcher = Pattern.compile("<path[^>]*d\\s*=\\s*\"([^\"]+)\"[^>]*>").matcher(xml);
        while (pathMatcher.find()) {
            String tag = pathMatcher.group(0);
            String dAttr = pathMatcher.group(1);

            Matcher fillMatcher = Pattern.compile("fill\\s*=\\s*\"(#[0-9a-fA-F]{3,8})\"").matcher(tag);
            if (fillMatcher.find()) {
                try {
                    paint.setColor(Color.parseColor(fillMatcher.group(1)));
                } catch (Exception ignored) {}
            } else {
                paint.setColor(0xFFECEFF1);
            }

            Path path = parseSvgPathData(dAttr);
            canvas.drawPath(path, paint);
        }

        return bmp;
    }

    /**
     * Dekoder struktur biner SVGB Symbian S60 (NVD / Tiny SVG Binary)
     */
	private static Bitmap renderBinarySvgb(byte[] raw, int size) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        // 1. Ekstrak dimensi viewBox (width & height float32) dari 48 byte pertama header SVGB
        float vbW = 0f, vbH = 0f;
        for (int i = 4; i <= Math.min(raw.length - 8, 56); i += 2) {
            float f1 = buf.getFloat(i);
            float f2 = buf.getFloat(i + 4);
            if (isValidCoordFloat(f1) && isValidCoordFloat(f2) && f1 >= 16f && f2 >= 16f) {
                vbW = f1;
                vbH = f2;
                break;
            }
        }

        List<Float> xs = new ArrayList<Float>();
        List<Float> ys = new ArrayList<Float>();
        List<Integer> colors = new ArrayList<Integer>();

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int activeColor = 0xFF4FC3F7;

        int pos = 16;
        while (pos <= raw.length - 8) {
            int b0 = raw[pos] & 0xFF;
            int b1 = raw[pos + 1] & 0xFF;

            // Lewati string UTF-16LE (seperti "tiny" atau "XMLID_2_...") agar tidak terbaca sebagai float
            if (b0 >= 0x20 && b0 <= 0x7E && b1 == 0x00 && pos + 6 < raw.length &&
                (raw[pos + 2] & 0xFF) >= 0x20 && (raw[pos + 3] & 0xFF) == 0x00) {
                pos += 4;
                continue;
            }

            // Tangkap warna RGB (pola: [R] [G] [B] [0x00] diikuti opcode 0x54 / 0xE8 / 0xFE)
            int r = raw[pos] & 0xFF;
            int g = raw[pos + 1] & 0xFF;
            int b = raw[pos + 2] & 0xFF;
            int pad = raw[pos + 3] & 0xFF;
            if (pad == 0x00 && pos + 4 < raw.length && (raw[pos + 4] & 0xFF) == 0x54) {
                if (!(r == 0 && g == 0 && b == 0)) {
                    activeColor = Color.rgb(r, g, b);
                }
            }

            // Tangkap pasangan koordinat IEEE Float32 (baik positif maupun negatif dalam batas wajar)
            float fx = buf.getFloat(pos);
            float fy = buf.getFloat(pos + 4);
            if (isValidCoordFloat(fx) && isValidCoordFloat(fy)) {
                xs.add(fx);
                ys.add(fy);
                colors.add(activeColor);

                if (fx < minX) minX = fx;
                if (fx > maxX) maxX = fx;
                if (fy < minY) minY = fy;
                if (fy > maxY) maxY = fy;

                pos += 8;
                continue;
            }

            pos++;
        }

        // Gunakan viewBox header jika valid, atau bounding box titik yang ditemukan
        if (vbW > 16f && vbH > 16f && minX >= -50f && maxX <= vbW * 1.5f) {
            minX = 0f;
            minY = 0f;
            maxX = vbW;
            maxY = vbH;
        }

        if (xs.size() >= 3 && (maxX - minX) > 1f && (maxY - minY) > 1f) {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(2f);

            float spanW = maxX - minX;
            float spanH = maxY - minY;
            float scale = Math.min((size * 0.84f) / spanW, (size * 0.84f) / spanH);
            float offX = (size - (spanW * scale)) / 2f;
            float offY = (size - (spanH * scale)) / 2f;

            Path path = new Path();
            int currentPathColor = colors.get(0);

            for (int i = 0; i < xs.size(); i++) {
                float px = offX + ((xs.get(i) - minX) * scale);
                float py = offY + ((ys.get(i) - minY) * scale);
                int ptColor = colors.get(i);

                if (i == 0) {
                    path.moveTo(px, py);
                } else {
                    float prevX = offX + ((xs.get(i - 1) - minX) * scale);
                    float prevY = offY + ((ys.get(i - 1) - minY) * scale);
                    // Pisahkan sub-path jika warna berubah atau jarak lompatan jauh
                    if (ptColor != currentPathColor || Math.hypot(px - prevX, py - prevY) > size * 0.6f) {
                        paint.setColor(currentPathColor);
                        canvas.drawPath(path, paint);
                        path.reset();
                        path.moveTo(px, py);
                        currentPathColor = ptColor;
                    } else {
                        path.lineTo(px, py);
                    }
                }
            }
            paint.setColor(currentPathColor);
            canvas.drawPath(path, paint);
            return bmp;
        }

        return createFallbackBadge("SVGB\n" + raw.length + "B", size, 0xFF4FC3F7);
    }
	/**
     * Membuat ikon badge vektor rapi (agar item di grid/popup tidak pernah kosong/transparan)
     */
    private static Bitmap createFallbackBadge(String label, int size, int accentColor) {
        int safeSize = Math.max(size, 48);
        Bitmap bmp = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Background kotak membulat gelap
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1E293B);
        RectF rect = new RectF(4f, 4f, safeSize - 4f, safeSize - 4f);
        canvas.drawRoundRect(rect, 12f, 12f, p);

        // Garis tepi aksen
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(accentColor);
        canvas.drawRoundRect(rect, 12f, 12f, p);

        // Teks label format (SVGB / SVG / KB)
        p.setStyle(Paint.Style.FILL);
        p.setColor(accentColor);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(safeSize * 0.22f);

        String[] lines = label.split("\n");
        float startY = (safeSize / 2f) - ((lines.length - 1) * (safeSize * 0.12f)) + (safeSize * 0.08f);
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], safeSize / 2f, startY + (i * safeSize * 0.26f), p);
        }
        return bmp;
    }
	

    /**
     * Memvalidasi apakah 32-bit float adalah koordinat grafis nyata (bukan byte teks/opcode)
     */
    private static boolean isValidCoordFloat(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        if (v == 0.0f) return true;
        float abs = Math.abs(v);
        return abs >= 0.05f && abs <= 2048.0f;
    }
	

    /**
     * Parser perintah d="..." pada SVG XML (M, L, H, V, C, Z)
     */
    private static Path parseSvgPathData(String d) {
        Path path = new Path();
        Matcher m = Pattern.compile("([MmLlHhVvCcZz])([^MmLlHhVvCcZz]*)").matcher(d);
        float curX = 0, curY = 0;

        while (m.find()) {
            char cmd = m.group(1).charAt(0);
            String[] nums = m.group(2).trim().split("[\\s,]+");
            try {
                if (cmd == 'M' && nums.length >= 2) {
                    curX = Float.parseFloat(nums[0]);
                    curY = Float.parseFloat(nums[1]);
                    path.moveTo(curX, curY);
                } else if (cmd == 'm' && nums.length >= 2) {
                    curX += Float.parseFloat(nums[0]);
                    curY += Float.parseFloat(nums[1]);
                    path.moveTo(curX, curY);
                } else if (cmd == 'L' && nums.length >= 2) {
                    curX = Float.parseFloat(nums[0]);
                    curY = Float.parseFloat(nums[1]);
                    path.lineTo(curX, curY);
                } else if (cmd == 'l' && nums.length >= 2) {
                    curX += Float.parseFloat(nums[0]);
                    curY += Float.parseFloat(nums[1]);
                    path.lineTo(curX, curY);
                } else if (cmd == 'H' && nums.length >= 1) {
                    curX = Float.parseFloat(nums[0]);
                    path.lineTo(curX, curY);
                } else if (cmd == 'V' && nums.length >= 1) {
                    curY = Float.parseFloat(nums[0]);
                    path.lineTo(curX, curY);
                } else if ((cmd == 'Z' || cmd == 'z')) {
                    path.close();
                }
            } catch (Exception ignored) {}
        }
        return path;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = fis.read(b)) != -1) bos.write(b, 0, n);
        fis.close();
        return bos.toByteArray();
    }
}

