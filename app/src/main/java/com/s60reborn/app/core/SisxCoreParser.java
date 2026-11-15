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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

public class SisxCoreParser {
	public static File dirPath;

    public static SisPackageInfo inspectSisx(File sisFile) throws Exception {
        byte[] fileBytes = readAllBytes(sisFile);
        if (fileBytes.length < 20) throw new IOException("File terlalu kecil.");
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);
        int uid2 = buf.getInt(4);

        if (uid1 == SisField.SISX_UID1_MAGIC) {
            return parseModernSisx(fileBytes, null, false);
        } else if (uid2 == SisField.LEGACY_SIS_UID2_MAGIC) {
            return unpackLegacySis(fileBytes, null);
        }
        throw new IOException("Format file tidak dikenali (UID1=0x" + Integer.toHexString(uid1) + ").");
    }

    public static SisPackageInfo unpackSisx(File sisFile, File outputDir) throws Exception {
		dirPath=outputDir;
        byte[] fileBytes = readAllBytes(sisFile);
        if (fileBytes.length < 20) throw new IOException("File terlalu kecil.");
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);
        int uid2 = buf.getInt(4);

        if (outputDir != null && !outputDir.exists()) outputDir.mkdirs();

        if (uid1 == SisField.SISX_UID1_MAGIC) {
            return parseModernSisx(fileBytes, outputDir, true);
        } else if (uid2 == SisField.LEGACY_SIS_UID2_MAGIC) {
            return unpackLegacySis(fileBytes, outputDir);
        }
        throw new IOException("Format file tidak dikenali.");
		
    }

	private static SisPackageInfo parseModernSisx(byte[] fileBytes, File outputDir, boolean writeToDisk) throws Exception {
		dirPath=outputDir;
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        SisPackageInfo info = new SisPackageInfo();
        info.formatLabel = "SISX (Symbian 9.x / S60v3-Belle)";
        info.uid1 = buf.getInt(0);
        info.uid2 = buf.getInt(4);
        info.uid3 = buf.getInt(8);
        info.uidChecksum = buf.getInt(12);

        // 1. EKA2L1: Baca header TLV utama (FIELD_CONTENTS = 12) mulai offset 16
        buf.position(16);
        int contentsTag = buf.getInt();
        long contentsLen = readEkaLength(buf);
        int contentsStart = buf.position();
        int contentsEnd = (int) Math.min(fileBytes.length, contentsStart + contentsLen);

        if (contentsTag != SisField.FIELD_CONTENTS || contentsLen <= 0) {
            throw new IOException(String.format(
									  "Bukan SISContents (12) valid: tag=%d, len=%d", contentsTag, contentsLen));
        }

        // 2. EKA2L1 SISContents::read() -> baca berurutan:
        //    [ControllerChecksum (34)] -> [DataChecksum (35)] -> Controller (13) -> Data (30)
        byte[] rawController = null;
        List<List<byte[]>> dataUnits = new ArrayList<>();
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
                    // Bungkus sementara dengan header FIELD_COMPRESSED agar kompatibel dengan pembaca stream
                    ByteBuffer tempBb = ByteBuffer.allocate(8 + (int) len).order(ByteOrder.LITTLE_ENDIAN);
                    tempBb.putInt(tag);
                    tempBb.putInt((int) len);
                    tempBb.put(fileBytes, bodyStart, (int) len);
                    rawController = readEkaControllerBytes(tempBb.array(), 0, tempBb.capacity());
                } else {
                    rawController = readEkaControllerBytes(fileBytes, bodyStart, (int) len);
                }
            } else if (tag == SisField.FIELD_DATA) {
                ByteBuffer dataBuf = ByteBuffer.wrap(fileBytes, bodyStart, (int) len).order(ByteOrder.LITTLE_ENDIAN);
                parseSisDataBlock(dataBuf, (int) len, dataUnits);
            }

            buf.position(bodyEnd);
            align4(buf);
        }

        if (rawController == null) {
            throw new IOException("SISController gagal didekode. Trace TLV: " + traceLog.toString());
        }

        // 3. EKA2L1 SISController::read() -> Parse seluruh tree (termasuk IF/ELSEIF & Sub-Controller)
        List<EntryWithUnit> parsedEntries = new ArrayList<>();
        ByteBuffer ctrlBuf = ByteBuffer.wrap(rawController).order(ByteOrder.LITTLE_ENDIAN);
        parseControllerRecursive(ctrlBuf, info, parsedEntries, 0);

        // 4. Deteksi multi-varian jika ada targetPath yang sama di cabang IF / ELSEIF
        Map<String, Integer> pathFreq = new HashMap<>();
        boolean hasMultipleVariants = false;
        for (EntryWithUnit ew : parsedEntries) {
            if (ew.entry.targetPath != null && !ew.entry.targetPath.isEmpty()) {
                String key = ew.entry.targetPath.toLowerCase().replace('\\', '/');
                int c = pathFreq.containsKey(key) ? pathFreq.get(key) + 1 : 1;
                pathFreq.put(key, c);
                if (c > 1) hasMultipleVariants = true;
            }
        }

        // 5. Ekstrak 100% file dari dataUnits[dataUnitIndex][fileIndex]
        Map<String, Integer> occurrenceMap = new HashMap<>();
        List<SisFileEntry> finalFiles = new ArrayList<>();

        for (EntryWithUnit ew : parsedEntries) {
            SisFileEntry entry = ew.entry;
            if (entry.targetPath == null || entry.targetPath.isEmpty()) continue;
            if (entry.operation == 4) continue; // EOpNull (FN - hapus file saat uninstall)

            int uIdx = (ew.dataUnitIndex >= 0 && ew.dataUnitIndex < dataUnits.size()) ? ew.dataUnitIndex : 0;
            if (uIdx >= dataUnits.size()) continue;
            List<byte[]> unitFiles = dataUnits.get(uIdx);
            if (entry.fileIndex < 0 || entry.fileIndex >= unitFiles.size()) continue;

			byte[] data = unitFiles.get(entry.fileIndex);
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
        }

        return info;
    }

    /**
     * EKA2L1 CSISCompressed::read() khusus untuk FIELD_CONTROLLER (Tag 13)
     */
	private static byte[] readEkaControllerBytes(byte[] fileBytes, int bodyStart, int bodyLen) throws Exception {
        if (bodyLen < 8 || bodyStart + bodyLen > fileBytes.length) return null;
        ByteBuffer b = ByteBuffer.wrap(fileBytes, bodyStart, bodyLen).order(ByteOrder.LITTLE_ENDIAN);
        int innerTag = b.getInt();

        if (innerTag == SisField.FIELD_INFO) {
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

        // Coba 1: Zlib standar (RFC 1950 - default Symbian RZip / makesis)
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

        // Coba 2: Raw Deflate (RFC 1951 - nowrap = true)
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
	public interface ParserLogger {
		void onLog(String message);
	}
	private static void parseSignatureCertChainBlock(
		ByteBuffer buf, int blockLen, SisPackageInfo info, File outputDir) {
        int end = buf.position() + blockLen;
        try {
            java.security.cert.CertificateFactory cf =
				java.security.cert.CertificateFactory.getInstance("X.509");
            int certIndex = 0;

            while (buf.position() + 8 <= end) {
                int tag = buf.getInt();
                long len = readFieldLength(buf);
                int fStart = buf.position();
                if (len < 0 || fStart + len > end) break;

                // Blok CertificateChain (Tag 42) berisi FIELD_ARRAY (Tag 2) dengan elemen FIELD_BLOB (Tag 37)
                if (tag == SisField.FIELD_CERTIFICATE_CHAIN && len >= 12) {
                    int arrTag = buf.getInt();
                    long arrLen = readFieldLength(buf);
                    int arrEnd = buf.position() + (int) arrLen;

                    if (arrTag == SisField.FIELD_ARRAY && arrEnd <= fStart + len && buf.remaining() >= 4) {
                        int elemType = buf.getInt(); // FIELD_BLOB (37)
                        if (elemType == SisField.FIELD_BLOB) {
                            while (buf.position() + 4 <= arrEnd) {
                                long certLen = readFieldLength(buf);
                                int certStart = buf.position();
                                if (certLen <= 0 || certStart + certLen > arrEnd) break;

                                byte[] certDer = new byte[(int) certLen];
                                buf.get(certDer);

                                // Parse info Subject sertifikat (misal: CN=RD Certificate)
                                java.security.cert.X509Certificate x509 =
									(java.security.cert.X509Certificate) cf.generateCertificate(
									new java.io.ByteArrayInputStream(certDer));
                                if (certIndex == 0) {
                                    info.signerInfo = "Signed: " + x509.getSubjectX500Principal().getName();
                                }

                                // Simpan file .cer jika sedang mode unpack ke disk
                                if (outputDir != null) {
                                    File certFile = new File(outputDir, "cert_" + certIndex + ".cer");
                                    FileOutputStream fos = new FileOutputStream(certFile);
                                    try {
                                        fos.write(certDer);
                                    } finally {
                                        fos.close();
                                    }
                                }
                                certIndex++;

                                buf.position(certStart + (int) certLen);
                                align4(buf);
                            }
                        }
                    }
                }

                buf.position(fStart + (int) len);
                align4(buf);
            }
        } catch (Exception e) {
            if (info.signerInfo == null || info.signerInfo.isEmpty()) {
                info.signerInfo = "Signed (SISX Certificate Chain)";
            }
        }
        buf.position(end);
    }
	public static long readFieldLength(ByteBuffer buf) {
        long len = buf.getInt() & 0xFFFFFFFFL;
        if ((len & 0x80000000L) != 0) {
            long high = buf.getInt() & 0xFFFFFFFFL;
            len = ((len & 0x7FFFFFFFL) << 32) | high;
        }
        return len;
    }

	private static void parseControllerRecursive(ByteBuffer buf, SisPackageInfo info, List<EntryWithUnit> outEntries, int defaultUnitIdx) {
        int startPos = buf.position();
        int limitPos = buf.limit();
        int currentDataUnit = defaultUnitIdx;

        // Pass 1: Cari FIELD_DATA_INDEX (Tag 40) milik controller ini (sangat penting untuk Embedded SIS seperti PIPS!)
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

            if (type == SisField.FIELD_INFO) {
                parseInfoBlock(buf, (int) len, info);
            } else if (type == SisField.FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) len, info, outEntries, currentDataUnit);
            } else if (type == SisField.FIELD_FILE_DESCRIPTION) {
                buf.position(fStart);
                SisFileEntry entry = parseFileDescription(buf, (int) len);
                if (entry != null) outEntries.add(new EntryWithUnit(entry, currentDataUnit));
            } else if (type == SisField.FIELD_ARRAY) {
                parseTypedArray(buf, (int) len, info, outEntries, currentDataUnit);
            } else if (type == SisField.FIELD_IF || type == SisField.FIELD_ELSE_IF) {
                buf.position(fStart);
                parseIfBlock(buf, (int) len, info, outEntries, currentDataUnit);
            } else if (type == SisField.FIELD_CONTROLLER) {
                parseEmbeddedController(buf, (int) len, info, outEntries, currentDataUnit + 1);
            } else if (type == SisField.FIELD_SIGNATURE_CERT_CHAIN) {
                info.isSigned = true;
                info.signerInfo = "Signed (SISX Certificate Chain)";
				parseSignatureCertChainBlock(buf,(int)len,info,dirPath);
            }else             if (type == SisField.FIELD_INFO) {
                parseInfoBlock(buf, (int) len, info);
            } else if (type == SisField.FIELD_SUPPORTED_OPTIONS) {
                List<String> opts = parseSupportedOptions(buf, (int) len);
                if (!opts.isEmpty()) {
                    // Simpan ke log/info agar terlihat opsi checkbox apa saja yang ada
                    String optSummary = "Opsi Install: " + android.text.TextUtils.join(", ", opts);
                    if (info.installNote == null || info.installNote.isEmpty()) {
                        info.installNote = optSummary;
                    } else {
                        info.installNote += "\n" + optSummary;
                    }
					
                }
            } else if (type == SisField.FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) len, info, outEntries, currentDataUnit);
            } else if (type == SisField.FIELD_FILE_DESCRIPTION) {
                buf.position(fStart);
                SisFileEntry entry = parseFileDescription(buf, (int) len);
                if (entry != null) outEntries.add(new EntryWithUnit(entry, currentDataUnit));
            } else if (type == SisField.FIELD_ARRAY) {
                parseTypedArray(buf, (int) len, info, outEntries, currentDataUnit);
            } else if (type == SisField.FIELD_IF || type == SisField.FIELD_ELSE_IF) {
                buf.position(fStart);
                parseIfBlock(buf, (int) len, info, outEntries, currentDataUnit);
            } else if (type == SisField.FIELD_CONTROLLER) {
                parseEmbeddedController(buf, (int) len, info, outEntries, currentDataUnit + 1);
            } else if (type == SisField.FIELD_SIGNATURE_CERT_CHAIN) {
                info.isSigned = true;
                info.signerInfo = "Signed (SISX Certificate Chain)";
            }
			

            buf.position(fStart + (int) len);
            align4(buf);
        }
    }
	
	/**
     * Membaca daftar opsi checkbox instalasi (FIELD_SUPPORTED_OPTIONS - Tag 16)
     */
    private static List<String> parseSupportedOptions(ByteBuffer buf, int len) {
        List<String> options = new ArrayList<>();
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4) {
                    int elemType = buf.getInt(); // FIELD_SUPPORTED_OPTION (20)
                    while (buf.position() + 4 <= arrEnd) {
                        long optLen = readEkaLength(buf);
                        int optStart = buf.position();
                        if (optLen < 0 || optStart + optLen > arrEnd) break;

                        // Di dalam FIELD_SUPPORTED_OPTION terdapat FIELD_ARRAY of FIELD_STRING (nama-nama bahasa)
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

    /**
     * Helper mengambil string UTF-16LE pertama di dalam blok sub-field (untuk label opsi / nama embedded SIS)
     */
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
	

    /**
     * Menangani Embedded SIS (SIS di dalam SIS, seperti PIPS / OpenC / Python runtime)
     * yang bisa berupa raw controller ataupun dibungkus FIELD_COMPRESSED (Tag 3)
     */
    private static void parseEmbeddedController(ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int fallbackUnitIdx) {
        if (len <= 8 || buf.remaining() < len) return;
        byte[] slice = new byte[len];
        buf.get(slice);
        try {
            byte[] rawSubCtrl = unwrapCompressedOrRaw(slice);
            if (rawSubCtrl == null) rawSubCtrl = slice;
            ByteBuffer subBuf = ByteBuffer.wrap(rawSubCtrl).order(ByteOrder.LITTLE_ENDIAN);
            // Gunakan subInfo sementara agar nama/UID utama paket tidak tertimpa oleh nama PIPS!
            SisPackageInfo subInfo = new SisPackageInfo();
            parseControllerRecursive(subBuf, subInfo, outEntries, fallbackUnitIdx);
        } catch (Exception ignored) {}
    }

    private static void parseInstallBlock(ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int unitIdx) {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_FILE_DESCRIPTION) {
                buf.position(fStart);
                SisFileEntry entry = parseFileDescription(buf, (int) fLen);
                if (entry != null) outEntries.add(new EntryWithUnit(entry, unitIdx));
            } else if (type == SisField.FIELD_ARRAY) {
                parseTypedArray(buf, (int) fLen, info, outEntries, unitIdx);
            } else if (type == SisField.FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) fLen, info, outEntries, unitIdx);
            } else if (type == SisField.FIELD_IF || type == SisField.FIELD_ELSE_IF) {
                parseIfBlock(buf, (int) fLen, info, outEntries, unitIdx);
            } else if (type == SisField.FIELD_CONTROLLER) {
                buf.position(fStart);
                parseEmbeddedController(buf, (int) fLen, info, outEntries, unitIdx + 1);
            }

            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static void parseIfBlock(ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int unitIdx) {
        int end = buf.position() + len;
        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) fLen, info, outEntries, unitIdx);
            } else if (type == SisField.FIELD_ARRAY) {
                parseTypedArray(buf, (int) fLen, info, outEntries, unitIdx);
            } else if (type == SisField.FIELD_ELSE_IF || type == SisField.FIELD_IF) {
                parseIfBlock(buf, (int) fLen, info, outEntries, unitIdx);
            } else if (type == SisField.FIELD_CONTROLLER) {
                buf.position(fStart);
                parseEmbeddedController(buf, (int) fLen, info, outEntries, unitIdx + 1);
            }

            buf.position(fStart + (int) fLen);
            align4(buf);
        }
        buf.position(end);
    }

    private static void parseTypedArray(ByteBuffer buf, int len, SisPackageInfo info, List<EntryWithUnit> outEntries, int unitIdx) {
        int end = buf.position() + len;
        if (buf.remaining() < 4) return;
        int elemType = buf.getInt();

        int embeddedCounter = 0;
        while (buf.position() + 4 <= end) {
            long itemLen = readEkaLength(buf);
            int itemStart = buf.position();
            if (itemLen < 0 || itemStart + itemLen > end) break;

            if (elemType == SisField.FIELD_FILE_DESCRIPTION) {
                SisFileEntry entry = parseFileDescription(buf, (int) itemLen);
                if (entry != null) outEntries.add(new EntryWithUnit(entry, unitIdx));
            } else if (elemType == SisField.FIELD_IF || elemType == SisField.FIELD_ELSE_IF) {
                parseIfBlock(buf, (int) itemLen, info, outEntries, unitIdx);
            } else if (elemType == SisField.FIELD_INSTALL_BLOCK) {
                parseInstallBlock(buf, (int) itemLen, info, outEntries, unitIdx);
            } else if (elemType == SisField.FIELD_CONTROLLER) {
                embeddedCounter++;
                parseEmbeddedController(buf, (int) itemLen, info, outEntries, unitIdx + embeddedCounter);
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

        // Di dalam SISFileDescription, 28 byte terakhir berisi:
        // operation(4) + options(4) + compressedSize(8) + uncompressedSize(8) + fileIndex(4)
        if (end - buf.position() >= 28) {
            // Jika posisi buffer belum tepat di 28 byte terakhir, geser kursor secara presisi ke (end - 28)
            buf.position(end - 28);
            entry.operation = buf.getInt();
            entry.operationOptions = buf.getInt();
            entry.compressedSize = buf.getLong();
            entry.uncompressedSize = buf.getLong();
            entry.fileIndex = buf.getInt();
        } else {
            // Fallback jika struktur biner sedikit bergeser: cari integer terakhir di blok ini sebagai fileIndex
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
        List<String> foundStrings = new ArrayList<>();

        while (buf.position() + 8 <= end) {
            int type = buf.getInt();
            long fLen = readEkaLength(buf);
            int fStart = buf.position();
            if (fLen < 0 || fStart + fLen > end) break;

            if (type == SisField.FIELD_STRING) {
                String s = readUtf16String(buf, (int) fLen);
                if (s != null && !s.isEmpty()) {
                    foundStrings.add(s);
                }
            } else if (type == SisField.FIELD_UID) {
                if (buf.remaining() >= 4 && info.uid3 == 0) {
                    info.uid3 = buf.getInt();
                }
            } else if (type == SisField.FIELD_ARRAY) {
                int arrEnd = fStart + (int) fLen;
                if (buf.remaining() >= 4) {
                    int elemType = buf.getInt();
                    while (buf.position() + 4 <= arrEnd) {
                        long itemLen = readEkaLength(buf);
                        int itemStart = buf.position();
                        if (itemLen < 0 || itemStart + itemLen > arrEnd) break;

                        if (elemType == SisField.FIELD_STRING) {
                            String s = readUtf16String(buf, (int) itemLen);
                            if (s != null && !s.isEmpty()) {
                                foundStrings.add(s);
                            }
                        }
                        buf.position(itemStart + (int) itemLen);
                        align4(buf);
                    }
                }
            } else if (type == SisField.FIELD_VERSION && fLen >= 12) {
                if (buf.remaining() >= 12) {
                    info.majorVersion = buf.getInt();
                    info.minorVersion = buf.getInt();
                    info.buildVersion = buf.getInt();
                }
            }

            buf.position(fStart + (int) fLen);
            align4(buf);
        }

        // Fallback raw scan UTF-16LE jika struktur standar tidak menangkap string
        if (foundStrings.isEmpty() && len > 16) {
            ByteBuffer rawScan = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            rawScan.position(end - len);
            byte[] blockBytes = new byte[len];
            rawScan.get(blockBytes);
            for (int i = 0; i < blockBytes.length - 8; i += 2) {
                int charCount = blockBytes[i] & 0xFF;
                if (charCount >= 3 && charCount <= 60 && (i + 1 + charCount * 2) <= blockBytes.length) {
                    boolean valid = true;
                    for (int c = 0; c < charCount; c++) {
                        int lo = blockBytes[i + 1 + c * 2] & 0xFF;
                        int hi = blockBytes[i + 1 + c * 2 + 1] & 0xFF;
                        if (hi != 0 || lo < 32 || lo > 126) {
                            valid = false;
                            break;
                        }
                    }
                    if (valid) {
                        String s = new String(blockBytes, i + 1, charCount * 2, StandardCharsets.UTF_16LE).trim();
                        if (!s.isEmpty() && !foundStrings.contains(s)) {
                            foundStrings.add(s);
                        }
                    }
                }
            }
        }

        // Tetapkan nama aplikasi dan vendor dari string yang berhasil dikumpulkan
        if (!foundStrings.isEmpty()) {
            if ("UnknownApp".equals(info.appName)) {
                // Jika string pertama adalah vendor, ambil string kedua sebagai appName (jika ada)
                if (foundStrings.size() > 1) {
                    info.vendorName = foundStrings.get(0);
                    info.appName = foundStrings.get(1);
                } else {
                    info.appName = foundStrings.get(0);
                }
            }
            if ("UnknownVendor".equals(info.vendorName) && foundStrings.size() > 1) {
                info.vendorName = foundStrings.get(0);
            }
        }

        buf.position(end);
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
                        List<byte[]> unitFiles = new ArrayList<>();
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
                        // Anti-null: pastikan tidak pernah menyimpan null ke unitFiles
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

        // Jika tidak terkompresi (langsung diawali FIELD_INFO = 14)
        if (tag == SisField.FIELD_INFO) {
            return block;
        }

        if (tag == SisField.FIELD_COMPRESSED) {
            b.position(4);
            long len = readEkaLength(b);
            int headerBytes = b.position(); // 8 atau 12
            if (headerBytes + len <= block.length && len > 12) {
                int alg = b.getInt();
                long uncompSize = b.getLong();
                int payloadStart = b.position();
                int compLen = (int) (len - 12);

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
        // Cari header Zlib (0x78) dalam 16 byte pertama jika ada padding
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

        // Percobaan ke-2: Raw Deflate (nowrap = true) jika tanpa 2-byte header Zlib
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

    private static byte[] fallbackFindController(byte[] fileBytes) {
        ByteBuffer b = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        // Cari FIELD_CONTROLLER (13) atau FIELD_COMPRESSED (3) pertama di dalam file
        for (int pos = 16; pos <= fileBytes.length - 24; pos += 4) {
            int t = b.getInt(pos);
            if (t == SisField.FIELD_CONTROLLER) {
                b.position(pos + 4);
                long len = readEkaLength(b);
                int start = b.position();
                if (len > 12 && start + len <= fileBytes.length) {
                    byte[] slice = new byte[(int) len];
                    System.arraycopy(fileBytes, start, slice, 0, (int) len);
                    try {
                        byte[] res = unwrapCompressedOrRaw(slice);
                        if (res != null) return res;
                    } catch (Exception ignored) {}
                }
            } else if (t == SisField.FIELD_COMPRESSED) {
                b.position(pos + 4);
                long len = readEkaLength(b);
                int start = b.position();
                if (len > 12 && start + len <= fileBytes.length) {
                    int alg = b.getInt(start);
                    long uSize = b.getLong(start + 4);
                    if (alg == 1 && uSize > 0 && uSize < 32 * 1024 * 1024) {
                        byte[] res = inflateZlibFlexible(fileBytes, start + 12, (int) (len - 12), (int) uSize);
                        if (res != null && res.length >= 16) {
                            int innerTag = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
                            if (innerTag == SisField.FIELD_INFO) return res;
                        }
                    }
                }
            }
        }
        return null;
    }
	
	

    
    // =========================================================================
    // LEGACY S60v1 / S60v2 EPOC UNPACKER (EKA2L1 sis_old.cpp)[span_0](start_span)[span_0](end_span)
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
        }
        return info;
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
        sb.append("; Target Platform: S60 3rd / 5th / Belle\n");
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

    private static String readFirstStringFromArray(ByteBuffer buf, int arrayLen) {
        int end = buf.position() + arrayLen;
        if (buf.remaining() < 4) return null;
        if (buf.getInt() == SisField.FIELD_STRING && buf.position() + 4 <= end) {
            long strLen = readEkaLength(buf);
            if (strLen >= 0 && buf.position() + strLen <= end) {
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
            while ((n = fis.read(buffer)) != -1) bos.write(buffer, 0, n);
            return bos.toByteArray();
        }
    }
}

