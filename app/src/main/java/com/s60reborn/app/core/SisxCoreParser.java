package com.s60reborn.app.core;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import com.s60reborn.app.model.*;

public class SisxCoreParser {
    public static File dirPath;
    private static List<List<byte[]>> activeDataUnits;
    private static List<EmbeddedSisInfo> extractedEmbeddedList = new ArrayList<EmbeddedSisInfo>();
	public static boolean recursiveEmbedded = false;

    public interface ParserLogger {
        void onLog(String message);
    }

    public static SisPackageInfo inspectSisx(File sisFile,String activeCondition) throws Exception {
        byte[] fileBytes = readAllBytes(sisFile);
        if (fileBytes.length < 20) throw new IOException("File terlalu kecil.");
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);
        int uid2 = buf.getInt(4);

        if (uid1 == SisField.SISX_UID1_MAGIC) {
            return parseModernSisx(fileBytes, null, false,activeCondition);
        } else if (uid2 == SisField.LEGACY_SIS_UID2_MAGIC) {
            return unpackLegacySis(fileBytes, null);
        }
        throw new IOException("Format file tidak dikenali (UID1=0x" + Integer.toHexString(uid1) + ").");
    }
	


    public static SisPackageInfo unpackSisx(File sisFile, File outputDir,String activeCondition) throws Exception {
        dirPath = outputDir;
        extractedEmbeddedList.clear();
        byte[] fileBytes = readAllBytes(sisFile);
        if (fileBytes.length < 20) throw new IOException("File terlalu kecil.");
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);
        int uid2 = buf.getInt(4);

        if (outputDir != null && !outputDir.exists()) outputDir.mkdirs();

        if (uid1 == SisField.SISX_UID1_MAGIC) {
            return parseModernSisx(fileBytes, outputDir, true,activeCondition);
        } else if (uid2 == SisField.LEGACY_SIS_UID2_MAGIC) {
            return unpackLegacySis(fileBytes, outputDir);
        }
        throw new IOException("Format file tidak dikenali.");
    }

    private static SisPackageInfo parseModernSisx(byte[] fileBytes, File outputDir, boolean writeToDisk,String activeCondition) throws Exception {
        dirPath = outputDir;
        extractedEmbeddedList.clear();
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        SisPackageInfo info = new SisPackageInfo();
        info.formatLabel = "SISX (Symbian 9.x / S60v3-Belle)";
        info.uid1 = buf.getInt(0);
        info.uid2 = buf.getInt(4);
        info.uid3 = buf.getInt(8);
        info.uidChecksum = buf.getInt(12);

        // 1. Baca header TLV utama (FIELD_CONTENTS = 12) mulai offset 16
        buf.position(16);
        int contentsTag = buf.getInt();
        long contentsLen = readEkaLength(buf);
        int contentsStart = buf.position();
        int contentsEnd = (int) Math.min(fileBytes.length, contentsStart + contentsLen);

        if (contentsTag != SisField.FIELD_CONTENTS || contentsLen <= 0) {
            throw new IOException(String.format(
									  "Bukan SISContents (12) valid: tag=%d, len=%d", contentsTag, contentsLen));
        }

        // 2. Baca SISController & SISData / SISDataUnit
        byte[] rawController = null;
        List<List<byte[]>> dataUnits = new ArrayList<List<byte[]>>();
        StringBuilder traceLog = new StringBuilder();

        buf.position(contentsStart);
        while (buf.position() + 8 <= contentsEnd) {
            int fieldPos = buf.position();
            int tag = buf.getInt();
            long len = readEkaLength(buf);
            int bodyStart = buf.position();
            int bodyEnd = (int) Math.min(contentsEnd, bodyStart + len);
            traceLog.append("[").append(tag).append("@").append(fieldPos).append(":len=").append(len).append("] ");

            if (len < 0 || bodyStart > contentsEnd) break;

            if (tag == SisField.FIELD_CONTROLLER || tag == SisField.FIELD_COMPRESSED) {
                if (tag == SisField.FIELD_COMPRESSED) {
                    ByteBuffer tempBb = ByteBuffer.allocate(8 + (int) len).order(ByteOrder.LITTLE_ENDIAN);
                    tempBb.putInt(tag);
                    tempBb.putInt((int) len);
                    tempBb.put(fileBytes, bodyStart, (int) len);
                    rawController = readEkaControllerBytes(tempBb.array(), 0, tempBb.capacity());
                } else {
                    rawController = readEkaControllerBytes(fileBytes, bodyStart, (int) len);
                }
            } else if (tag == SisField.FIELD_DATA || tag == 29) {
                ByteBuffer dataBuf = ByteBuffer.wrap(fileBytes, bodyStart, (int) len).order(ByteOrder.LITTLE_ENDIAN);
                parseSisDataBlock(dataBuf, (int) len, dataUnits);
            } else if (tag == SisField.FIELD_DATA_UNIT || tag == 30) {
                // Beberapa paket S60v3 menaruh FIELD_DATA_UNIT (30) langsung di bawah SISContents tanpa wrapper FIELD_DATA
                ByteBuffer unitBuf = ByteBuffer.wrap(fileBytes, bodyStart, (int) len).order(ByteOrder.LITTLE_ENDIAN);
                List<byte[]> unitFiles = new ArrayList<byte[]>();
                parseDataUnit(unitBuf, (int) len, unitFiles);
                dataUnits.add(unitFiles);
            }

            buf.position(bodyEnd);
            align4(buf);
        }

        activeDataUnits = dataUnits;

        if (rawController == null) {
            throw new IOException("SISController gagal didekode. Trace TLV: " + traceLog.toString());
        }

        // Jika hasil dekompresi controller dibungkus wrapper [Tag 13 | FIELD_CONTROLLER], buka isinya langsung
        rawController = unwrapOuterControllerWrapper(rawController);

        // 3. Parse seluruh tree SISController (termasuk IF/ELSEIF & Sub-Controller)
        List<EntryWithUnit> parsedEntries = new ArrayList<EntryWithUnit>();
        ByteBuffer ctrlBuf = ByteBuffer.wrap(rawController).order(ByteOrder.LITTLE_ENDIAN);
        parseControllerRecursive(ctrlBuf, info, parsedEntries, 0, true,activeCondition);

        // Warisi AppName/VendorName dari Embedded SIS pertama jika paket induk adalah wrapper tanpa nama
        if (!extractedEmbeddedList.isEmpty()) {
            EmbeddedSisInfo firstEmb = extractedEmbeddedList.get(0);
            if (isPlaceholderOrEmpty(info.appName) && !isPlaceholderOrEmpty(firstEmb.appName)) {
                info.appName = firstEmb.appName;
            }
            if (isPlaceholderOrEmpty(info.vendorName) && !isPlaceholderOrEmpty(firstEmb.vendorName)) {
                info.vendorName = firstEmb.vendorName;
            }
        }

        // 4. Deteksi multi-varian jika ada targetPath yang sama di cabang IF / ELSEIF
        Map<String, Integer> pathFreq = new HashMap<String, Integer>();
        boolean hasMultipleVariants = false;
        for (EntryWithUnit ew : parsedEntries) {
            if (ew.entry.targetPath != null && !ew.entry.targetPath.isEmpty()) {
                String key = ew.entry.targetPath.toLowerCase().replace('\\', '/');
                int c = pathFreq.containsKey(key) ? pathFreq.get(key) + 1 : 1;
                pathFreq.put(key, c);
                if (c > 1) hasMultipleVariants = true;
            }
        }

        // 5. Ekstrak seluruh file dari dataUnits[dataUnitIndex][fileIndex]
        Map<String, Integer> occurrenceMap = new HashMap<String, Integer>();
        List<SisFileEntry> finalFiles = new ArrayList<SisFileEntry>();

        for (EntryWithUnit ew : parsedEntries) {
            SisFileEntry entry = ew.entry;
            if (entry.targetPath == null || entry.targetPath.isEmpty()) continue;
            if (entry.operation == 4) continue; // EOpNull (FN)

            int uIdx = (ew.dataUnitIndex >= 0 && ew.dataUnitIndex < dataUnits.size()) ? ew.dataUnitIndex : 0;
            List<byte[]> unitFiles = (uIdx < dataUnits.size()) ? dataUnits.get(uIdx) : new ArrayList<byte[]>();

            byte[] data = (entry.fileIndex >= 0 && entry.fileIndex < unitFiles.size())
				? unitFiles.get(entry.fileIndex)
				: new byte[0];
            entry.rawData = (data != null) ? data : new byte[0];
            entry.uncompressedSize = entry.rawData.length;

            String key = entry.targetPath.toLowerCase().replace('\\', '/');
            int occ = occurrenceMap.containsKey(key) ? occurrenceMap.get(key) + 1 : 1;
            occurrenceMap.put(key, occ);

            String cleanRel = sanitizeTargetPath(entry.targetPath);
            entry.localRelPath = hasMultipleVariants
				? ("varian_" + occ + "/" + cleanRel)
				: cleanRel;

            finalFiles.add(entry);

            if (writeToDisk && outputDir != null) {
                File outFile = new File(outputDir, entry.localRelPath);
                if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
                    outFile.getParentFile().mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(entry.rawData);
                }
            }
        }

        info.files = finalFiles;

        if (writeToDisk && outputDir != null) {
            generatePkgScript(info, new File(outputDir, "project.pkg"));
			SisProjectConfig config = new SisProjectConfig();
			SisProjectConfig.syncConfigFromUnpackedInfo(info, config);
			config.saveToFile(outputDir);
        }

        return info;
	
    }

    /**
     * Jika byte ke-0 dari controller adalah wrapper FIELD_CONTROLLER (13) tunggal yang membungkus
     * seluruh controller utama, ekstrak langsung isi di dalamnya agar tidak dianggap sub-controller embedded.
     */
    private static byte[] unwrapOuterControllerWrapper(byte[] rawCtrl) {
        if (rawCtrl == null || rawCtrl.length < 12) return rawCtrl;
        ByteBuffer b = ByteBuffer.wrap(rawCtrl).order(ByteOrder.LITTLE_ENDIAN);
        int firstTag = b.getInt();
        long firstLen = readEkaLength(b);
        int bodyStart = b.position();
        if ((firstTag == SisField.FIELD_CONTROLLER || firstTag == 13)
			&& firstLen > 0 && bodyStart + firstLen <= rawCtrl.length) {
            byte[] inner = new byte[(int) firstLen];
            System.arraycopy(rawCtrl, bodyStart, inner, 0, (int) firstLen);
            return inner;
        }
        return rawCtrl;
    }

    private static byte[] readEkaControllerBytes(byte[] fileBytes, int bodyStart, int bodyLen) throws Exception {
        if (bodyLen < 8 || bodyStart + bodyLen > fileBytes.length) return null;
        ByteBuffer b = ByteBuffer.wrap(fileBytes, bodyStart, bodyLen).order(ByteOrder.LITTLE_ENDIAN);
        int innerTag = b.getInt();

        if (innerTag == SisField.FIELD_INFO || innerTag == SisField.FIELD_CONTROLLER || innerTag == 13) {
            byte[] raw = new byte[bodyLen];
            System.arraycopy(fileBytes, bodyStart, raw, 0, bodyLen);
            return raw;
        }

        if (innerTag == SisField.FIELD_COMPRESSED || innerTag == 3) {
            long compFieldLen = readEkaLength(b);
            int alg = b.getInt();
            long uncompSize = b.getLong();
            int streamStart = bodyStart + b.position();
            int streamLen = (int) Math.min(fileBytes.length - streamStart, compFieldLen - 12);

            if (alg == 0) {
                byte[] raw = new byte[streamLen];
                System.arraycopy(fileBytes, streamStart, raw, 0, streamLen);
                return raw;
            } else if (alg == 1) {
                return inflateEkaStream(fileBytes, streamStart, streamLen, (int) uncompSize);
            }
        }
        return null;
    }

    private static byte[] inflateEkaStream(byte[] src, int offset, int length, int uncompSize) throws Exception {
        if (length <= 0 || offset + length > src.length) return null;

        try {
            Inflater inf = new Inflater(false);
            inf.setInput(src, offset, length);
            int targetCap = (uncompSize > 0 && uncompSize < 64 * 1024 * 1024) ? uncompSize : (length * 4);
            byte[] out = new byte[targetCap];
            int total = 0;
            while (total < out.length && !inf.finished()) {
                int n = inf.inflate(out, total, out.length - total);
                if (n > 0) {
                    total += n;
                } else if (inf.needsInput() || inf.needsDictionary()) {
                    break;
                }
            }
            inf.end();
            if (total > 0) {
                if (total == out.length) return out;
                byte[] trimmed = new byte[total];
                System.arraycopy(out, 0, trimmed, 0, total);
                return trimmed;
            }
        } catch (Exception ignored) {}

        try {
            Inflater rawInf = new Inflater(true);
            rawInf.setInput(src, offset, length);
            int targetCap = (uncompSize > 0 && uncompSize < 64 * 1024 * 1024) ? uncompSize : (length * 4);
            byte[] out = new byte[targetCap];
            int total = 0;
            while (total < out.length && !rawInf.finished()) {
                int n = rawInf.inflate(out, total, out.length - total);
                if (n > 0) {
                    total += n;
                } else if (rawInf.needsInput() || rawInf.needsDictionary()) {
                    break;
                }
            }
            rawInf.end();
            if (total > 0) {
                if (total == out.length) return out;
                byte[] trimmed = new byte[total];
                System.arraycopy(out, 0, trimmed, 0, total);
                return trimmed;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static class EntryWithUnit {
        SisFileEntry entry;
        int dataUnitIndex;

        EntryWithUnit(SisFileEntry e, int u) {
            this.entry = e;
            this.dataUnitIndex = u;
        }
    }

    public static long readFieldLength(ByteBuffer buf) {
        long len = buf.getInt() & 0xFFFFFFFFL;
        if ((len & 0x80000000L) != 0) {
            long high = buf.getInt() & 0xFFFFFFFFL;
            len = ((len & 0x7FFFFFFFL) << 32) | high;
        }
        return len;
    }

    private static void parseControllerRecursive(
		ByteBuffer buf, SisPackageInfo info, List<EntryWithUnit> outEntries, int defaultUnitIdx, boolean isRoot,String activeCondition) {
        int startPos = buf.position();
        int limitPos = buf.limit();
        int currentDataUnit = defaultUnitIdx;

        // Pass 1: Cari FIELD_DATA_INDEX (Tag 40) milik controller ini
        ByteBuffer scanBuf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        while (scanBuf.remaining() >= 8) {
            int type = scanBuf.getInt();
            long len = readEkaLength(scanBuf);
            int fStart = scanBuf.position();
            if (len < 0 || fStart + len > limitPos) break;
            if (type == SisField.FIELD_DATA_INDEX && len >= 4) {
                currentDataUnit = scanBuf.getInt();
            }
            scanBuf.position(fStart + (int) len);
            align4(scanBuf);
        }

        // Pass 2: Baca seluruh field di level controller ini
        buf.position(startPos);
        while (buf.remaining() >= 8) {
            int type = buf.getInt();
            long len = readEkaLength(buf);
            int fStart = buf.position();
            if (len < 0 || fStart + len > limitPos) break;

            if (type == SisField.FIELD_INFO || type == 14) {
                parseInfoBlock(buf, (int) len, info);
            } else if (type == SisField.FIELD_SUPPORTED_OPTIONS || type == 16) {
                List<String> opts = parseSupportedOptions(buf, (int) len);
                if (!opts.isEmpty()) {
                    String optSummary = "Opsi Install: " + android.text.TextUtils.join(", ", opts);
                    if (info.installNote == null || info.installNote.isEmpty()) {
                        info.installNote = optSummary;
                    } else {
                        info.installNote += "\n" + optSummary;
                    }
                }
            } else if (type == SisField.FIELD_INSTALL_BLOCK || type == 19 || type == 23) {
                // Tag 23 = SISInstallBlock pada skema Symbian 9.x (EKA2L1)
                buf.position(fStart);
                parseInstallBlock(buf, (int) len, info, outEntries, currentDataUnit, null);
            } else if (type == SisField.FIELD_FILE_DESCRIPTION || type == 24 || type == 25) {
                buf.position(fStart);
                SisFileEntry entry = parseFileDescription(buf, (int) len);
                if (entry != null) outEntries.add(new EntryWithUnit(entry, currentDataUnit));
            } else if (type == SisField.FIELD_ARRAY || type == 2) {
                buf.position(fStart);
                parseTypedArray(buf, (int) len, info, outEntries, currentDataUnit, null);
            } else if (type == SisField.FIELD_IF || type == SisField.FIELD_ELSE_IF || type == 26 || type == 27 || type == 28 || type == 29) {
                // Tag 28 = SISIf, Tag 29 = SISElseIf pada skema Symbian 9.x
                buf.position(fStart);
                parseIfBlockWithCondition(buf, (int) len, info, outEntries, currentDataUnit, null);
            } else if (type == SisField.FIELD_CONTROLLER || type == 13) {
                buf.position(fStart);
                parseEmbeddedController(buf, (int) len, info, outEntries, currentDataUnit + 1,activeCondition);
            } else if (type == SisField.FIELD_SIGNATURES || type == SisField.FIELD_SIGNATURE_CERT_CHAIN || type == 39) {
                buf.position(fStart - 8);
                List<ExtractedCertInfo> certs = extractAllCertificates(buf, fStart + (int) len, info, dirPath);
                if (!certs.isEmpty()) {
                    info.isSigned = true;
                    info.signerInfo = "Signed (" + certs.size() + " .cer): " + certs.get(0).subject;
                }
			} else if (type == SisField.FIELD_PREREQUISITES || type == 17) {
                buf.position(fStart);
                parsePrerequisitesBlock(buf, (int) len, info);
			}

            buf.position(fStart + (int) len);
            align4(buf);
        }
    }
	
	private static void parsePrerequisitesBlock(ByteBuffer buf, int len, SisPackageInfo info) {
        int end = buf.position() + len;
        int arrayIdx = 0;

        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_ARRAY && fLen >= 4) {
                int arrEnd = fStart + (int) fLen;
                int elemType = buf.getInt();
                if (elemType == SisField.FIELD_DEPENDENCY || elemType == 18) {
                    while (buf.position() + 4 <= arrEnd) {
                        long itemLen = readEkaLength(buf);
                        int itemStart = buf.position();
                        if (itemLen < 0 || itemStart + itemLen > arrEnd) break;

                        ByteBuffer itemBuf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        itemBuf.position(itemStart);
                        itemBuf.limit(itemStart + (int) itemLen);
                        SisPackageInfo.DependencyItem dep = parseSingleDependencyItem(itemBuf);
                        if (dep != null) {
                            if (arrayIdx == 0) {
                                info.targetDevices.add(dep);
                            } else {
                                info.dependencies.add(dep);
                            }
                        }

                        buf.position(itemStart + (int) itemLen);
                        align4(buf);
                    }
                }
                arrayIdx++;
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static SisPackageInfo.DependencyItem parseSingleDependencyItem(ByteBuffer buf) {
        int uid = 0;
        int maj = 0, min = 0, bld = 0;
        String name = "";

        while (buf.remaining() >= 8) {
            int tag = buf.getInt();
            long len = readEkaLength(buf);
            int start = buf.position();
            if (len < 0 || start + len > buf.limit()) break;

            if (tag == SisField.FIELD_UID && len >= 4) {
                uid = buf.getInt();
            } else if (tag == SisField.FIELD_VERSION_RANGE && len >= 8) {
                int vTag = buf.getInt();
                long vLen = readEkaLength(buf);
                if (vTag == SisField.FIELD_VERSION && vLen >= 12 && buf.remaining() >= 12) {
                    maj = buf.getInt();
                    min = buf.getInt();
                    bld = buf.getInt();
                }
            } else if (tag == SisField.FIELD_ARRAY && len >= 8) {
                String extracted = extractFirstStringFromBlock(buf.duplicate().order(ByteOrder.LITTLE_ENDIAN));
                if (extracted != null) name = extracted;
            }
            buf.position(start + (int) len);
            align4(buf);
        }
        return (uid != 0) ? new SisPackageInfo.DependencyItem(uid, maj, min, bld, name) : null;
    }
	

    private static List<String> parseSupportedOptions(ByteBuffer buf, int len) {
        List<String> options = new ArrayList<String>();
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4) {
                    buf.getInt();
                    while (buf.position() + 4 <= arrEnd) {
                        long optLen = readEkaLength(buf);
                        int optStart = buf.position();
                        if (optLen < 0 || optStart + optLen > arrEnd) break;

                        ByteBuffer optBuf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        optBuf.position(optStart);
                        optBuf.limit(optStart + (int) optLen);
                        String label = extractFirstStringFromBlock(optBuf);
                        if (label != null && !label.isEmpty()) {
                            options.add(label);
                        }

                        buf.position(optStart + (int) optLen);
                        align4(buf);
                    }
                }
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
        return options;
    }

    private static String extractFirstStringFromBlock(ByteBuffer buf) {
        while (buf.remaining() >= 8) {
            int type = buf.getInt();
            long len = readEkaLength(buf);
            int start = buf.position();
            if (len < 0 || start + len > buf.limit()) break;

            if (type == SisField.FIELD_STRING && len > 0) {
                return readUtf16String(buf, (int) len);
            } else if (type == SisField.FIELD_ARRAY && len >= 8) {
                int elemType = buf.getInt();
                if (elemType == SisField.FIELD_STRING) {
                    long sLen = readEkaLength(buf);
                    if (sLen > 0 && buf.position() + sLen <= start + len) {
                        return readUtf16String(buf, (int) sLen);
                    }
                }
            }
            buf.position(start + (int) len);
            align4(buf);
        }
        return null;
    }

    private static void parseEmbeddedController(ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int fallbackUnitIdx, String activeCondition) {
		if (len <= 8 || buf.remaining() < len) return;
		byte[] slice = new byte[len];
		buf.get(slice);
		try {
			byte[] rawSubCtrl = extractRawSubController(slice);
			if (rawSubCtrl == null) rawSubCtrl = slice;
			rawSubCtrl = unwrapOuterControllerWrapper(rawSubCtrl);

			ByteBuffer subBuf = ByteBuffer.wrap(rawSubCtrl).order(ByteOrder.LITTLE_ENDIAN);
			SisPackageInfo subInfo = new SisPackageInfo();

			// 1. GUNAKAN LIST TERPISAH AGAR TIDAK LANGSUNG BOCOR KE outEntries UTAMA
			List<EntryWithUnit> subEntries = new ArrayList<EntryWithUnit>();
			parseControllerRecursive(subBuf, subInfo, subEntries, fallbackUnitIdx, false, activeCondition);

			int actualUnitIdx = fallbackUnitIdx;
			if (!subEntries.isEmpty()) {
				actualUnitIdx = subEntries.get(0).dataUnitIndex;
			}

			EmbeddedSisInfo embInfo = null;
			if (dirPath != null && activeDataUnits != null
				&& actualUnitIdx >= 0 && actualUnitIdx < activeDataUnits.size()) {
				embInfo = rebuildAndSaveEmbeddedSis(
					rawSubCtrl,
					activeDataUnits.get(actualUnitIdx),
					dirPath,
					extractedEmbeddedList.size() + 1,
					activeCondition
				);
			}

			if (embInfo != null) {
				extractedEmbeddedList.add(embInfo);

				if (!recursiveEmbedded) {
					// 2. MODE STANDAR: SKIP subEntries, JANGAN masukkan ke outEntries induk!
					// Cukup buat 1 baris referensi ke folder embedded_sis/
					String rawName = subInfo.appName;
					if (isPlaceholderOrEmpty(rawName) || "EPOC".equalsIgnoreCase(rawName.trim())) {
						rawName = "embedded_pkg_" + extractedEmbeddedList.size();
					}
					String cleanName = rawName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
					String uidHex = String.format("0x%08X", subInfo.uid3);
					String sisFileName = embInfo.savedSisFile != null ? embInfo.savedSisFile.getName() : (cleanName + "_" + uidHex + ".sisx");

					SisFileEntry embRef = new SisFileEntry();
					embRef.isEmbeddedSis = true;
					embRef.embeddedUid3 = subInfo.uid3;
					embRef.ifCondition = activeCondition;
					embRef.localRelPath = "embedded_sis/" + sisFileName;
					embRef.targetPath = embRef.localRelPath;
					embRef.uncompressedSize = (embInfo.savedSisFile != null) ? embInfo.savedSisFile.length() : 0;

					outEntries.add(new EntryWithUnit(embRef, actualUnitIdx));
				} else {
					// 3. MODE REKURSIF (Ceklis): Bongkar seluruh isi sub-paket ke proyek utama
					for (EntryWithUnit se : subEntries) {
						if (se.entry.ifCondition == null || se.entry.ifCondition.isEmpty()) {
							se.entry.ifCondition = activeCondition;
						}
					}
					outEntries.addAll(subEntries);
				}
			} else {
				// Fallback jika sub-paket gagal disimpan sebagai .sisx mandiri, masukkan subEntries
				for (EntryWithUnit se : subEntries) {
					if (se.entry.ifCondition == null || se.entry.ifCondition.isEmpty()) {
						se.entry.ifCondition = activeCondition;
					}
				}
				outEntries.addAll(subEntries);
			}
		} catch (Exception ignored) {}
	}
	

    /**
     * Mem-parse blok kondisi SISIf (Tag 26/28) dan SISElseIf (Tag 27/29).
     * Di Symbian 9.x (EKA2L1):
     * - SISIf berisi: [Expression (32)], [InstallBlock (23)], [Array of ElseIf (2 -> 29)]
     * - SISElseIf berisi: [Expression (32)], [InstallBlock (23)]
     */
	private static void parseIfBlockWithCondition(
		ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int unitIdx, String parentCondition) {
        int end = buf.position() + len;
        String currentCondition = parentCondition;

        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == 32) { // HANYA Tag 32 = SISExpression
                ByteBuffer exprBuf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                exprBuf.position(fStart);
                exprBuf.limit(fStart + (int) fLen);
                String decoded = decodeExpressionToPkg(exprBuf);
                if (!decoded.isEmpty()) {
                    currentCondition = (parentCondition != null && !parentCondition.isEmpty())
						? ("(" + parentCondition + " AND " + decoded + ")")
						: decoded;
                }
            } else if (type == SisField.FIELD_STRING && fLen > 0) {
                currentCondition = readUtf16String(buf, (int) fLen);
            } else if (type == SisField.FIELD_INSTALL_BLOCK || type == 19 || type == 23) {
                buf.position(fStart);
                String condToUse = (currentCondition != null && !currentCondition.isEmpty()) ? currentCondition : "1";
                parseInstallBlock(buf, (int) fLen, info, outEntries, unitIdx, condToUse);
            } else if (type == SisField.FIELD_ARRAY || type == 2) {
                buf.position(fStart);
                parseTypedArray(buf, (int) fLen, info, outEntries, unitIdx, parentCondition);
            } else if (type == SisField.FIELD_ELSE_IF || type == SisField.FIELD_IF || type == 26 || type == 27 || type == 28 || type == 29) {
                buf.position(fStart);
                parseIfBlockWithCondition(buf, (int) fLen, info, outEntries, unitIdx, parentCondition);
            } else if (type == SisField.FIELD_FILE_DESCRIPTION || type == 24 || type == 25) {
                buf.position(fStart);
                SisFileEntry entry = parseFileDescription(buf, (int) fLen);
                if (entry != null) {
                    entry.ifCondition = (currentCondition != null && !currentCondition.isEmpty()) ? currentCondition : "1";
                    outEntries.add(new EntryWithUnit(entry, unitIdx));
                }
			} else if (type == SisField.FIELD_CONTROLLER || type == 13) {
                buf.position(fStart);
                parseEmbeddedController(buf, (int) fLen, info, outEntries, unitIdx + 1, currentCondition);
            }
			

            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }
	

    private static String decodeExpressionToPkg(ByteBuffer buf) {
        int start = buf.position();
        int end = buf.limit();
        if (end - start < 8) return "";

        // Di Symbian 9.x (EKA2L1), SISExpression diawali 8 byte header: [operator (int32)] [integerValue (int32)]
        // lalu diikuti sub-TLV opsional (FIELD_STRING atau sub-SISExpression)
        int op = buf.getInt();
        int intVal = buf.getInt();

        // Validasi apakah op adalah operator Symbian valid (1..17). Jika tidak, fallback baca TLV dari awal.
        if (op < 1 || op > 17) {
            buf.position(start);
            List<String> subExprs = new ArrayList<String>();
            String strVal = "";
            while (buf.position() + 8 <= end) {
                int tag = buf.getInt();
                long len = readEkaLength(buf);
                int s = buf.position();
                if (len < 0 || s + len > end) break;
                if (tag == 30 || tag == 31 || tag == 32 || tag == 28) {
                    ByteBuffer sub = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    sub.position(s);
                    sub.limit(s + (int) len);
                    subExprs.add(decodeExpressionToPkg(sub));
                } else if (tag == SisField.FIELD_STRING && len > 0) {
                    strVal = readUtf16String(buf, (int) len);
                }
                buf.position(s + (int) len);
                align4(buf);
            }
            if (buf.remaining() >= 8) {
                op = buf.getInt();
                intVal = buf.getInt();
                return formatSymbianOp(op, intVal, strVal, subExprs);
            }
            return !strVal.isEmpty() ? "\"" + strVal + "\"" : "";
        }

        List<String> subExprs = new ArrayList<String>();
        String strVal = "";
        while (buf.position() + 8 <= end) {
            int tag = buf.getInt();
            long len = readEkaLength(buf);
            int s = buf.position();
            if (len < 0 || s + len > end) break;

            if (tag == 30 || tag == 31 || tag == 32 || tag == 28) {
                ByteBuffer sub = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                sub.position(s);
                sub.limit(s + (int) len);
                subExprs.add(decodeExpressionToPkg(sub));
            } else if (tag == SisField.FIELD_STRING && len > 0) {
                strVal = readUtf16String(buf, (int) len);
            }
            buf.position(s + (int) len);
            align4(buf);
        }

        return formatSymbianOp(op, intVal, strVal, subExprs);
    }

    private static String formatSymbianOp(int op, int intVal, String strVal, List<String> subExprs) {
        String left = subExprs.size() > 0 ? subExprs.get(0) : "";
        String right = subExprs.size() > 1 ? subExprs.get(1) : "";

        switch (op) {
            case 1:  return "(" + left + " = " + right + ")";
            case 2:  return "(" + left + " <> " + right + ")";
            case 3:  return "(" + left + " > " + right + ")";
            case 4:  return "(" + left + " < " + right + ")";
            case 5:  return "(" + left + " >= " + right + ")";
            case 6:  return "(" + left + " <= " + right + ")";
            case 7:  return "(" + left + " AND " + right + ")";
            case 8:  return "(" + left + " OR " + right + ")";
            case 9:  return "NOT " + left;
            case 10: return "EXISTS(\"" + (!strVal.isEmpty() ? strVal : left.replace("\"", "")) + "\")";
            case 11: return "EXISTS(\"" + (!strVal.isEmpty() ? strVal : left.replace("\"", "")) + "\")";
            case 12: return "APPPROP(" + left + ", " + right + ")";
            case 13: return "PACKAGE(" + (left.isEmpty() ? String.format("0x%08X", intVal) : left) + ")";
            case 14: return "option" + (intVal + 1);
            case 15: return String.format("VAR_0x%X", intVal);
            case 16: return String.format("0x%X", intVal);
            case 17: return "\"" + strVal + "\"";
            default: return !strVal.isEmpty() ? "\"" + strVal + "\"" : "";
        }
    }
	

    private static void parseInstallBlock(
		ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int unitIdx, String activeCondition) {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_FILE_DESCRIPTION || type == 24 || type == 25) {
                buf.position(fStart);
                SisFileEntry entry = parseFileDescription(buf, (int) fLen);
                if (entry != null) {
                    entry.ifCondition = activeCondition;
                    outEntries.add(new EntryWithUnit(entry, unitIdx));
                }
            } else if (type == SisField.FIELD_ARRAY || type == 2) {
                buf.position(fStart);
                parseTypedArray(buf, (int) fLen, info, outEntries, unitIdx, activeCondition);
            } else if (type == SisField.FIELD_INSTALL_BLOCK || type == 19 || type == 23) {
                buf.position(fStart);
                parseInstallBlock(buf, (int) fLen, info, outEntries, unitIdx, activeCondition);
            } else if (type == SisField.FIELD_IF || type == SisField.FIELD_ELSE_IF || type == 26 || type == 27 || type == 28 || type == 29) {
                buf.position(fStart);
                parseIfBlockWithCondition(buf, (int) fLen, info, outEntries, unitIdx, activeCondition);
            } else if (type == SisField.FIELD_CONTROLLER || type == 13) {
                buf.position(fStart);
                parseEmbeddedController(buf, (int) fLen, info, outEntries, unitIdx + 1,activeCondition);
            }

            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }
	
	/**
     * Mengekstrak satu file spesifik dari file .sisx ke disk tanpa unpack seluruh proyek.
     */
    public static boolean extractSingleFileFromSisx(File sisFile, SisFileEntry targetEntry, File outputFile) {
        try {
            byte[] fileBytes = targetEntry.rawData;
            if (fileBytes == null || fileBytes.length == 0) {
                // Jika rawData kosong, coba parse ulang secara cepat dari sisFile
                byte[] allBytes = readAllBytes(sisFile);
                // (Opsional: Anda bisa memparsing ulang data unit terkait jika rawData belum dimuat)
                return false;
            }

            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(fileBytes);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
	

    private static void parseTypedArray(
		ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int unitIdx, String activeCondition) {
        int end = buf.position() + len;
        if (buf.remaining() < 4) return;
        int elemType = buf.getInt();

        int embeddedCounter = 0;
        while (buf.position() + 4 <= end) {
            long itemLen = readEkaLength(buf);
            int itemStart = buf.position();
            if (itemLen < 0 || itemStart + itemLen > end) break;

            if (elemType == SisField.FIELD_FILE_DESCRIPTION || elemType == 24 || elemType == 25) {
                buf.position(itemStart);
                SisFileEntry entry = parseFileDescription(buf, (int) itemLen);
                if (entry != null) {
                    entry.ifCondition = activeCondition;
                    outEntries.add(new EntryWithUnit(entry, unitIdx));
                }
            } else if (elemType == SisField.FIELD_IF || elemType == SisField.FIELD_ELSE_IF || elemType == 26 || elemType == 27 || elemType == 28 || elemType == 29) {
                buf.position(itemStart);
                parseIfBlockWithCondition(buf, (int) itemLen, info, outEntries, unitIdx, activeCondition);
            } else if (elemType == SisField.FIELD_INSTALL_BLOCK || elemType == 19 || elemType == 23) {
                buf.position(itemStart);
                parseInstallBlock(buf, (int) itemLen, info, outEntries, unitIdx, activeCondition);
            } else if (elemType == SisField.FIELD_CONTROLLER || elemType == 13) {
                buf.position(itemStart);
                embeddedCounter++;
                parseEmbeddedController(buf, (int) itemLen, info, outEntries, unitIdx + embeddedCounter,activeCondition);
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
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_STRING && entry.targetPath == null) {
                entry.targetPath = readUtf16String(buf, (int) fLen);
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }

        if (end - buf.position() >= 28) {
            buf.position(end - 28);
            entry.operation = buf.getInt();
            entry.operationOptions = buf.getInt();
            entry.compressedSize = buf.getLong();
            entry.uncompressedSize = buf.getLong();
            entry.fileIndex = buf.getInt();
        } else {
            ByteBuffer fallbackBuf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            fallbackBuf.position(end - 4);
            if (fallbackBuf.remaining() >= 4) {
                entry.fileIndex = fallbackBuf.getInt();
            }
        }

        buf.position(end);
        return (entry.targetPath != null && !entry.targetPath.isEmpty()) ? entry : null;
    }

    private static void parseInfoBlock(ByteBuffer buf, int len, SisPackageInfo info) {
        int end = buf.position() + len;
        String vendorUnique = null;
        List<List<String>> allStringArrays = new ArrayList<List<String>>();

        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_STRING) {
                String s = readUtf16String(buf, (int) fLen);
                if (s != null && !s.trim().isEmpty() && vendorUnique == null) {
                    vendorUnique = s.trim();
                }
            } else if (type == SisField.FIELD_UID) {
                if (buf.remaining() >= 4 && info.uid3 == 0) {
                    info.uid3 = buf.getInt();
                }
            } else if (type == SisField.FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4) {
                    int elemType = buf.getInt();
                    if (elemType == SisField.FIELD_STRING) {
                        List<String> currentArr = new ArrayList<String>();
                        while (buf.position() + 4 <= arrEnd) {
                            long itemLen = readEkaLength(buf);
                            int itemStart = buf.position();
                            if (itemLen < 0 || itemStart + itemLen > arrEnd) break;

                            String s = readUtf16String(buf, (int) itemLen);
                            if (s != null && !s.trim().isEmpty()) {
                                currentArr.add(s.trim());
                            }
                            buf.position(itemStart + (int) itemLen);
                            align4(buf);
                        }
                        if (!currentArr.isEmpty()) {
                            allStringArrays.add(currentArr);
                        }
                    }
                }
            } else if (type == SisField.FIELD_VERSION && fLen >= 12) {
                if (buf.remaining() >= 12) {
                    info.majorVersion = buf.getInt();
                    info.minorVersion = buf.getInt();
                    info.buildVersion = buf.getInt();
                }
			} else if (type == SisField.FIELD_DATE_TIME) {
                buf.position(fStart + (int) fLen);
                align4(buf);
                // Tepat setelah FIELD_DATE_TIME, tersisa 2 byte: installType & installFlags
                if (end - buf.position() >= 2) {
                    info.installType = buf.get() & 0xFF;
                    info.installFlags = buf.get() & 0xFF;
                }
                break;
            }
			

            buf.position(fStart + (int) fLen);
            align4(buf);
        }

        if (isPlaceholderOrEmpty(info.appName)) {
            if (allStringArrays.size() >= 1) {
                info.appName = allStringArrays.get(0).get(0);
            } else if (vendorUnique != null) {
                info.appName = vendorUnique;
            }
        }

        if (isPlaceholderOrEmpty(info.vendorName)) {
            if (allStringArrays.size() >= 2) {
                info.vendorName = allStringArrays.get(1).get(0);
            } else if (vendorUnique != null) {
                info.vendorName = vendorUnique;
            }
        }

        buf.position(end);
    }

    private static boolean isPlaceholderOrEmpty(String val) {
        if (val == null) return true;
        String clean = val.trim();
        return clean.isEmpty()
			|| clean.equalsIgnoreCase("UnknownApp")
			|| clean.equalsIgnoreCase("Unknown App")
			|| clean.equalsIgnoreCase("UnknownVendor")
			|| clean.equalsIgnoreCase("Unknown Vendor");
    }

    private static void parseSisDataBlock(ByteBuffer buf, int len, List<List<byte[]>> dataUnits) throws Exception {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fStart + fLen > end) break;

            if (type == SisField.FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4 && buf.getInt() == SisField.FIELD_DATA_UNIT) {
                    while (buf.position() + 4 <= arrEnd) {
                        long unitLen = readEkaLength(buf);
                        int unitStart = buf.position();
                        if (unitStart + unitLen > arrEnd) break;
                        List<byte[]> unitFiles = new ArrayList<byte[]>();
                        parseDataUnit(buf, (int) unitLen, unitFiles);
                        dataUnits.add(unitFiles);
                        buf.position(unitStart + (int) unitLen);
                        align4(buf);
                    }
                }
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static void parseDataUnit(ByteBuffer buf, int len, List<byte[]> unitFiles) throws Exception {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4 && buf.getInt() == SisField.FIELD_FILE_DATA) {
                    while (buf.position() + 4 <= arrEnd) {
                        long fdLen = readEkaLength(buf);
                        int fdStart = buf.position();
                        if (fdStart + fdLen > arrEnd) break;
                        byte[] compBlock = new byte[(int) fdLen];
                        buf.get(compBlock);
                        byte[] unpacked = unwrapCompressedOrRaw(compBlock);
                        unitFiles.add(unpacked != null ? unpacked : new byte[0]);
                        buf.position(fdStart + (int) fdLen);
                        align4(buf);
                    }
                }
            }
            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static byte[] unwrapCompressedOrRaw(byte[] block) throws Exception {
        if (block == null || block.length < 8) return null;
        ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        int tag = b.getInt(0);

        if (tag == SisField.FIELD_INFO || tag == SisField.FIELD_CONTROLLER || tag == 13) {
            return block;
        }

        if (tag == SisField.FIELD_COMPRESSED || tag == 3) {
            b.position(4);
            long len = readEkaLength(b);
            int headerBytes = b.position();
            if (len > 12 && headerBytes + 12 < block.length) {
                int alg = b.getInt();
                long uncompSize = b.getLong();
                int payloadStart = b.position();
                int maxAvail = block.length - payloadStart;
                int compLen = (int) Math.min(maxAvail, len - 12);

                if (alg == 0) {
                    byte[] out = new byte[compLen];
                    System.arraycopy(block, payloadStart, out, 0, compLen);
                    return out;
                } else if (alg == 1) {
                    return inflateZlibFlexible(block, payloadStart, compLen, (int) uncompSize);
                }
            }
        }
        return null;
    }

    private static byte[] inflateZlibFlexible(byte[] src, int offset, int length, int hintSize) {
        int zlibStart = offset;
        for (int z = 0; z < Math.min(16, length - 1); z++) {
            int b0 = src[offset + z] & 0xFF;
            int b1 = src[offset + z + 1] & 0xFF;
            if (b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA)) {
                zlibStart = offset + z;
                break;
            }
        }
        int actualLen = (offset + length) - zlibStart;
        if (actualLen <= 0) return null;

        try {
            Inflater inf = new Inflater();
            inf.setInput(src, zlibStart, actualLen);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(hintSize > 0 ? hintSize : actualLen * 3);
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n > 0) {
                    bos.write(buf, 0, n);
                } else if (inf.needsInput() || inf.needsDictionary()) {
                    break;
                }
            }
            inf.end();
            if (bos.size() > 0) return bos.toByteArray();
        } catch (Exception ignored) {}

        try {
            Inflater rawInf = new Inflater(true);
            rawInf.setInput(src, offset, length);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(hintSize > 0 ? hintSize : length * 3);
            byte[] buf = new byte[4096];
            while (!rawInf.finished()) {
                int n = rawInf.inflate(buf);
                if (n > 0) {
                    bos.write(buf, 0, n);
                } else if (rawInf.needsInput() || rawInf.needsDictionary()) {
                    break;
                }
            }
            rawInf.end();
            if (bos.size() > 0) return bos.toByteArray();
        } catch (Exception ignored) {}

        return null;
    }

    // =========================================================================
    // LEGACY S60v1 / S60v2 EPOC UNPACKER
    // =========================================================================
    private static SisPackageInfo unpackLegacySis(byte[] fileBytes, File outputDir) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        SisPackageInfo info = new SisPackageInfo();
        info.formatLabel = "Legacy SIS (S60v1 / S60v2)";
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

        int ptrFiles = buf.getInt(48);
        int ptrCompNames = buf.getInt(60);

        if (ptrCompNames > 0 && ptrCompNames + (8 * numLangs) <= fileBytes.length && numLangs > 0) {
            int nameLen = buf.getInt(ptrCompNames);
            int nameOff = buf.getInt(ptrCompNames + (4 * numLangs));
            if (nameOff > 0 && nameOff + nameLen <= fileBytes.length && nameLen > 0) {
                info.appName = new String(fileBytes, nameOff, nameLen, StandardCharsets.UTF_16LE).trim();
            }
        }

        int cursor = ptrFiles;
        for (int i = 0; i < numFiles && cursor + 4 <= fileBytes.length; i++) {
            int recordType = buf.getInt(cursor);
            if (recordType == 0 || recordType == 1) {
                int langCount = (recordType == 1) ? numLangs : 1;
                if (cursor + 28 + (12 * langCount) > fileBytes.length) break;

                int dstNameLen = buf.getInt(cursor + 20);
                int dstNamePtr = buf.getInt(cursor + 24);
                int dataLen = buf.getInt(cursor + 28);
                int dataPtr = buf.getInt(cursor + 28 + (4 * langCount));
                int origLen = buf.getInt(cursor + 28 + (8 * langCount));

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
                    if (outputDir != null) {
                        File outFile = new File(outputDir, entry.localRelPath);
                        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            fos.write(entry.rawData);
                        }
                    }
                }
                cursor += 28 + (12 * langCount);
            } else if (recordType == 2 || recordType == 3) {
                int exprLen = buf.getInt(cursor + 4);
                cursor += 8 + exprLen;
            } else {
                cursor += 4;
            }
        }

		if (outputDir != null && !info.files.isEmpty()) {
			generatePkgScript(info, new File(outputDir, "project.pkg"));
			SisProjectConfig legacyCfg = new SisProjectConfig();
			SisProjectConfig.syncConfigFromUnpackedInfo(info, legacyCfg);
			legacyCfg.saveToFile(outputDir);
		}
		
        return info;
    }

	public static void generatePkgScript(SisPackageInfo info, File pkgFile) throws IOException {
        String[] typeNames = { "SA", "SP", "PU", "PA", "PP" };
        String pkgType = (info.installType >= 0 && info.installType < typeNames.length)
            ? typeNames[info.installType] : "SA";

        StringBuilder sb = new StringBuilder();
        sb.append("; Auto-generated by S60Reborn\n");
        sb.append("&EN\n\n");
        sb.append("#{\"").append(info.appName).append("\"},(")
            .append(info.getFormattedUid3()).append("),")
            .append(info.getFormattedVersion()).append(",TYPE=").append(pkgType).append("\n\n");
        sb.append("%{\"").append(info.vendorName).append("\"}\n");
        sb.append(":\"").append(info.vendorName).append("\"\n\n");

        sb.append("; Target Platform\n");
        if (info.targetDevices != null && !info.targetDevices.isEmpty()) {
            for (SisPackageInfo.DependencyItem dev : info.targetDevices) {
                String dName = (dev.name != null && !dev.name.isEmpty()) ? dev.name : "Series60ProductID";
                sb.append(String.format("[0x%08X], %d, %d, %d, {\"%s\"}\n",
										dev.uid, dev.major, dev.minor, dev.build, dName));
            }
        } else {
            sb.append("[0x101F7961], 0, 0, 0, {\"Series60ProductID\"}\n");
        }
        sb.append("\n");

        if (info.dependencies != null && !info.dependencies.isEmpty()) {
            sb.append("; Software Dependencies\n");
            for (SisPackageInfo.DependencyItem dep : info.dependencies) {
                String dName = (dep.name != null && !dep.name.isEmpty()) ? dep.name : "Dependency";
                sb.append(String.format("(0x%08X), %d, %d, %d, {\"%s\"}\n",
										dep.uid, dep.major, dep.minor, dep.build, dName));
            }
            sb.append("\n");
        }

        sb.append("; Files & Conditional Blocks\n");
        String lastCondition = null;
        for (SisFileEntry f : info.files) {
            String winRelPath = f.localRelPath.replace('/', '\\');
            if (f.ifCondition != null && !f.ifCondition.isEmpty()) {
                if (!f.ifCondition.equals(lastCondition)) {
                    if (lastCondition != null) sb.append("ENDIF\n");
                    sb.append("IF ").append(f.ifCondition).append("\n");
                    lastCondition = f.ifCondition;
                }
            } else if (lastCondition != null) {
                sb.append("ENDIF\n");
                lastCondition = null;
            }
            String indent = (lastCondition != null) ? "    " : "";
            sb.append(indent).append("\"").append(winRelPath).append("\"-\"")
                .append(f.targetPath).append("\"\n");
        }
        if (lastCondition != null) sb.append("ENDIF\n");

        try (FileOutputStream fos = new FileOutputStream(pkgFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
	

    private static long readEkaLength(ByteBuffer buf) {
        if (buf.remaining() < 4) return -1;
        long len = buf.getInt() & 0xFFFFFFFFL;
        if ((len & 0x80000000L) != 0) {
            if (buf.remaining() < 4) return -1;
            long high = buf.getInt() & 0xFFFFFFFFL;
            len = ((len & 0x7FFFFFFFL) << 32) | high;
        }
        return len;
    }

    private static String sanitizeTargetPath(String symbianPath) {
        String p = symbianPath.replace('\\', '/');
        if (p.length() >= 3 && p.charAt(1) == ':' && p.charAt(2) == '/') {
            p = p.substring(3);
        } else if (p.length() >= 2 && p.charAt(1) == ':') {
            p = p.substring(2);
        }
        while (p.startsWith("/")) p = p.substring(1);
        return p.isEmpty() ? "unknown_file.bin" : p;
    }

    private static void align4(ByteBuffer buf) {
        int mod = buf.position() % 4;
        if (mod != 0 && buf.remaining() >= (4 - mod)) {
            buf.position(buf.position() + (4 - mod));
        }
    }

    private static String readUtf16String(ByteBuffer buf, int byteLen) {
        if (byteLen <= 0 || buf.remaining() < byteLen) return "";
        byte[] strBytes = new byte[byteLen];
        buf.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_16LE).trim();
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

    public static class ExtractedCertInfo {
        public int chainIndex;
        public int certIndex;
        public String fileName;
        public String subject;
        public String issuer;
        public File savedFile;
    }

	public static List<ExtractedCertInfo> extractAllCertificates(
        ByteBuffer ctrlBuf, int endLimit, SisPackageInfo info, File outputDir) {
        List<ExtractedCertInfo> certList = new ArrayList<ExtractedCertInfo>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int chainCounter = 0;

            while (ctrlBuf.position() + 8 <= endLimit) {
                int tag = ctrlBuf.getInt();
                long len = readEkaLength(ctrlBuf);
                int fStart = ctrlBuf.position();
                if (len < 0 || fStart + len > endLimit) break;

                if ((tag == SisField.FIELD_SIGNATURE_CERT_CHAIN || tag == 39) && len >= 12) {
                    ByteBuffer directSlice = ctrlBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    directSlice.position(fStart);
                    directSlice.limit(fStart + (int) len);
                    parseSingleCertChain(directSlice, chainCounter++, cf, info, outputDir, certList);
                }

                ctrlBuf.position(fStart + (int) len);
                align4(ctrlBuf);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return certList;
    }

    private static void parseSingleCertChain(
        ByteBuffer buf,
        int chainIdx,
        CertificateFactory cf,
        SisPackageInfo info,
        File outputDir,
        List<ExtractedCertInfo> outList) throws Exception {

        int end = buf.limit();
        while (buf.position() + 8 <= end) {
            int tag = buf.getInt();
            long len = readEkaLength(buf);
            int fStart = buf.position();
            if (len < 0 || fStart + len > end) break;

            if ((tag == SisField.FIELD_CERTIFICATE_CHAIN || tag == 22 || tag == 23 || tag == 37 || tag == 42) && len >= 12) {
                int arrTag = buf.getInt();
                long arrLen = readEkaLength(buf);
                int arrEnd = buf.position() + (int) arrLen;

                if (arrTag == SisField.FIELD_ARRAY && arrEnd <= fStart + len && buf.remaining() >= 4) {
                    int elemType = buf.getInt();
                    if (elemType == SisField.FIELD_BLOB || elemType == 37 || elemType == 3) {
                        int certIdx = 0;
                        while (buf.position() + 4 <= arrEnd) {
                            long certLen = readEkaLength(buf);
                            int certStart = buf.position();
                            if (certLen <= 0 || certStart + certLen > arrEnd) break;

                            byte[] certDer = new byte[(int) certLen];
                            buf.get(certDer);

                            X509Certificate x509 = (X509Certificate) cf.generateCertificate(
                                new ByteArrayInputStream(certDer));

                            ExtractedCertInfo cInfo = new ExtractedCertInfo();
                            cInfo.chainIndex = chainIdx;
                            cInfo.certIndex = certIdx;
                            cInfo.subject = x509.getSubjectX500Principal().getName();
                            cInfo.issuer = x509.getIssuerX500Principal().getName();
                            cInfo.fileName = "chain" + chainIdx + "_cert" + certIdx + ".cer";

                            if (outputDir != null) {
                                File certDir = new File(outputDir, "certificates");
                                if (!certDir.exists()) certDir.mkdirs();
                                File outCer = new File(certDir, cInfo.fileName);
                                FileOutputStream fos = new FileOutputStream(outCer);
                                try {
                                    fos.write(certDer);
                                } finally {
                                    fos.close();
                                }
                                cInfo.savedFile = outCer;
                            }

                            outList.add(cInfo);
                            info.isSigned = true;
                            if (outList.size() == 1) {
                                info.signerInfo = "Signed: " + cInfo.subject;
                            }

                            certIdx++;
                            buf.position(certStart + (int) certLen);
                            align4(buf);
                        }
                    }
                }
            }

            buf.position(fStart + (int) len);
            align4(buf);
        }
    }
	
	


    private static void saveAndRegisterCert(
        byte[] certDer,
        int chainIdx,
        int certIdx,
        CertificateFactory cf,
        SisPackageInfo info,
        File outputDir,
        List<ExtractedCertInfo> outList) throws Exception {

        X509Certificate x509 = (X509Certificate) cf.generateCertificate(
            new ByteArrayInputStream(certDer));

        ExtractedCertInfo cInfo = new ExtractedCertInfo();
        cInfo.chainIndex = chainIdx;
        cInfo.certIndex = certIdx;
        cInfo.subject = x509.getSubjectX500Principal().getName();
        cInfo.issuer = x509.getIssuerX500Principal().getName();
        cInfo.fileName = "chain" + chainIdx + "_cert" + certIdx + ".cer";

        if (outputDir != null) {
            File certDir = new File(outputDir, "certificates");
            if (!certDir.exists()) certDir.mkdirs();
            File outCer = new File(certDir, cInfo.fileName);
            FileOutputStream fos = new FileOutputStream(outCer);
            try {
                fos.write(certDer);
            } finally {
                fos.close();
            }
            cInfo.savedFile = outCer;
        }

        outList.add(cInfo);
        info.isSigned = true;
        if (outList.size() == 1) {
            info.signerInfo = "Signed: " + cInfo.subject;
        }
    }
	
    public static class EmbeddedSisInfo {
        public String appName;
        public String vendorName;
        public String uidHex;
        public int dataUnitIndex;
        public File savedSisFile;
        public int fileCount;
    }

    private static byte[] resetDataIndexToZero(byte[] rawCtrl) {
        byte[] copy = rawCtrl.clone();
        ByteBuffer b = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 8) {
            int tag = b.getInt();
            long len = readEkaLength(b);
            int fStart = b.position();
            if (len < 0 || fStart + len > copy.length) break;
            if (tag == SisField.FIELD_DATA_INDEX && len >= 4) {
                b.putInt(fStart, 0);
            }
            b.position(fStart + (int) len);
            align4(b);
        }
        return copy;
    }

    private static EmbeddedSisInfo rebuildAndSaveEmbeddedSis(
		byte[] rawSubController,
		List<byte[]> subUnitFiles,
		File outputDir,
		int embedIndex,String activeCondition) {
        try {
            SisPackageInfo subInfo = new SisPackageInfo();
            List<EntryWithUnit> subEntries = new ArrayList<EntryWithUnit>();
            ByteBuffer subCtrlBuf = ByteBuffer.wrap(rawSubController).order(ByteOrder.LITTLE_ENDIAN);
            parseControllerRecursive(subCtrlBuf, subInfo, subEntries, 0, false,activeCondition);

            String rawName = subInfo.appName;
            if (isPlaceholderOrEmpty(rawName) || "EPOC".equalsIgnoreCase(rawName.trim())) {
                rawName = "embedded_pkg_" + embedIndex;
            }
            String cleanName = rawName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            String uidHex = String.format("0x%08X", subInfo.uid3);

            if (outputDir == null) return null;
            File embedDir = new File(outputDir, "embedded_sis");
            if (!embedDir.exists()) embedDir.mkdirs();

            File outSisFile = new File(embedDir, cleanName + "_" + uidHex + ".sisx");

            byte[] fixedSubCtrl = resetDataIndexToZero(rawSubController);
            byte[] tlvController = SisField.wrapTlv(SisField.FIELD_CONTROLLER, fixedSubCtrl);

            ByteArrayOutputStream fileDataArrayBody = new ByteArrayOutputStream();
            fileDataArrayBody.write(SisField.intToLe(SisField.FIELD_FILE_DATA));

            if (subUnitFiles != null) {
                for (int i = 0; i < subUnitFiles.size(); i++) {
                    byte[] rawFile = subUnitFiles.get(i);
                    byte[] compFile = SisField.deflateBytes(rawFile);
                    byte[] tlvFileComp = SisField.buildCompressedTlv(1, rawFile.length, compFile);
                    fileDataArrayBody.write(SisField.intToLe(tlvFileComp.length));
                    fileDataArrayBody.write(SisField.pad4(tlvFileComp));
                }
            }

            byte[] tlvFileDataArray = SisField.wrapTlv(SisField.FIELD_ARRAY, fileDataArrayBody.toByteArray());

            ByteArrayOutputStream dataUnitArrayBody = new ByteArrayOutputStream();
            dataUnitArrayBody.write(SisField.intToLe(SisField.FIELD_DATA_UNIT));
            dataUnitArrayBody.write(SisField.intToLe(tlvFileDataArray.length));
            dataUnitArrayBody.write(SisField.pad4(tlvFileDataArray));

            byte[] tlvDataUnitArray = SisField.wrapTlv(SisField.FIELD_ARRAY, dataUnitArrayBody.toByteArray());
            byte[] tlvData = SisField.wrapTlv(SisField.FIELD_DATA, tlvDataUnitArray);

            ByteArrayOutputStream contentsBody = new ByteArrayOutputStream();
            contentsBody.write(tlvController);
            contentsBody.write(tlvData);
            byte[] tlvContents = SisField.wrapTlv(SisField.FIELD_CONTENTS, contentsBody.toByteArray());

            int uid1 = SisField.SISX_UID1_MAGIC;
            int uid2 = 0x00000000;
            int uid3 = (subInfo.uid3 != 0) ? subInfo.uid3 : (0x2000F000 + embedIndex);
            int checksum = SisField.calculateSymbianUidChecksum(uid1, uid2, uid3);

            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(uid1);
            header.putInt(uid2);
            header.putInt(uid3);
            header.putInt(checksum);

            try (FileOutputStream fos = new FileOutputStream(outSisFile)) {
                fos.write(header.array());
                fos.write(tlvContents);
            }

            EmbeddedSisInfo res = new EmbeddedSisInfo();
            res.appName = subInfo.appName;
            res.vendorName = subInfo.vendorName;
            res.uidHex = uidHex;
            res.savedSisFile = outSisFile;
            res.fileCount = (subUnitFiles != null) ? subUnitFiles.size() : 0;
            return res;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static byte[] extractRawSubController(byte[] slice) throws Exception {
        if (slice == null || slice.length < 8) return null;
        byte[] direct = unwrapCompressedOrRaw(slice);
        if (direct != null) return direct;

        ByteBuffer b = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 8) {
            int tag = b.getInt();
            long len = readEkaLength(b);
            int start = b.position();
            if (len < 0 || start + len > slice.length) break;

            if (tag == SisField.FIELD_INFO || tag == SisField.FIELD_CONTROLLER || tag == 13) {
                byte[] out = new byte[(int) len + 8];
                System.arraycopy(slice, start - 8, out, 0, out.length);
                return out;
            } else if (tag == SisField.FIELD_COMPRESSED || tag == 3) {
                byte[] subSlice = new byte[(int) len + 8];
                System.arraycopy(slice, start - 8, subSlice, 0, subSlice.length);
                byte[] inflated = unwrapCompressedOrRaw(subSlice);
                if (inflated != null) return inflated;
            }
            b.position(start + (int) len);
            align4(b);
        }
        return slice;
    }
}

