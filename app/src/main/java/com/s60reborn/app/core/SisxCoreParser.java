package com.s60reborn.app.core;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class SisxCoreParser {

    // Magic UIDs
    private static final int SISX_UID1_MAGIC = 0x10201A7A;       // Symbian OS 9.x (.sisx / .sis S60v3/v5)
    private static final int LEGACY_SIS_UID2_MAGIC = 0x10000419; // Symbian OS 6/7/8 (.sis S60v1/v2)

    // Field Types SISX (OS 9.x)
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
    private static final int FIELD_CONTENTS = 12;   // 0x0C
    private static final int FIELD_LOGO = 15;
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
	

	public static SisPackageInfo inspectSisx(File sisFile) throws Exception {
        byte[] fileBytes = readAllBytes(sisFile);
        if (fileBytes.length < 20) throw new IOException("File terlalu kecil.");
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);
        int uid2 = buf.getInt(4);

        if (uid1 == SISX_UID1_MAGIC) {
            return scanModernSisx(fileBytes, null, false);
        } else if (uid2 == LEGACY_SIS_UID2_MAGIC) {
            return unpackLegacySis(fileBytes, null);
        }
        throw new IOException("Format file tidak dikenali.");
    }

    public static SisPackageInfo unpackSisx(File sisFile, File outputDir) throws Exception {
        byte[] fileBytes = readAllBytes(sisFile);
		
        if (fileBytes.length < 20) throw new IOException("File terlalu kecil.");
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);
        int uid2 = buf.getInt(4);

        if (!outputDir.exists()) outputDir.mkdirs();

        if (uid1 == SISX_UID1_MAGIC) {
            return scanModernSisx(fileBytes, outputDir, true);
        } else if (uid2 == LEGACY_SIS_UID2_MAGIC) {
            return unpackLegacySis(fileBytes, outputDir);
        }
        throw new IOException("Format file tidak dikenali.");
    }

	private static SisPackageInfo scanModernSisx(byte[] fileBytes, File outputDir, boolean writeToDisk) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        SisPackageInfo info = new SisPackageInfo();
        info.formatLabel = "SISX (Symbian 9.x / S60v3-Belle)";
        info.uid1 = buf.getInt(0);
        info.uid2 = buf.getInt(4);
        info.uid3 = buf.getInt(8);
        info.uidChecksum = buf.getInt(12);

        List<byte[]> decompressedBlobs = new ArrayList<>();

        for (int pos = 16; pos <= fileBytes.length - 22; pos++) {
            int type = buf.getInt(pos);
            if (type == FIELD_COMPRESSED || type==3) { // FIELD_COMPRESSED = 2
                long len = buf.getInt(pos + 4) & 0xFFFFFFFFL;
                int alg = buf.getInt(pos + 8);
                long uncompSize = buf.getLong(pos + 12);

                if (len > 12 && pos + 8 + len <= fileBytes.length && uncompSize > 0 && uncompSize < 50 * 1024 * 1024) {
                    int compDataLen = (int) (len - 12);

                    // Cari offset byte 0x78 (Zlib) secara dinamis dalam rentang 16 byte pertama payload
                    int zlibOffset = -1;
                    for (int z = 0; z < Math.min(16, compDataLen); z++) {
                        int checkPos = pos + 20 + z;
                        if (checkPos < fileBytes.length && (fileBytes[checkPos] & 0xFF) == 0x78) {
                            if (checkPos + 1 < fileBytes.length) {
                                int nextByte = fileBytes[checkPos + 1] & 0xFF;
                                if (nextByte == 0x01 || nextByte == 0x5E || nextByte == 0x9C || nextByte == 0xDA) {
                                    zlibOffset = checkPos;
                                    break;
                                }
                            }
                        }
                    }

                    if (alg == 1 && zlibOffset != -1) {
                        int actualCompLen = (int) ((pos + 8 + len) - zlibOffset);
                        try {
                            byte[] compData = new byte[actualCompLen];
                            System.arraycopy(fileBytes, zlibOffset, compData, 0, actualCompLen);

                            Inflater inf = new Inflater();
                            inf.setInput(compData);
                            byte[] out = new byte[(int) uncompSize];
                            int count = inf.inflate(out);
                            inf.end();
                            if (count > 0) {
                                decompressedBlobs.add(out);
                                pos += 8 + (int) len - 1;
                            }
                        } catch (Exception ignored) {}
                    } else if (alg == 0 && compDataLen == uncompSize) {
                        byte[] rawData = new byte[compDataLen];
                        System.arraycopy(fileBytes, pos + 20, rawData, 0, compDataLen);
                        decompressedBlobs.add(rawData);
                        pos += 8 + (int) len - 1;
                    }
                }
            } else if (type == 13 || type == 34) {
                long len = buf.getInt(pos + 4) & 0xFFFFFFFFL;
                if (len > 20 && pos + 8 + len <= fileBytes.length) {
                    info.isSigned = true;
                    info.signerInfo = "Signed (SISX Certificate Chain)";
                }
            }
        }
		
		// 2. Kelompokkan blob data fisik ke Queue berdasarkan Magic Bytes Symbian
		java.util.Queue<byte[]> mbmQueue = new java.util.LinkedList<>();
        java.util.Queue<byte[]> mifQueue = new java.util.LinkedList<>();
        java.util.Queue<byte[]> iniQueue = new java.util.LinkedList<>();
        java.util.Queue<byte[]> sknQueue = new java.util.LinkedList<>();
        java.util.Queue<byte[]> otherQueue = new java.util.LinkedList<>();
        java.util.List<byte[]> installLogos = new java.util.ArrayList<>();
		
		boolean isThemePackage = false;

        if (!decompressedBlobs.isEmpty()) {
            // Gabungkan semua controller blob jika ada sub-controller (embedded SIS)
			// 1. Pindai SEMUA blob untuk menemukan seluruh daftar path file (termasuk dari Subcomponents / Embedded SIS)
			for (byte[] blob : decompressedBlobs) {
				if (blob.length >= 32) {
					ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
					int m1 = b.getInt(0);
					// Lewati file biner MBM (0x10000037) dan MIF (0x34232342)
					if (m1 != 0x10000037 && m1 != 0x34232342) {
						extractMetadataAndPathsFromController(blob, info);
					}
				}
			}

			
			for (SisFileEntry f : info.files) {
				if (f.targetPath != null && f.targetPath.toLowerCase().endsWith(".skn")) {
					isThemePackage = true;
					break;
				}
			}

			for (int i = 1; i < decompressedBlobs.size(); i++) {
				byte[] blob = decompressedBlobs.get(i);
				if (blob.length >= 8) {
					ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
					int m1 = b.getInt(0);
					int m2 = b.getInt(4);

					if (containsSymbianPathString(blob)) {
						continue;
					}

					boolean isJpeg = (blob[0] & 0xFF) == 0xFF && (blob[1] & 0xFF) == 0xD8 && (blob[2] & 0xFF) == 0xFF;
					boolean isPng = (blob[0] & 0xFF) == 0x89 && (blob[1] & 0xFF) == 0x50
                        && (blob[2] & 0xFF) == 0x4E && (blob[3] & 0xFF) == 0x47;

					if (isJpeg || isPng) {
						installLogos.add(blob);
					} else if (m1 == 0x10000037 && m2 == 0x10000042) {
						mbmQueue.add(blob);
					} else if (m1 == 0x34232342) {
						mifQueue.add(blob);
					} else if (blob.length <= 512 && isMostlyTextOrIni(blob)) {
						iniQueue.add(blob);
					} else if (isThemePackage) {
						// Paket tema: blob biner lain adalah file .skn
						sknQueue.add(blob);
					} else {
						// Paket aplikasi / patch biasa: masukkan ke otherQueue (.exe, .dll, .rsc, .r01, dll.)
						otherQueue.add(blob);
					}
				} else if (blob.length > 0) {
					if (isThemePackage) iniQueue.add(blob);
					else otherQueue.add(blob);
				}
			}
			
			
        }

        // Pasangkan setiap path dengan data tipe yang cocok & pisahkan cabang duplikat ke varian_1, varian_2, dst.
        java.util.Map<String, Integer> totalPathFreq = new java.util.HashMap<>();
        boolean hasMultipleVariants = false;
        for (SisFileEntry entry : info.files) {
            if (entry.targetPath != null) {
                String normKey = entry.targetPath.toLowerCase().replace("\\", "/");
                int count = totalPathFreq.containsKey(normKey) ? totalPathFreq.get(normKey) + 1 : 1;
                totalPathFreq.put(normKey, count);
                if (count > 1) {
                    hasMultipleVariants = true;
                }
            }
        }

        // 4. Pasangkan setiap path dengan data fisik & arahkan ke folder varian hanya jika hasMultipleVariants == true
        List<SisFileEntry> allEntries = new ArrayList<>();
        java.util.Map<String, Integer> pathOccurrenceCount = new java.util.HashMap<>();
        byte[] lastMbm = null, lastMif = null, lastIni = null, lastSkn = null;

        for (SisFileEntry entry : info.files) {
            String normKey = entry.targetPath.toLowerCase().replace("\\", "/");
            int occurrence = pathOccurrenceCount.containsKey(normKey) ? pathOccurrenceCount.get(normKey) + 1 : 1;
            pathOccurrenceCount.put(normKey, occurrence);

            String cleanSymbianRel = sanitizeTargetPath(entry.targetPath);
            String lowerName = new File(cleanSymbianRel).getName().toLowerCase();
            byte[] matchedData = null;

			if (lowerName.endsWith(".mbm")) {
                if (!mbmQueue.isEmpty()) lastMbm = mbmQueue.poll();
                matchedData = lastMbm;
            } else if (lowerName.endsWith(".mif")) {
                if (!mifQueue.isEmpty()) lastMif = mifQueue.poll();
                matchedData = lastMif;
            } else if (lowerName.endsWith(".skn")) {
                if (!sknQueue.isEmpty()) lastSkn = sknQueue.poll();
                matchedData = lastSkn;
            } else if (isThemePackage && lowerName.endsWith(".ini")) {
                if (!iniQueue.isEmpty()) lastIni = iniQueue.poll();
                matchedData = lastIni;
            } else {
                // Semua file aplikasi (.exe, .dll, .rsc, .r01, _reg.rsc, .txt, .xml, dll.)
                if (!otherQueue.isEmpty()) {
                    matchedData = otherQueue.poll();
                } else if (!iniQueue.isEmpty()) {
                    matchedData = iniQueue.poll();
                }
            }

            if (matchedData != null && matchedData.length > 0) {
                entry.rawData = matchedData;
                entry.uncompressedSize = matchedData.length;

                if (hasMultipleVariants) {
                    // Paket multi-varian -> pisahkan ke varian_1/, varian_2/, dst.
                    entry.localRelPath = "varian_" + occurrence + "/" + cleanSymbianRel;
                } else {
                    // Paket biasa (tanpa varian) -> langsung ke folder normal (sys/bin/..., resource/apps/...)
                    entry.localRelPath = cleanSymbianRel;
                }

                allEntries.add(entry);

                if (writeToDisk && outputDir != null) {
                    File outFile = hasMultipleVariants
						? resolveVariantOutputFile(outputDir, occurrence, entry.targetPath)
						: new File(outputDir, cleanSymbianRel);

                    if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
                        outFile.getParentFile().mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(matchedData);
                    }
                }
            }
        }

		info.files = allEntries;

        if (writeToDisk && outputDir != null) {
            generatePkgScript(info, new File(outputDir, "project.pkg"));

            // TAMBAHKAN INI: Rekam struktur TLV Controller (termasuk SISIf/ElseIf) ke header_offset.txt
            if (!decompressedBlobs.isEmpty()) {
                SisManifestManager.recordAndSaveFromController(
					new File(outputDir, "header_offset.txt"),
					info,
					decompressedBlobs.get(0)
                );
            }
        }

        return info;
		

    }
	
	private static boolean containsSymbianPathString(byte[] blob) {
        if (blob == null || blob.length < 24) return false;
        ByteBuffer cBuf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int limit = Math.min(blob.length - 8, 4096);
        for (int pos = 0; pos <= limit; pos += 4) {
            if (cBuf.getInt(pos) == FIELD_STRING) {
                int byteLen = cBuf.getInt(pos + 4);
                if (byteLen >= 6 && byteLen <= 512 && byteLen % 2 == 0 && pos + 8 + byteLen <= blob.length) {
                    String s = new String(blob, pos + 8, byteLen, StandardCharsets.UTF_16LE).trim();
                    if (s.contains(":\\") || s.startsWith("!:")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
	
	
    private static void extractMetadataAndPathsFromController(byte[] ctrlBytes, SisPackageInfo info) {
        ByteBuffer cBuf = ByteBuffer.wrap(ctrlBytes).order(ByteOrder.LITTLE_ENDIAN);
        List<String> metaStrings = new ArrayList<>();

        for (int pos = 0; pos <= ctrlBytes.length - 8; pos += 4) {
            int type = cBuf.getInt(pos);
            if (type == FIELD_STRING) {
                int byteLen = cBuf.getInt(pos + 4);
                if (byteLen > 2 && byteLen <= 512 && byteLen % 2 == 0 && pos + 8 + byteLen <= ctrlBytes.length) {
                    String s = new String(ctrlBytes, pos + 8, byteLen, StandardCharsets.UTF_16LE).trim();
                    if (!s.isEmpty() && isPrintableSymbianString(s)) {
                        if (s.contains(":\\") || s.startsWith("!:") || s.startsWith("c:") || s.startsWith("e:")) {
                            SisFileEntry entry = new SisFileEntry();
                            entry.fileIndex = info.files.size();
                            entry.targetPath = s;
                            entry.localRelPath = sanitizeTargetPath(s);
                            info.files.add(entry);
                        } else if (!s.equals("Series60ProductID") && !s.startsWith("1.2.")) {
                            metaStrings.add(s);
                        }
                    }
                }
            } else if (type == FIELD_VERSION) {
                int len = cBuf.getInt(pos + 4);
                if (len == 12 && pos + 20 <= ctrlBytes.length && info.majorVersion == 1 && info.minorVersion == 0) {
                    int maj = cBuf.getInt(pos + 8);
                    int min = cBuf.getInt(pos + 12);
                    int bld = cBuf.getInt(pos + 16);
                    if (maj >= 0 && maj < 100 && min >= 0 && min < 100) {
                        info.majorVersion = maj;
                        info.minorVersion = min;
                        info.buildVersion = bld;
                    }
                }
            }
        }

        // Di SISInfo: string[0] = VendorUniqueName ("Nokia"), string[1] = AppName ("musicplayer")
        if (metaStrings.size() >= 2) {
            info.vendorName = metaStrings.get(0);
            info.appName = metaStrings.get(1);
        } else if (metaStrings.size() == 1) {
            info.appName = metaStrings.get(0);
        }
    }
	

    private static boolean isPrintableSymbianString(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\t') return false;
        }
        return true;
    }
	

    private static void parseContents(ByteBuffer buf, int blockLen, SisPackageInfo info, List<byte[]> dataUnits) throws Exception {
        int endPos = buf.position() + blockLen;
        while (buf.position() + 8 <= endPos) {
            int type = buf.getInt();
            long len = readFieldLength(buf);
            int fieldStart = buf.position();
            if (len < 0 || fieldStart + len > endPos) break;

            if (type == FIELD_CONTROLLER) {
                byte[] ctrlData = new byte[(int) len];
                buf.get(ctrlData);
                byte[] uncompressedCtrl = unwrapIfCompressed(ctrlData);
                parseController(ByteBuffer.wrap(uncompressedCtrl).order(ByteOrder.LITTLE_ENDIAN), info);
            } else if (type == FIELD_DATA) {
                parseDataBlock(buf, (int) len, dataUnits);
            } else {
                buf.position(fieldStart + (int) len);
            }
            align4(buf);
        }
        buf.position(endPos);
    }

    private static void parseController(ByteBuffer buf, SisPackageInfo info) {
        while (buf.remaining() >= 8) {
            int type = buf.getInt();
            long len = readFieldLength(buf);
            int start = buf.position();
            if (len < 0 || start + len > buf.limit()) break;

            if (type == FIELD_INFO) {
                parseInfoBlock(buf, (int) len, info);
            } else if (type == FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) len, info);
            } else {
                buf.position(start + (int) len);
            }
            align4(buf);
        }
    }

    private static void parseInfoBlock(ByteBuffer buf, int len, SisPackageInfo info) {
        int end = buf.position() + len;
        if (buf.remaining() >= 4) {
            int pkgUid = buf.getInt();
            if (info.uid3 == 0) info.uid3 = pkgUid;
        }
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readFieldLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == FIELD_STRING && info.vendorName.equals("UnknownVendor")) {
                info.vendorName = readUtf16String(buf, (int) fLen);
            } else if (type == FIELD_ARRAY) {
                String firstStr = readFirstStringFromArray(buf, (int) fLen);
                if (firstStr != null && !firstStr.isEmpty()) {
                    if (info.appName.equals("UnknownApp")) {
                        info.appName = firstStr;
                    } else if (info.vendorName.equals("UnknownVendor")) {
                        info.vendorName = firstStr;
                    }
                }
            } else if (type == FIELD_VERSION && fLen >= 12) {
                info.majorVersion = buf.getInt();
                info.minorVersion = buf.getInt();
                info.buildVersion = buf.getInt();
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    /**
     * SISInstallBlock berisi:
     * 1. Array of SISFileDescription
     * 2. Array of SISIf
     */
    private static void parseInstallBlock(ByteBuffer buf, int len, SisPackageInfo info) {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readFieldLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == FIELD_ARRAY) {
                parseTypedArray(buf, (int) fLen, info);
            } else if (type == FIELD_FILE_DESCRIPTION) {
                SisFileEntry entry = parseFileDescription(buf, (int) fLen);
                if (entry != null) info.files.add(entry);
            } else if (type == FIELD_IF) {
                parseIfBlock(buf, (int) fLen, info);
            }

            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    /**
     * SISIf berisi urutan TLV:
     * - SISExpression (type 21) -> skip
     * - SISInstallBlock (type 20) -> parse!
     * - Array of SISElseIf (type 2) -> parse setiap ElseIf!
     */
    private static void parseIfBlock(ByteBuffer buf, int len, SisPackageInfo info) {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readFieldLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) fLen, info);
            } else if (type == FIELD_ARRAY) {
                parseTypedArray(buf, (int) fLen, info);
            } else if (type == FIELD_ELSE_IF) {
                parseIfBlock(buf, (int) fLen, info);
            }

            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static void parseTypedArray(ByteBuffer buf, int len, SisPackageInfo info) {
        int end = buf.position() + len;
        if (buf.remaining() < 4) return;
        int elemType = buf.getInt();

        while (buf.position() + 4 <= end) {
            long itemLen = readFieldLength(buf);
            int itemStart = buf.position();
            if (itemLen < 0 || itemStart + itemLen > end) break;

            if (elemType == FIELD_FILE_DESCRIPTION) {
                SisFileEntry entry = parseFileDescription(buf, (int) itemLen);
                if (entry != null) info.files.add(entry);
            } else if (elemType == FIELD_IF || elemType == FIELD_ELSE_IF) {
                parseIfBlock(buf, (int) itemLen, info);
            } else if (elemType == FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) itemLen, info);
            }

            buf.position(itemStart + (int) itemLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static SisFileEntry parseFileDescription(ByteBuffer buf, int len) {
        int end = buf.position() + len;
        SisFileEntry entry = new SisFileEntry();
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readFieldLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == FIELD_STRING && entry.targetPath == null) {
                entry.targetPath = readUtf16String(buf, (int) fLen);
                entry.localRelPath = sanitizeTargetPath(entry.targetPath);
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        if (end - buf.position() >= 28) {
            buf.position(end - 28);
            buf.getInt(); // operation
            buf.getInt(); // options
            entry.compressedSize = buf.getLong();
            entry.uncompressedSize = buf.getLong();
            entry.fileIndex = buf.getInt();
        }
        buf.position(end);
        return (entry.targetPath != null && !entry.targetPath.isEmpty()) ? entry : null;
    }

    private static void parseDataBlock(ByteBuffer buf, int len, List<byte[]> dataUnits) throws Exception {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readFieldLength(buf);
            int fStart = buf.position();
            if (fStart + fLen > end) break;

            if (type == FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4) {
                    int elemType = buf.getInt();
                    if (elemType == FIELD_DATA_UNIT) {
                        while (buf.position() + 4 <= arrEnd) {
                            long unitLen = readFieldLength(buf);
                            int unitStart = buf.position();
                            if (unitStart + unitLen > arrEnd) break;
                            parseDataUnit(buf, (int) unitLen, dataUnits);
                            buf.position(unitStart + (int) unitLen);
                            align4(buf);
                        }
                    }
                }
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static void parseDataUnit(ByteBuffer buf, int len, List<byte[]> dataUnits) throws Exception {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readFieldLength(buf);
            int fStart = buf.position();
            if (fStart + fLen > end) break;

            if (type == FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4) {
                    int elemType = buf.getInt();
                    if (elemType == FIELD_FILE_DATA) {
                        while (buf.position() + 4 <= arrEnd) {
                            long fileDataLen = readFieldLength(buf);
                            int fdStart = buf.position();
                            if (fdStart + fileDataLen > arrEnd) break;
                            byte[] rawField = new byte[(int) fileDataLen];
                            buf.get(rawField);
                            dataUnits.add(unwrapIfCompressed(rawField));
                            buf.position(fdStart + (int) fileDataLen);
                            align4(buf);
                        }
                    }
                }
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    // =========================================================================
    // 2. PARSER LEGACY SIS (.sis S60v1 / S60v2 - EPOC Format)
    // =========================================================================
    private static SisPackageInfo unpackLegacySis(byte[] fileBytes, File outputDir) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        SisPackageInfo info = new SisPackageInfo();
        info.uid1 = buf.getInt(0);
        info.uid2 = buf.getInt(4);
        info.uid3 = buf.getInt(8);
        info.uidChecksum = buf.getInt(12);

        int numLangs = buf.getShort(18) & 0xFFFF;
        int numFiles = buf.getShort(20) & 0xFFFF;
        int options = buf.getShort(32) & 0xFFFF;
        info.majorVersion = buf.getShort(36) & 0xFFFF;
        info.minorVersion = buf.getShort(38) & 0xFFFF;
        info.buildVersion = buf.getInt(40);

        int ptrLangs = buf.getInt(44);
        int ptrFiles = buf.getInt(48);
        int ptrReqs = buf.getInt(52);
        int ptrCerts = buf.getInt(56);
        int ptrCompNames = buf.getInt(60);

        // Baca Nama Aplikasi dari ptrCompNames
        if (ptrCompNames > 0 && ptrCompNames + (8 * numLangs) <= fileBytes.length && numLangs > 0) {
            int nameLen = buf.getInt(ptrCompNames);
            int nameOff = buf.getInt(ptrCompNames + (4 * numLangs));
            if (nameOff > 0 && nameOff + nameLen <= fileBytes.length && nameLen > 0) {
                info.appName = new String(fileBytes, nameOff, nameLen, StandardCharsets.UTF_16LE).trim();
            }
        }

        // Iterasi File Records pada ptrFiles
        int cursor = ptrFiles;
        for (int i = 0; i < numFiles && cursor + 4 <= fileBytes.length; i++) {
            int recordType = buf.getInt(cursor);

            if (recordType == 0 || recordType == 1) {
                // recordType 0: Single language file, recordType 1: Multi-language file
                int langCountForFile = (recordType == 1) ? numLangs : 1;
                if (cursor + 28 + (12 * langCountForFile) > fileBytes.length) break;

                int dstNameLen = buf.getInt(cursor + 20);
                int dstNamePtr = buf.getInt(cursor + 24);

                int dataLen = buf.getInt(cursor + 28);
                int dataPtr = buf.getInt(cursor + 28 + (4 * langCountForFile));
                int origLen = buf.getInt(cursor + 28 + (8 * langCountForFile));

                String dstPath = (dstNamePtr > 0 && dstNamePtr + dstNameLen <= fileBytes.length && dstNameLen > 0)
					? new String(fileBytes, dstNamePtr, dstNameLen, StandardCharsets.UTF_16LE).trim()
					: "file_" + i + ".bin";

                if (!dstPath.isEmpty() && dataPtr > 0 && dataPtr + dataLen <= fileBytes.length && dataLen > 0) {
                    SisFileEntry entry = new SisFileEntry();
                    entry.fileIndex = i;
                    entry.targetPath = dstPath;
                    entry.localRelPath = sanitizeTargetPath(dstPath);
                    entry.compressedSize = dataLen;
                    entry.uncompressedSize = (origLen > 0) ? origLen : dataLen;

                    byte[] slice = new byte[dataLen];
                    System.arraycopy(fileBytes, dataPtr, slice, 0, dataLen);

                    // Pada Legacy SIS, jika uncompressed (bit 3 = 0x08 berarti NO compression!)
                    boolean isUncompressed = (options & 0x08) != 0;
                    if (!isUncompressed && origLen > 0 && origLen != dataLen) {
                        try {
                            Inflater inf = new Inflater();
                            inf.setInput(slice);
                            byte[] out = new byte[origLen];
                            int n = inf.inflate(out);
                            inf.end();
                            entry.rawData = (n > 0) ? out : slice;
                        } catch (Exception ex) {
                            entry.rawData = slice;
                        }
                    } else {
                        entry.rawData = slice;
                    }

                    info.files.add(entry);
                    writeEntryToDisk(outputDir, entry);
                }
                cursor += 28 + (12 * langCountForFile);
            } else if (recordType == 2) {
                // IF block: recordType(4) + exprLen(4) + exprBytes
                int exprLen = buf.getInt(cursor + 4);
                cursor += 8 + exprLen;
            } else if (recordType == 3) {
                // ELSEIF block
                int exprLen = buf.getInt(cursor + 4);
                cursor += 8 + exprLen;
            } else {
                // ELSE (4) / ENDIF (5)
                cursor += 4;
            }
        }

        if (info.files.isEmpty()) {
            throw new IOException(String.format(
									  "Legacy SIS terbaca (UID3=0x%08X, numFiles=%d, ptrFiles=0x%X), tapi 0 file valid.",
									  info.uid3, numFiles, ptrFiles
								  ));
        }

        generatePkgScript(info, new File(outputDir, "project.pkg"));
        return info;
    }

    // =========================================================================
    // HELPER UMUM
    // =========================================================================
    private static byte[] unwrapIfCompressed(byte[] block) throws DataFormatException {
        ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() >= 20) {
            int innerType = b.getInt();
            long innerLen = readFieldLength(b);
            if (innerType == FIELD_COMPRESSED && b.remaining() >= 12) {
                int alg = b.getInt();
                long uncompressedSize = b.getLong();
                byte[] compBytes = new byte[b.remaining()];
                b.get(compBytes);

                if (alg == 0) {
                    return compBytes;
                } else if (alg == 1) {
                    Inflater inflater = new Inflater();
                    inflater.setInput(compBytes);
                    byte[] result = new byte[(int) uncompressedSize];
                    int count = inflater.inflate(result);
                    inflater.end();
                    if (count < result.length) {
                        byte[] trimmed = new byte[count];
                        System.arraycopy(result, 0, trimmed, 0, count);
                        return trimmed;
                    }
                    return result;
                }
            }
        }
        return block;
    }

    public static void generatePkgScript(SisPackageInfo info, File pkgFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("; Auto-generated by S60Reborn\n");
        sb.append("&EN\n\n");
        sb.append("#{\"").append(info.appName).append("\"},(")
			.append(info.getFormattedUid3()).append("),")
			.append(info.getFormattedVersion()).append(",TYPE=SA\n\n");
        sb.append("%{\"").append(info.vendorName).append("\"}\n");
        sb.append(":\"").append(info.vendorName).append("\"\n\n");
        sb.append("; Target Platform: S60 3rd / 5th Edition\n");
        sb.append("[0x101F7961], 0, 0, 0, {\"Series60ProductID\"}\n");
        sb.append("[0x1028315F], 0, 0, 0, {\"Series60ProductID\"}\n\n");
        sb.append("; Files\n");

        for (SisFileEntry f : info.files) {
            String winRelPath = f.localRelPath.replace('/', '\\');
            sb.append("\"").append(winRelPath).append("\"-\"")
				.append(f.targetPath).append("\"\n");
        }

        try (FileOutputStream fos = new FileOutputStream(pkgFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeEntryToDisk(File baseDir, SisFileEntry entry) throws IOException {
        File outFile = new File(baseDir, entry.localRelPath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(entry.rawData);
        }
    }

    private static String sanitizeTargetPath(String symbianPath) {
        String p = symbianPath.replace('\\', '/');
        if (p.length() >= 3 && p.charAt(1) == ':' && p.charAt(2) == '/') {
            p = p.substring(3);
        } else if (p.length() >= 2 && p.charAt(1) == ':') {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.isEmpty() ? "unknown_file.bin" : p;
    }

    private static long readFieldLength(ByteBuffer buf) {
        long len = buf.getInt() & 0xFFFFFFFFL;
        if ((len & 0x80000000L) != 0) {
            long high = buf.getInt() & 0xFFFFFFFFL;
            len = ((len & 0x7FFFFFFFL) << 32) | high;
        }
        return len;
    }

    private static void align4(ByteBuffer buf) {
        int pos = buf.position();
        int mod = pos % 4;
        if (mod != 0) {
            int skip = 4 - mod;
            if (buf.remaining() >= skip) {
                buf.position(pos + skip);
            }
        }
    }

    private static String readUtf16String(ByteBuffer buf, int byteLen) {
        if (byteLen <= 0 || buf.remaining() < byteLen) return "";
        byte[] strBytes = new byte[byteLen];
        buf.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_16LE).trim();
    }

    private static String readFirstStringFromArray(ByteBuffer buf, int arrayLen) {
        int end = buf.position() + arrayLen;
        if (buf.remaining() < 4) return null;
        int elemType = buf.getInt();
        if (elemType == FIELD_STRING && buf.position() + 4 <= end) {
            long strLen = readFieldLength(buf);
            if (buf.position() + strLen <= end) {
                return readUtf16String(buf, (int) strLen);
            }
        }
        return null;
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        }
    }
	
	
    private static void parseContentsWithSignCheck(ByteBuffer buf, int blockLen, SisPackageInfo info,
                                                   List<byte[]> dataUnits, StringBuilder diagLog) throws Exception {
        int endPos = buf.position() + blockLen;
        while (buf.position() + 8 <= endPos) {
            int type = buf.getInt();
            long len = readFieldLength(buf);
            int fieldStart = buf.position();
            if (len < 0 || fieldStart + len > endPos) break;

            if (type == FIELD_CONTROLLER) {
                byte[] ctrlData = new byte[(int) len];
                buf.get(ctrlData);
                byte[] uncompressedCtrl = unwrapIfCompressed(ctrlData);
                ByteBuffer ctrlBuf = ByteBuffer.wrap(uncompressedCtrl).order(ByteOrder.LITTLE_ENDIAN);
                parseControllerDeepWithSign(ctrlBuf, info, diagLog);
            } else if (type == FIELD_DATA) {
                parseDataBlock(buf, (int) len, dataUnits);
            } else if (type == 13 || type == 34) {
                info.isSigned = true;
                info.signerInfo = "Signed (SISX Certificate Chain)";
                buf.position(fieldStart + (int) len);
            } else {
                buf.position(fieldStart + (int) len);
            }
            align4(buf);
        }
        buf.position(endPos);
    }

    private static void parseControllerDeepWithSign(ByteBuffer buf, SisPackageInfo info, StringBuilder diagLog) {
        while (buf.remaining() >= 8) {
            int type = buf.getInt();
            long len = readFieldLength(buf);
            int start = buf.position();
            if (len < 0 || start + len > buf.limit()) break;

            if (type == FIELD_INFO) {
                parseInfoBlock(buf, (int) len, info);
			} else if (type == FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) len, info);
            } else if (type == FIELD_IF || type == FIELD_ELSE_IF) {
                parseIfBlock(buf, (int) len, info);
            } else if (type == FIELD_ARRAY) {
                parseTypedArray(buf, (int) len, info);
            } else if (type == FIELD_FILE_DESCRIPTION) {
                SisFileEntry entry = parseFileDescription(buf, (int) len);
                if (entry != null) info.files.add(entry);
            } else if (type == 13 || type == 34) {
                info.isSigned = true;
                info.signerInfo = "Signed (Controller Certificate Chain)";
            }

            buf.position(start + (int) len);
            align4(buf);
        }
    }
	
	private static boolean isMostlyTextOrIni(byte[] data) {
        int printable = 0;
        for (byte b : data) {
            int c = b & 0xFF;
            if ((c >= 32 && c <= 126) || c == '\r' || c == '\n' || c == '\t' || c == 0) {
                printable++;
            }
        }
        return printable > (data.length * 0.85);
    }
	
	// Di dalam loop pembacaan TLV SISInstallBlock / SISIf:
    private static void extractFileDescription(
		File projectRootDir,
		int branchIndex,
		String targetSymbianPath,
		byte[] fileContentBytes,
		StringBuilder pkgBuilder,
		String indent) throws Exception {

        // 1. Tentukan lokasi fisik di dalam namatema/varian_X/private/...
        File outFile = resolveVariantOutputFile(projectRootDir, branchIndex, targetSymbianPath);

        // 2. Tulis byte file fisik tanpa menimpa varian lain
        java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
        fos.write(fileContentBytes);
        fos.close();

        // 3. Hitung path relatif terhadap projectRootDir untuk ditulis ke project.pkg
        String rootPath = projectRootDir.getAbsolutePath();
        String relLocalPath = outFile.getAbsolutePath().substring(rootPath.length() + 1).replace("\\", "/");

        // 4. Catat ke project.pkg
        pkgBuilder.append(indent)
			.append("\"").append(relLocalPath).append("\"-\"")
			.append(targetSymbianPath).append("\"\n");
    }
	
	public static java.io.File resolveVariantOutputFile(java.io.File projectRootDir, int branchIndex, String symbianTargetPath) {
        if (branchIndex < 1) branchIndex = 1;
        java.io.File variantDir = new java.io.File(projectRootDir, "varian_" + branchIndex);

        // Bersihkan prefix drive Symbian ("!:\" atau "c:\") menjadi path relatif standar
        String cleanPath = symbianTargetPath.replace("\\", "/");
        if (cleanPath.length() >= 2 && cleanPath.charAt(1) == ':') {
            cleanPath = cleanPath.substring(2);
        }
        while (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }
        if (cleanPath.isEmpty()) {
            cleanPath = "unknown_file.bin";
        }

        java.io.File outFile = new java.io.File(variantDir, cleanPath);

        // Pengaman tambahan: jika path persis sama sudah terisi di varian tersebut,
        // geser ke folder varian berikutnya agar tidak pernah menimpa file yang sudah ada
        int nextBranch = branchIndex + 1;
        while (outFile.exists()) {
            variantDir = new java.io.File(projectRootDir, "varian_" + nextBranch++);
            outFile = new java.io.File(variantDir, cleanPath);
        }

        if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
            outFile.getParentFile().mkdirs();
        }
        return outFile;
    }
	
	
}

