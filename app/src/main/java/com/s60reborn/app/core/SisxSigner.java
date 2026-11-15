package com.s60reborn.app.core;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.nio.charset.StandardCharsets;


public class SisxSigner {

	
	public static void signSisFromAssets(Context context, File inputSis, File outputSis) throws Exception {
        String[] assetFiles = context.getAssets().list("");
        String foundCer = null;
        String foundKey = null;

        if (assetFiles != null) {
            for (String name : assetFiles) {
                String lower = name.toLowerCase();
                if (lower.endsWith(".cer") || lower.endsWith(".crt") || lower.endsWith(".pem")) {
                    foundCer = name;
                } else if (lower.endsWith(".key")) {
                    foundKey = name;
                }
            }
        }

        if (foundCer != null && foundKey != null) {
            signSisFromAssets(context, inputSis, outputSis, foundCer, foundKey);
        } else {
            // Fallback otomatis jika di assets/ belum ada pasangan .cer & .key
            signSisFile(inputSis, outputSis, null, null);
        }
    }
    public static int calculateSymbianUidChecksum(int uid1, int uid2, int uid3) {
        return SisField.calculateSymbianUidChecksum(uid1, uid2, uid3);
    }

    /**
     * Dipanggil MainActivity: Sign SISX memakai .cer & .key dari User Picker (atau auto-generate jika null)
     */
    public static void signSisFile(File inputSis, File outputSis, File cerFile, File keyFile) throws Exception {
        byte[] cerBytes;
        byte[] keyBytes;

        if (cerFile != null && cerFile.exists() && keyFile != null && keyFile.exists()) {
            cerBytes = readAllBytes(cerFile);
            keyBytes = readAllBytes(keyFile);
        } else {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            cerBytes = kp.getPublic().getEncoded();
            keyBytes = kp.getPrivate().getEncoded();
        }

        applySymbianSignature(inputSis, outputSis, cerBytes, keyBytes);
    }

    /**
     * Dipanggil MainActivity: Sign SISX memakai .cer & .key bawaan dari folder assets/ APK
     */
    public static void signSisFromAssets(Context context, File inputSis, File outputSis, String cerAssetName, String keyAssetName) throws Exception {
        byte[] cerBytes = readAssetBytes(context, cerAssetName);
        byte[] keyBytes = readAssetBytes(context, keyAssetName);
        applySymbianSignature(inputSis, outputSis, cerBytes, keyBytes);
    }

    private static void applySymbianSignature(File inputSis, File outputSis, byte[] cerBytes, byte[] keyBytes) throws Exception {
        byte[] sisBytes = readAllBytes(inputSis);
        if (sisBytes.length < 24) throw new IOException("File SISX tidak valid untuk di-sign.");

        ByteBuffer buf = ByteBuffer.wrap(sisBytes).order(ByteOrder.LITTLE_ENDIAN);
        int uid1 = buf.getInt(0);

        // Jika Legacy SIS (S60v1/v2), tidak memakai certificate chain SISX -> salin langsung
        if (uid1 != SisField.SISX_UID1_MAGIC) {
            try (FileOutputStream fos = new FileOutputStream(outputSis)) {
                fos.write(sisBytes);
            }
            return;
        }

        // Bersihkan PEM header jika file .key berformat PEM ("-----BEGIN PRIVATE KEY-----")
        byte[] pkcs8Key = decodePemIfNeeded(keyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Key));

        // Hitung tanda tangan digital RSA-SHA1 (standar Symbian OS 9.x) atas payload SISX
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(privateKey);
        signer.update(sisBytes, 16, sisBytes.length - 16);
        byte[] sigBytes = signer.sign();

        // Bangun blok TLV Signature Certificate Chain (Tag 39) sesuai standar EKA2L1
        byte[] certChainBlock = buildSignatureCertChainTlv(sigBytes, cerBytes);

        // Sisipkan ke dalam SISContents (Tag 12) dan perbarui panjang FIELD_CONTENTS
        int oldContentsLen = buf.getInt(20);
        int newContentsLen = oldContentsLen + certChainBlock.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream(sisBytes.length + certChainBlock.length);
        out.write(sisBytes, 0, 20);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(newContentsLen).array());
        out.write(sisBytes, 24, sisBytes.length - 24);
        out.write(certChainBlock);

        try (FileOutputStream fos = new FileOutputStream(outputSis)) {
            fos.write(out.toByteArray());
        }
    }

    private static byte[] buildSignatureCertChainTlv(byte[] sigBytes, byte[] cerBytes) throws IOException {
        // FIELD_SIGNATURE_ALGORITHM (Tag 38) -> OID "1.2.840.113549.1.1.5" (sha1WithRSAEncryption)
        byte[] oidUtf16 = "1.2.840.113549.1.1.5".getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream algBos = new ByteArrayOutputStream();
        algBos.write(tlv(SisField.FIELD_STRING, oidUtf16.length));
        algBos.write(oidUtf16);
        byte[] algPayload = algBos.toByteArray();

        // FIELD_SIGNATURE (Tag 36) = FIELD_SIGNATURE_ALGORITHM (38) + FIELD_BLOB (37)
        ByteArrayOutputStream sigBos = new ByteArrayOutputStream();
        sigBos.write(tlv(SisField.FIELD_SIGNATURE_ALGORITHM, algPayload.length));
        sigBos.write(algPayload);
        sigBos.write(tlv(SisField.FIELD_BLOB, sigBytes.length));
        sigBos.write(sigBytes);
        writeAlign4(sigBos, sigBytes.length);
        byte[] sigPayload = sigBos.toByteArray();

        // FIELD_CERTIFICATE_CHAIN (Tag 42) -> FIELD_BLOB (37)
        ByteArrayOutputStream chainBos = new ByteArrayOutputStream();
        chainBos.write(tlv(SisField.FIELD_BLOB, cerBytes.length));
        chainBos.write(cerBytes);
        writeAlign4(chainBos, cerBytes.length);
        byte[] chainPayload = chainBos.toByteArray();

        // FIELD_SIGNATURE_CERT_CHAIN (Tag 39)
        ByteArrayOutputStream rootBos = new ByteArrayOutputStream();
        rootBos.write(tlv(SisField.FIELD_SIGNATURE, sigPayload.length));
        rootBos.write(sigPayload);
        rootBos.write(tlv(SisField.FIELD_CERTIFICATE_CHAIN, chainPayload.length));
        rootBos.write(chainPayload);
        byte[] rootPayload = rootBos.toByteArray();

        ByteArrayOutputStream finalTlv = new ByteArrayOutputStream();
        finalTlv.write(tlv(SisField.FIELD_SIGNATURE_CERT_CHAIN, rootPayload.length));
        finalTlv.write(rootPayload);
        return finalTlv.toByteArray();
    }

    private static byte[] decodePemIfNeeded(byte[] raw) {
        String text = new String(raw, StandardCharsets.US_ASCII).trim();
        if (text.startsWith("-----BEGIN")) {
            String base64 = text
				.replaceAll("-----BEGIN [^-]+-----", "")
				.replaceAll("-----END [^-]+-----", "")
				.replaceAll("\\s+", "");
            return Base64.getDecoder().decode(base64);
        }
        return raw;
    }

    private static byte[] tlv(int tag, int len) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(tag).putInt(len).array();
    }

    private static void writeAlign4(ByteArrayOutputStream bos, int len) {
        int mod = len % 4;
        if (mod != 0) {
            for (int i = 0; i < (4 - mod); i++) bos.write(0);
        }
    }

    private static byte[] readAssetBytes(Context ctx, String assetName) throws IOException {
        try (InputStream is = ctx.getAssets().open(assetName);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
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
	public static void showCertificateInfoDialog(android.content.Context ctx, java.io.File cerFile) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(cerFile);
            java.security.cert.X509Certificate cert;
            try {
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                cert = (java.security.cert.X509Certificate) cf.generateCertificate(fis);
            } finally {
                fis.close();
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
            tvBody.setPadding(0, (int)(10 * d), 0, (int)(12 * d));
            scroll.addView(tvBody);
            root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
							 android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d)));

            android.widget.Button btnClose = new android.widget.Button(ctx);
            btnClose.setText("TUTUP");
            btnClose.setTextColor(0xFF121417);
            btnClose.setBackgroundColor(0xFF00E676);
            btnClose.setTypeface(null, android.graphics.Typeface.BOLD);
            btnClose.setOnClickListener(new android.view.View.OnClickListener() {
					@Override
					public void onClick(android.view.View v) {
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

