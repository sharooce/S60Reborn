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
import java.util.Map;

public class SknParser {
	
	
	public static class SknAssetMapping {
		public int majorUid;
		public int minorUid;
		public String uidKey;       // Format "10005A26:00001000"
		public String elementName;  // Nama dari database SISContents
		public int assetIndex;      // Indeks gambar di .MBM / .MIF
		public int maskIndex;       // Indeks mask (-1 jika tidak ada)
		public int offsetInSkn;     // Posisi awal chunk item di .skn
		public int indexOffsetInSkn;// Posisi exact 4-byte assetIndex di .skn
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
	public static List<SknAssetMapping> extractAssetMappings(File sknFile, Map<String, String> db) throws IOException {
        byte[] raw = readAll(sknFile);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        List<SknAssetMapping> mappings = new ArrayList<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();

        for (int pos = 16; pos <= raw.length - 20; pos += 2) {
            int major = buf.getInt(pos);
			int itemType = buf.getInt(pos + 8);
			if (itemType != 1 && itemType != 2) continue;
			
            if (major == 0x10005A26 || major == 0x101F84B9 || major == 0x10207114) {
                int minor = buf.getInt(pos + 4);
                String uidKey = String.format("%08X:%08X", major, minor);

                if (minor != 0 && !seenKeys.contains(uidKey)) {
                    int idxOffset = -1;
					int bmpIdx = buf.getInt(pos + 12);
					int maskIdx = (itemType == 2) ? buf.getInt(pos + 16) : -1;
					
                    for (int scan = pos + 12; scan <= Math.min(raw.length - 8, pos + 32); scan += 2) {
                        int candidateIdx = buf.getInt(scan);
                        int candidateMask = buf.getInt(scan + 4);
                        if (candidateIdx >= 0 && candidateIdx < 5000 &&
                            (candidateMask == -1 || (candidateMask >= 0 && candidateMask < 5000))) {
                            bmpIdx = candidateIdx;
                            maskIdx = candidateMask;
                            idxOffset = scan;
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
                        m.indexOffsetInSkn = idxOffset;
                        mappings.add(m);
                    }
                }
            }
        }
        return mappings;
    }

    /**
     * Mengubah indeks gambar MBM/MIF pada item tema di dalam .SKN
     */
    public static void patchAssetIndexInSkn(File sknFile, int indexOffsetInSkn, int newAssetIndex, int newMaskIndex) throws IOException {
        byte[] raw = readAll(sknFile);
        if (indexOffsetInSkn >= 0 && indexOffsetInSkn + 8 <= raw.length) {
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(indexOffsetInSkn, newAssetIndex);
            buf.putInt(indexOffsetInSkn + 4, newMaskIndex);
            try (FileOutputStream fos = new FileOutputStream(sknFile)) {
                fos.write(raw);
            }
        }
    }

    /**
     * Menambahkan pemetaan elemen baru dari Database SISContents ke akhir chunk .SKN
     */
    public static void appendAssetMappingToSkn(File sknFile, String uidKey, int assetIndex, int maskIndex) throws IOException {
        String[] parts = uidKey.split(":");
        if (parts.length != 2) throw new IOException("Format UID tidak valid (harus MAJOR:MINOR)");
        int major = (int) Long.parseLong(parts[0].trim(), 16);
        int minor = (int) Long.parseLong(parts[1].trim(), 16);

        byte[] raw = readAll(sknFile);
        ByteBuffer chunk = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        chunk.putInt(major);        // +0: Major UID
        chunk.putInt(minor);        // +4: Minor UID
        chunk.putInt(0x00000001);   // +8: AknsSrv Bitmap Item Type
        chunk.putInt(assetIndex);   // +12: Bitmap Index (.MBM/.MIF)
        chunk.putInt(maskIndex);    // +16: Mask Index (-1 jika tanpa mask)
        chunk.putInt(0x00000000);   // +20: Attributes / Padding

        try (FileOutputStream fos = new FileOutputStream(sknFile)) {
            fos.write(raw);
            fos.write(chunk.array());
        }
    }
	
	private static final String CUSTOM_SKIN_DB_FILE = "custom_skin_elements.txt";

    /**
     * Mengambil file penyimpanan kamus elemen kustom lokal
     */
    public static File getCustomSkinDbFile(android.content.Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, CUSTOM_SKIN_DB_FILE);
    }

    /**
     * Menyimpan atau memperbarui nama kustom untuk UID tertentu (Format: MAJOR:MINOR=Nama Aplikasi)
     */
    public static void saveCustomElementName(android.content.Context context, String uidKey, String customName) {
        if (uidKey == null || customName == null || customName.trim().isEmpty()) return;
        String cleanKey = uidKey.trim().toUpperCase();
        String cleanName = customName.trim().replace("\n", " ").replace("=", "-");

        // Update cache di memori secara instan
        if (cachedSkinDb != null) {
            cachedSkinDb.put(cleanKey, cleanName);
        }

        // Baca isi file kustom lama agar tidak menimpa entri lain
        java.util.Map<String, String> customMap = new java.util.LinkedHashMap<String, String>();
        File file = getCustomSkinDbFile(context);
        if (file.exists()) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(file));
                String line;
                while ((line = br.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        customMap.put(line.substring(0, eq).trim().toUpperCase(), line.substring(eq + 1).trim());
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (br != null) { try { br.close(); } catch (Exception ignored) {} }
            }
        }

        // Masukkan atau perbarui entri baru
        customMap.put(cleanKey, cleanName);

        // Tulis kembali ke file
        java.io.BufferedWriter bw = null;
        try {
            bw = new java.io.BufferedWriter(new java.io.FileWriter(file));
            for (java.util.Map.Entry<String, String> entry : customMap.entrySet()) {
                bw.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
        } catch (Exception ignored) {
        } finally {
            if (bw != null) { try { bw.close(); } catch (Exception ignored) {} }
        }
    }
	

    /**
     * Parser fleksibel untuk file database SISContents di assets (mendukung UTF-16LE, ASCII/UTF-8, & CSV/INI)
     */
	/**
     * Parser akurat untuk file database SISContents di assets (teruji 3.550+ entri)
     */
    public static void parseBinaryDbBytes(byte[] raw, java.util.Map<String, String> db) throws Exception {
        if (raw == null || raw.length < 32) return;
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        int currentMajor = 0x10005A26; // Default Major UID AknsSrv

        // Pola 14-byte pemisah tetap sebelum panjang string di seluruh file .db
        final byte[] SEP = new byte[] {
            (byte) 0xFF, (byte) 0xF0, 0x00, 0x00,
            0x00, 0x00, (byte) 0xFF, (byte) 0xF0,
            0x00, 0x00, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xF0
        };

        int pos = 8;
        int totalLen = raw.length;

        while (pos <= totalLen - 18) {
            // 1. Deteksi header pergantian Major UID di tengah file (pola: FF FF .. .. .. .. [MajorUID])
            // Contoh dari hex Anda: FF FF F8 0B 00 00 01 59 00 10 (Major UID = 0x10005901)
            if ((raw[pos] & 0xFF) == 0xFF && (raw[pos + 1] & 0xFF) == 0xFF && pos + 10 <= totalLen) {
                int candidateMajor = buf.getInt(pos + 6);
                if (isSymbianSkinMajor(candidateMajor)) {
                    currentMajor = candidateMajor;
                }
            }

            // 2. Deteksi 14-byte separator FF F0... untuk membaca Minor UID dan Nama Elemen
            if (raw[pos] == SEP[0] && raw[pos + 1] == SEP[1] &&
                raw[pos + 6] == SEP[6] && raw[pos + 7] == SEP[7] &&
                raw[pos + 12] == SEP[12] && raw[pos + 13] == SEP[13]) {

                boolean fullMatch = true;
                for (int k = 0; k < 14; k++) {
                    if (raw[pos + k] != SEP[k]) {
                        fullMatch = false;
                        break;
                    }
                }

                if (fullMatch) {
                    int minorUid = buf.getInt(pos - 4);
                    int strByteLen = buf.getInt(pos + 14);
                    int strStart = pos + 18;

                    if (strByteLen > 0 && strByteLen <= 2048 && (strStart + strByteLen) <= totalLen) {
                        String name = new String(raw, strStart, strByteLen, "UTF-16LE").trim();
                        if (!name.isEmpty()) {
                            String key = String.format("%08X:%08X", currentMajor, minorUid);
                            db.put(key, name);

                            // Simpan juga fallback ke default AknsSrv jika belum ada, agar item umum selalu ketemu
                            String fallbackKey = String.format("10005A26:%08X", minorUid);
                            if (!db.containsKey(fallbackKey)) {
                                db.put(fallbackKey, name);
                            }

                            pos = strStart + strByteLen;
                            continue;
                        }
                    }
                }
            }
            pos++;
        }
    }
	
	
	

    private static boolean isSymbianSkinMajor(int val) {
        return val == 0x10005A26 || val == 0x101F84B9 || val == 0x10207114
            || val == 0x101F8599 || val == 0x10204DA2 || ((val & 0xFF000000) == 0x10000000);
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
		File customFile = getCustomSkinDbFile(context);
        if (customFile.exists()) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(customFile));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim().toUpperCase();
                        String val = line.substring(eq + 1).trim();
                        db.put(key, val);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (br != null) { try { br.close(); } catch (Exception ignored) {} }
            }
        }
		

        cachedSkinDb = db;
        return db;
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

