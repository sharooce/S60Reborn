package com.s60reborn.app.core;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;
import com.s60reborn.app.model.SisProjectConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

public class SisLegacyParser {

    public static SisPackageInfo inspect(File sisFile) throws Exception {
        return parse(readAllBytes(sisFile), null);
    }

    public static SisPackageInfo unpack(File sisFile, File outputDir) throws Exception {
        if (outputDir != null && !outputDir.exists()) outputDir.mkdirs();
        return parse(readAllBytes(sisFile), outputDir);
    }

    public static SisPackageInfo parse(byte[] fileBytes, File outputDir) throws Exception {
        if (fileBytes == null || fileBytes.length < LegacySisField.HEADER_SIZE) {
            throw new IOException("Header SIS Legacy tidak valid (< 64 byte).");
        }

        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        SisPackageInfo info = new SisPackageInfo();
        info.uid1 = buf.getInt(LegacySisField.OFF_UID1);
        info.uid2 = buf.getInt(LegacySisField.OFF_UID2);

        boolean isExtendedV2 = (info.uid1 == LegacySisField.UID1_EPOC_V2);
        int shift = LegacySisField.getHeaderShift(info.uid1);

        info.formatLabel = isExtendedV2
            ? "Legacy SIS v2 (S60v2 / Symbian 7-8)"
            : "Legacy SIS v1 (S60v1 / Symbian 6)";

        info.uid3 = isExtendedV2
            ? buf.getInt(LegacySisField.OFF_UID3_V2)
            : buf.getInt(LegacySisField.OFF_UID3_V1);
        info.uidChecksum = buf.getInt(LegacySisField.OFF_UID_CHECKSUM);

        int numLangs = buf.getShort(LegacySisField.OFF_NUM_LANGS) & 0xFFFF;
        int numFiles = buf.getShort(LegacySisField.OFF_NUM_FILES) & 0xFFFF;
        int numReqs  = buf.getShort(LegacySisField.OFF_NUM_REQS) & 0xFFFF;
        int rawInstallType = buf.get(LegacySisField.OFF_INSTALL_TYPE) & 0xFF;
        int options  = buf.getShort(LegacySisField.OFF_OPTIONS) & 0xFFFF;
        info.majorVersion = buf.getShort(LegacySisField.OFF_MAJOR_VER) & 0xFFFF;
        info.minorVersion = buf.getShort(LegacySisField.OFF_MINOR_VER) & 0xFFFF;
        info.buildVersion = buf.getShort(LegacySisField.OFF_BUILD_VER) & 0xFFFF;

        boolean isUncompressed = (options & LegacySisField.OPT_NO_COMPRESS) != 0;

        if (rawInstallType == LegacySisField.TYPE_PU) info.installType = 2;
        else if (rawInstallType == LegacySisField.TYPE_PA) info.installType = 3;
        else info.installType = 0;

        int flags = 0;
        if ((options & LegacySisField.OPT_SHUTDOWN_APPS) != 0) flags |= 0x01;
        if ((options & LegacySisField.OPT_NON_REMOVABLE) != 0) flags |= 0x02;
        info.installFlags = flags;

        int ptrFiles     = buf.getInt(LegacySisField.OFF_PTR_FILES_BASE + shift);
        int ptrReqs      = buf.getInt(LegacySisField.OFF_PTR_REQS_BASE + shift);
        int ptrCompNames = buf.getInt(LegacySisField.OFF_PTR_COMP_BASE + shift);
        int langCountSafe = Math.max(1, numLangs);

        // 1. Component Name (App Name)
        if (ptrCompNames > 0 && ptrCompNames + (8 * langCountSafe) <= fileBytes.length) {
            int nameLen = buf.getInt(ptrCompNames);
            int nameOff = buf.getInt(ptrCompNames + (4 * langCountSafe));
            String parsedApp = readEpocString(fileBytes, nameOff, nameLen);
            if (!parsedApp.isEmpty()) info.appName = parsedApp;
        }

        // 2. Vendor Name (jika tersedia di extended header)
        if (isExtendedV2 && fileBytes.length >= 0x58) {
            int ptrVendor = buf.getInt(0x54);
            if (ptrVendor <= 0 || ptrVendor == ptrCompNames) ptrVendor = buf.getInt(0x4C);
            if (ptrVendor > 0 && ptrVendor != ptrCompNames && ptrVendor + (8 * langCountSafe) <= fileBytes.length) {
                int vLen = buf.getInt(ptrVendor);
                int vOff = buf.getInt(ptrVendor + (4 * langCountSafe));
                String parsedVendor = readEpocString(fileBytes, vOff, vLen);
                if (!parsedVendor.isEmpty()) info.vendorName = parsedVendor;
            }
        }

        if (info.appName == null || info.appName.trim().isEmpty() || "UnknownApp".equals(info.appName)) {
            info.appName = "LegacyS60App";
        }
        if (info.vendorName == null || info.vendorName.trim().isEmpty() || "UnknownVendor".equals(info.vendorName)) {
            info.vendorName = "SymbianDev";
        }

        // 3. Prerequisites / Target Device (Series60ProductID dll.)
		// 3. Prerequisites / Target Device (Series60ProductID dll.)
        if (ptrReqs > 0 && numReqs > 0 && ptrReqs < fileBytes.length) {
            int rcur = ptrReqs;
            for (int r = 0; r < numReqs; r++) {
                int recLen = 12 + (8 * langCountSafe);
                if (rcur + recLen > fileBytes.length) break;
                int rUid = buf.getInt(rcur);
                int rMaj = buf.getShort(rcur + 4) & 0xFFFF;
                int rMin = buf.getShort(rcur + 6) & 0xFFFF;
                int rBld = buf.getInt(rcur + 8);
                int rNameLen = buf.getInt(rcur + 12);
                int rNameOff = buf.getInt(rcur + 12 + (4 * langCountSafe));
                String rName = readEpocString(fileBytes, rNameOff, rNameLen);
                if (rName.isEmpty()) rName = "Series60ProductID";

                SisPackageInfo.DependencyItem dep = new SisPackageInfo.DependencyItem(rUid,rMaj,rMin,rBld,rName);

                if (rName.contains("ProductID") || rName.contains("Series60") || rName.contains("UIQ")) {
                    info.targetDevices.add(dep);
                } else {
                    info.dependencies.add(dep);
                }
                rcur += recLen;
            }
        }
		

        // 4. File Records mulai tepat di ptrFiles
        if (ptrFiles > 0 && numFiles > 0 && ptrFiles < fileBytes.length) {
            parseFileRecords(buf, fileBytes, ptrFiles, numFiles, langCountSafe, isExtendedV2, isUncompressed, info, outputDir);
        }

        // 5. Simpan project.pkg & project_config.ini saat mode Unpack
        if (outputDir != null) {
            SisxCoreParser.generatePkgScript(info, new File(outputDir, "project.pkg"));
            SisProjectConfig legacyCfg = new SisProjectConfig();
            SisProjectConfig.syncConfigFromUnpackedInfo(info, legacyCfg);
            legacyCfg.packageFormat = 1; // SIS Legacy (S60v1 / S60v2)
            legacyCfg.signMode = 0;      // Tanpa sertifikat
            legacyCfg.saveToFile(outputDir);
        }

        return info;
    }
	
	private static String extractCertCommonName(byte[] data, int start, int end) {
        // OID X.509 Common Name (2.5.4.3) dalam DER: 06 03 55 04 03, diikuti tag string (13/0C/14/16) + length
        for (int i = start; i + 7 < end; i++) {
            if (data[i] == 0x06 && data[i + 1] == 0x03
				&& data[i + 2] == 0x55 && data[i + 3] == 0x04 && data[i + 4] == 0x03) {
                int strLen = data[i + 6] & 0xFF;
                int strOff = i + 7;
                if (strLen > 0 && strLen < 64 && strOff + strLen <= end) {
                    String cn = new String(data, strOff, strLen, StandardCharsets.UTF_8).trim();
                    if (!cn.isEmpty()) return cn;
                }
            }
        }
        return "";
    }
	

    private static int parseFileRecords(
		ByteBuffer buf, byte[] fileBytes, int cursor, int count, int numLangs,
		boolean isExtendedV2, boolean isUncompressed,
		SisPackageInfo info, File outputDir) throws Exception {

        int processed = 0;
        while (processed < count && cursor + 8 <= fileBytes.length) {
            int recType, fTypeField, fDetails, pos;
            if (isExtendedV2) {
                recType    = buf.getShort(cursor) & 0xFFFF;
                fTypeField = buf.getShort(cursor + 2) & 0xFFFF;
                fDetails   = buf.getInt(cursor + 4);
                pos        = cursor + 8;
            } else {
                recType    = buf.getInt(cursor);
                fTypeField = buf.getInt(cursor + 4);
                fDetails   = buf.getInt(cursor + 8);
                pos        = cursor + 12;
            }

            if (recType == LegacySisField.REC_SINGLE_LANG_FILE
				|| recType == LegacySisField.REC_MULTI_LANG_FILE) {

                int langCount = (recType == LegacySisField.REC_MULTI_LANG_FILE) ? numLangs : 1;
                int minNeed = (isExtendedV2 ? 4 : 0) + 16 + (12 * langCount) + (isExtendedV2 ? 8 : 0);
                if (pos + minNeed > fileBytes.length) break;

                int fileFlags = 0;
                if (isExtendedV2) {
                    fileFlags = buf.getInt(pos);
                    pos += 4;
                }

                int srcNameLen = buf.getInt(pos);
                int srcNamePtr = buf.getInt(pos + 4);
                int dstNameLen = buf.getInt(pos + 8);
                int dstNamePtr = buf.getInt(pos + 12);
                pos += 16;

                int dataLen = buf.getInt(pos);
                int dataPtr = buf.getInt(pos + (4 * langCount));
                int origLen = buf.getInt(pos + (8 * langCount));
                pos += 12 * langCount;

                if (isExtendedV2) {
                    // mimeLen (4B) + mimePtr (4B) -> total record 48B
                    pos += 8;
                }

                // Pada S60v2, fDetails (0=FF, 1=FT, 2=Component, 3=FR, 4=FN) menentukan jenis operasi file
                int effectiveFileType = isExtendedV2 ? fDetails : fTypeField;
                String srcPath = readEpocString(fileBytes, srcNamePtr, srcNameLen);
                String dstPath = readEpocString(fileBytes, dstNamePtr, dstNameLen);

                if (effectiveFileType != LegacySisField.FILE_TYPE_NULL
					&& dataPtr > 0 && dataLen > 0 && dataPtr + dataLen <= fileBytes.length) {

                    boolean isSubSis = (effectiveFileType == LegacySisField.FILE_TYPE_COMPONENT);
                    String cleanApp = info.appName.replaceAll("[^a-zA-Z0-9_]", "");
                    if (cleanApp.isEmpty()) cleanApp = "LegacyApp";

                    if (dstPath.isEmpty()) {
                        // Untuk file teks pop-up (FT) atau tanpa path tujuan, ambil nama asli dari SrcPath
                        String baseName = extractFileName(srcPath);
                        if (baseName.isEmpty()) {
                            String ext = isSubSis ? ".sis"
                                : (effectiveFileType == LegacySisField.FILE_TYPE_TEXT ? ".txt" : ".bin");
                            baseName = "item_" + info.files.size() + ext;
                        }
                        dstPath = isSubSis
                            ? ("embedded_sis/" + baseName)
                            : ("!:\\System\\Apps\\" + cleanApp + "\\" + baseName);
                    }

                    SisFileEntry entry = new SisFileEntry();
                    entry.fileIndex = info.files.size();
                    entry.operation = effectiveFileType;
                    entry.operationOptions = fileFlags;
                    entry.isEmbeddedSis = isSubSis;
                    entry.targetPath = dstPath;
                    entry.localRelPath = sanitizePath(dstPath);
                    entry.compressedSize = dataLen;
                    entry.uncompressedSize = (origLen > 0) ? origLen : dataLen;

                    byte[] slice = new byte[dataLen];
                    System.arraycopy(fileBytes, dataPtr, slice, 0, dataLen);

                    if (origLen > 0 && origLen != dataLen) {
                        entry.rawData = inflateSafely(slice, origLen);
                    } else if (!isUncompressed && origLen > 0) {
                        entry.rawData = inflateSafely(slice, origLen);
                    } else {
                        entry.rawData = slice;
                    }

                    if (isSubSis && entry.rawData.length >= LegacySisField.HEADER_SIZE) {
                        ByteBuffer subHdr = ByteBuffer.wrap(entry.rawData).order(ByteOrder.LITTLE_ENDIAN);
                        int subUid1 = subHdr.getInt(LegacySisField.OFF_UID1);
                        entry.embeddedUid3 = (subUid1 == LegacySisField.UID1_EPOC_V2)
                            ? subHdr.getInt(LegacySisField.OFF_UID3_V2)
                            : subHdr.getInt(LegacySisField.OFF_UID3_V1);
                    }

                    info.files.add(entry);
                    if (outputDir != null) {
                        File outFile = new File(outputDir, entry.localRelPath);
                        if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
                            outFile.getParentFile().mkdirs();
                        }
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            fos.write(entry.rawData);
                        }
                    }
                }

                cursor = pos;
                processed++;

            } else if (recType == LegacySisField.REC_IF || recType == LegacySisField.REC_ELSE_IF) {
                if (pos + 4 > fileBytes.length) break;
                int exprLen = fDetails;
                int subRecords = buf.getInt(pos);
                cursor = pos + 4 + Math.max(0, exprLen);
                if (subRecords > 0) {
                    cursor = parseFileRecords(
                        buf, fileBytes, cursor, subRecords, numLangs, isExtendedV2, isUncompressed, info, outputDir);
                }
                processed++;

            } else if (recType == LegacySisField.REC_ELSE) {
                int subRecords = fDetails;
                cursor = pos;
                if (subRecords > 0) {
                    cursor = parseFileRecords(
                        buf, fileBytes, cursor, subRecords, numLangs, isExtendedV2, isUncompressed, info, outputDir);
                }
                processed++;

            } else if (recType == LegacySisField.REC_ENDIF) {
                cursor = pos;
                processed++;

            } else {
                break;
            }
        }
        return cursor;
    }

    private static String extractFileName(String path) {
        if (path == null || path.isEmpty()) return "";
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        return (slash >= 0) ? norm.substring(slash + 1).trim() : norm.trim();
    }

    private static String readEpocString(byte[] data, int offset, int byteLen) {
        if (offset <= 0 || byteLen <= 0 || offset + byteLen > data.length) return "";
        String raw = (byteLen >= 2 && (byteLen % 2 == 0))
            ? new String(data, offset, byteLen, StandardCharsets.UTF_16LE)
            : new String(data, offset, byteLen, StandardCharsets.ISO_8859_1);
        return raw.replace("\0", "").trim();
    }

    private static byte[] inflateSafely(byte[] slice, int origLen) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(slice);
            byte[] out = new byte[origLen];
            int n = inf.inflate(out);
            inf.end();
            if (n > 0) return out;
        } catch (Exception ignored) {}
        return slice;
    }

    private static String sanitizePath(String symbianPath) {
        String p = symbianPath.replace('\\', '/');
        if (p.length() >= 3 && p.charAt(1) == ':' && p.charAt(2) == '/') p = p.substring(3);
        else if (p.length() >= 2 && p.charAt(1) == ':') p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p.isEmpty() ? "unknown_file.bin" : p;
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

