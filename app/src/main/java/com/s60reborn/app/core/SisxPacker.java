package com.s60reborn.app.core;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

public class SisxPacker {

    // Konstanta Resmi Symbian OS 9.x (sisfieldtypes.h)
    private static final int SISX_UID1_MAGIC = 0x10201A7A;
    private static final int FIELD_STRING = 1;
    private static final int FIELD_COMPRESSED = 2;
    private static final int FIELD_VERSION = 3;
    private static final int FIELD_VERSION_RANGE = 4;
    private static final int FIELD_INFO = 5;
    private static final int FIELD_SUPPORTED_LANGUAGES = 6;
    private static final int FIELD_SUPPORTED_OPTIONS = 7;
    private static final int FIELD_PREREQUISITES = 8;
    private static final int FIELD_DEPENDENCY = 9;
    private static final int FIELD_PROPERTIES = 10;
    private static final int FIELD_PROPERTY = 11;
    private static final int FIELD_CONTENTS = 12; // 0x0C
    private static final int FIELD_SUPPORTED_OPTION = 15;
    private static final int FIELD_SUPPORTED_LANGUAGE = 16;
    private static final int FIELD_FILE_DESCRIPTION = 17;
    private static final int FIELD_HASH = 18;
    private static final int FIELD_IF = 19;
    private static final int FIELD_ELSE_IF = 20;
    private static final int FIELD_INSTALL_BLOCK = 21;
    private static final int FIELD_EXPRESSION = 22;
    private static final int FIELD_DATA_UNIT = 23;
    private static final int FIELD_FILE_DATA = 24;
    private static final int FIELD_DATA_INDEX = 25;
    private static final int FIELD_CAPABILITIES = 32;
    private static final int FIELD_ARRAY = 33;      // 0x21
    private static final int FIELD_CONTROLLER = 34; // 0x22
    private static final int FIELD_DATA = 35;       // 0x23
    private static final int FIELD_BLOB = 36;       // 0x24
    private static final int FIELD_DATE_TIME = 37;  // 0x25
    private static final int FIELD_DATE = 38;       // 0x26
    private static final int FIELD_TIME = 39;       // 0x27

    public static SisPackageInfo packFromPkg(File pkgFile, File outputSisFile) throws Exception {
        File projectDir = pkgFile.getParentFile();
        SisPackageInfo info = parsePkgFile(pkgFile, projectDir);

        for (String subPath : info.embeddedSisPaths) {
            File subFile = new File(projectDir, subPath);
            if (subFile.exists()) {
                SisPackageInfo subInfo = SisxCoreParser.inspectSisx(subFile);
                for (SisFileEntry subEntry : subInfo.files) {
                    if (subEntry.rawData != null && subEntry.rawData.length > 0
						&& !subEntry.targetPath.startsWith("[Run During Install]")) {
                        info.files.add(subEntry);
                    }
                }
            }
        }

        if (info.files.isEmpty()) {
            throw new IOException("0 file terbaca dari project.pkg! Periksa daftar file sebelum Build.");
        }

        List<byte[]> compressedBlocks = new ArrayList<>();
        List<Long> exactCompSizes = new ArrayList<>();
        List<byte[]> fileHashes = new ArrayList<>();
        Map<String, Integer> sha1ToDataIndex = new HashMap<>();
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");

        for (int i = 0; i < info.files.size(); i++) {
            SisFileEntry entry = info.files.get(i);
            entry.uncompressedSize = entry.rawData.length;

            sha1.reset();
            byte[] digest = sha1.digest(entry.rawData);
            fileHashes.add(digest);

            String hexHash = bytesToHex(digest);
            if (sha1ToDataIndex.containsKey(hexHash)) {
                int existingDataIdx = sha1ToDataIndex.get(hexHash);
                entry.fileIndex = existingDataIdx;
                entry.compressedSize = exactCompSizes.get(existingDataIdx);
            } else {
                int newDataIdx = compressedBlocks.size();
                sha1ToDataIndex.put(hexHash, newDataIdx);
                entry.fileIndex = newDataIdx;

                byte[] comp = deflateBytes(entry.rawData);
                entry.compressedSize = comp.length;
                exactCompSizes.add((long) comp.length);

                // FIELD_FILE_DATA (24) membungkus FIELD_COMPRESSED (2)
                byte[] compField = buildCompressedField(1, entry.uncompressedSize, comp);
                compressedBlocks.add(compField);
            }
        }

		// 1. Controller: Langsung rangkai Tag 34 + Tag 2 + Compressed Data (TANPA panjang tengah)
        byte[] rawController = buildControllerBytes(info, fileHashes);
        byte[] compController = deflateBytes(rawController);
        byte[] rawCompFieldCtrl = buildCompressedField(1, rawController.length, compController);

        // Bungkus dengan FIELD_COMPRESSED (tag 2) beserta panjangnya
        byte[] tlvCompCtrl = wrapTlv(FIELD_COMPRESSED, rawCompFieldCtrl);

        // LANGSUNG tempel tag 34 di depan tlvCompCtrl (jangan tulis length lagi!)
        ByteArrayOutputStream ctrlBlock = new ByteArrayOutputStream();
        ctrlBlock.write(intToLe(34)); // FIELD_CONTROLLER
        ctrlBlock.write(tlvCompCtrl);
        byte[] tlvController = ctrlBlock.toByteArray();

        // 2. Data: Langsung rangkai Tag 35 + Tag 2 + Compressed Data (TANPA panjang tengah)
        byte[] rawDataBlock = buildDataBlockBytes(compressedBlocks);
        byte[] compData = deflateBytes(rawDataBlock);
        byte[] rawCompFieldData = buildCompressedField(1, rawDataBlock.length, compData);

        // Bungkus dengan FIELD_COMPRESSED (tag 2) beserta panjangnya
        byte[] tlvCompData = wrapTlv(FIELD_COMPRESSED, rawCompFieldData);

        // LANGSUNG tempel tag 35 di depan tlvCompData (jangan tulis length lagi!)
        ByteArrayOutputStream dataBlock = new ByteArrayOutputStream();
        dataBlock.write(intToLe(35)); // FIELD_DATA
        dataBlock.write(tlvCompData);
        byte[] tlvData = dataBlock.toByteArray();

        // 3. Masukkan keduanya ke FIELD_CONTENTS (12)
        ByteArrayOutputStream contentsBody = new ByteArrayOutputStream();
        contentsBody.write(tlvController);
        contentsBody.write(tlvData);
        byte[] tlvContents = wrapTlv(12, contentsBody.toByteArray());
		

        // 5. Header 16-byte SISX (UID1, UID2, UID3, UID_Checksum) + SISContents
        info.uid1 = SISX_UID1_MAGIC;
        info.uid2 = 0x00000000;
        info.uidChecksum = calculateSymbianUidChecksum(info.uid1, info.uid2, info.uid3);

        ByteBuffer headerBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        headerBuf.putInt(info.uid1);
        headerBuf.putInt(info.uid2);
        headerBuf.putInt(info.uid3);
        headerBuf.putInt(info.uidChecksum);

        if (outputSisFile.getParentFile() != null && !outputSisFile.getParentFile().exists()) {
            outputSisFile.getParentFile().mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(outputSisFile)) {
            fos.write(headerBuf.array());
            fos.write(tlvContents);
        }

        return info;
    }

    public static SisPackageInfo packLegacyV2FromPkg(File pkgFile, File outputSisFile) throws Exception {
        File projectDir = pkgFile.getParentFile();
        SisPackageInfo info = parsePkgFile(pkgFile, projectDir);

        int uid1 = info.uid3;
        int uid2 = 0x10000419;
        int uid3 = 0x10003A12;
        int uidChecksum = calculateSymbianUidChecksum(uid1, uid2, uid3);

        byte[] appNameBytes = info.appName.getBytes(StandardCharsets.UTF_16LE);

        List<byte[]> compFiles = new ArrayList<>();
        List<byte[]> dstPathBytesList = new ArrayList<>();
        for (SisFileEntry f : info.files) {
            f.uncompressedSize = f.rawData.length;
            byte[] comp = deflateBytes(f.rawData);
            f.compressedSize = comp.length;
            compFiles.add(comp);
            dstPathBytesList.add(f.targetPath.getBytes(StandardCharsets.UTF_16LE));
        }

        int headerSize = 64;
        int ptrLangs = headerSize;
        int ptrCompNames = ptrLangs + 4;
        int compNameStrOffset = ptrCompNames + 8;
        int ptrReqs = compNameStrOffset + pad4Len(appNameBytes.length);

        byte[] reqNameBytes = "Series60ProductID".getBytes(StandardCharsets.UTF_16LE);
        int reqRecordSize = 20 + 8 + pad4Len(reqNameBytes.length);
        int ptrFiles = ptrReqs + reqRecordSize;

        int fileRecordsHeaderTotal = info.files.size() * 40;
        int dataCursor = ptrFiles + fileRecordsHeaderTotal;

        ByteArrayOutputStream dynamicData = new ByteArrayOutputStream();
        List<Integer> dstPtrs = new ArrayList<>();
        List<Integer> dataPtrs = new ArrayList<>();

        for (int i = 0; i < info.files.size(); i++) {
            byte[] dstB = dstPathBytesList.get(i);
            dstPtrs.add(dataCursor);
            dynamicData.write(dstB);
            int padDst = pad4Len(dstB.length) - dstB.length;
            for (int p = 0; p < padDst; p++) dynamicData.write(0);
            dataCursor += pad4Len(dstB.length);

            byte[] compB = compFiles.get(i);
            dataPtrs.add(dataCursor);
            dynamicData.write(compB);
            int padComp = pad4Len(compB.length) - compB.length;
            for (int p = 0; p < padComp; p++) dynamicData.write(0);
            dataCursor += pad4Len(compB.length);
        }

        ByteArrayOutputStream sisOut = new ByteArrayOutputStream();

        ByteBuffer hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(uid1);
        hdr.putInt(uid2);
        hdr.putInt(uid3);
        hdr.putInt(uidChecksum);
        hdr.putShort((short) 0);
        hdr.putShort((short) 1);
        hdr.putShort((short) info.files.size());
        hdr.putShort((short) 1);
        hdr.putShort((short) 0);
        hdr.putShort((short) 0);
        hdr.putShort((short) 0);
        hdr.putShort((short) 0);
        hdr.putInt(0);
        hdr.putShort((short) 0);
        hdr.putShort((short) 0);
        hdr.putShort((short) info.majorVersion);
        hdr.putShort((short) info.minorVersion);
        hdr.putInt(info.buildVersion);
        hdr.putInt(ptrLangs);
        hdr.putInt(ptrFiles);
        hdr.putInt(ptrReqs);
        hdr.putInt(0);
        hdr.putInt(ptrCompNames);
        sisOut.write(hdr.array());

        ByteBuffer langBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        langBuf.putShort((short) 1);
        langBuf.putShort((short) 0);
        sisOut.write(langBuf.array());

        sisOut.write(intToLe(appNameBytes.length));
        sisOut.write(intToLe(compNameStrOffset));
        sisOut.write(pad4(appNameBytes));

        ByteBuffer reqBuf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        reqBuf.putInt(0x101F6F88);
        reqBuf.putShort((short) 2);
        reqBuf.putShort((short) 0);
        reqBuf.putInt(0);
        reqBuf.putInt(reqNameBytes.length);
        reqBuf.putInt(ptrReqs + 28);
        reqBuf.putInt(0);
        reqBuf.putInt(0);
        sisOut.write(reqBuf.array());
        sisOut.write(pad4(reqNameBytes));

        for (int i = 0; i < info.files.size(); i++) {
            SisFileEntry f = info.files.get(i);
            ByteBuffer fRec = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            fRec.putInt(0);
            fRec.putInt(0);
            fRec.putInt(0);
            fRec.putInt(0);
            fRec.putInt(0);
            fRec.putInt(dstPathBytesList.get(i).length);
            fRec.putInt(dstPtrs.get(i));
            fRec.putInt((int) f.compressedSize);
            fRec.putInt(dataPtrs.get(i));
            fRec.putInt((int) f.uncompressedSize);
            sisOut.write(fRec.array());
        }

        sisOut.write(dynamicData.toByteArray());

        if (outputSisFile.getParentFile() != null && !outputSisFile.getParentFile().exists()) {
            outputSisFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outputSisFile)) {
            fos.write(sisOut.toByteArray());
        }

        return info;
    }

    private static int pad4Len(int len) {
        int rem = len % 4;
        return (rem == 0) ? len : len + (4 - rem);
    }

    public static SisPackageInfo parsePkgFile(File pkgFile, File baseDir) throws IOException {
        SisPackageInfo info = new SisPackageInfo();
        Pattern headerPat = Pattern.compile("#\\s*\\{\\s*\"([^\"]+)\"\\s*\\}\\s*,\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)");
        Pattern vendorPat = Pattern.compile("%\\s*\\{\\s*\"([^\"]+)\"\\s*\\}");
        Pattern filePat = Pattern.compile("^\"([^\"]+)\"\\s*-\\s*\"([^\"]+)\"");
        Pattern embeddedPat = Pattern.compile("^@\\s*\"([^\"]+)\"");
        Pattern ifNotExistsPat = Pattern.compile("(?i)^IF\\s+NOT\\s+EXISTS\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

        String activeConditionPath = null;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(pkgFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;

                Matcher mIf = ifNotExistsPat.matcher(line);
                if (mIf.find()) {
                    activeConditionPath = mIf.group(1);
                    continue;
                }
                if (line.equalsIgnoreCase("ENDIF")) {
                    activeConditionPath = null;
                    continue;
                }

                Matcher mEmb = embeddedPat.matcher(line);
                if (mEmb.find()) {
                    String subRel = mEmb.group(1).replace('\\', '/').trim();
                    info.embeddedSisPaths.add(subRel);
                    continue;
                }

                Matcher mHeader = headerPat.matcher(line);
                if (mHeader.find()) {
                    info.appName = mHeader.group(1);
                    String uidStr = mHeader.group(2);
                    info.uid3 = uidStr.startsWith("0x") || uidStr.startsWith("0X")
						? (int) Long.parseLong(uidStr.substring(2), 16)
						: Integer.parseInt(uidStr);
                    info.majorVersion = Integer.parseInt(mHeader.group(3));
                    info.minorVersion = Integer.parseInt(mHeader.group(4));
                    info.buildVersion = Integer.parseInt(mHeader.group(5));
                    continue;
                }

                Matcher mVendor = vendorPat.matcher(line);
                if (mVendor.find()) {
                    info.vendorName = mVendor.group(1);
                    continue;
                }

                Matcher mFile = filePat.matcher(line);
                if (mFile.find()) {
                    String srcRel = mFile.group(1).replace('\\', '/').trim();
                    String dstTarget = mFile.group(2).trim();

                    if (dstTarget.startsWith("[Run During Install]")) continue;

                    File srcFile = new File(baseDir, srcRel);
                    if (!srcFile.exists() || srcFile.length() == 0) {
                        throw new IOException("File aset hilang atau 0 byte: " + srcFile.getAbsolutePath());
                    }

                    SisFileEntry entry = new SisFileEntry();
                    entry.localRelPath = srcRel;
                    entry.targetPath = dstTarget;
                    entry.rawData = readAllBytes(srcFile);
                    entry.conditionNotExistsPath = activeConditionPath;

                    String upperLine = line.toUpperCase();
                    if (upperLine.contains("FT")) {
                        entry.operation = 2;
                        if (upperLine.contains("TC")) entry.operationOptions = 1;
                    } else if (upperLine.contains("FR")) {
                        entry.operation = 4;
                        if (upperLine.contains("RI")) entry.operationOptions = 2;
                    }

                    info.files.add(entry);
                }
            }
        }
        return info;
    }

    private static byte[] buildControllerBytes(SisPackageInfo info, List<byte[]> fileHashes) throws IOException {
        // 0. KELOMPOKKAN VARIAN & KONDISI DI PALING ATAS
        List<Integer> commonIdx = new ArrayList<>();
        Map<String, List<Integer>> variantGroups = new LinkedHashMap<>();

        for (int i = 0; i < info.files.size(); i++) {
            SisFileEntry f = info.files.get(i);
            String rel = f.localRelPath.replace('\\', '/');
            if (rel.startsWith("varian_")) {
                int slash = rel.indexOf('/');
                String vKey = (slash > 0) ? rel.substring(0, slash) : "varian_1";
                if (!variantGroups.containsKey(vKey)) {
                    variantGroups.put(vKey, new ArrayList<Integer>());
                }
                variantGroups.get(vKey).add(i);
            } else {
                commonIdx.add(i);
            }
        }

        if (variantGroups.size() <= 1) {
            for (List<Integer> list : variantGroups.values()) {
                commonIdx.addAll(list);
            }
            variantGroups.clear();
        }

        List<Integer> plainCommonIdx = new ArrayList<>();
        Map<String, List<Integer>> condNotExistsGroups = new LinkedHashMap<>();

        for (int idx : commonIdx) {
            SisFileEntry f = info.files.get(idx);
            if (f.conditionNotExistsPath != null && !f.conditionNotExistsPath.isEmpty()) {
                if (!condNotExistsGroups.containsKey(f.conditionNotExistsPath)) {
                    condNotExistsGroups.put(f.conditionNotExistsPath, new ArrayList<Integer>());
                }
                condNotExistsGroups.get(f.conditionNotExistsPath).add(idx);
            } else {
                plainCommonIdx.add(idx);
            }
        }

        ByteArrayOutputStream ctrl = new ByteArrayOutputStream();

        // 1. SISInfo (FIELD_INFO = 5)
        ByteArrayOutputStream infoBody = new ByteArrayOutputStream();
        infoBody.write(intToLe(info.uid3));
        infoBody.write(wrapStringUtf16(info.vendorName));
        infoBody.write(wrapStringArray(info.appName));
        infoBody.write(wrapStringArray(info.vendorName));

        ByteBuffer verBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        verBuf.putInt(info.majorVersion);
        verBuf.putInt(info.minorVersion);
        verBuf.putInt(info.buildVersion);
        infoBody.write(wrapTlv(FIELD_VERSION, verBuf.array()));

        ByteBuffer dateBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        dateBuf.putShort((short) 2026);
        dateBuf.put((byte) 10);
        dateBuf.put((byte) 14);
        ByteBuffer timeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        timeBuf.put((byte) 9);
        timeBuf.put((byte) 0);
        timeBuf.put((byte) 0);
        timeBuf.put((byte) 0);

        ByteArrayOutputStream dtBody = new ByteArrayOutputStream();
        dtBody.write(wrapTlv(FIELD_DATE, dateBuf.array()));
        dtBody.write(wrapTlv(FIELD_TIME, timeBuf.array()));
        infoBody.write(wrapTlv(FIELD_DATE_TIME, dtBody.toByteArray()));

        ByteBuffer metaBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        metaBuf.put((byte) info.installType); // 0 = SA
        metaBuf.put((byte) 0);                // Flags = 0
        infoBody.write(metaBuf.array());

        ctrl.write(wrapTlv(FIELD_INFO, infoBody.toByteArray()));

        // 2. SISSupportedOptions (FIELD_SUPPORTED_OPTIONS = 7)
        ctrl.write(wrapTlv(FIELD_SUPPORTED_OPTIONS, wrapTlv(FIELD_ARRAY, intToLe(FIELD_SUPPORTED_OPTION))));

        // 3. SISSupportedLanguages (FIELD_SUPPORTED_LANGUAGES = 6 -> FIELD_ARRAY(33) -> FIELD_SUPPORTED_LANGUAGE(16))
        ByteArrayOutputStream langArr = new ByteArrayOutputStream();
        langArr.write(intToLe(FIELD_SUPPORTED_LANGUAGE));
        langArr.write(intToLe(1)); // LANG_English = 1
        ctrl.write(wrapTlv(FIELD_SUPPORTED_LANGUAGES, wrapTlv(FIELD_ARRAY, langArr.toByteArray())));

        // 4. SISPrerequisites (FIELD_PREREQUISITES = 8)
        ByteArrayOutputStream prereqBody = new ByteArrayOutputStream();
        ByteArrayOutputStream depList = new ByteArrayOutputStream();
        depList.write(intToLe(FIELD_DEPENDENCY));

        ByteArrayOutputStream depItem = new ByteArrayOutputStream();
        depItem.write(intToLe(0x101F7961)); // Series60ProductID S60v3
        ByteBuffer vRange = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        vRange.putInt(0); vRange.putInt(0); vRange.putInt(0);
        vRange.putInt(5); vRange.putInt(0); vRange.putInt(0);
        depItem.write(wrapTlv(FIELD_VERSION_RANGE, vRange.array()));
        depItem.write(wrapStringArray("Series60ProductID"));

        byte[] depItemBytes = depItem.toByteArray();
        depList.write(intToLe(depItemBytes.length));
        depList.write(pad4(depItemBytes));
        prereqBody.write(wrapTlv(FIELD_ARRAY, depList.toByteArray()));
		prereqBody.write(wrapTlv(FIELD_ARRAY, intToLe(FIELD_DEPENDENCY)));
        ctrl.write(wrapTlv(FIELD_PREREQUISITES, prereqBody.toByteArray()));

        // 5. SISProperties (FIELD_PROPERTIES = 10)
        ctrl.write(wrapTlv(FIELD_PROPERTIES, wrapTlv(FIELD_ARRAY, intToLe(FIELD_PROPERTY))));

        // 6. SISInstallBlock (FIELD_INSTALL_BLOCK = 21)
        ByteArrayOutputStream installBody = new ByteArrayOutputStream();
        ByteArrayOutputStream fileArrayBody = new ByteArrayOutputStream();
        fileArrayBody.write(intToLe(FIELD_FILE_DESCRIPTION));
        for (int idx : plainCommonIdx) {
            byte[] fdBytes = buildFileDescriptionBytes(info.files.get(idx), fileHashes.get(idx));
            fileArrayBody.write(intToLe(fdBytes.length));
            fileArrayBody.write(pad4(fdBytes));
        }
        installBody.write(wrapTlv(FIELD_ARRAY, fileArrayBody.toByteArray()));

        ByteArrayOutputStream ifArrayBody = new ByteArrayOutputStream();
        ifArrayBody.write(intToLe(FIELD_IF));

        for (Map.Entry<String, List<Integer>> cEntry : condNotExistsGroups.entrySet()) {
            ByteArrayOutputStream condIfBody = new ByteArrayOutputStream();
            condIfBody.write(buildNotExistsExpression(cEntry.getKey()));
            condIfBody.write(buildSingleInstallBlockForIndices(cEntry.getValue(), info, fileHashes));

            byte[] condIfBytes = condIfBody.toByteArray();
            ifArrayBody.write(intToLe(condIfBytes.length));
            ifArrayBody.write(pad4(condIfBytes));
        }

        if (variantGroups.size() >= 2) {
            List<List<Integer>> groupsList = new ArrayList<>(variantGroups.values());
            byte[] ifBlockBytes = buildMultiVariantIfBlock(groupsList, info, fileHashes);
            ifArrayBody.write(intToLe(ifBlockBytes.length));
            ifArrayBody.write(pad4(ifBlockBytes));
        }

        installBody.write(wrapTlv(FIELD_ARRAY, ifArrayBody.toByteArray()));
        ctrl.write(wrapTlv(FIELD_INSTALL_BLOCK, installBody.toByteArray()));

        // 7. DataIndex (FIELD_DATA_INDEX = 25)
        ctrl.write(wrapTlv(FIELD_DATA_INDEX, intToLe(0)));

        return ctrl.toByteArray();
    }
	

	private static byte[] buildFileDescriptionBytes(SisFileEntry f, byte[] sha1Hash) throws IOException {
        ByteArrayOutputStream fd = new ByteArrayOutputStream();
        fd.write(wrapStringUtf16(f.targetPath)); // 1. Target
        fd.write(wrapStringUtf16(""));           // 2. MimeType

        // 3. Hash (FIELD_HASH = 18) WAJIB lebih dulu
        ByteArrayOutputStream hashBody = new ByteArrayOutputStream();
        hashBody.write(intToLe(1)); // SHA1
        hashBody.write(wrapTlv(FIELD_BLOB, sha1Hash));
        fd.write(wrapTlv(FIELD_HASH, hashBody.toByteArray()));

        // 4. Capabilities (FIELD_CAPABILITIES = 32): Baca 8 byte dari offset 0x80 jika file .exe / .dll
        byte[] capBytes = extractEpocCapabilities(f.targetPath, f.rawData);
        if (capBytes != null) {
            fd.write(wrapTlv(FIELD_CAPABILITIES, capBytes));
        }

        // 5. Operation (4), OperationOptions (4), CompressedLength (8), UncompressedLength (8), FileIndex (4)
        ByteBuffer tail = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        tail.putInt(f.operation > 0 ? f.operation : 1);
        tail.putInt(f.operationOptions);
        tail.putLong(f.compressedSize);
        tail.putLong(f.uncompressedSize);
        tail.putInt(f.fileIndex);
        fd.write(tail.array());

        return fd.toByteArray();
    }

    /**
     * Mengekstrak 8-byte bitmask kapabilitas dari header E32Image (.exe / .dll) Symbian OS 9.x (offset 0x80)
     */
    private static byte[] extractEpocCapabilities(String targetPath, byte[] rawData) {
        if (rawData == null || rawData.length < 0x88) return null;
        String lower = targetPath.toLowerCase();
        if (!lower.endsWith(".exe") && !lower.endsWith(".dll")) return null;

        // Cek signature 'EPOC' di offset 0x10 (16) pada header E32Image
        if (rawData[16] == 'E' && rawData[17] == 'P' && rawData[18] == 'O' && rawData[19] == 'C') {
            byte[] caps = new byte[8];
            System.arraycopy(rawData, 0x80, caps, 0, 8);
            return caps;
        }
        return null;
    }
	

    private static byte[] buildMultiVariantIfBlock(
		List<List<Integer>> groups,
		SisPackageInfo info,
		List<byte[]> fileHashes) throws IOException {

        ByteArrayOutputStream exprBody = new ByteArrayOutputStream();
        exprBody.write(intToLe(12)); // EPrimTypeNumber = 12
        exprBody.write(intToLe(1));  // IntValue = 1 (True)
        byte[] tlvExpr = wrapTlv(FIELD_EXPRESSION, exprBody.toByteArray());

        byte[] branch1Block = buildSingleInstallBlockForIndices(groups.get(0), info, fileHashes);

        ByteArrayOutputStream elseIfArr = new ByteArrayOutputStream();
        elseIfArr.write(intToLe(FIELD_ELSE_IF));
        for (int g = 1; g < groups.size(); g++) {
            ByteArrayOutputStream elseIfItem = new ByteArrayOutputStream();
            elseIfItem.write(tlvExpr);
            elseIfItem.write(buildSingleInstallBlockForIndices(groups.get(g), info, fileHashes));

            byte[] itemBytes = elseIfItem.toByteArray();
            elseIfArr.write(intToLe(itemBytes.length));
            elseIfArr.write(pad4(itemBytes));
        }

        ByteArrayOutputStream sisIfBody = new ByteArrayOutputStream();
        sisIfBody.write(tlvExpr);
        sisIfBody.write(branch1Block);
        sisIfBody.write(wrapTlv(FIELD_ARRAY, elseIfArr.toByteArray()));
        return sisIfBody.toByteArray();
    }

    private static byte[] buildNotExistsExpression(String checkTargetPath) throws IOException {
        ByteArrayOutputStream strExpr = new ByteArrayOutputStream();
        strExpr.write(intToLe(10)); // EPrimTypeString = 10
        strExpr.write(intToLe(0));
        strExpr.write(wrapStringUtf16(checkTargetPath));
        byte[] tlvStrExpr = wrapTlv(FIELD_EXPRESSION, strExpr.toByteArray());

        ByteArrayOutputStream existsExpr = new ByteArrayOutputStream();
        existsExpr.write(intToLe(1000)); // EFuncExists = 1000
        existsExpr.write(intToLe(0));
        existsExpr.write(tlvStrExpr);
        byte[] tlvExistsExpr = wrapTlv(FIELD_EXPRESSION, existsExpr.toByteArray());

        ByteArrayOutputStream notExpr = new ByteArrayOutputStream();
        notExpr.write(intToLe(7)); // EUnaryOpNot = 7
        notExpr.write(intToLe(0));
        notExpr.write(tlvExistsExpr);
        return wrapTlv(FIELD_EXPRESSION, notExpr.toByteArray());
    }

    private static byte[] buildSingleInstallBlockForIndices(
		List<Integer> indices,
		SisPackageInfo info,
		List<byte[]> fileHashes) throws IOException {

        ByteArrayOutputStream fileArr = new ByteArrayOutputStream();
        fileArr.write(intToLe(FIELD_FILE_DESCRIPTION));
        for (int idx : indices) {
            byte[] fdBytes = buildFileDescriptionBytes(info.files.get(idx), fileHashes.get(idx));
            fileArr.write(intToLe(fdBytes.length));
            fileArr.write(pad4(fdBytes));
        }
        ByteArrayOutputStream blockBody = new ByteArrayOutputStream();
        blockBody.write(wrapTlv(FIELD_ARRAY, fileArr.toByteArray()));
        blockBody.write(wrapTlv(FIELD_ARRAY, intToLe(FIELD_IF)));
        return wrapTlv(FIELD_INSTALL_BLOCK, blockBody.toByteArray());
    }

	private static byte[] buildDataBlockBytes(List<byte[]> wrappedFileDatas) throws IOException {
        // 1. Susun array data unit & file data
        ByteArrayOutputStream fileDataArr = new ByteArrayOutputStream();
        fileDataArr.write(intToLe(FIELD_FILE_DATA));
        for (byte[] item : wrappedFileDatas) {
            fileDataArr.write(intToLe(item.length));
            fileDataArr.write(pad4(item));
        }
        byte[] dataUnitBody = wrapTlv(FIELD_ARRAY, fileDataArr.toByteArray());

        ByteArrayOutputStream unitArr = new ByteArrayOutputStream();
        unitArr.write(intToLe(FIELD_DATA_UNIT));
        unitArr.write(intToLe(dataUnitBody.length));
        unitArr.write(pad4(dataUnitBody));

        byte[] rawDataBlock = wrapTlv(FIELD_ARRAY, unitArr.toByteArray());

	// 2. Kompresi seluruh block data fisik dengan Zlib
	byte[] compData = deflateBytes(rawDataBlock);

        // 3. Bungkus dengan FIELD_COMPRESSED (tag 2)
        byte[] compFieldData = buildCompressedField(1, rawDataBlock.length, compData);

        // 4. Bungkus luar dengan FIELD_DATA (tag 35)
        return wrapTlv(FIELD_DATA, compFieldData);
		}
		

	private static byte[] buildCompressedField(int algorithm, long uncompressedSize, byte[] compressedData) throws IOException {
        // Langsung tulis format FIELD_COMPRESSED (tag 2) sesuai standar Symbian OS 9.x
        ByteArrayOutputStream compHeadBody = new ByteArrayOutputStream();
        compHeadBody.write(intToLe(algorithm)); // 1 = Zlib
        ByteBuffer uSizeBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(uncompressedSize);
        compHeadBody.write(uSizeBuf.array());
        compHeadBody.write(compressedData);

        return compHeadBody.toByteArray();
    }
	
	

    private static byte[] deflateBytes(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            bos.write(buf, 0, count);
        }
        deflater.end();
        return bos.toByteArray();
    }

    public static int calculateSymbianUidChecksum(int uid1, int uid2, int uid3) {
        byte[] uidBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
			.putInt(uid1).putInt(uid2).putInt(uid3).array();
        byte[] evenBytes = new byte[6];
        byte[] oddBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            evenBytes[i] = uidBytes[i * 2];
            oddBytes[i] = uidBytes[i * 2 + 1];
        }
        int crcEven = crc16Ccitt(evenBytes);
        int crcOdd = crc16Ccitt(oddBytes);
        return (crcOdd << 16) | (crcEven & 0xFFFF);
    }

    private static int crc16Ccitt(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static byte[] wrapTlv(int type, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(intToLe(type));
        out.write(intToLe(data.length));
        out.write(pad4(data));
        return out.toByteArray();
    }

    private static byte[] wrapStringUtf16(String str) throws IOException {
        byte[] strBytes = str.getBytes(StandardCharsets.UTF_16LE);
        return wrapTlv(FIELD_STRING, strBytes);
    }

    private static byte[] wrapStringArray(String str) throws IOException {
        byte[] strBytes = str.getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream arr = new ByteArrayOutputStream();
        arr.write(intToLe(FIELD_STRING));
        arr.write(intToLe(strBytes.length));
        arr.write(pad4(strBytes));
        return wrapTlv(FIELD_ARRAY, arr.toByteArray());
    }

    private static byte[] pad4(byte[] data) throws IOException {
        int rem = data.length % 4;
        if (rem == 0) return data;
        int padLen = 4 - rem;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + padLen);
        out.write(data);
        for (int i = 0; i < padLen; i++) out.write(0);
        return out.toByteArray();
    }

    private static byte[] intToLe(int val) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(val).array();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}

