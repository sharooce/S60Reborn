package com.s60reborn.app.core;

import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

public class SisManifestManager {

    // Konstanta resmi sisfieldtypes.h sesuai SisxCoreParser & SisxPacker
    public static final int SISX_UID1_MAGIC = 0x10201A7A;
    public static final int FIELD_STRING = 1;
    public static final int FIELD_COMPRESSED = 2;
    public static final int FIELD_VERSION = 3;
    public static final int FIELD_INFO = 5;
    public static final int FIELD_CONTENTS = 12;         // 0x0C
    public static final int FIELD_FILE_DESCRIPTION = 17; // 0x11
    public static final int FIELD_HASH = 18;             // 0x12
    public static final int FIELD_IF = 19;               // 0x13
    public static final int FIELD_ELSE_IF = 20;          // 0x14
    public static final int FIELD_INSTALL_BLOCK = 21;    // 0x15
    public static final int FIELD_EXPRESSION = 22;       // 0x16
    public static final int FIELD_DATA_UNIT = 23;        // 0x17
    public static final int FIELD_FILE_DATA = 24;        // 0x18
    public static final int FIELD_DATA_INDEX = 25;       // 0x19
    public static final int FIELD_CAPABILITIES = 32;     // 0x20
    public static final int FIELD_ARRAY = 33;            // 0x21
    public static final int FIELD_CONTROLLER = 34;       // 0x22
    public static final int FIELD_DATA = 35;             // 0x23
    public static final int FIELD_BLOB = 36;             // 0x24

    public static class TreeNode {
        public int nodeId;
        public int parentId;
        public int fieldType;
        public String nodeType;   // "CONTROLLER_ROOT", "CONTAINER", "TYPED_ARRAY", "FILE_DESC", "RAW_STATIC"
        public int arrayElemType; // Khusus nodeType == "TYPED_ARRAY"
        public int fileRef = -1;  // Khusus nodeType == "FILE_DESC"
        public byte[] rawPayload; // Prefix expression (pada SISIf/ElseIf) atau raw body (pada RAW_STATIC)
        public List<TreeNode> children = new ArrayList<TreeNode>();

        public TreeNode(int nodeId, int parentId, int fieldType, String nodeType) {
            this.nodeId = nodeId;
            this.parentId = parentId;
            this.fieldType = fieldType;
            this.nodeType = nodeType;
        }
    }

    public static class ProjectManifest {
        public int uid1 = SISX_UID1_MAGIC;
        public int uid2 = 0;
        public int uid3 = 0;
        public String appName = "UnknownApp";
        public String vendorName = "UnknownVendor";
        public int majorVersion = 1, minorVersion = 0, buildVersion = 0;
        public List<TreeNode> allNodes = new ArrayList<TreeNode>();
        public List<SisFileEntry> files = new ArrayList<SisFileEntry>();
    }

    // =========================================================================
    // 1. REKAM & SIMPAN header_offset.txt SAAT UNPACK
    // =========================================================================

    public static void recordAndSaveFromController(
		File manifestFile, SisPackageInfo info, byte[] controllerBytes) throws IOException {

        ProjectManifest m = new ProjectManifest();
        m.uid1 = (info.uid1 != 0) ? info.uid1 : SISX_UID1_MAGIC;
        m.uid2 = info.uid2;
        m.uid3 = info.uid3;
        m.appName = info.appName;
        m.vendorName = info.vendorName;
        m.majorVersion = info.majorVersion;
        m.minorVersion = info.minorVersion;
        m.buildVersion = info.buildVersion;
        m.files = new ArrayList<SisFileEntry>(info.files);

        int[] idGen = new int[]{0};
        TreeNode root = new TreeNode(idGen[0]++, -1, FIELD_CONTROLLER, "CONTROLLER_ROOT");
        m.allNodes.add(root);

        int startOff = 0;
        int endOff = controllerBytes.length;
        if (controllerBytes.length >= 8) {
            ByteBuffer b = ByteBuffer.wrap(controllerBytes).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) == FIELD_CONTROLLER) {
                int cLen = b.getInt(4);
                if (8 + cLen <= controllerBytes.length) {
                    startOff = 8;
                    endOff = 8 + cLen;
                }
            }
        }

        parseTlvStreamRecursive(controllerBytes, startOff, endOff, root.nodeId, idGen, m);
        saveManifest(manifestFile, m);
    }

    private static void parseTlvStreamRecursive(
		byte[] buf, int offset, int endOffset,
		int parentId, int[] idGen, ProjectManifest m) {

        while (offset + 8 <= endOffset) {
            int type = readIntLE(buf, offset);
            int len = readIntLE(buf, offset + 4);
            int bodyStart = offset + 8;
            int bodyEnd = bodyStart + len;

            if (len < 0 || bodyEnd > endOffset) break;

            // Lewati signature chain lama (27..31) agar hasil repack menjadi UNSIGNED bersih
            if (type >= 27 && type <= 31) {
                offset = align4(bodyEnd);
                continue;
            }

            if (type == FIELD_INSTALL_BLOCK) { // 21
                int myId = idGen[0]++;
                m.allNodes.add(new TreeNode(myId, parentId, type, "CONTAINER"));
                parseTlvStreamRecursive(buf, bodyStart, bodyEnd, myId, idGen, m);

            } else if (type == FIELD_IF || type == FIELD_ELSE_IF) { // 19 atau 20
                int myId = idGen[0]++;
                TreeNode ifNode = new TreeNode(myId, parentId, type, "CONTAINER");

                if (bodyStart + 8 <= bodyEnd && readIntLE(buf, bodyStart) == FIELD_EXPRESSION) {
                    int exprLen = readIntLE(buf, bodyStart + 4);
                    int exprPaddedTotal = align4(8 + exprLen);
                    if (bodyStart + exprPaddedTotal <= bodyEnd) {
                        ifNode.rawPayload = Arrays.copyOfRange(buf, bodyStart, bodyStart + exprPaddedTotal);
                        m.allNodes.add(ifNode);
                        parseTlvStreamRecursive(buf, bodyStart + exprPaddedTotal, bodyEnd, myId, idGen, m);
                    }
                } else {
                    m.allNodes.add(ifNode);
                    parseTlvStreamRecursive(buf, bodyStart, bodyEnd, myId, idGen, m);
                }

            } else if (type == FIELD_ARRAY) { // 33 (0x21)
                int myId = idGen[0]++;
                TreeNode arrNode = new TreeNode(myId, parentId, type, "TYPED_ARRAY");
                if (bodyStart + 4 <= bodyEnd) {
                    int elemType = readIntLE(buf, bodyStart);
                    arrNode.arrayElemType = elemType;
                    m.allNodes.add(arrNode);

                    int cursor = bodyStart + 4;
                    while (cursor + 4 <= bodyEnd) {
                        int itemLen = readIntLE(buf, cursor);
                        int itemStart = cursor + 4;
                        int itemEnd = itemStart + itemLen;
                        if (itemLen < 0 || itemEnd > bodyEnd) break;

                        parseArrayElement(buf, itemStart, itemEnd, elemType, myId, idGen, m);
                        cursor = align4(itemEnd);
                    }
                }

            } else if (type == FIELD_FILE_DESCRIPTION) { // 17
                int myId = idGen[0]++;
                TreeNode fdNode = new TreeNode(myId, parentId, type, "FILE_DESC");
                if (bodyEnd - bodyStart >= 28) {
                    fdNode.fileRef = readIntLE(buf, bodyEnd - 4);
                }
                m.allNodes.add(fdNode);

            } else {
                int myId = idGen[0]++;
                TreeNode statNode = new TreeNode(myId, parentId, type, "RAW_STATIC");
                statNode.rawPayload = Arrays.copyOfRange(buf, bodyStart, bodyEnd);
                m.allNodes.add(statNode);
            }

            offset = align4(bodyEnd);
        }
    }

    private static void parseArrayElement(
		byte[] buf, int itemStart, int itemEnd,
		int elemType, int parentArrayId, int[] idGen, ProjectManifest m) {

        if (elemType == FIELD_FILE_DESCRIPTION) {
            int myId = idGen[0]++;
            TreeNode fdNode = new TreeNode(myId, parentArrayId, elemType, "FILE_DESC");
            if (itemEnd - itemStart >= 28) {
                fdNode.fileRef = readIntLE(buf, itemEnd - 4);
            }
            m.allNodes.add(fdNode);

        } else if (elemType == FIELD_IF || elemType == FIELD_ELSE_IF) {
            int myId = idGen[0]++;
            TreeNode ifNode = new TreeNode(myId, parentArrayId, elemType, "CONTAINER");
            if (itemStart + 8 <= itemEnd && readIntLE(buf, itemStart) == FIELD_EXPRESSION) {
                int exprLen = readIntLE(buf, itemStart + 4);
                int exprTotal = align4(8 + exprLen);
                if (itemStart + exprTotal <= itemEnd) {
                    ifNode.rawPayload = Arrays.copyOfRange(buf, itemStart, itemStart + exprTotal);
                    m.allNodes.add(ifNode);
                    parseTlvStreamRecursive(buf, itemStart + exprTotal, itemEnd, myId, idGen, m);
                    return;
                }
            }
            m.allNodes.add(ifNode);
            parseTlvStreamRecursive(buf, itemStart, itemEnd, myId, idGen, m);

        } else if (elemType == FIELD_INSTALL_BLOCK) {
            int myId = idGen[0]++;
            m.allNodes.add(new TreeNode(myId, parentArrayId, elemType, "CONTAINER"));
            parseTlvStreamRecursive(buf, itemStart, itemEnd, myId, idGen, m);

        } else {
            int myId = idGen[0]++;
            TreeNode rawElem = new TreeNode(myId, parentArrayId, elemType, "RAW_STATIC");
            rawElem.rawPayload = Arrays.copyOfRange(buf, itemStart, itemEnd);
            m.allNodes.add(rawElem);
        }
    }

    public static void saveManifest(File manifestFile, ProjectManifest m) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(manifestFile), StandardCharsets.UTF_8));
        try {
            pw.println("[HEADER]");
            pw.printf("UID1=0x%08X%n", m.uid1);
            pw.printf("UID2=0x%08X%n", m.uid2);
            pw.printf("UID3=0x%08X%n", m.uid3);
            pw.println("NAME=" + m.appName);
            pw.println("VENDOR=" + m.vendorName);
            pw.println("VERSION=" + m.majorVersion + "." + m.minorVersion + "." + m.buildVersion);
            pw.println();

            pw.println("[TREE_STRUCTURE]");
            pw.println("# NODE_ID | PARENT_ID | FIELD_TYPE | NODE_TYPE | ARRAY_ELEM_TYPE | FILE_REF | RAW_HEX");
            for (TreeNode n : m.allNodes) {
                String hex = (n.rawPayload != null && n.rawPayload.length > 0) ? bytesToHex(n.rawPayload) : "-";
                pw.printf("%d | %d | %d | %s | %d | %d | %s%n",
						  n.nodeId, n.parentId, n.fieldType, n.nodeType, n.arrayElemType, n.fileRef, hex);
            }
            pw.println();

            pw.println("[FILES]");
            pw.println("# FILE_INDEX | LOCAL_REL_PATH | TARGET_PATH | OPERATION | OPTIONS");
            for (SisFileEntry f : m.files) {
                pw.printf("%d | %s | %s | %d | %d%n",
						  f.fileIndex, f.localRelPath, f.targetPath,
						  (f.operation > 0 ? f.operation : 1), f.operationOptions);
            }
        } finally {
            pw.close();
        }
    }

    public static ProjectManifest loadManifest(File manifestFile) throws IOException {
        ProjectManifest m = new ProjectManifest();
        String section = "";
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(manifestFile), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line;
                    continue;
                }
                if ("[HEADER]".equals(section)) {
                    String[] kv = line.split("=", 2);
                    if (kv.length < 2) continue;
                    String key = kv[0].trim();
                    if ("UID1".equals(key)) {
                        m.uid1 = (int) Long.parseLong(kv[1].trim().replace("0x", ""), 16);
                    } else if ("UID2".equals(key)) {
                        m.uid2 = (int) Long.parseLong(kv[1].trim().replace("0x", ""), 16);
                    } else if ("UID3".equals(key)) {
                        m.uid3 = (int) Long.parseLong(kv[1].trim().replace("0x", ""), 16);
                    } else if ("NAME".equals(key)) {
                        m.appName = kv[1].trim();
                    } else if ("VENDOR".equals(key)) {
                        m.vendorName = kv[1].trim();
                    } else if ("VERSION".equals(key)) {
                        String[] v = kv[1].trim().split("\\.");
                        if (v.length >= 3) {
                            m.majorVersion = Integer.parseInt(v[0]);
                            m.minorVersion = Integer.parseInt(v[1]);
                            m.buildVersion = Integer.parseInt(v[2]);
                        }
                    }
                } else if ("[TREE_STRUCTURE]".equals(section)) {
                    String[] p = line.split("\\|");
                    if (p.length >= 7) {
                        TreeNode n = new TreeNode(
							Integer.parseInt(p[0].trim()),
							Integer.parseInt(p[1].trim()),
							Integer.parseInt(p[2].trim()),
							p[3].trim()
                        );
                        n.arrayElemType = Integer.parseInt(p[4].trim());
                        n.fileRef = Integer.parseInt(p[5].trim());
                        String hex = p[6].trim();
                        if (!"-".equals(hex)) n.rawPayload = hexToBytes(hex);
                        m.allNodes.add(n);
                    }
                } else if ("[FILES]".equals(section)) {
                    String[] p = line.split("\\|");
                    if (p.length >= 5) {
                        SisFileEntry f = new SisFileEntry();
                        f.fileIndex = Integer.parseInt(p[0].trim());
                        f.localRelPath = p[1].trim();
                        f.targetPath = p[2].trim();
                        f.operation = Integer.parseInt(p[3].trim());
                        f.operationOptions = Integer.parseInt(p[4].trim());
                        m.files.add(f);
                    }
                }
            }
        } finally {
            br.close();
        }
        return m;
    }

    // =========================================================================
    // 2. REPACK / BUILD SISX DARI header_offset.txt (BOTTOM-UP - JAVA 7)
    // =========================================================================

    public static SisPackageInfo buildSisxFromManifest(File projectDir, ProjectManifest m, File outputSisFile) throws Exception {
        // Pengganti Comparator.comparingInt untuk Java 7
        Collections.sort(m.files, new Comparator<SisFileEntry>() {
				@Override
				public int compare(SisFileEntry a, SisFileEntry b) {
					return Integer.compare(a.fileIndex, b.fileIndex);
				}
			});

        List<byte[]> fileDataCompressedBlocks = new ArrayList<byte[]>();
        Map<Integer, byte[]> sha1Map = new HashMap<Integer, byte[]>();
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        Map<Integer, Integer> oldToNewDataIdx = new HashMap<Integer, Integer>();

        for (SisFileEntry f : m.files) {
            File srcFile = new File(projectDir, f.localRelPath);
            if (!srcFile.exists() || srcFile.length() == 0) {
                throw new FileNotFoundException("File aset tidak ditemukan: " + srcFile.getAbsolutePath());
            }
            byte[] raw = readAllBytes(srcFile);
            f.rawData = raw;
            f.uncompressedSize = raw.length;

            sha1.reset();
            byte[] digest = sha1.digest(raw);
            sha1Map.put(f.fileIndex, digest);

            byte[] deflated = deflateBytes(raw);
            byte[] tlvCompFile = buildCompressedTlv(1, raw.length, deflated);
            f.compressedSize = tlvCompFile.length;

            int newDataIdx = fileDataCompressedBlocks.size();
            oldToNewDataIdx.put(f.fileIndex, newDataIdx);
            fileDataCompressedBlocks.add(tlvCompFile);
        }

        // Bangun FIELD_DATA (Tag 35)
        ByteArrayOutputStream fileDataArrayBody = new ByteArrayOutputStream();
        fileDataArrayBody.write(intToLe(FIELD_FILE_DATA));
        for (byte[] compFileTlv : fileDataCompressedBlocks) {
            fileDataArrayBody.write(intToLe(compFileTlv.length));
            fileDataArrayBody.write(pad4(compFileTlv));
        }
        byte[] dataUnitBody = wrapTlv(FIELD_ARRAY, fileDataArrayBody.toByteArray());

        ByteArrayOutputStream dataUnitArrayBody = new ByteArrayOutputStream();
        dataUnitArrayBody.write(intToLe(FIELD_DATA_UNIT));
        dataUnitArrayBody.write(intToLe(dataUnitBody.length));
        dataUnitArrayBody.write(pad4(dataUnitBody));

        byte[] dataBody = wrapTlv(FIELD_ARRAY, dataUnitArrayBody.toByteArray());
        byte[] tlvData = wrapTlv(FIELD_DATA, dataBody);

        // Rekonstruksi Tree Hierarchy
        Map<Integer, TreeNode> nodeMap = new LinkedHashMap<Integer, TreeNode>();
        TreeNode rootNode = null;
        for (TreeNode n : m.allNodes) {
            n.children.clear();
            nodeMap.put(n.nodeId, n);
            if (n.parentId == -1) rootNode = n;
        }
        for (TreeNode n : m.allNodes) {
            if (n.parentId != -1 && nodeMap.containsKey(n.parentId)) {
                nodeMap.get(n.parentId).children.add(n);
            }
        }
        if (rootNode == null) {
            throw new IllegalStateException("CONTROLLER_ROOT tidak ditemukan di header_offset.txt");
        }

        // Build SISController secara Bottom-Up
        byte[] rawControllerBody = serializeChildrenBottomUp(rootNode, m, sha1Map, oldToNewDataIdx);
        byte[] compCtrl = deflateBytes(rawControllerBody);
        byte[] tlvCompCtrl = buildCompressedTlv(1, rawControllerBody.length, compCtrl);
        byte[] tlvController = wrapTlv(FIELD_CONTROLLER, tlvCompCtrl);

        // Gabungkan ke FIELD_CONTENTS (Tag 12)
        ByteArrayOutputStream contentsBody = new ByteArrayOutputStream();
        contentsBody.write(tlvController);
        contentsBody.write(tlvData);
        byte[] tlvContents = wrapTlv(FIELD_CONTENTS, contentsBody.toByteArray());

        int uid1 = SISX_UID1_MAGIC;
        int uid2 = 0x00000000;
        int checksum = SisxPacker.calculateSymbianUidChecksum(uid1, uid2, m.uid3);

        ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(uid1);
        hdr.putInt(uid2);
        hdr.putInt(m.uid3);
        hdr.putInt(checksum);

        if (outputSisFile.getParentFile() != null && !outputSisFile.getParentFile().exists()) {
            outputSisFile.getParentFile().mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(outputSisFile);
        try {
            fos.write(hdr.array());
            fos.write(tlvContents);
        } finally {
            fos.close();
        }

        SisPackageInfo outInfo = new SisPackageInfo();
        outInfo.uid1 = uid1;
        outInfo.uid2 = uid2;
        outInfo.uid3 = m.uid3;
        outInfo.uidChecksum = checksum;
        outInfo.appName = m.appName;
        outInfo.vendorName = m.vendorName;
        outInfo.majorVersion = m.majorVersion;
        outInfo.minorVersion = m.minorVersion;
        outInfo.buildVersion = m.buildVersion;
        outInfo.files = m.files;
        return outInfo;
    }

    private static byte[] serializeChildrenBottomUp(
		TreeNode parent, ProjectManifest m,
		Map<Integer, byte[]> sha1Map, Map<Integer, Integer> oldToNewDataIdx) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (parent.rawPayload != null && parent.rawPayload.length > 0) {
            out.write(parent.rawPayload);
        }
        for (TreeNode child : parent.children) {
            out.write(serializeSingleNode(child, m, sha1Map, oldToNewDataIdx));
        }
        return out.toByteArray();
    }

    private static byte[] serializeSingleNode(
		TreeNode node, ProjectManifest m,
		Map<Integer, byte[]> sha1Map, Map<Integer, Integer> oldToNewDataIdx) throws IOException {

        if ("RAW_STATIC".equals(node.nodeType)) {
            return wrapTlv(node.fieldType, node.rawPayload != null ? node.rawPayload : new byte[0]);
        }

        if ("FILE_DESC".equals(node.nodeType)) {
            SisFileEntry fe = findFile(m.files, node.fileRef);
            int mappedIdx = oldToNewDataIdx.containsKey(fe.fileIndex) ? oldToNewDataIdx.get(fe.fileIndex) : fe.fileIndex;
            byte[] fdBody = buildFileDescriptionBody(fe, sha1Map.get(fe.fileIndex), mappedIdx);
            return wrapTlv(FIELD_FILE_DESCRIPTION, fdBody);
        }

        if ("TYPED_ARRAY".equals(node.nodeType)) {
            ByteArrayOutputStream arrBody = new ByteArrayOutputStream();
            arrBody.write(intToLe(node.arrayElemType));
            for (TreeNode elem : node.children) {
                byte[] elemBody = serializeArrayElementBody(elem, m, sha1Map, oldToNewDataIdx);
                arrBody.write(intToLe(elemBody.length));
                arrBody.write(pad4(elemBody));
            }
            return wrapTlv(FIELD_ARRAY, arrBody.toByteArray());
        }

        byte[] body = serializeChildrenBottomUp(node, m, sha1Map, oldToNewDataIdx);
        return wrapTlv(node.fieldType, body);
    }

    private static byte[] serializeArrayElementBody(
		TreeNode elem, ProjectManifest m,
		Map<Integer, byte[]> sha1Map, Map<Integer, Integer> oldToNewDataIdx) throws IOException {

        if ("RAW_STATIC".equals(elem.nodeType)) {
            return elem.rawPayload != null ? elem.rawPayload : new byte[0];
        }
        if ("FILE_DESC".equals(elem.nodeType)) {
            SisFileEntry fe = findFile(m.files, elem.fileRef);
            int mappedIdx = oldToNewDataIdx.containsKey(fe.fileIndex) ? oldToNewDataIdx.get(fe.fileIndex) : fe.fileIndex;
            return buildFileDescriptionBody(fe, sha1Map.get(fe.fileIndex), mappedIdx);
        }
        return serializeChildrenBottomUp(elem, m, sha1Map, oldToNewDataIdx);
    }

    private static byte[] buildFileDescriptionBody(SisFileEntry f, byte[] sha1Hash, int mappedFileIndex) throws IOException {
        ByteArrayOutputStream fd = new ByteArrayOutputStream();
        fd.write(wrapTlv(FIELD_STRING, f.targetPath.getBytes(StandardCharsets.UTF_16LE)));
        fd.write(wrapTlv(FIELD_STRING, new byte[0]));

        ByteArrayOutputStream hashBody = new ByteArrayOutputStream();
        hashBody.write(intToLe(1)); // SHA1
        hashBody.write(wrapTlv(FIELD_BLOB, sha1Hash));
        fd.write(wrapTlv(FIELD_HASH, hashBody.toByteArray()));

        if (f.rawData != null && f.rawData.length >= 0x88) {
            String low = f.targetPath.toLowerCase();
            if ((low.endsWith(".exe") || low.endsWith(".dll"))
				&& f.rawData[16] == 'E' && f.rawData[17] == 'P' && f.rawData[18] == 'O' && f.rawData[19] == 'C') {
                byte[] caps = new byte[8];
                System.arraycopy(f.rawData, 0x80, caps, 0, 8);
                fd.write(wrapTlv(FIELD_CAPABILITIES, caps));
            }
        }

        ByteBuffer tail = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        tail.putInt(f.operation > 0 ? f.operation : 1);
        tail.putInt(f.operationOptions);
        tail.putLong(f.compressedSize);
        tail.putLong(f.uncompressedSize);
        tail.putInt(mappedFileIndex);
        fd.write(tail.array());
        return fd.toByteArray();
    }

    private static byte[] buildCompressedTlv(int algorithm, long uncompressedSize, byte[] compData) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(intToLe(algorithm));
        ByteBuffer uSize = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(uncompressedSize);
        body.write(uSize.array());
        body.write(compData);
        return wrapTlv(FIELD_COMPRESSED, body.toByteArray());
    }

    private static byte[] wrapTlv(int type, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(intToLe(type));
        out.write(intToLe(data.length));
        out.write(pad4(data));
        return out.toByteArray();
    }

    private static byte[] pad4(byte[] data) throws IOException {
        int rem = data.length % 4;
        if (rem == 0) return data;
        int pad = 4 - rem;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + pad);
        out.write(data);
        for (int i = 0; i < pad; i++) out.write(0);
        return out.toByteArray();
    }

    private static byte[] deflateBytes(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        return bos.toByteArray();
    }

    private static SisFileEntry findFile(List<SisFileEntry> files, int fileIndex) {
        for (SisFileEntry f : files) {
            if (f.fileIndex == fileIndex) return f;
        }
        return files.get(0);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
			| ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int align4(int off) {
        int rem = off % 4;
        return (rem == 0) ? off : off + (4 - rem);
    }

    private static byte[] intToLe(int val) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(val).array();
    }

    private static byte[] readAllBytes(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
				+ Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}

