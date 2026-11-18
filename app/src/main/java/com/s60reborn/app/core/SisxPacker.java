package com.s60reborn.app.core;

import android.util.Base64;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;
import com.s60reborn.app.model.SisProjectConfig;

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
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

public class SisxPacker {

    private static final Charset UTF16LE = Charset.forName("UTF-16LE");

	public static SisPackageInfo parsePkgFile(File pkgFile, File baseDir) throws Exception {
        SisPackageInfo info = new SisPackageInfo();
        info.files = new ArrayList<SisFileEntry>();
        if (baseDir == null) baseDir = pkgFile.getParentFile();

        Pattern headerPat = Pattern.compile("#\\s*\\{\\s*\"([^\"]+)\"[^}]*\\}\\s*,\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)");
        Pattern vendorPat = Pattern.compile("^:\\s*\"([^\"]+)\"");
        Pattern targetDevPat = Pattern.compile("^\\[\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*\\{\\s*\"([^\"]+)\"");
        Pattern softDepPat = Pattern.compile("^\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*\\{\\s*\"([^\"]+)\"");
        Pattern filePat = Pattern.compile("^\"([^\"]+)\"\\s*-\\s*\"([^\"]+)\"");
        Pattern embedPat = Pattern.compile("^@\\s*\"([^\"]+)\"\\s*,\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)");

        String activeCondition = null;

        try (BufferedReader br = new BufferedReader(new FileReader(pkgFile))) {
            String line;
            int idx = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;

                if (line.toUpperCase().startsWith("IF ")) {
                    activeCondition = line.substring(3).trim();
                    continue;
                } else if (line.equalsIgnoreCase("ENDIF")) {
                    activeCondition = null;
                    continue;
                }

                Matcher mHdr = headerPat.matcher(line);
                if (mHdr.find()) {
                    info.appName = mHdr.group(1);
                    info.uid3 = parseUidInt(mHdr.group(2));
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

                // TANGKAP TARGET DEVICES: [0x20022E6D], 0, 0, 0, {"S60ProductID"}
                Matcher mDev = targetDevPat.matcher(line);
                if (mDev.find()) {
                    info.targetDevices.add(new SisPackageInfo.DependencyItem(
											   parseUidInt(mDev.group(1)),
											   Integer.parseInt(mDev.group(2)),
											   Integer.parseInt(mDev.group(3)),
											   Integer.parseInt(mDev.group(4)),
											   mDev.group(5)
										   ));
                    continue;
                }

                // TANGKAP SOFTWARE DEPENDENCIES: (0x2001E61C), 4, 7, 4, {"Qt"}
                Matcher mDep = softDepPat.matcher(line);
                if (mDep.find()) {
                    info.dependencies.add(new SisPackageInfo.DependencyItem(
											  parseUidInt(mDep.group(1)),
											  Integer.parseInt(mDep.group(2)),
											  Integer.parseInt(mDep.group(3)),
											  Integer.parseInt(mDep.group(4)),
											  mDep.group(5)
										  ));
                    continue;
                }

                Matcher mEmb = embedPat.matcher(line);
                if (mEmb.find()) {
                    String embRel = mEmb.group(1).replace('\\', '/');
                    int embUid = parseUidInt(mEmb.group(2));

                    SisFileEntry entry = new SisFileEntry();
                    entry.isEmbeddedSis = true;
                    entry.embeddedUid3 = embUid;
                    entry.ifCondition = activeCondition;
                    entry.localRelPath = embRel;
                    entry.targetPath = embRel;

                    File embFile = new File(baseDir, embRel);
                    if (embFile.exists() && embFile.isFile()) {
                        entry.rawData = readAllBytes(embFile);
                        entry.uncompressedSize = entry.rawData.length;
                    } else {
                        entry.rawData = new byte[0];
                    }
                    info.files.add(entry);
                    continue;
                }

                Matcher mFile = filePat.matcher(line);
                if (mFile.find()) {
                    String srcRel = mFile.group(1).replace('\\', '/');
                    String dstSymbian = mFile.group(2).replace('/', '\\').trim();

                    SisFileEntry entry = new SisFileEntry();
                    entry.fileIndex = idx++;
                    entry.localRelPath = srcRel;
                    entry.targetPath = dstSymbian;
                    entry.ifCondition = activeCondition;
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
	

    private static int parseUidInt(String uidStr) {
        String clean = uidStr.trim();
        return clean.toLowerCase().startsWith("0x")
            ? (int) Long.parseLong(clean.substring(2), 16)
            : Integer.parseInt(clean);
    }

    // =========================================================================
    // ENTRY POINTS UTAMA: GABUNGAN REPACK + REBUILD DENGAN SERTIFIKAT (.CER)
    // =========================================================================

    public static SisPackageInfo packFromPkgWithConfig(
		File pkgFile, File baseDir, File outputFile, SisProjectConfig config) throws Exception {
        if (baseDir == null) baseDir = pkgFile.getParentFile();
        SisPackageInfo info = parsePkgFile(pkgFile, baseDir);
        applyConfigRulesToFiles(info.files, config);

        if (config != null && config.isLegacySisV1V2()) {
            packLegacySis(info, info.files, outputFile);
        } else {
            packModernSisxWithConfig(info, info.files, outputFile, config);
        }
        return info;
    }

    public static void packPackageWithConfig(
		SisPackageInfo info, List<SisFileEntry> files, File outputFile, SisProjectConfig config) throws Exception {
        applyConfigRulesToFiles(files, config);
        if (config != null && config.isLegacySisV1V2()) {
            packLegacySis(info, files, outputFile);
        } else {
            packModernSisxWithConfig(info, files, outputFile, config);
        }
    }

    public static SisPackageInfo packFromPkg(File pkgFile, File outputSisx) throws Exception {
        return packFromPkgWithConfig(pkgFile, pkgFile.getParentFile(), outputSisx, null);
    }

    public static SisPackageInfo packLegacyV2FromPkg(File pkgFile, File outputSis) throws Exception {
        SisPackageInfo info = parsePkgFile(pkgFile, pkgFile.getParentFile());
        packLegacySis(info, info.files, outputSis);
        return info;
    }

    public static void packFromPkg(File pkgFile, File baseDir, File outputSisx) throws Exception {
        packFromPkgWithConfig(pkgFile, baseDir, outputSisx, null);
    }

    public static void packLegacyV2FromPkg(File pkgFile, File baseDir, File outputSis) throws Exception {
        SisPackageInfo info = parsePkgFile(pkgFile, baseDir);
        packLegacySis(info, info.files, outputSis);
    }

    public static void packPackage(SisPackageInfo info, List<SisFileEntry> files, File outputFile, int versionMode) throws Exception {
        if (versionMode == 1) {
            packLegacySis(info, files, outputFile);
        } else {
            packModernSisxWithConfig(info, files, outputFile, null);
        }
    }

    public static void packModernSisx(SisPackageInfo info, List<SisFileEntry> files, File outputFile) throws Exception {
        packModernSisxWithConfig(info, files, outputFile, null);
    }

    private static void applyConfigRulesToFiles(List<SisFileEntry> files, SisProjectConfig config) {
        if (config == null || files == null) return;

        // Terapkan Checkbox Pilihan Install (option1, option2, ...) dari config.installOptions
        if (config.installOptions != null) {
            for (int optIdx = 0; optIdx < config.installOptions.size(); optIdx++) {
                SisProjectConfig.InstallOption opt = config.installOptions.get(optIdx);
                if (opt.fileRelPaths == null) continue;
                String optExpr = "option" + (optIdx + 1);
                for (SisFileEntry f : files) {
                    if (f.localRelPath != null && opt.fileRelPaths.contains(f.localRelPath)) {
                        f.ifCondition = optExpr;
                    }
                }
            }
        }
    }

    private static class EmbeddedUnitBundle {
        byte[] rawSubController;
        List<byte[]> rawUnitFiles;
        String ifCondition;
    }

    public static void packModernSisxWithConfig(
		SisPackageInfo info,
		List<SisFileEntry> files,
		File outputFile,
		SisProjectConfig config) throws Exception {

        List<SisFileEntry> regularFiles = new ArrayList<SisFileEntry>();
        List<EmbeddedUnitBundle> embeddedBundles = new ArrayList<EmbeddedUnitBundle>();

        for (SisFileEntry f : files) {
            if (f.isEmbeddedSis && f.rawData != null && f.rawData.length > 20) {
                EmbeddedUnitBundle bundle = extractBundleFromStandaloneSisx(f.rawData, embeddedBundles.size() + 1, f.ifCondition);
                if (bundle != null) {
                    embeddedBundles.add(bundle);
                }
            } else if (!f.isEmbeddedSis) {
                regularFiles.add(f);
            }
        }

        if (config != null && config.embeddedSisPaths != null) {
            for (String embPath : config.embeddedSisPaths) {
                File embFile = new File(embPath);
                if (embFile.exists() && embFile.isFile()) {
                    byte[] embBytes = readAllBytes(embFile);
                    EmbeddedUnitBundle bundle = extractBundleFromStandaloneSisx(embBytes, embeddedBundles.size() + 1, null);
                    if (bundle != null) {
                        embeddedBundles.add(bundle);
                    }
                }
            }
        }

        // 1. Bangun SISController (Tag 13) + tanda tangani langsung dengan .CER/.KEY jika aktif
        byte[] controllerTlv = buildAndSignControllerTlv(info, regularFiles, embeddedBundles, config);
        byte[] compressedController = wrapAsCompressed(controllerTlv);

        // 2. Bangun blok FIELD_DATA (Tag 30)
        byte[] dataBytes = buildMultiUnitDataBlock(regularFiles, embeddedBundles);

        // 3. Hitung CRC16 Symbian atas Controller (yang sudah memuat Tag 39 jika di-sign) & Data
        short ctrlCrc = calculateSymbianCrc16(controllerTlv);
        short dataCrc = calculateSymbianCrc16(dataBytes);

        byte[] ctrlCrcTlv = SisField.wrapTlv(34, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(ctrlCrc).array());
        byte[] dataCrcTlv = SisField.wrapTlv(35, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dataCrc).array());

        // 4. Susun isi FIELD_CONTENTS (Tag 12): Checksum (34, 35) + Compressed Controller (3) + Data (30)
        ByteArrayOutputStream contentsPayload = new ByteArrayOutputStream();
        contentsPayload.write(ctrlCrcTlv);
        contentsPayload.write(dataCrcTlv);
        contentsPayload.write(compressedController);
        contentsPayload.write(dataBytes);
        byte[] finalContents = SisField.wrapTlv(SisField.FIELD_CONTENTS, contentsPayload.toByteArray());

        // 5. Header 16-byte Symbian SISX
        int uid1 = SisField.SISX_UID1_MAGIC;
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

    /**
     * Membangun FIELD_CONTROLLER (Tag 13) sekaligus menyisipkan tanda tangan X.509 (Tag 39)
     * sebelum FIELD_DATA_INDEX (Tag 40) jika mode signing / rebuildWithCert aktif.
     */
	/**
     * Membangun FIELD_CONTROLLER (Tag 13) langsung ke file output akhir (tanpa membuat 2 file):
     * - signMode == 0 (Unsigned): build controller biasa tanpa Tag 39.
     * - signMode == 1 (Self-Signed): rebuild langsung dengan Auto RSA 1024-bit.
     * - signMode == 2 (Custom .CER + .KEY): rebuild langsung dengan file .CER & .KEY yang dipilih.
     */
	

    private static byte[] buildControllerBodyWithoutDataIndex(
		SisPackageInfo info,
		List<SisFileEntry> regularFiles,
		List<EmbeddedUnitBundle> embeddedBundles,
		SisProjectConfig config) throws Exception {

        ByteArrayOutputStream ctrl = new ByteArrayOutputStream();

        String appName = (info.appName != null && !info.appName.isEmpty()) ? info.appName : "S60App";
        String vendorName = (info.vendorName != null && !info.vendorName.isEmpty()) ? info.vendorName : "S60Reborn";

        // 1. FIELD_INFO (Tag 14)
        ByteArrayOutputStream infoBody = new ByteArrayOutputStream();
        infoBody.write(SisField.wrapTlv(9, SisField.intToLe(info.uid3))); // Tag 9 = FIELD_UID
        infoBody.write(SisField.wrapStringUtf16(vendorName));
        infoBody.write(SisField.wrapSingleStringArray(appName));
        infoBody.write(SisField.wrapSingleStringArray(vendorName));

        ByteBuffer ver = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        ver.putInt(info.majorVersion);
        ver.putInt(info.minorVersion);
        ver.putInt(info.buildVersion);
        infoBody.write(SisField.wrapTlv(4, ver.array())); // Tag 4 = FIELD_VERSION

        ByteArrayOutputStream dtBody = new ByteArrayOutputStream();
        ByteBuffer dateBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        dateBuf.putShort((short) 2026);
        dateBuf.put((byte) 11);
        dateBuf.put((byte) 18);
        dtBody.write(SisField.wrapTlv(6, dateBuf.array())); // Tag 6 = FIELD_DATE

        ByteBuffer timeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        timeBuf.put((byte) 12);
        timeBuf.put((byte) 0);
        timeBuf.put((byte) 0);
        timeBuf.put((byte) 0);
        dtBody.write(SisField.wrapTlv(7, timeBuf.array())); // Tag 7 = FIELD_TIME
        infoBody.write(SisField.wrapTlv(8, dtBody.toByteArray())); // Tag 8 = FIELD_DATE_TIME

        int installType = (config != null) ? Math.max(0, Math.min(4, config.installType)) : 0;
        int installFlags = 0;
        if (config != null) {
            if (config.flagShutdownApps) installFlags |= 0x01;
            if (config.flagRomUpgrade)   installFlags |= 0x02;
        }
        infoBody.write(installType & 0xFF);
        infoBody.write(installFlags & 0xFF);

        ctrl.write(SisField.wrapTlv(14, infoBody.toByteArray())); // Tag 14 = FIELD_INFO

        // 2. FIELD_SUPPORTED_OPTIONS (Tag 16)
        ByteArrayOutputStream optArr = new ByteArrayOutputStream();
        optArr.write(SisField.intToLe(33)); // Tag 33 = FIELD_SUPPORTED_OPTION
        if (config != null && config.installOptions != null) {
            for (SisProjectConfig.InstallOption opt : config.installOptions) {
                String label = (opt.label != null && !opt.label.trim().isEmpty()) ? opt.label.trim() : "Option";
                byte[] singleOptTlv = SisField.wrapSingleStringArray(label);
                optArr.write(SisField.intToLe(singleOptTlv.length));
                optArr.write(SisField.pad4(singleOptTlv));
            }
        }
        ctrl.write(SisField.wrapTlv(16, SisField.wrapTlv(2, optArr.toByteArray())));

        // 3. FIELD_SUPPORTED_LANGUAGES (Tag 15)
        ByteArrayOutputStream langArr = new ByteArrayOutputStream();
        langArr.write(SisField.intToLe(11));
        langArr.write(SisField.intToLe(4));
        langArr.write(SisField.intToLe(1)); // English = 1
        ctrl.write(SisField.wrapTlv(15, SisField.wrapTlv(2, langArr.toByteArray())));

		// 4. FIELD_PREREQUISITES (Tag 17)
        List<SisPackageInfo.DependencyItem> finalDevices = new ArrayList<SisPackageInfo.DependencyItem>(info.targetDevices);
        if (finalDevices.isEmpty()) {
            int targetPlatUid = (config != null && config.targetPlatformUid != 0) ? config.targetPlatformUid : 0x101F7961;
            finalDevices.add(new SisPackageInfo.DependencyItem(targetPlatUid, 0, 0, 0, "Series60ProductID"));
        }

        List<SisPackageInfo.DependencyItem> finalDeps = new ArrayList<SisPackageInfo.DependencyItem>(info.dependencies);
        if (finalDeps.isEmpty()) {
            // Auto-detect Qt & QtMobility dari binary .exe jika belum tertulis di .pkg
            boolean hasQt = false, hasQtMob = false;
            for (SisFileEntry f : regularFiles) {
                if (f.rawData != null && f.rawData.length > 0x9C) {
                    String scan = new String(f.rawData, Charset.forName("ISO-8859-1"));
                    if (scan.contains("QtCore.dll") || scan.contains("qts60main.dll")) hasQt = true;
                    if (scan.contains("QtMobility") || scan.contains("QtSystemInfo.dll") || scan.contains("QtMultimediaKit.dll")) hasQtMob = true;
                }
            }
            if (hasQt) finalDeps.add(new SisPackageInfo.DependencyItem(0x2001E61C, 4, 6, 0, "Qt"));
            if (hasQtMob) finalDeps.add(new SisPackageInfo.DependencyItem(0x2002AC89, 1, 0, 0, "QtMobility"));
        }

        ByteArrayOutputStream targetDevArr = new ByteArrayOutputStream();
        targetDevArr.write(SisField.intToLe(18));
        for (SisPackageInfo.DependencyItem dev : finalDevices) {
            byte[] dBytes = buildDependencyTlv(dev);
            targetDevArr.write(SisField.intToLe(dBytes.length));
            targetDevArr.write(SisField.pad4(dBytes));
        }

        ByteArrayOutputStream depArr = new ByteArrayOutputStream();
        depArr.write(SisField.intToLe(18));
        for (SisPackageInfo.DependencyItem dep : finalDeps) {
            byte[] dBytes = buildDependencyTlv(dep);
            depArr.write(SisField.intToLe(dBytes.length));
            depArr.write(SisField.pad4(dBytes));
        }

        ByteArrayOutputStream prereqBody = new ByteArrayOutputStream();
        prereqBody.write(SisField.wrapTlv(2, targetDevArr.toByteArray()));
        prereqBody.write(SisField.wrapTlv(2, depArr.toByteArray()));
        ctrl.write(SisField.wrapTlv(17, prereqBody.toByteArray()));
		

        // 5. FIELD_PROPERTIES (Tag 19)
        ctrl.write(SisField.wrapTlv(19, SisField.wrapTlv(2, buildEmptyArrayPayload(20))));

        // 6. FIELD_INSTALL_BLOCK (Tag 28)
        List<SisFileEntry> unconditionalFiles = new ArrayList<SisFileEntry>();
        Map<String, List<SisFileEntry>> conditionalFilesMap = new LinkedHashMap<String, List<SisFileEntry>>();

        for (int i = 0; i < regularFiles.size(); i++) {
            SisFileEntry f = regularFiles.get(i);
            f.fileIndex = i;
            if (f.ifCondition != null && !f.ifCondition.trim().isEmpty() && !"1".equals(f.ifCondition.trim())) {
                String cond = f.ifCondition.trim();
                if (!conditionalFilesMap.containsKey(cond)) {
                    conditionalFilesMap.put(cond, new ArrayList<SisFileEntry>());
                }
                conditionalFilesMap.get(cond).add(f);
            } else {
                unconditionalFiles.add(f);
            }
        }

        // Array 1: SISFileDescription[] (Tag 24)
        ByteArrayOutputStream filesArr = new ByteArrayOutputStream();
        filesArr.write(SisField.intToLe(24));
        for (SisFileEntry f : unconditionalFiles) {
            byte[] fdBytes = buildFullFileDescription(f, f.fileIndex, config);
            filesArr.write(SisField.intToLe(fdBytes.length));
            filesArr.write(SisField.pad4(fdBytes));
        }

        // Array 2: SISIf[] (Tag 26)
        ByteArrayOutputStream ifArr = new ByteArrayOutputStream();
        ifArr.write(SisField.intToLe(26));
        for (Map.Entry<String, List<SisFileEntry>> entry : conditionalFilesMap.entrySet()) {
            byte[] ifBlockBytes = buildSingleIfBlock(entry.getKey(), entry.getValue(), null, config);
            ifArr.write(SisField.intToLe(ifBlockBytes.length));
            ifArr.write(SisField.pad4(ifBlockBytes));
        }

        // Array 3: Embedded SISController[] (Tag 13)
        ByteArrayOutputStream unconditionalEmbedArr = new ByteArrayOutputStream();
        unconditionalEmbedArr.write(SisField.intToLe(13));
        for (EmbeddedUnitBundle emb : embeddedBundles) {
            if (emb.ifCondition != null && !emb.ifCondition.trim().isEmpty() && !"1".equals(emb.ifCondition.trim())) {
                byte[] ifBlockBytes = buildSingleIfBlock(emb.ifCondition.trim(), null, emb.rawSubController, config);
                ifArr.write(SisField.intToLe(ifBlockBytes.length));
                ifArr.write(SisField.pad4(ifBlockBytes));
            } else {
                unconditionalEmbedArr.write(SisField.intToLe(emb.rawSubController.length));
                unconditionalEmbedArr.write(SisField.pad4(emb.rawSubController));
            }
        }

        ByteArrayOutputStream installBody = new ByteArrayOutputStream();
        installBody.write(SisField.wrapTlv(2, filesArr.toByteArray()));             // 1. Files (Tag 24)
        installBody.write(SisField.wrapTlv(2, unconditionalEmbedArr.toByteArray())); // 2. Controllers (Tag 13)
        installBody.write(SisField.wrapTlv(2, ifArr.toByteArray()));                // 3. Ifs (Tag 26)

        ctrl.write(SisField.wrapTlv(28, installBody.toByteArray()));
        return ctrl.toByteArray();
    }
	
	private static class DependencyItem {
        int uid;
        int major, minor, build;
        String name;

        DependencyItem(int uid, int major, int minor, int build, String name) {
            this.uid = uid;
            this.major = major;
            this.minor = minor;
            this.build = build;
            this.name = name;
        }
    }

	private static byte[] buildDependencyTlv(SisPackageInfo.DependencyItem item) throws IOException {
        ByteArrayOutputStream depBody = new ByteArrayOutputStream();

        // 1. FIELD_UID (Tag 9)
        depBody.write(SisField.wrapTlv(9, SisField.intToLe(item.uid)));

        // 2. FIELD_VERSION_RANGE (Tag 5) -> berisi FIELD_VERSION (Tag 4)
        ByteBuffer vFrom = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(item.major)
            .putInt(item.minor)
            .putInt(item.build);
        ByteArrayOutputStream vRange = new ByteArrayOutputStream();
        vRange.write(SisField.wrapTlv(4, vFrom.array()));
        depBody.write(SisField.wrapTlv(5, vRange.toByteArray()));

        // 3. Nama Dependency / ProductID (Array of UTF-16LE String)
        String depName = (item.name != null && !item.name.isEmpty()) ? item.name : "Dependency";
        depBody.write(SisField.wrapSingleStringArray(depName));

        return depBody.toByteArray();
    }
	

    private static boolean hasValidCustomCert(SisProjectConfig config) {
        if (config == null || config.signMode != 2) return false;
        if (config.customCerPath == null || config.customCerPath.trim().isEmpty()) return false;
        if (config.customKeyPath == null || config.customKeyPath.trim().isEmpty()) return false;
        File cer = new File(config.customCerPath);
        File key = new File(config.customKeyPath);
        return cer.exists() && cer.isFile() && key.exists() && key.isFile();
    }

    private static byte[] buildAndSignControllerTlv(
        SisPackageInfo info,
        List<SisFileEntry> regularFiles,
        List<EmbeddedUnitBundle> embeddedBundles,
        SisProjectConfig config) throws Exception {

        byte[] unsignedBody = buildControllerBodyWithoutDataIndex(info, regularFiles, embeddedBundles, config);
        byte[] dataIndexTlv = SisField.wrapTlv(40, SisField.intToLe(0)); // Tag 40 = FIELD_DATA_INDEX

        int signMode = (config != null) ? config.signMode : 0;

        // Mode 0: Unsigned -> langsung tulis biasa tanpa signature
        if (signMode == 0) {
            ByteArrayOutputStream fullBody = new ByteArrayOutputStream();
            fullBody.write(unsignedBody);
            fullBody.write(dataIndexTlv);
            return SisField.wrapTlv(SisField.FIELD_CONTROLLER, fullBody.toByteArray());
        }

        byte[] derCer;
        PrivateKey privateKey;

        if (hasValidCustomCert(config)) {
            File customCerFile = new File(config.customCerPath);
            File customKeyFile = new File(config.customKeyPath);
            derCer = decodePemIfNeeded(readAllBytes(customCerFile));
            byte[] pkcs8Key = decodePemIfNeeded(readAllBytes(customKeyFile));
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Key));
        } else {
            // Mode 1 (Self-Signed) atau fallback jika file .cer/.key kosong
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(1024);
            KeyPair kp = kpg.generateKeyPair();
            privateKey = kp.getPrivate();
            derCer = generateSelfSignedX509Der(kp.getPublic(), privateKey);
        }

        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(privateKey);
        signer.update(unsignedBody);
        byte[] sigBytes = signer.sign();

        byte[] certChainTlv = buildSignatureCertChainTlv(sigBytes, derCer);

        ByteArrayOutputStream signedBody = new ByteArrayOutputStream();
        signedBody.write(unsignedBody);
        signedBody.write(certChainTlv);
        signedBody.write(dataIndexTlv);

        return SisField.wrapTlv(SisField.FIELD_CONTROLLER, signedBody.toByteArray());
    }
	

    private static byte[] buildSingleIfBlock(
		String conditionStr,
		List<SisFileEntry> condFiles,
		byte[] subController,
		SisProjectConfig config) throws Exception {
        ByteArrayOutputStream ifBody = new ByteArrayOutputStream();

        byte[] exprPayload = encodePkgConditionToExpression(conditionStr);
        ifBody.write(SisField.wrapTlv(23, exprPayload));

        ByteArrayOutputStream subFilesArr = new ByteArrayOutputStream();
        subFilesArr.write(SisField.intToLe(24));
        if (condFiles != null) {
            for (SisFileEntry f : condFiles) {
                byte[] fdBytes = buildFullFileDescription(f, f.fileIndex, config);
                subFilesArr.write(SisField.intToLe(fdBytes.length));
                subFilesArr.write(SisField.pad4(fdBytes));
            }
        }

        ByteArrayOutputStream subCtrlArr = new ByteArrayOutputStream();
        subCtrlArr.write(SisField.intToLe(13));
        if (subController != null) {
            subCtrlArr.write(SisField.intToLe(subController.length));
            subCtrlArr.write(SisField.pad4(subController));
        }

        ByteArrayOutputStream subInstallBody = new ByteArrayOutputStream();
        subInstallBody.write(SisField.wrapTlv(2, subFilesArr.toByteArray()));
        subInstallBody.write(SisField.wrapTlv(2, subCtrlArr.toByteArray()));
        subInstallBody.write(SisField.wrapTlv(2, buildEmptyArrayPayload(26)));

        ifBody.write(SisField.wrapTlv(28, subInstallBody.toByteArray()));
        ifBody.write(SisField.wrapTlv(2, buildEmptyArrayPayload(27)));
        return ifBody.toByteArray();
    }

    /**
     * Membangun SISFileDescription (Tag 24):
     * - Jika rebuildWithCert == true atau Custom CER aktif (signMode == 2):
     *   TIDAK mem-patch file E32, menjaga capability & biner .exe/.dll 100% asli.
     * - Jika Self-Signed otomatis tanpa rebuildWithCert:
     *   menyelaraskan capability E32 ke batas aman Self-Signed melalui E32ImagePatcher.patchE32File.
     */
    private static byte[] buildFullFileDescription(
		SisFileEntry f, int fileIndex, SisProjectConfig config) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] raw = (f.rawData != null) ? f.rawData : new byte[0];

        String dst = (f.targetPath != null ? f.targetPath : "C:\\Data\\file.bin").replace('/', '\\').trim();
        bos.write(SisField.wrapStringUtf16(dst));
        bos.write(SisField.wrapStringUtf16("")); // MimeType kosong

        boolean isE32Binary = raw.length > 0x9C
			&& raw[0x10] == 'E' && raw[0x11] == 'P'
			&& raw[0x12] == 'O' && raw[0x13] == 'C';

		if (isE32Binary) {
            int caps = E32ImagePatcher.inspectE32Bytes(raw).capabilities;

            // Jika Custom .CER + .KEY aktif (signMode == 2), pertahankan capability .exe asli.
            // Jika Unsigned (0) atau Self-Signed (1), pangkas ke batas aman agar tidak ditolak SWInstall.
            if (!hasValidCustomCert(config)) {
                int safeCaps = caps & E32ImagePatcher.MASK_SELF_SIGNED_SAFE;
                if (safeCaps != caps) {
                    raw = E32ImagePatcher.patchE32Bytes(raw, safeCaps);
                    f.rawData = raw;
                    caps = safeCaps;
                }
            }
            bos.write(SisField.wrapTlv(41, SisField.intToLe(caps))); // Tag 41 = FIELD_CAPABILITIES
        }
		
		

        // Hash SHA-1 atas raw data final
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(raw);
        ByteArrayOutputStream hashBody = new ByteArrayOutputStream();
        hashBody.write(SisField.intToLe(1)); // EHashAlgSHA1
        hashBody.write(SisField.wrapTlv(SisField.FIELD_BLOB, digest));
        bos.write(SisField.wrapTlv(SisField.FIELD_HASH, hashBody.toByteArray()));

        byte[] compData = zlibCompress(raw);
        long compressedPayloadLen = 12L + compData.length;
        ByteBuffer tail = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        tail.putInt(1); // Operation: EOpInstall
        tail.putInt(0); // OperationOptions
        tail.putLong(compressedPayloadLen);
        tail.putLong(raw.length);
        tail.putInt(fileIndex);
        bos.write(tail.array());

        return bos.toByteArray();
    }

    private static byte[] buildMultiUnitDataBlock(
		List<SisFileEntry> regularFiles,
		List<EmbeddedUnitBundle> embeddedBundles) throws Exception {

        ByteArrayOutputStream unitsArr = new ByteArrayOutputStream();
        unitsArr.write(SisField.intToLe(SisField.FIELD_DATA_UNIT));

        ByteArrayOutputStream rootFilesArr = new ByteArrayOutputStream();
        rootFilesArr.write(SisField.intToLe(SisField.FIELD_FILE_DATA));
        for (SisFileEntry f : regularFiles) {
            byte[] raw = (f.rawData != null) ? f.rawData : new byte[0];
            byte[] compBlock = wrapAsCompressed(raw);
            rootFilesArr.write(SisField.intToLe(compBlock.length));
            rootFilesArr.write(SisField.pad4(compBlock));
        }
        ByteArrayOutputStream rootUnitBody = new ByteArrayOutputStream();
        rootUnitBody.write(SisField.wrapTlv(SisField.FIELD_ARRAY, rootFilesArr.toByteArray()));
        byte[] rootUnitBytes = rootUnitBody.toByteArray();

        unitsArr.write(SisField.intToLe(rootUnitBytes.length));
        unitsArr.write(SisField.pad4(rootUnitBytes));

        for (EmbeddedUnitBundle emb : embeddedBundles) {
            ByteArrayOutputStream embFilesArr = new ByteArrayOutputStream();
            embFilesArr.write(SisField.intToLe(SisField.FIELD_FILE_DATA));
            for (byte[] raw : emb.rawUnitFiles) {
                byte[] safeRaw = (raw != null) ? raw : new byte[0];
                byte[] compBlock = wrapAsCompressed(safeRaw);
                embFilesArr.write(SisField.intToLe(compBlock.length));
                embFilesArr.write(SisField.pad4(compBlock));
            }
            ByteArrayOutputStream embUnitBody = new ByteArrayOutputStream();
            embUnitBody.write(SisField.wrapTlv(SisField.FIELD_ARRAY, embFilesArr.toByteArray()));
            byte[] embUnitBytes = embUnitBody.toByteArray();

            unitsArr.write(SisField.intToLe(embUnitBytes.length));
            unitsArr.write(SisField.pad4(embUnitBytes));
        }

        ByteArrayOutputStream dataBody = new ByteArrayOutputStream();
        dataBody.write(SisField.wrapTlv(SisField.FIELD_ARRAY, unitsArr.toByteArray()));
        return SisField.wrapTlv(SisField.FIELD_DATA, dataBody.toByteArray());
    }

    // =========================================================================
    // HELPER SIGNING X.509 & CHECKSUM SYMBIAN (TERINTEGRASI LANGSUNG)
    // =========================================================================

    private static short calculateSymbianCrc16(byte[] data) {
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
        return (short) (crc & 0xFFFF);
    }

    private static byte[] buildSignatureCertChainTlv(byte[] sigBytes, byte[] cerBytes) throws IOException {
        byte[] oidTlv = SisField.wrapStringUtf16("1.2.840.113549.1.1.5");
        byte[] algTlv = SisField.wrapTlv(SisField.FIELD_SIGNATURE_ALGORITHM, oidTlv);

        ByteArrayOutputStream sigItemBody = new ByteArrayOutputStream();
        sigItemBody.write(algTlv);
        sigItemBody.write(SisField.wrapTlv(SisField.FIELD_BLOB, sigBytes));
        byte[] sigItemBytes = sigItemBody.toByteArray();

        ByteArrayOutputStream sigArrBody = new ByteArrayOutputStream();
        sigArrBody.write(SisField.intToLe(SisField.FIELD_SIGNATURE));
        sigArrBody.write(SisField.intToLe(sigItemBytes.length));
        sigArrBody.write(SisField.pad4(sigItemBytes));
        byte[] sigArrayTlv = SisField.wrapTlv(SisField.FIELD_ARRAY, sigArrBody.toByteArray());

        ByteArrayOutputStream certArrBody = new ByteArrayOutputStream();
        certArrBody.write(SisField.intToLe(SisField.FIELD_BLOB));
        certArrBody.write(SisField.intToLe(cerBytes.length));
        certArrBody.write(SisField.pad4(cerBytes));
        byte[] certArrayTlv = SisField.wrapTlv(SisField.FIELD_ARRAY, certArrBody.toByteArray());
        byte[] certChainTlv = SisField.wrapTlv(SisField.FIELD_CERTIFICATE_CHAIN, certArrayTlv);

        ByteArrayOutputStream rootBody = new ByteArrayOutputStream();
        rootBody.write(sigArrayTlv);
        rootBody.write(certChainTlv);
        return SisField.wrapTlv(SisField.FIELD_SIGNATURE_CERT_CHAIN, rootBody.toByteArray());
    }

    private static byte[] generateSelfSignedX509Der(PublicKey pubKey, PrivateKey privKey) throws Exception {
        byte[] serial = new byte[]{ 0x02, 0x01, 0x01 };
        byte[] algId = new byte[]{
            0x30, 0x0D,
            0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x05,
            0x05, 0x00
        };
        byte[] cnName = new byte[]{
            0x30, 0x14, 0x31, 0x12, 0x30, 0x10,
            0x06, 0x03, 0x55, 0x04, 0x03,
            0x0C, 0x09, 'S', '6', '0', 'R', 'e', 'b', 'o', 'r', 'n'
        };
        byte[] validity = new byte[]{
            0x30, 0x1E,
            0x17, 0x0D, '2', '0', '0', '1', '0', '1', '0', '0', '0', '0', '0', '0', 'Z',
            0x17, 0x0D, '4', '9', '1', '2', '3', '1', '2', '3', '5', '9', '5', '9', 'Z'
        };
        byte[] spki = pubKey.getEncoded();

        ByteArrayOutputStream tbsContent = new ByteArrayOutputStream();
        tbsContent.write(serial);
        tbsContent.write(algId);
        tbsContent.write(cnName);
        tbsContent.write(validity);
        tbsContent.write(cnName);
        tbsContent.write(spki);
        byte[] tbsCertificate = wrapAsn1Tag(0x30, tbsContent.toByteArray());

        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(privKey);
        sig.update(tbsCertificate);
        byte[] signatureBytes = sig.sign();

        ByteArrayOutputStream bitStrContent = new ByteArrayOutputStream();
        bitStrContent.write(0x00);
        bitStrContent.write(signatureBytes);
        byte[] sigBitString = wrapAsn1Tag(0x03, bitStrContent.toByteArray());

        ByteArrayOutputStream certContent = new ByteArrayOutputStream();
        certContent.write(tbsCertificate);
        certContent.write(algId);
        certContent.write(sigBitString);
        return wrapAsn1Tag(0x30, certContent.toByteArray());
    }

    private static byte[] wrapAsn1Tag(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] decodePemIfNeeded(byte[] raw) {
        String text = new String(raw, StandardCharsets.US_ASCII).trim();
        if (text.startsWith("-----BEGIN")) {
            String base64 = text
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
            return Base64.decode(base64, Base64.DEFAULT);
        }
        return raw;
    }

    // =========================================================================
    // HELPER EMBEDDED SIS, EXPRESSIONS & LEGACY S60v1/v2
    // =========================================================================

    private static EmbeddedUnitBundle extractBundleFromStandaloneSisx(byte[] sisxBytes, int targetUnitIndex, String condition) {
        try {
            ByteBuffer b = ByteBuffer.wrap(sisxBytes).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != SisField.SISX_UID1_MAGIC) return null;
            b.position(16);
            int cTag = b.getInt();
            long cLen = readLen(b);
            int cEnd = (int) Math.min(sisxBytes.length, b.position() + cLen);
            if (cTag != SisField.FIELD_CONTENTS) return null;

            byte[] subCtrl = null;
            List<byte[]> subFiles = new ArrayList<byte[]>();

            while (b.position() + 8 <= cEnd) {
                int tag = b.getInt();
                long len = readLen(b);
                int start = b.position();
                int end = (int) Math.min(cEnd, start + len);
                if (len < 0) break;

                if (tag == SisField.FIELD_CONTROLLER) {
                    byte[] raw = copySlice(sisxBytes, start, (int) len);
                    subCtrl = unwrapIfCompressedOrController(raw);
                } else if (tag == SisField.FIELD_COMPRESSED) {
                    byte[] raw = copySlice(sisxBytes, start - 8, (int) len + 8);
                    subCtrl = unwrapIfCompressedOrController(raw);
                } else if (tag == SisField.FIELD_DATA || tag == SisField.FIELD_DATA_UNIT) {
                    extractRawFilesFromDataRegion(sisxBytes, start, (int) len, subFiles);
                }

                b.position(end);
                align4(b);
            }

            if (subCtrl == null) return null;

            EmbeddedUnitBundle bundle = new EmbeddedUnitBundle();
            bundle.rawSubController = setDataIndexInController(subCtrl, targetUnitIndex);
            bundle.rawUnitFiles = subFiles;
            bundle.ifCondition = condition;
            return bundle;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] unwrapIfCompressedOrController(byte[] block) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        int tag = b.getInt(0);
        if (tag == SisField.FIELD_COMPRESSED) {
            b.position(4);
            long len = readLen(b);
            int alg = b.getInt();
            long uncomp = b.getLong();
            int start = b.position();
            int compLen = (int) Math.min(block.length - start, len - 12);
            byte[] inflated = (alg == 0) ? copySlice(block, start, compLen) : zlibInflate(block, start, compLen, (int) uncomp);
            return inflated != null ? unwrapIfCompressedOrController(inflated) : null;
        } else if (tag == SisField.FIELD_CONTROLLER) {
            b.position(4);
            long len = readLen(b);
            int start = b.position();
            if (start + len <= block.length) {
                return copySlice(block, start, (int) len);
            }
        }
        return block;
    }

    private static void extractRawFilesFromDataRegion(byte[] src, int offset, int length, List<byte[]> outFiles) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(src, offset, length).order(ByteOrder.LITTLE_ENDIAN);
        int end = offset + length;
        while (b.position() + 8 <= end) {
            int tag = b.getInt();
            long len = readLen(b);
            int start = b.position();
            if (len < 0 || start + len > end) break;

            if (tag == SisField.FIELD_ARRAY) {
                int elemType = b.getInt();
                int arrEnd = start + (int) len;
                if (elemType == SisField.FIELD_FILE_DATA) {
                    while (b.position() + 4 <= arrEnd) {
                        long fLen = readLen(b);
                        int fStart = b.position();
                        if (fLen < 0 || fStart + fLen > arrEnd) break;
                        byte[] fdBlock = copySlice(src, fStart, (int) fLen);
                        byte[] raw = unwrapIfCompressedOrController(fdBlock);
                        outFiles.add(raw != null ? raw : new byte[0]);
                        b.position(fStart + (int) fLen);
                        align4(b);
                    }
                } else {
                    extractRawFilesFromDataRegion(src, start + 4, (int) len - 4, outFiles);
                }
            } else if (tag == SisField.FIELD_DATA_UNIT) {
                extractRawFilesFromDataRegion(src, start, (int) len, outFiles);
            }
            b.position(start + (int) len);
            align4(b);
        }
    }

    private static byte[] setDataIndexInController(byte[] rawCtrl, int newIndex) {
        byte[] copy = rawCtrl.clone();
        ByteBuffer b = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 8) {
            int tag = b.getInt();
            long len = readLen(b);
            int start = b.position();
            if (len < 0 || start + len > copy.length) break;
            if (tag == SisField.FIELD_DATA_INDEX && len >= 4) {
                b.putInt(start, newIndex);
            }
            b.position(start + (int) len);
            align4(b);
        }
        return copy;
    }

    private static byte[] encodePkgConditionToExpression(String cond) throws IOException {
        String c = cond.trim();

        if (c.toLowerCase().startsWith("option")) {
            int optIdx = 0;
            try {
                optIdx = Math.max(0, Integer.parseInt(c.substring(6).trim()) - 1);
            } catch (Exception ignored) {}
            ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            hdr.putInt(14);
            hdr.putInt(optIdx);
            return hdr.array();
        }

        if (c.toUpperCase().startsWith("NOT EXISTS")) {
            int q1 = c.indexOf('"');
            int q2 = c.lastIndexOf('"');
            String path = (q1 >= 0 && q2 > q1) ? c.substring(q1 + 1, q2) : "";

            ByteArrayOutputStream existsExpr = new ByteArrayOutputStream();
            ByteBuffer exHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            exHdr.putInt(10);
            exHdr.putInt(0);
            existsExpr.write(exHdr.array());
            existsExpr.write(SisField.wrapStringUtf16(path.replace('/', '\\')));

            ByteArrayOutputStream notExpr = new ByteArrayOutputStream();
            ByteBuffer notHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            notHdr.putInt(9);
            notHdr.putInt(0);
            notExpr.write(notHdr.array());
            notExpr.write(SisField.wrapTlv(23, existsExpr.toByteArray()));
            return notExpr.toByteArray();
        }

        if (c.toUpperCase().startsWith("PACKAGE")) {
            int p1 = c.indexOf('(');
            int p2 = c.lastIndexOf(')');
            int pkgUid = 0;
            if (p1 >= 0 && p2 > p1) {
                try {
                    pkgUid = parseUidInt(c.substring(p1 + 1, p2));
                } catch (Exception ignored) {}
            }
            ByteArrayOutputStream numExpr = new ByteArrayOutputStream();
            ByteBuffer numHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            numHdr.putInt(16);
            numHdr.putInt(pkgUid);
            numExpr.write(numHdr.array());

            ByteArrayOutputStream pkgExpr = new ByteArrayOutputStream();
            ByteBuffer pkgHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            pkgHdr.putInt(13);
            pkgHdr.putInt(pkgUid);
            pkgExpr.write(pkgHdr.array());
            pkgExpr.write(SisField.wrapTlv(23, numExpr.toByteArray()));
            return pkgExpr.toByteArray();
        }

        ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(16);
        hdr.putInt(1);
        return hdr.array();
    }

    public static void packLegacySis(SisPackageInfo info, List<SisFileEntry> files, File outputFile) throws Exception {
        int numFiles = files.size();
        byte[] appNameUtf16 = (info.appName != null ? info.appName : "App").getBytes(UTF16LE);

        List<byte[]> dstPathsUtf16 = new ArrayList<byte[]>();
        List<byte[]> compBlobs = new ArrayList<byte[]>();

        for (SisFileEntry f : files) {
            String dst = (f.targetPath != null ? f.targetPath : "C:\\Data\\file.bin").replace('/', '\\');
            dstPathsUtf16.add(dst.getBytes(UTF16LE));
            byte[] raw = f.rawData != null ? f.rawData : new byte[0];
            compBlobs.add(zlibCompress(raw));
        }

        int cursor = 64;
        int ptrLangs = cursor; cursor += 2;
        int ptrCompNames = cursor; cursor += 8;
        int offAppName = cursor; cursor += appNameUtf16.length;
        int ptrFiles = cursor; cursor += numFiles * 40;

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
            out.putInt(0); out.putInt(0); out.putInt(0); out.putInt(0); out.putInt(0);
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

    private static byte[] buildEmptyArrayPayload(int elemTag) {
        return SisField.intToLe(elemTag);
    }

    private static byte[] wrapAsCompressed(byte[] rawPayload) throws IOException {
        byte[] compressedData = zlibCompress(rawPayload);
        return SisField.buildCompressedTlv(1, rawPayload.length, compressedData);
    }

    private static byte[] zlibCompress(byte[] raw) {
        return SisField.deflateBytes(raw);
    }

    private static byte[] zlibInflate(byte[] src, int offset, int length, int uncompHint) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(src, offset, length);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(uncompHint > 0 ? uncompHint : length * 3);
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n > 0) bos.write(buf, 0, n);
                else if (inf.needsInput() || inf.needsDictionary()) break;
            }
            inf.end();
            return bos.size() > 0 ? bos.toByteArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static long readLen(ByteBuffer buf) {
        if (buf.remaining() < 4) return -1;
        long len = buf.getInt() & 0xFFFFFFFFL;
        if ((len & 0x80000000L) != 0) {
            if (buf.remaining() < 4) return -1;
            long high = buf.getInt() & 0xFFFFFFFFL;
            len = ((len & 0x7FFFFFFFL) << 32) | high;
        }
        return len;
    }

    private static void align4(ByteBuffer buf) {
        int mod = buf.position() % 4;
        if (mod != 0 && buf.remaining() >= (4 - mod)) {
            buf.position(buf.position() + (4 - mod));
        }
    }

    private static byte[] copySlice(byte[] src, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
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

