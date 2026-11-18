package com.s60reborn.app.core;

import android.content.Context;
import android.util.Base64;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class SisxSigner {

    public static void signSisFromAssets(Context context, File inputSis, File outputSis) throws Exception {
        signSisFile(inputSis, null, null, outputSis);
    }

    public static void signSisFile(File inputSis, File cerFile, File keyFile, File outputSis) throws Exception {
        byte[] derCer;
        PrivateKey privateKey;

        if (cerFile != null && cerFile.exists() && keyFile != null && keyFile.exists()) {
            derCer = decodePemIfNeeded(readAllBytes(cerFile));
            byte[] pkcs8Key = decodePemIfNeeded(readAllBytes(keyFile));
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Key));
        } else {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(1024);
            KeyPair kp = kpg.generateKeyPair();
            privateKey = kp.getPrivate();
            derCer = generateSelfSignedX509Der(kp.getPublic(), privateKey);
        }

        applySymbianSignature(inputSis, outputSis, derCer, privateKey);
    }

    private static void applySymbianSignature(File inputSis, File outputSis, byte[] derCer, PrivateKey privateKey) throws Exception {
        byte[] sisBytes = readAllBytes(inputSis);
        if (sisBytes.length < 32) throw new IOException("File SISX tidak valid untuk di-sign.");

        ByteBuffer buf = ByteBuffer.wrap(sisBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);

        if (uid1 != SisField.SISX_UID1_MAGIC) {
            try (FileOutputStream fos = new FileOutputStream(outputSis)) {
                fos.write(sisBytes);
            }
            return;
        }

        int contentsEnd = Math.min(sisBytes.length, 24 + buf.getInt(20));
        buf.position(24);

        int ctrlCrcBodyPos = -1;
        int ctrlBlockStart = -1;
        int ctrlBlockEnd = -1;
        byte[] rawControllerTlv = null;

        // 1. Pindai isi FIELD_CONTENTS (Tag 12): temukan Tag 34 (Checksum) & Tag 3 (Compressed Controller)
        while (buf.position() + 8 <= contentsEnd) {
            int start = buf.position();
            int tag = buf.getInt();
            int len = buf.getInt();
            int bodyStart = buf.position();
            int pad = (4 - (len % 4)) % 4;
            int nextPos = bodyStart + len + pad;

            if (tag == 34 && len >= 2) { // FIELD_CONTROLLER_CHECKSUM
                ctrlCrcBodyPos = bodyStart;
            } else if (tag == SisField.FIELD_COMPRESSED || tag == 3) {
                ctrlBlockStart = start;
                ctrlBlockEnd = nextPos;
                int alg = buf.getInt();
                long uncompSize = buf.getLong();
                byte[] compData = new byte[len - 12];
                buf.get(compData);
                rawControllerTlv = (alg == 0) ? compData : zlibInflate(compData, (int) uncompSize);
            } else if (tag == SisField.FIELD_CONTROLLER || tag == 13) {
                ctrlBlockStart = start;
                ctrlBlockEnd = nextPos;
                rawControllerTlv = new byte[len + 8];
                System.arraycopy(sisBytes, start, rawControllerTlv, 0, len + 8);
            }
            buf.position(nextPos);
        }

        if (rawControllerTlv == null || rawControllerTlv.length < 16) {
            throw new IOException("Blok Controller tidak ditemukan di dalam SISX.");
        }

        // 2. Temukan posisi Tag 40 (FIELD_DATA_INDEX) atau Tag 39 lama di dalam FIELD_CONTROLLER (Tag 13)
        ByteBuffer cBuf = ByteBuffer.wrap(rawControllerTlv).order(ByteOrder.LITTLE_ENDIAN);
        int rootLen = cBuf.getInt(4);
        int cEnd = Math.min(rawControllerTlv.length, 8 + rootLen);

        cBuf.position(8);
        int signEndOffset = cEnd;
        int dataIndexOffset = -1;

        while (cBuf.position() + 8 <= cEnd) {
            int pos = cBuf.position();
            int tag = cBuf.getInt();
            int len = cBuf.getInt();
            int pad = (4 - (len % 4)) % 4;

            if (tag == 39) {
                signEndOffset = pos;
            } else if (tag == 40) {
                if (signEndOffset == cEnd) signEndOffset = pos;
                dataIndexOffset = pos;
                break;
            }
            cBuf.position(pos + 8 + len + pad);
        }

        // 3. Tanda tangani isi Controller (offset 8 s/d signEndOffset) dengan RSA-SHA1
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(privateKey);
        signer.update(rawControllerTlv, 8, signEndOffset - 8);
        byte[] sigBytes = signer.sign();

        // 4. Bangun blok Tag 39 (FIELD_SIGNATURE_CERT_CHAIN) sesuai standar Symbian 9.x
        byte[] certChainTlv = buildSignatureCertChainTlv(sigBytes, derCer);

        // 5. Susun ulang FIELD_CONTROLLER (Tag 13) dengan Tag 39 sebelum Tag 40
        ByteArrayOutputStream newCtrlBody = new ByteArrayOutputStream();
        newCtrlBody.write(rawControllerTlv, 8, signEndOffset - 8);
        newCtrlBody.write(certChainTlv);
        if (dataIndexOffset != -1 && dataIndexOffset < cEnd) {
            newCtrlBody.write(rawControllerTlv, dataIndexOffset, cEnd - dataIndexOffset);
        }

        byte[] newCtrlTlvBytes = SisField.wrapTlv(SisField.FIELD_CONTROLLER, newCtrlBody.toByteArray());

        // 6. Hitung ulang CRC16 Controller (Tag 34) agar SWInstall Nokia E72 tidak menganggap corrupt
        byte[] workingSis = sisBytes.clone();
        if (ctrlCrcBodyPos != -1 && ctrlCrcBodyPos + 2 <= workingSis.length) {
            short newCtrlCrc = calculateSymbianCrc16(newCtrlTlvBytes);
            ByteBuffer.wrap(workingSis).order(ByteOrder.LITTLE_ENDIAN).putShort(ctrlCrcBodyPos, newCtrlCrc);
        }

        // 7. Kompres ulang Controller baru menjadi FIELD_COMPRESSED (Tag 3) & rakit file SISX akhir
        byte[] newCompressedCtrl = wrapAsCompressed(newCtrlTlvBytes);

        ByteArrayOutputStream newContents = new ByteArrayOutputStream();
        newContents.write(workingSis, 24, ctrlBlockStart - 24);
        newContents.write(newCompressedCtrl);
        if (ctrlBlockEnd < contentsEnd) {
            newContents.write(workingSis, ctrlBlockEnd, contentsEnd - ctrlBlockEnd);
        }
        byte[] newContentsBytes = newContents.toByteArray();

        ByteArrayOutputStream finalSis = new ByteArrayOutputStream();
        finalSis.write(workingSis, 0, 20);
        finalSis.write(SisField.intToLe(newContentsBytes.length));
        finalSis.write(newContentsBytes);

        try (FileOutputStream fos = new FileOutputStream(outputSis)) {
            fos.write(finalSis.toByteArray());
        }
    }

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

    private static byte[] wrapAsCompressed(byte[] raw) throws IOException {
        Deflater def = new Deflater();
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream compBos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) {
            int n = def.deflate(buf);
            if (n > 0) compBos.write(buf, 0, n);
            else break;
        }
        def.end();
        byte[] compBytes = compBos.toByteArray();

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(SisField.intToLe(1)); // ECompressDeflate
        payload.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(raw.length).array());
        payload.write(compBytes);

        return SisField.wrapTlv(SisField.FIELD_COMPRESSED, payload.toByteArray());
    }

    private static byte[] zlibInflate(byte[] compData, int uncompSize) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(compData);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(uncompSize > 0 ? uncompSize : compData.length * 2);
        byte[] buf = new byte[4096];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n > 0) bos.write(buf, 0, n);
            else if (inf.needsInput() || inf.needsDictionary()) break;
        }
        inf.end();
        return bos.toByteArray();
    }

    private static byte[] buildSignatureCertChainTlv(byte[] sigBytes, byte[] cerBytes) throws IOException {
        // 1. FIELD_SIGNATURE_ALGORITHM (Tag 38) -> OID SHA1withRSA
        byte[] oidTlv = SisField.wrapStringUtf16("1.2.840.113549.1.1.5");
        byte[] algTlv = SisField.wrapTlv(SisField.FIELD_SIGNATURE_ALGORITHM, oidTlv);

        // 2. FIELD_SIGNATURE (Tag 36) -> Algorithm (38) + Blob Signature (37)
        ByteArrayOutputStream sigItemBody = new ByteArrayOutputStream();
        sigItemBody.write(algTlv);
        sigItemBody.write(SisField.wrapTlv(SisField.FIELD_BLOB, sigBytes));
        byte[] sigItemBytes = sigItemBody.toByteArray();

        ByteArrayOutputStream sigArrBody = new ByteArrayOutputStream();
        sigArrBody.write(SisField.intToLe(SisField.FIELD_SIGNATURE));
        sigArrBody.write(SisField.intToLe(sigItemBytes.length));
        sigArrBody.write(SisField.pad4(sigItemBytes));
        byte[] sigArrayTlv = SisField.wrapTlv(SisField.FIELD_ARRAY, sigArrBody.toByteArray());

        // 3. FIELD_CERTIFICATE_CHAIN (Tag 22) -> FIELD_ARRAY (Tag 2) of FIELD_BLOB (Tag 37)
        ByteArrayOutputStream certArrBody = new ByteArrayOutputStream();
        certArrBody.write(SisField.intToLe(SisField.FIELD_BLOB));
        certArrBody.write(SisField.intToLe(cerBytes.length));
        certArrBody.write(SisField.pad4(cerBytes));
        byte[] certArrayTlv = SisField.wrapTlv(SisField.FIELD_ARRAY, certArrBody.toByteArray());
        byte[] certChainTlv = SisField.wrapTlv(SisField.FIELD_CERTIFICATE_CHAIN, certArrayTlv);

        // 4. FIELD_SIGNATURE_CERT_CHAIN (Tag 39)
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

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) bos.write(buffer, 0, n);
            return bos.toByteArray();
        }
    }

    public static void showCertificateInfoDialog(Context ctx, File cerFile) {
        try {
            java.security.cert.X509Certificate cert;
            try (FileInputStream fis = new FileInputStream(cerFile)) {
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                cert = (java.security.cert.X509Certificate) cf.generateCertificate(fis);
            }

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault());
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(cert.getEncoded());
            StringBuilder fp = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) fp.append(":");
                fp.append(String.format("%02X", digest[i] & 0xFF));
            }

            String infoText =
                "Subjek (Pemilik):\n" + cert.getSubjectX500Principal().getName() + "\n\n" +
                "Penerbit (Issuer):\n" + cert.getIssuerX500Principal().getName() + "\n\n" +
                "Masa Berlaku:\n" + sdf.format(cert.getNotBefore()) + " s/d " + sdf.format(cert.getNotAfter()) + "\n\n" +
                "Nomor Seri:\n" + cert.getSerialNumber().toString(16).toUpperCase() + "\n\n" +
                "Algoritma:\n" + cert.getSigAlgName() + "\n\n" +
                "SHA-1 Fingerprint:\n" + fp.toString();

            final android.app.Dialog dialog = new android.app.Dialog(ctx);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            float d = ctx.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setBackgroundResource(com.s60reborn.app.R.drawable.dialog_glass_bg);
            int pad = (int) (18 * d);
            root.setPadding(pad, pad, pad, pad);

            android.widget.TextView tvTitle = new android.widget.TextView(ctx);
            tvTitle.setText("🔐 Detail Sertifikat (" + cerFile.getName() + ")");
            tvTitle.setTextColor(0xFF00E676);
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            root.addView(tvTitle);

            android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
            android.widget.TextView tvBody = new android.widget.TextView(ctx);
            tvBody.setText(infoText);
            tvBody.setTextColor(0xFFE0F2F1);
            tvBody.setTextSize(11.5f);
            tvBody.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvBody.setPadding(0, (int) (10 * d), 0, (int) (12 * d));
            scroll.addView(tvBody);
            root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
							 android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d)));

            android.widget.Button btnClose = new android.widget.Button(ctx);
            btnClose.setText("TUTUP");
            btnClose.setTextColor(0xFF121417);
            btnClose.setBackgroundColor(0xFF00E676);
            btnClose.setTypeface(null, android.graphics.Typeface.BOLD);
            btnClose.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            root.addView(btnClose);

            dialog.setContentView(root);
            dialog.show();
        } catch (Exception e) {
            android.widget.Toast.makeText(ctx, "Gagal membaca sertifikat: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}

