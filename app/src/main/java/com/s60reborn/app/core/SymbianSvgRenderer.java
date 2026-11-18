package com.s60reborn.app.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.caverock.androidsvg.SVG;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.InflaterInputStream;

public class SymbianSvgRenderer {

    public static Bitmap renderToBitmap(File file, int targetSize) {
        if (file == null || !file.exists()) return null;
        try {
            return renderBytesToBitmap(readAll(file), targetSize);
        } catch (Exception ignored) {}
        return null;
    }

    public static Bitmap renderBytesToBitmap(byte[] inputRaw, int targetSize) {
        if (inputRaw == null || inputRaw.length < 4) return null;
        try {
            byte[] raw = unwrapMifAndZlib(inputRaw);
            if (raw == null || raw.length < 4) return null;

            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;

            // 1. Raster PNG / JPG
            if (b0 == 0x89 && b1 == 0x50) {
                return BitmapFactory.decodeByteArray(raw, 0, raw.length);
            }

            // 2. SVGB Biner Symbian -> Konversi ke SVG XML standar lalu render via AndroidSVG
            if ((b0 == 0xCC || b0 == 0xCD || b0 == 0xCE) && b1 == 0x56) {
                String svgXml = convertSvgbBytesToXmlString(raw);
                if (svgXml != null && !svgXml.isEmpty()) {
                    return renderXmlWithAndroidSvg(svgXml, targetSize);
                }
                return createFallbackBadge("SVGB\n" + raw.length + "B", targetSize, 0xFF4FC3F7);
            }

            // 3. SVG XML Teks langsung -> Render via AndroidSVG
            String head = new String(raw, 0, Math.min(raw.length, 256), StandardCharsets.UTF_8);
            if (head.contains("<svg") || head.contains("<?xml")) {
                return renderXmlWithAndroidSvg(new String(raw, StandardCharsets.UTF_8), targetSize);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Render string SVG XML menggunakan library AndroidSVG (mendukung penuh gradient, transform, primitive, dll)
     */
	private static Bitmap renderXmlWithAndroidSvg(String svgXml, int size) {
        try {
            SVG svg = SVG.getFromString(svgXml);
            RectF vb = svg.getDocumentViewBox();
            if (vb == null || vb.width() <= 0 || vb.height() <= 0) {
                svg.setDocumentViewBox(0f, 0f, 100f, 100f);
            }
            svg.setDocumentWidth(size);
            svg.setDocumentHeight(size);

            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            svg.renderToCanvas(canvas);
            return bmp;
        } catch (Exception e) {
            return createFallbackBadge("SVG ERR", size, 0xFFE57373);
        }
    }
	

    /**
     * Konversi universal SVGB v2 (0xCC/0xCD) & SVGB v3 (0xCE) -> Teks SVG XML standar
     * mempertahankan urutan layer asli dan seluruh warna/gradient dari file.
     */
    public static String convertSvgbBytesToXmlString(byte[] inputRaw) {
        byte[] raw = unwrapMifAndZlib(inputRaw);
        if (raw == null || raw.length < 16) return null;

        int magic0 = raw[0] & 0xFF;
        if (magic0 == 0xCD || magic0 == 0xCC) {
            return decodeSvgbV2ToXml(raw);
        }
        return decodeSvgbV3ToXml(raw);
    }

    private static byte[] unwrapMifAndZlib(byte[] raw) {
        if (raw == null || raw.length < 4) return raw;
        byte[] data = raw;

        if (data.length >= 32
			&& data[0] == 0x43 && data[1] == 0x23
			&& data[2] == 0x23 && data[3] == 0x34) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int dataOff = bb.getInt(8);
            int dataLen = bb.getInt(12);
            if (dataOff >= 16 && dataOff < data.length) {
                int end = (dataLen > 0 && dataOff + dataLen <= data.length) ? (dataOff + dataLen) : data.length;
                byte[] sliced = new byte[end - dataOff];
                System.arraycopy(data, dataOff, sliced, 0, sliced.length);
                data = sliced;
            }
        }

        if (data.length > 6 && (data[0] & 0xFF) == 0x78) {
            int b1 = data[1] & 0xFF;
            if (b1 == 0x01 || b1 == 0x9C || b1 == 0xDA) {
                try{
					InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data));
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = iis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    return bos.toByteArray();
                } catch (Exception ignored) {}
            }
        }
		
        return data;
    }

    // =========================================================================
    // DECODER SVGB v3 (0xCE 0x56 0xFA 0x03) -> SVG XML
    // =========================================================================
    private static String decodeSvgbV3ToXml(byte[] raw) {
        if (raw.length < 64) return null;
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        // 1. Ekstrak semua definisi gradient (<linearGradient> / <radialGradient> + <stop>)
        List<List<String>> gradients = new ArrayList<>();
        List<String> currentStops = new ArrayList<>();

        for (int i = 54; i <= raw.length - 8; i++) {
            int b = raw[i] & 0xFF;
            if (b == 0x5C && (raw[i + 1] & 0xFF) == 0x00) {
                int len = buf.getShort(i + 2) & 0xFFFF;
                if (len >= 4 && len <= 48 && i + 4 + len <= raw.length) {
                    String tag = new String(raw, i + 4, len, StandardCharsets.UTF_16LE);
                    if (tag.startsWith("linearGradient") || tag.startsWith("radialGradient")) {
                        if (!currentStops.isEmpty()) {
                            gradients.add(new ArrayList<>(currentStops));
                            currentStops.clear();
                        }
                    }
                }
            }
            if ((b == 0x0F || b == 0x25 || b == 0x26)
				&& (raw[i + 1] & 0xFF) == 0x3D
				&& (raw[i + 2] & 0xFF) == 0x03
				&& (raw[i + 3] & 0xFF) == 0x00) {
                int r = raw[i + 4] & 0xFF;
                int g = raw[i + 5] & 0xFF;
                int bl = raw[i + 6] & 0xFF;
                if (!(r <= 0x10 && g == 0 && bl == 0)) {
                    currentStops.add(String.format("#%02X%02X%02X", r, g, bl));
                }
            }
        }
        if (!currentStops.isEmpty()) {
            gradients.add(currentStops);
        }

        // 2. Ekstrak semua kurva <path> sesuai urutan kemunculan asli di file
        List<String> pathElements = new ArrayList<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        int pos = 54;
        int gradIdx = 0;

        while (pos <= raw.length - 8) {
            if ((raw[pos] & 0xFF) == 0x4F && (raw[pos + 1] & 0xFF) == 0x00) {
                int segCount = buf.getShort(pos + 2) & 0xFFFF;
                int ccOff = pos + 4 + segCount;

                if (segCount >= 1 && segCount <= 4000 && ccOff + 2 <= raw.length) {
                    boolean valid = ((raw[pos + 4] & 0xFF) == 0);
                    int expected = 0;
                    if (valid) {
                        for (int i = 0; i < segCount; i++) {
                            int op = raw[pos + 4 + i] & 0xFF;
                            if (op == 0 || op == 1 || op == 6) expected += 2;
                            else if (op == 2 || op == 5) expected += 4;
                            else if (op == 3) expected += 6;
                            else if (op != 4) { valid = false; break; }
                        }
                    }

                    if (valid) {
                        int coordCount = buf.getShort(ccOff) & 0xFFFF;
                        int cStart = ccOff + 2;
                        int cEnd = cStart + (coordCount * 4);

                        if (coordCount == expected && cEnd <= raw.length) {
                            String fillAttr = "#CFD8DC";

                            if (pos >= 7 && (raw[pos - 1] & 0xFF) == 0x00
								&& raw[pos - 7] == 0 && raw[pos - 6] == 0 && raw[pos - 5] == 0) {
                                int r = raw[pos - 4] & 0xFF;
                                int g = raw[pos - 3] & 0xFF;
                                int b = raw[pos - 2] & 0xFF;
                                fillAttr = String.format("#%02X%02X%02X", r, g, b);
                            } else if (!gradients.isEmpty()) {
                                int useGrad = gradIdx % gradients.size();
                                fillAttr = "url(#g" + useGrad + ")";
                                gradIdx++;
                            }

                            for (int back = Math.max(54, pos - 24); back <= pos - 4; back++) {
                                if ((raw[back] & 0xFF) == 0x0F && (raw[back + 1] & 0xFF) == 0x3D
									&& raw[back + 2] == 0 && raw[back + 3] == 0) {
                                    fillAttr = "none";
                                    break;
                                }
                            }

                            float[] coords = new float[coordCount];
                            for (int i = 0; i < coordCount; i++) {
                                coords[i] = buf.getInt(cStart + (i * 4)) / 65536.0f;
                            }

                            StringBuilder d = new StringBuilder();
                            int ci = 0;
                            for (int i = 0; i < segCount; i++) {
                                int op = raw[pos + 4 + i] & 0xFF;
                                if (op == 0) {
                                    float x = coords[ci], y = coords[ci + 1]; ci += 2;
                                    d.append(String.format(Locale.US, "M%.3f %.3f ", x, y));
                                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                                } else if (op == 1) {
                                    float x = coords[ci], y = coords[ci + 1]; ci += 2;
                                    d.append(String.format(Locale.US, "L%.3f %.3f ", x, y));
                                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                                } else if (op == 2) {
                                    float x1 = coords[ci], y1 = coords[ci + 1], x = coords[ci + 2], y = coords[ci + 3]; ci += 4;
                                    d.append(String.format(Locale.US, "Q%.3f %.3f %.3f %.3f ", x1, y1, x, y));
                                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                                } else if (op == 3) {
                                    float x1 = coords[ci], y1 = coords[ci + 1], x2 = coords[ci + 2], y2 = coords[ci + 3], x = coords[ci + 4], y = coords[ci + 5]; ci += 6;
                                    d.append(String.format(Locale.US, "C%.3f %.3f %.3f %.3f %.3f %.3f ", x1, y1, x2, y2, x, y));
                                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                                } else if (op == 4) {
                                    d.append("Z ");
                                }
                            }

                            if (d.length() > 0) {
                                pathElements.add(String.format(Locale.US,
															   "  <path d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
															   d.toString().trim(), fillAttr));
                            }
                            pos = cEnd;
                            continue;
                        }
                    }
                }
            }
            pos++;
        }

        if (pathElements.isEmpty() || maxX <= minX || maxY <= minY) return null;

        float w = Math.max(1f, maxX - minX);
        float h = Math.max(1f, maxY - minY);
        float pad = Math.max(w, h) * 0.04f;

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append(String.format(Locale.US,
								 "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"%.2f %.2f %.2f %.2f\">\n",
								 minX - pad, minY - pad, w + pad * 2f, h + pad * 2f));

        if (!gradients.isEmpty()) {
            xml.append("  <defs>\n");
            for (int g = 0; g < gradients.size(); g++) {
                List<String> stops = gradients.get(g);
                xml.append(String.format(Locale.US, "    <linearGradient id=\"g%d\" x1=\"0%%\" y1=\"0%%\" x2=\"0%%\" y2=\"100%%\">\n", g));
                for (int s = 0; s < stops.size(); s++) {
                    float pct = stops.size() == 1 ? 0f : (s * 100f) / (stops.size() - 1);
                    xml.append(String.format(Locale.US, "      <stop offset=\"%.1f%%\" stop-color=\"%s\"/>\n", pct, stops.get(s)));
                }
                xml.append("    </linearGradient>\n");
            }
            xml.append("  </defs>\n");
        }

        for (String p : pathElements) {
            xml.append(p).append("\n");
        }
        xml.append("</svg>\n");
        return xml.toString();
    }

    // =========================================================================
    // DECODER SVGB v2 (0xCD / 0xCC 0x56 0xFA 0x03) -> SVG XML
    // =========================================================================
    private static String decodeSvgbV2ToXml(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        Map<Integer, String> colorMap = new LinkedHashMap<>();
        for (int i = 0; i <= raw.length - 6; i++) {
            if ((raw[i] & 0xFF) == 0x2A && (raw[i + 1] & 0xFF) == 0x51) {
                int r = raw[i + 2] & 0xFF;
                int g = raw[i + 3] & 0xFF;
                int b = raw[i + 4] & 0xFF;
                colorMap.put(i, String.format("#%02X%02X%02X", r, g, b));
            }
        }

        List<String> pathElements = new ArrayList<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        int pos = 16;
        while (pos <= raw.length - 12) {
            int b = raw[pos] & 0xFF;
            if (b == 0x4F || b == 0x4E) {
                int endPos = -1;
                int limit = Math.min(raw.length, pos + 2048);
                for (int scan = pos + 6; scan < limit; scan++) {
                    if ((raw[scan] & 0xFF) == 0xFE) {
                        endPos = scan;
                        break;
                    }
                }

                if (endPos != -1) {
                    float[] bestCoords = null;
                    for (int numFloats = 4; numFloats < 512; numFloats += 2) {
                        int fStart = endPos - (numFloats * 4);
                        if (fStart <= pos + 1) break;
                        int hint = raw[fStart - 1] & 0xFF;
                        if (hint == (numFloats / 2) || hint == numFloats) {
                            float[] floats = new float[numFloats];
                            boolean ok = true;
                            for (int k = 0; k < numFloats; k++) {
                                float v = Float.intBitsToFloat(buf.getInt(fStart + (k * 4)));
                                if (Float.isNaN(v) || Float.isInfinite(v) || v < -4096f || v > 4096f) {
                                    ok = false; break;
                                }
                                floats[k] = v;
                            }
                            if (ok) {
                                bestCoords = floats;
                                break;
                            }
                        }
                    }

                    if (bestCoords != null && bestCoords.length >= 4) {
                        String fillHex = "#CFD8DC";
                        for (Map.Entry<Integer, String> entry : colorMap.entrySet()) {
                            if (entry.getKey() < pos) fillHex = entry.getValue();
                            else break;
                        }

                        StringBuilder d = new StringBuilder();
                        for (int i = 0; i < bestCoords.length - 1; i += 2) {
                            float x = bestCoords[i], y = bestCoords[i + 1];
                            d.append(String.format(Locale.US, "%s%.3f %.3f ", (i == 0 ? "M" : "L"), x, y));
                            if (x < minX) minX = x; if (x > maxX) maxX = x;
                            if (y < minY) minY = y; if (y > maxY) maxY = y;
                        }
                        if (b == 0x4F) d.append("Z");

                        pathElements.add(String.format(Locale.US,
													   "  <path d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
													   d.toString().trim(), fillHex));
                        pos = endPos + 1;
                        continue;
                    }
                }
            }
            pos++;
        }

        if (pathElements.isEmpty() || maxX <= minX || maxY <= minY) return null;

        float w = Math.max(1f, maxX - minX);
        float h = Math.max(1f, maxY - minY);
        float pad = Math.max(w, h) * 0.04f;

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append(String.format(Locale.US,
								 "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"%.2f %.2f %.2f %.2f\">\n",
								 minX - pad, minY - pad, w + pad * 2f, h + pad * 2f));
        for (String p : pathElements) {
            xml.append(p).append("\n");
        }
        xml.append("</svg>\n");
        return xml.toString();
    }

    private static Bitmap createFallbackBadge(String label, int size, int accentColor) {
        int safeSize = Math.max(size, 48);
        Bitmap bmp = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1E293B);
        RectF rect = new RectF(4f, 4f, safeSize - 4f, safeSize - 4f);
        canvas.drawRoundRect(rect, 12f, 12f, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(accentColor);
        canvas.drawRoundRect(rect, 12f, 12f, p);

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

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = fis.read(b)) != -1) bos.write(b, 0, n);
            return bos.toByteArray();
        }
    }
}

