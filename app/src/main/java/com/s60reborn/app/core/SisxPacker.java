package com.s60reborn.app.core;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

public class SisxPacker {

    private static final Charset UTF16LE = Charset.forName("UTF-16LE");

    public static SisPackageInfo parsePkgFile(File pkgFile, File baseDir) throws Exception {
        SisPackageInfo info = new SisPackageInfo();
        info.files = new ArrayList<>();
        if (baseDir == null) baseDir = pkgFile.getParentFile();

        Pattern headerPat = Pattern.compile("#\\s*\\{\\s*\"([^\"]+)\"[^}]*\\}\\s*,\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)");
        Pattern vendorPat = Pattern.compile("^:\\s*\"([^\"]+)\"");
        Pattern filePat = Pattern.compile("^\"([^\"]+)\"\\s*-\\s*\"([^\"]+)\"");

        try (BufferedReader br = new BufferedReader(new FileReader(pkgFile))) {
            String line;
            int idx = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;

                Matcher mHdr = headerPat.matcher(line);
                if (mHdr.find()) {
                    info.appName = mHdr.group(1);
                    String uidStr = mHdr.group(2);
                    info.uid3 = uidStr.toLowerCase().startsWith("0x")
						? (int) Long.parseLong(uidStr.substring(2), 16)
						: Integer.parseInt(uidStr);
                    info.majorVersion = Integer.parseInt(mHdr.group(3));
                    info.minorVersion = Integer.parseInt(mHdr.group(4));
                    info.buildVersion = Integer.parseInt(mHdr.group(5));
                    continue;
                }

                Matcher mVen = vendorPat.matcher(line);
                if (mVen.find()) {
                    info.vendorName = mVen.group(1);
                    continue;
                }

                Matcher mFile = filePat.matcher(line);
                if (mFile.find()) {
                    String srcRel = mFile.group(1).replace('\\', '/');
                    String dstSymbian = mFile.group(2);

                    SisFileEntry entry = new SisFileEntry();
                    entry.fileIndex = idx++;
                    entry.localRelPath = srcRel;
                    entry.targetPath = dstSymbian;
                    entry.operation = 1;

                    File srcFile = new File(baseDir, srcRel);
                    if (srcFile.exists() && srcFile.isFile()) {
                        entry.rawData = readAllBytes(srcFile);
                        entry.uncompressedSize = entry.rawData.length;
                        entry.compressedSize = entry.rawData.length;
                    } else {
                        entry.rawData = new byte[0];
                    }
                    info.files.add(entry);
                }
            }
        }
        return info;
    }

    public static SisPackageInfo packFromPkg(File pkgFile, File outputSisx) throws Exception {
        SisPackageInfo info = parsePkgFile(pkgFile, pkgFile.getParentFile());
        packModernSisx(info, info.files, outputSisx);
        return info;
    }

    public static SisPackageInfo packLegacyV2FromPkg(File pkgFile, File outputSis) throws Exception {
        SisPackageInfo info = parsePkgFile(pkgFile, pkgFile.getParentFile());
        packLegacySis(info, info.files, outputSis);
        return info;
    }

    public static void packFromPkg(File pkgFile, File baseDir, File outputSisx) throws Exception {
        SisPackageInfo info = parsePkgFile(pkgFile, baseDir);
        packModernSisx(info, info.files, outputSisx);
    }

    public static void packLegacyV2FromPkg(File pkgFile, File baseDir, File outputSis) throws Exception {
        SisPackageInfo info = parsePkgFile(pkgFile, baseDir);
        packLegacySis(info, info.files, outputSis);
    }

    public static void packPackage(SisPackageInfo info, List<SisFileEntry> files, File outputFile, int versionMode) throws Exception {
        if (versionMode == 1) {
            packLegacySis(info, files, outputFile);
        } else {
            packModernSisx(info, files, outputFile);
        }
    }

    /**
     * Pengemasan Modern SISX (Symbian 9.x / S60v3 - Belle) sesuai spesifikasi CSISController
     */
    public static void packModernSisx(SisPackageInfo info, List<SisFileEntry> files, File outputFile) throws Exception {
        // 1. Bangun blok SISController lengkap (Info, Options, Languages, Prerequisites, Properties, InstallBlock, DataIndex)
        byte[] controllerBytes = buildFullControllerBlock(info, files);
        byte[] compressedController = wrapAsCompressed(controllerBytes);

        // 2. Bangun blok SISData (setiap file dikompresi Zlib di dalam FIELD_FILE_DATA)
        byte[] dataBytes = buildDataBlock(files);

        // 3. Susun isi FIELD_CONTENTS (Tag 12):
        //    FIELD_CONTROLLER (Tag 13) + FIELD_DATA (Tag 30)
        //    (Checksum 34 & 35 dihilangkan karena opsional dan akan dianggap corrupt jika tidak dihitung CRC16-CCITT)
        ByteArrayOutputStream contentsPayload = new ByteArrayOutputStream();
        writeTlvField(contentsPayload, SisField.FIELD_CONTROLLER, compressedController);
        contentsPayload.write(dataBytes);

        byte[] contentsPayloadBytes = contentsPayload.toByteArray();

        ByteArrayOutputStream contentsBlock = new ByteArrayOutputStream();
        writeTlvField(contentsBlock, SisField.FIELD_CONTENTS, contentsPayloadBytes);
        byte[] finalContents = contentsBlock.toByteArray();

        // 4. Header 16 byte SISX
        int uid1 = SisField.SISX_UID1_MAGIC; // 0x10201A7A
        int uid2 = 0x00000000;
        int uid3 = info.uid3;
        int uidChecksum = SisField.calculateSymbianUidChecksum(uid1, uid2, uid3);

        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(uid1);
        header.putInt(uid2);
        header.putInt(uid3);
        header.putInt(uidChecksum);

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(header.array());
            fos.write(finalContents);
        }
    }

    private static byte[] buildFullControllerBlock(SisPackageInfo info, List<SisFileEntry> files) throws Exception {
        ByteArrayOutputStream ctrl = new ByteArrayOutputStream();

        String appName = (info.appName != null && !info.appName.isEmpty()) ? info.appName : "S60App";
        String vendorName = (info.vendorName != null && !info.vendorName.isEmpty()) ? info.vendorName : "S60Reborn";

        // 1. FIELD_INFO (Tag 14)
        ByteArrayOutputStream infoBody = new ByteArrayOutputStream();
        // - UID (Tag 9)
        writeTlvField(infoBody, SisField.FIELD_UID, int32(info.uid3));
        // - VendorUniqueName (Tag 1: FIELD_STRING)
        writeTlvField(infoBody, SisField.FIELD_STRING, vendorName.getBytes(UTF16LE));
        // - ComponentNames (Tag 2: FIELD_ARRAY of FIELD_STRING)
        writeTlvField(infoBody, SisField.FIELD_ARRAY, buildStringArrayPayload(appName));
        // - VendorNames (Tag 2: FIELD_ARRAY of FIELD_STRING)
        writeTlvField(infoBody, SisField.FIELD_ARRAY, buildStringArrayPayload(vendorName));
        // - Version (Tag 4: FIELD_VERSION)
        ByteBuffer ver = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        ver.putInt(info.majorVersion);
        ver.putInt(info.minorVersion);
        ver.putInt(info.buildVersion);
        writeTlvField(infoBody, SisField.FIELD_VERSION, ver.array());
        // - CreationTime (Tag 8: FIELD_DATE_TIME -> FIELD_DATE (6) + FIELD_TIME (7))
        ByteArrayOutputStream dtBody = new ByteArrayOutputStream();
        ByteBuffer dateBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        dateBuf.putShort((short) 2026);
        dateBuf.put((byte) 10); // Month (0-based: Nov = 10)
        dateBuf.put((byte) 14); // Day
        writeTlvField(dtBody, SisField.FIELD_DATE, dateBuf.array());
        ByteBuffer timeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        timeBuf.put((byte) 12); // Hour
        timeBuf.put((byte) 0);  // Minute
        timeBuf.put((byte) 0);  // Second
        timeBuf.put((byte) 0);  // Pad
        writeTlvField(dtBody, SisField.FIELD_TIME, timeBuf.array());
        writeTlvField(infoBody, SisField.FIELD_DATE_TIME, dtBody.toByteArray());
        // - InstallType (1 byte: 0 = SA) + InstallFlags (1 byte: 0)
        infoBody.write(0);
        infoBody.write(0);

        writeTlvField(ctrl, SisField.FIELD_INFO, infoBody.toByteArray());

        // 2. FIELD_SUPPORTED_OPTIONS (Tag 16) -> FIELD_ARRAY kosong bertipe FIELD_SUPPORTED_OPTION (20)
        writeTlvField(ctrl, SisField.FIELD_SUPPORTED_OPTIONS, buildEmptyArrayPayload(SisField.FIELD_SUPPORTED_OPTION));

        // 3. FIELD_SUPPORTED_LANGUAGES (Tag 15) -> FIELD_ARRAY berisi 1 FIELD_SUPPORTED_LANGUAGE (11) = 1 (English)
        ByteArrayOutputStream langArr = new ByteArrayOutputStream();
        langArr.write(int32(SisField.FIELD_SUPPORTED_LANGUAGE));
        langArr.write(int32(4)); // item length
        langArr.write(int32(1)); // ELangEnglish = 1
        writeTlvField(ctrl, SisField.FIELD_SUPPORTED_LANGUAGES, langArr.toByteArray());

        // 4. FIELD_PREREQUISITES (Tag 17) -> TargetDevices (ARRAY of FIELD_DEPENDENCY) + Dependencies (ARRAY of FIELD_DEPENDENCY)
        ByteArrayOutputStream prereqBody = new ByteArrayOutputStream();
        // Target Device: Series60ProductID [0x101F7961] v0.0.0 - v5.0.0
        ByteArrayOutputStream depBody = new ByteArrayOutputStream();
        writeTlvField(depBody, SisField.FIELD_UID, int32(0x101F7961));
        ByteBuffer vFrom = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(0);
        ByteBuffer vTo = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(5).putInt(0).putInt(0);
        ByteArrayOutputStream vRange = new ByteArrayOutputStream();
        writeTlvField(vRange, SisField.FIELD_VERSION, vFrom.array());
        writeTlvField(vRange, SisField.FIELD_VERSION, vTo.array());
        writeTlvField(depBody, SisField.FIELD_VERSION_RANGE, vRange.toByteArray());
        writeTlvField(depBody, SisField.FIELD_ARRAY, buildStringArrayPayload("Series60ProductID"));
        byte[] depBytes = depBody.toByteArray();

        ByteArrayOutputStream targetDevArr = new ByteArrayOutputStream();
        targetDevArr.write(int32(SisField.FIELD_DEPENDENCY));
        targetDevArr.write(int32(depBytes.length));
        targetDevArr.write(depBytes);
        writeAlign4(targetDevArr, depBytes.length);

        writeTlvField(prereqBody, SisField.FIELD_ARRAY, targetDevArr.toByteArray());
        writeTlvField(prereqBody, SisField.FIELD_ARRAY, buildEmptyArrayPayload(SisField.FIELD_DEPENDENCY));
        writeTlvField(ctrl, SisField.FIELD_PREREQUISITES, prereqBody.toByteArray());

        // 5. FIELD_PROPERTIES (Tag 19) -> FIELD_ARRAY kosong bertipe FIELD_PROPERTY (18)
        writeTlvField(ctrl, SisField.FIELD_PROPERTIES, buildEmptyArrayPayload(SisField.FIELD_PROPERTY));

        // 6. FIELD_INSTALL_BLOCK (Tag 28) -> Files (ARRAY of FIELD_FILE_DESCRIPTION) + IfBlocks (ARRAY of FIELD_IF)
        ByteArrayOutputStream filesArr = new ByteArrayOutputStream();
        filesArr.write(int32(SisField.FIELD_FILE_DESCRIPTION));
        for (int i = 0; i < files.size(); i++) {
            byte[] fdBytes = buildFullFileDescription(files.get(i), i);
            filesArr.write(int32(fdBytes.length));
            filesArr.write(fdBytes);
            writeAlign4(filesArr, fdBytes.length);
        }

        ByteArrayOutputStream installBody = new ByteArrayOutputStream();
        writeTlvField(installBody, SisField.FIELD_ARRAY, filesArr.toByteArray());
        writeTlvField(installBody, SisField.FIELD_ARRAY, buildEmptyArrayPayload(SisField.FIELD_IF));
        writeTlvField(ctrl, SisField.FIELD_INSTALL_BLOCK, installBody.toByteArray());

        // 7. FIELD_DATA_INDEX (Tag 40) -> 0
        writeTlvField(ctrl, SisField.FIELD_DATA_INDEX, int32(0));

        return ctrl.toByteArray();
    }

    private static byte[] buildFullFileDescription(SisFileEntry f, int fileIndex) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] raw = (f.rawData != null) ? f.rawData : new byte[0];

        // 1. TargetPath (Tag 1: FIELD_STRING)
        String dst = (f.targetPath != null ? f.targetPath : "C:\\Data\\file.bin").replace('/', '\\');
        writeTlvField(bos, SisField.FIELD_STRING, dst.getBytes(UTF16LE));

        // 2. MimeType (Tag 1: FIELD_STRING kosong)
        writeTlvField(bos, SisField.FIELD_STRING, new byte[0]);

        // 3. FIELD_HASH (Tag 25): HashAlgorithm (4B: 1 = SHA-1) + FIELD_BLOB (Tag 37: 20 byte SHA-1 digest)
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(raw);
        ByteArrayOutputStream hashBody = new ByteArrayOutputStream();
        hashBody.write(int32(1)); // EHashAlgSHA1 = 1
        writeTlvField(hashBody, SisField.FIELD_BLOB, digest);
        writeTlvField(bos, SisField.FIELD_HASH, hashBody.toByteArray());

        // 4. 28-byte fixed tail: operation(4) + options(4) + compressedSize(8) + uncompressedSize(8) + fileIndex(4)
        byte[] compData = zlibCompress(raw);
        ByteBuffer tail = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        tail.putInt(1); // EOpInstall = 1
        tail.putInt(0); // Options = 0
        tail.putLong(compData.length);
        tail.putLong(raw.length);
        tail.putInt(fileIndex);
        bos.write(tail.array());

        return bos.toByteArray();
    }

    private static byte[] buildDataBlock(List<SisFileEntry> files) throws Exception {
        ByteArrayOutputStream filesArr = new ByteArrayOutputStream();
        filesArr.write(int32(SisField.FIELD_FILE_DATA));

        for (SisFileEntry f : files) {
            byte[] raw = (f.rawData != null) ? f.rawData : new byte[0];
            byte[] compBlock = wrapAsCompressed(raw);
            filesArr.write(int32(compBlock.length));
            filesArr.write(compBlock);
            writeAlign4(filesArr, compBlock.length);
        }

        ByteArrayOutputStream unitBody = new ByteArrayOutputStream();
        writeTlvField(unitBody, SisField.FIELD_ARRAY, filesArr.toByteArray());
        byte[] unitBytes = unitBody.toByteArray();

        ByteArrayOutputStream unitsArr = new ByteArrayOutputStream();
        unitsArr.write(int32(SisField.FIELD_DATA_UNIT));
        unitsArr.write(int32(unitBytes.length));
        unitsArr.write(unitBytes);
        writeAlign4(unitsArr, unitBytes.length);

        ByteArrayOutputStream dataBody = new ByteArrayOutputStream();
        writeTlvField(dataBody, SisField.FIELD_ARRAY, unitsArr.toByteArray());

        ByteArrayOutputStream rootData = new ByteArrayOutputStream();
        writeTlvField(rootData, SisField.FIELD_DATA, dataBody.toByteArray());
        return rootData.toByteArray();
    }

    public static void packLegacySis(SisPackageInfo info, List<SisFileEntry> files, File outputFile) throws Exception {
        int numFiles = files.size();
        byte[] appNameUtf16 = (info.appName != null ? info.appName : "App").getBytes(UTF16LE);

        List<byte[]> dstPathsUtf16 = new ArrayList<>();
        List<byte[]> compBlobs = new ArrayList<>();

        for (SisFileEntry f : files) {
            String dst = (f.targetPath != null ? f.targetPath : "C:\\Data\\file.bin").replace('/', '\\');
            dstPathsUtf16.add(dst.getBytes(UTF16LE));
            byte[] raw = f.rawData != null ? f.rawData : new byte[0];
            compBlobs.add(zlibCompress(raw));
        }

        int cursor = 64;
        int ptrLangs = cursor;
        cursor += 2;
        int ptrCompNames = cursor;
        cursor += 8;
        int offAppName = cursor;
        cursor += appNameUtf16.length;
        int ptrFiles = cursor;
        cursor += numFiles * 40;

        int[] offDstNames = new int[numFiles];
        for (int i = 0; i < numFiles; i++) {
            offDstNames[i] = cursor;
            cursor += dstPathsUtf16.get(i).length;
        }
        int[] offFileData = new int[numFiles];
        for (int i = 0; i < numFiles; i++) {
            offFileData[i] = cursor;
            cursor += compBlobs.get(i).length;
        }

        int uid1 = info.uid3;
        int uid2 = SisField.LEGACY_SIS_UID2_MAGIC;
        int uid3 = 0x10003A12;
        int uidChecksum = SisField.calculateSymbianUidChecksum(uid1, uid2, uid3);

        ByteBuffer out = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0, uid1);
        out.putInt(4, uid2);
        out.putInt(8, uid3);
        out.putInt(12, uidChecksum);
        out.putShort(16, (short) 0x0001);
        out.putShort(18, (short) 1);
        out.putShort(20, (short) numFiles);
        out.putShort(32, (short) 0x0000);
        out.putShort(36, (short) info.majorVersion);
        out.putShort(38, (short) info.minorVersion);
        out.putInt(40, info.buildVersion);
        out.putInt(44, ptrLangs);
        out.putInt(48, ptrFiles);
        out.putInt(60, ptrCompNames);

        out.position(ptrLangs);
        out.putShort((short) 1);
        out.position(ptrCompNames);
        out.putInt(appNameUtf16.length);
        out.putInt(offAppName);
        out.position(offAppName);
        out.put(appNameUtf16);

        out.position(ptrFiles);
        for (int i = 0; i < numFiles; i++) {
            byte[] raw = files.get(i).rawData != null ? files.get(i).rawData : new byte[0];
            out.putInt(0);
            out.putInt(0);
            out.putInt(0);
            out.putInt(0);
            out.putInt(0);
            out.putInt(dstPathsUtf16.get(i).length);
            out.putInt(offDstNames[i]);
            out.putInt(compBlobs.get(i).length);
            out.putInt(offFileData[i]);
            out.putInt(raw.length);
        }

        for (int i = 0; i < numFiles; i++) {
            out.position(offDstNames[i]);
            out.put(dstPathsUtf16.get(i));
        }
        for (int i = 0; i < numFiles; i++) {
            out.position(offFileData[i]);
            out.put(compBlobs.get(i));
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(out.array());
        }
    }

    private static byte[] buildStringArrayPayload(String val) throws IOException {
        byte[] strBytes = val.getBytes(UTF16LE);
        ByteArrayOutputStream arr = new ByteArrayOutputStream();
        arr.write(int32(SisField.FIELD_STRING));
        arr.write(int32(strBytes.length));
        arr.write(strBytes);
        writeAlign4(arr, strBytes.length);
        return arr.toByteArray();
    }

    private static byte[] buildEmptyArrayPayload(int elemTag) {
        return int32(elemTag);
    }

    private static byte[] wrapAsCompressed(byte[] rawPayload) throws IOException {
        byte[] compressedData = zlibCompress(rawPayload);
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(1); // ECompressDeflate = 1
        header.putLong(rawPayload.length);

        ByteArrayOutputStream compPayload = new ByteArrayOutputStream();
        compPayload.write(header.array());
        compPayload.write(compressedData);

        ByteArrayOutputStream tlvOut = new ByteArrayOutputStream();
        writeTlvField(tlvOut, SisField.FIELD_COMPRESSED, compPayload.toByteArray());
        return tlvOut.toByteArray();
    }

    private static byte[] zlibCompress(byte[] raw) {
        Deflater def = new Deflater();
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length + 64);
        byte[] buf = new byte[4096];
        while (!def.finished()) {
            int n = def.deflate(buf);
            if (n > 0) bos.write(buf, 0, n);
            else break;
        }
        def.end();
        return bos.toByteArray();
    }

    private static void writeTlvField(ByteArrayOutputStream bos, int tag, byte[] payload) throws IOException {
        bos.write(int32(tag));
        bos.write(int32(payload.length));
        bos.write(payload);
        writeAlign4(bos, payload.length);
    }

    private static byte[] int32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static void writeAlign4(ByteArrayOutputStream bos, int len) {
        int mod = len % 4;
        if (mod != 0) {
            for (int i = 0; i < (4 - mod); i++) bos.write(0);
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) bos.write(buffer, 0, n);
            return bos.toByteArray();
        }
    }
}

