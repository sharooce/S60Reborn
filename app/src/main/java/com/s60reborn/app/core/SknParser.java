package com.s60reborn.app.core;

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

public class SknParser {
	
	public static class SknAssetMapping {
        public int majorUid;
        public int minorUid;
        public String uidKey;       // Format "10005A26:00001000"
        public String elementName;  // Nama dari database SISContents
        public int assetIndex;      // Indeks gambar di .MBM / .MIF
        public int maskIndex;       // Indeks mask (-1 jika tidak ada)
        public int offsetInSkn;     // Posisi byte di .skn jika ingin diganti indeksnya
    }
	

    public static class SknColorItem {
        public String label;
        public int offsetInFile; // Posisi byte warna di .skn agar bisa di-patch langsung
        public int colorArgb;

        public SknColorItem(String label, int offsetInFile, int colorArgb) {
            this.label = label;
            this.offsetInFile = offsetInFile;
            this.colorArgb = colorArgb;
        }
    }

    public static class SknThemeData {
        public String skinName = "Symbian Theme";
        public int idleTextColor = Color.WHITE;
        public int menuTextColor = Color.WHITE;
        public int highlightColor = 0xFF00E676;
        public int softkeyTextColor = Color.WHITE;
        public List<SknColorItem> extractedColors = new ArrayList<>();
    }

    /**
     * Membaca struktur biner .skn Symbian dan mengekstrak tabel warna tema
     */
    public static SknThemeData parseSkn(File sknFile) throws IOException {
        byte[] raw = readAll(sknFile);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        SknThemeData data = new SknThemeData();
        data.skinName = sknFile.getName().replaceAll("(?i)\\.skn$", "");

        // Pindai blok Color Table di dalam .skn (AknsSrv Color Table Chunk: type 0x0003 / 0x0004)
        // Di Symbian .skn, entri warna berformat: [Index (2B) + RGB (4B: R, G, B, 0x00)] atau [UID (8B) + RGB (4B)]
        String[] commonLabels = new String[]{
			"Teks Layar Utama (Idle)",
			"Teks Menu Grid",
			"Teks Softkey Bawah",
			"Warna Sorotan (Highlight)",
			"Teks Header & Jam",
			"Teks Pop-up / Dialog"
        };

        for (int pos = 16; pos <= raw.length - 12; pos += 2) {
            int major = buf.getInt(pos);
            // Major UID standar AknsSrv untuk warna & skin item: 0x10005A26 atau 0x101F84B9
            if (major == 0x10005A26 || major == 0x101F84B9) {
                int minor = buf.getInt(pos + 4);
                // Cek area setelah UID untuk entri RGB (byte ke-3 = 0x00 pada 0x00BBGGRR)
                for (int scan = pos + 8; scan <= Math.min(raw.length - 4, pos + 64); scan += 2) {
                    int r = raw[scan] & 0xFF;
                    int g = raw[scan + 1] & 0xFF;
                    int b = raw[scan + 2] & 0xFF;
                    int pad = raw[scan + 3] & 0xFF;

                    if (pad == 0x00 && !(r == 0 && g == 0 && b == 0) && data.extractedColors.size() < 12) {
                        int argb = Color.rgb(r, g, b);
                        String lbl = (data.extractedColors.size() < commonLabels.length)
							? commonLabels[data.extractedColors.size()]
							: String.format("Skin Color #%d (0x%X)", data.extractedColors.size() + 1, minor);
                        data.extractedColors.add(new SknColorItem(lbl, scan, argb));
                        break;
                    }
                }
            }
        }

        if (!data.extractedColors.isEmpty()) {
            data.idleTextColor = data.extractedColors.get(0).colorArgb;
            if (data.extractedColors.size() > 1) data.menuTextColor = data.extractedColors.get(1).colorArgb;
            if (data.extractedColors.size() > 2) data.softkeyTextColor = data.extractedColors.get(2).colorArgb;
            if (data.extractedColors.size() > 3) data.highlightColor = data.extractedColors.get(3).colorArgb;
        }
        return data;
    }

    /**
     * Memperbarui byte warna RGB langsung di dalam file .skn tanpa merusak struktur chunk
     */
    public static void patchColorInSkn(File sknFile, int offsetInFile, int newArgb) throws IOException {
        byte[] raw = readAll(sknFile);
        if (offsetInFile >= 0 && offsetInFile + 3 < raw.length) {
            raw[offsetInFile] = (byte) Color.red(newArgb);
            raw[offsetInFile + 1] = (byte) Color.green(newArgb);
            raw[offsetInFile + 2] = (byte) Color.blue(newArgb);
            raw[offsetInFile + 3] = 0x00;
            try (FileOutputStream fos = new FileOutputStream(sknFile)) {
                fos.write(raw);
            }
        }
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
     * Memindai seluruh pemetaan ID Ikon & Elemen Tema di dalam file .SKN
     */
    public static java.util.List<SknAssetMapping> extractAssetMappings(File sknFile, java.util.Map<String, String> db) throws IOException {
        byte[] raw = readAll(sknFile);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        java.util.List<SknAssetMapping> mappings = new java.util.ArrayList<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();

        for (int pos = 16; pos <= raw.length - 20; pos += 2) {
            int major = buf.getInt(pos);
            // Major UID standar AknsSrv: Skin (0x10005A26), AppIcon (0x101F84B9), Custom (0x10207114)
            if (major == 0x10005A26 || major == 0x101F84B9 || major == 0x10207114) {
                int minor = buf.getInt(pos + 4);
                String uidKey = String.format("%08X:%08X", major, minor);

                if (minor != 0 && !seenKeys.contains(uidKey)) {
                    // Cari indeks bitmap/ikon valid (0..5000) setelah header chunk item
                    int bmpIdx = -1;
                    int maskIdx = -1;
                    for (int scan = pos + 12; scan <= Math.min(raw.length - 8, pos + 32); scan += 2) {
                        int candidateIdx = buf.getInt(scan);
                        int candidateMask = buf.getInt(scan + 4);
                        if (candidateIdx >= 0 && candidateIdx < 5000 &&
                            (candidateMask == -1 || (candidateMask >= 0 && candidateMask < 5000))) {
                            bmpIdx = candidateIdx;
                            maskIdx = candidateMask;
                            break;
                        }
                    }

                    if (bmpIdx >= 0) {
                        seenKeys.add(uidKey);
                        SknAssetMapping m = new SknAssetMapping();
                        m.majorUid = major;
                        m.minorUid = minor;
                        m.uidKey = uidKey;
                        m.elementName = db.containsKey(uidKey) ? db.get(uidKey) : ("Item 0x" + Integer.toHexString(minor).toUpperCase());
                        m.assetIndex = bmpIdx;
                        m.maskIndex = maskIdx;
                        m.offsetInSkn = pos;
                        mappings.add(m);
                    }
                }
            }
        }
        return mappings;
    }
	

    /**
     * Memuat database biner SISContents langsung dari folder assets/ APK (dengan caching memori)
     */
	public static java.util.Map<String, String> loadSisContentsDatabase(File customDbFile) throws Exception {
        java.util.Map<String, String> db = new java.util.HashMap<>();

        // Database inti bawaan S60v3 / S60v5 / Belle
        db.put("10005A26:00001000", "Idle Wallpaper");
        db.put("10005A26:00001001", "Menu Background");
        db.put("10005A26:00001012", "Grid Highlight");
        db.put("10005A26:00001013", "List Highlight");
        db.put("101F84B9:10005901", "Calendar");
        db.put("101F84B9:10005902", "Contacts");
        db.put("101F84B9:10005903", "Log");
        db.put("101F84B9:102072C4", "Applications");
        db.put("101F84B9:100058C5", "Messaging");
        db.put("101F84B9:10008D39", "Internet");
        db.put("101F84B9:101F8599", "Media");
        db.put("101F84B9:2001A93D", "Store");
        db.put("101F84B9:101F4CD5", "Office");
        db.put("101F84B9:100058CD", "Email");
        db.put("101F84B9:100058EC", "Ctrl. panel");
        db.put("101F84B9:10005234", "Help");

        // Baca file database tambahan dari SISContents jika ada
        if (customDbFile != null && customDbFile.exists()) {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(customDbFile));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim().toUpperCase().replace("0X", "");
                    String val = line.substring(eq + 1).trim();
                    db.put(key, val);
                }
            }
            br.close();
        }
        return db;
    }

    public static java.util.Map<String, String> loadBinarySisContentsDb(File binDbFile) throws Exception {
        java.util.Map<String, String> db = loadSisContentsDatabase(null);
        if (binDbFile == null || !binDbFile.exists() || binDbFile.length() < 16) {
            return db;
        }
        byte[] raw = readAll(binDbFile);
        parseBinaryDbBytes(raw, db);
        return db;
    }
	

    /**
     * Memindai byte array database biner SISContents (pasangan MajorUID + MinorUID -> Nama Elemen)
     */
    
	private static boolean isPrintableAscii(byte[] data, int off, int len) {
        for (int i = 0; i < len; i++) {
            int c = data[off + i] & 0xFF;
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    private static boolean isPrintableUtf16(byte[] data, int off, int charCount) {
        for (int i = 0; i < charCount; i++) {
            int lo = data[off + i * 2] & 0xFF;
            int hi = data[off + i * 2 + 1] & 0xFF;
            if (hi != 0x00 || lo < 32 || lo > 126) return false;
        }
        return true;
    }
	
	private static java.util.Map<String, String> cachedSkinDb = null;

    public static java.util.Map<String, String> loadBinarySisContentsDbFromAssets(
		android.content.Context context, String assetName) throws Exception {
        if (cachedSkinDb != null) {
            return cachedSkinDb;
        }

        java.util.Map<String, String> db = loadSisContentsDatabase(null);
        try {
            java.io.InputStream is = context.getAssets().open(assetName);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            parseBinaryDbBytes(bos.toByteArray(), db);
        } catch (Exception e) {
            e.printStackTrace();
        }

        cachedSkinDb = db;
        return db;
    }

	public static void parseBinaryDbBytes(byte[] raw, java.util.Map<String, String> db) throws Exception {
        if (raw == null || raw.length < 32) return;
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        // Scan seluruh file mencari pola [Short ByteLength] + [UTF-16LE String]
        for (int pos = 16; pos <= raw.length - 10; pos += 2) {
            int byteLen = buf.getShort(pos) & 0xFFFF;

            // Panjang string dalam byte biasanya genap, antara 4 hingga 80 byte, dan kelipatan 2
            if (byteLen >= 4 && byteLen <= 80 && byteLen % 2 == 0) {
                int textStart = pos + 2;
                int charCount = byteLen / 2;

                if (textStart + byteLen <= raw.length && isPrintableUtf16(raw, textStart, charCount)) {
                    String name = new String(raw, textStart, byteLen, "UTF-16LE").trim();

                    // Validasi string harus berupa teks nama yang valid (diawali huruf)
                    if (!name.isEmpty() && Character.isLetter(name.charAt(0))) {

                        // Cari Major & Minor UID terdekat di sekitar posisi ini (mundur hingga 32 byte ke belakang)
                        int foundMajor = 0;
                        int foundMinor = 0;

                        for (int searchPos = Math.max(0, pos - 48); searchPos <= pos - 4; searchPos += 2) {
                            int val = buf.getInt(searchPos);
                            // Cek apakah ini Major UID Symbian (0x10xxxxxx)
                            if ((val & 0xF0000000) == 0x10000000 || (val & 0xF0000000) == 0x20000000) {
                                foundMajor = val;
                                foundMinor = buf.getInt(searchPos + 4);
                                break;
                            }
                        }

                        if (foundMajor != 0 && foundMinor != 0 && foundMinor != -1) {
                            String key = String.format("%08X:%08X", foundMajor, foundMinor);
                            db.put(key, name);
                        }
                    }
                }
            }
        }
    }
	    /**
     * Menentukan path tujuan ekstraksi di dalam subfolder varian_X
     * tanpa mengubah nama file aslinya.
     */
    public static File resolveVariantOutputFile(File projectRootDir, int branchIndex, String symbianTargetPath) {
        // Folder varian: namatema/varian_1, namatema/varian_2, dst.
        File variantDir = new File(projectRootDir, "varian_" + branchIndex);

        // Bersihkan prefix drive Symbian ("!:\" atau "c:\") menjadi path relatif standar
        String cleanPath = symbianTargetPath.replace("\\", "/");
        if (cleanPath.length() >= 2 && cleanPath.charAt(1) == ':') {
            cleanPath = cleanPath.substring(2);
        }
        while (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }

        File outFile = new File(variantDir, cleanPath);

        // Pengaman tambahan: jika dalam 1 blok yang sama masih terjadi bentrok jalur persis sama,
        // buat folder varian berikutnya secara otomatis
        int nextBranch = branchIndex + 1;
        while (outFile.exists()) {
            variantDir = new File(projectRootDir, "varian_" + nextBranch++);
            outFile = new File(variantDir, cleanPath);
        }

        if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
            outFile.getParentFile().mkdirs();
        }
        return outFile;
    }

	
	
}

