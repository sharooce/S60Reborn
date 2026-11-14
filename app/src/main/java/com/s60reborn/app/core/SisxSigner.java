package com.s60reborn.app.core;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

public class SisxSigner {

	private static final int FIELD_CONTENTS = 12;                    // 0x0C
    private static final int FIELD_SIGNATURE_CERTIFICATE_CHAIN = 27; // 0x1B
    private static final int FIELD_SIGNATURE = 28;                   // 0x1C
    private static final int FIELD_SIGNATURE_ALGORITHM = 29;         // 0x1D
    private static final int FIELD_SIGNATURES = 30;                  // 0x1E
    private static final int FIELD_CERTIFICATE_CHAIN = 31;           // 0x1F
    private static final int FIELD_ARRAY = 33;                       // 0x21
    private static final int FIELD_BLOB = 36;                        // 0x24
	

    /**
     * Menandatangani file .sis menggunakan file sertifikat (.cer) & private key (.key) custom.
     */
    public static void signSisFile(File unsignedSis, File certFile, File keyFile, File signedSisxOut) throws Exception {
        byte[] sisBytes = readAllBytes(unsignedSis);
        X509Certificate cert = loadCertFromFile(certFile);
        PrivateKey privateKey = loadKeyFromFile(keyFile);
        writeSignedSisx(sisBytes, cert, privateKey, signedSisxOut);
    }

    /**
     * Default Self-Sign: Mencoba assets/sign.cer & sign.key; jika tidak ada/terenkripsi, otomatis generate RSA 1024-bit.
     */
    public static void signSisFromAssets(Context ctx, File unsignedSis, File signedSisxOut) throws Exception {
        byte[] sisBytes = readAllBytes(unsignedSis);
        try {
            X509Certificate cert = loadCertFromAssets(ctx, "sign.cer");
            PrivateKey privateKey = loadKeyFromAssets(ctx, "sign.key");
            writeSignedSisx(sisBytes, cert, privateKey, signedSisxOut);
        } catch (Exception e) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(1024, new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            X509Certificate selfCert = generateSelfSignedX509Certificate(kp);
            writeSignedSisx(sisBytes, selfCert, kp.getPrivate(), signedSisxOut);
        }
    }

    /**
     * Menyisipkan SISSignatureCertificateChain ke DALAM FIELD_CONTENTS (36) dan memperbarui panjangnya.
     */
    private static void writeSignedSisx(byte[] sisBytes, X509Certificate cert, PrivateKey privateKey, File signedSisxOut) throws Exception {
        ByteBuffer hdrCheck = ByteBuffer.wrap(sisBytes).order(ByteOrder.LITTLE_ENDIAN);
        int contentsTag = hdrCheck.getInt(16);
        int origContentsLen = hdrCheck.getInt(20);
        if (contentsTag != FIELD_CONTENTS) {
            throw new IOException("File .sis tidak valid (bukan SISContents=36).");
        }

        byte[] origContentsBody = new byte[origContentsLen];
        System.arraycopy(sisBytes, 24, origContentsBody, 0, origContentsLen);

        // Ekstrak raw TLV FIELD_CONTROLLER dari awal origContentsBody untuk ditandatangani
        ByteBuffer bodyBuf = ByteBuffer.wrap(origContentsBody).order(ByteOrder.LITTLE_ENDIAN);
        int ctrlLen = bodyBuf.getInt(4);
        int ctrlPaddedTotal = 8 + ((ctrlLen + 3) & ~3);

        boolean isDsa = (privateKey instanceof DSAPrivateKey) || "DSA".equalsIgnoreCase(privateKey.getAlgorithm());
        String sigAlgName = isDsa ? "SHA1withDSA" : "SHA1withRSA";
        String oidStr = isDsa ? "1.2.840.10040.4.3" : "1.2.840.113549.1.1.5";

        Signature signer = Signature.getInstance(sigAlgName);
        signer.initSign(privateKey);
        signer.update(origContentsBody, 0, ctrlPaddedTotal);
        byte[] signatureBytes = signer.sign();

        // 1. SignatureAlgorithm
        byte[] oidBytes = oidStr.getBytes(StandardCharsets.UTF_16LE);
        byte[] tlvAlg = wrapTlv(FIELD_SIGNATURE_ALGORITHM, wrapTlv(1, oidBytes));

        // 2. Signature
        ByteArrayOutputStream sigItem = new ByteArrayOutputStream();
        sigItem.write(tlvAlg);
        sigItem.write(wrapTlv(FIELD_BLOB, signatureBytes));
        byte[] tlvSig = wrapTlv(FIELD_SIGNATURE, sigItem.toByteArray());

        // 3. Array of Signatures
        ByteArrayOutputStream sigArr = new ByteArrayOutputStream();
        sigArr.write(intToLe(FIELD_SIGNATURE));
        sigArr.write(intToLe(tlvSig.length));
        sigArr.write(pad4(tlvSig));
        byte[] tlvSigArray = wrapTlv(FIELD_SIGNATURES, sigArr.toByteArray());
		
        // 4. CertificateChain: FIELD_CERTIFICATE_CHAIN(14) -> FIELD_ARRAY(2) -> FIELD_BLOB(25)
        byte[] certDer = cert.getEncoded();
        ByteArrayOutputStream certArr = new ByteArrayOutputStream();
        certArr.write(intToLe(FIELD_BLOB));
        certArr.write(intToLe(certDer.length));
        certArr.write(pad4(certDer));
        byte[] tlvCertChain = wrapTlv(FIELD_CERTIFICATE_CHAIN, wrapTlv(FIELD_ARRAY, certArr.toByteArray()));

        // 5. SISSignatureCertificateChain (34)
        ByteArrayOutputStream chainBody = new ByteArrayOutputStream();
        chainBody.write(tlvSigArray);
        chainBody.write(tlvCertChain);
        byte[] tlvSignedChain = wrapTlv(FIELD_SIGNATURE_CERTIFICATE_CHAIN, chainBody.toByteArray());

        // 6. Array of SignatureCertificateChain (FIELD_ARRAY = 2) di dalam SISContents
        ByteArrayOutputStream chainArr = new ByteArrayOutputStream();
        chainArr.write(intToLe(FIELD_SIGNATURE_CERTIFICATE_CHAIN));
        chainArr.write(intToLe(tlvSignedChain.length));
        chainArr.write(pad4(tlvSignedChain));
        byte[] tlvChainArray = wrapTlv(FIELD_ARRAY, chainArr.toByteArray());

        // 7. Gabungkan ke dalam body FIELD_CONTENTS (36)
        ByteArrayOutputStream newContentsBody = new ByteArrayOutputStream();
        newContentsBody.write(pad4(origContentsBody));
        newContentsBody.write(tlvChainArray);
        byte[] newTlvContents = wrapTlv(FIELD_CONTENTS, newContentsBody.toByteArray());

        if (signedSisxOut.getParentFile() != null && !signedSisxOut.getParentFile().exists()) {
            signedSisxOut.getParentFile().mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(signedSisxOut)) {
            fos.write(sisBytes, 0, 16); // Header 16 byte UID asli
            fos.write(newTlvContents);  // SISContents lengkap dengan SignatureChain di dalamnya
        }
    }

    private static X509Certificate generateSelfSignedX509Certificate(KeyPair kp) throws Exception {
        PublicKey pubKey = kp.getPublic();
        PrivateKey privKey = kp.getPrivate();

        byte[] serial = derInt(new BigInteger(64, new SecureRandom()).abs().toByteArray());

        byte[] sigAlgSeq = new byte[] {
            0x30, 0x0D,
            0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x05,
            0x05, 0x00
        };

        byte[] cnOid = new byte[] { 0x06, 0x03, 0x55, 0x04, 0x03 };
        byte[] cnVal = derWrap((byte) 0x13, "S60Reborn SelfSigned".getBytes(StandardCharsets.US_ASCII));
        byte[] rdnSeq = derWrap((byte) 0x30, concat(cnOid, cnVal));
        byte[] rdnSet = derWrap((byte) 0x31, rdnSeq);
        byte[] nameDn = derWrap((byte) 0x30, rdnSet);

        byte[] notBefore = derWrap((byte) 0x17, "200101000000Z".getBytes(StandardCharsets.US_ASCII));
        byte[] notAfter = derWrap((byte) 0x17, "450101000000Z".getBytes(StandardCharsets.US_ASCII));
        byte[] validity = derWrap((byte) 0x30, concat(notBefore, notAfter));

        byte[] spki = pubKey.getEncoded();

        ByteArrayOutputStream tbsBody = new ByteArrayOutputStream();
        tbsBody.write(serial);
        tbsBody.write(sigAlgSeq);
        tbsBody.write(nameDn);
        tbsBody.write(validity);
        tbsBody.write(nameDn);
        tbsBody.write(spki);
        byte[] tbsCert = derWrap((byte) 0x30, tbsBody.toByteArray());

        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(privKey);
        sig.update(tbsCert);
        byte[] sigVal = sig.sign();

        byte[] bitStrBody = new byte[sigVal.length + 1];
        bitStrBody[0] = 0x00;
        System.arraycopy(sigVal, 0, bitStrBody, 1, sigVal.length);
        byte[] sigBitString = derWrap((byte) 0x03, bitStrBody);

        ByteArrayOutputStream certOut = new ByteArrayOutputStream();
        certOut.write(tbsCert);
        certOut.write(sigAlgSeq);
        certOut.write(sigBitString);
        byte[] fullCertDer = derWrap((byte) 0x30, certOut.toByteArray());

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(fullCertDer));
    }

    public static X509Certificate loadCertFromAssets(Context ctx, String assetName) throws Exception {
        try (InputStream is = ctx.getAssets().open(assetName)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(is);
        }
    }

    public static X509Certificate loadCertFromFile(File cerFile) throws Exception {
        try (FileInputStream fis = new FileInputStream(cerFile)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(fis);
        }
    }

    public static PrivateKey loadKeyFromAssets(Context ctx, String assetName) throws Exception {
        try (InputStream is = ctx.getAssets().open(assetName);
		ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return parsePrivateKeyBytes(bos.toByteArray());
        }
    }

    public static PrivateKey loadKeyFromFile(File keyFile) throws Exception {
        return parsePrivateKeyBytes(readAllBytes(keyFile));
    }

    private static PrivateKey parsePrivateKeyBytes(byte[] rawBytes) throws Exception {
        String text = new String(rawBytes, StandardCharsets.UTF_8).trim();
        if (text.contains("DEK-Info:") || text.contains("ENCRYPTED")) {
            throw new IOException("Private key terenkripsi password.");
        }

        boolean isPkcs1Rsa = text.contains("-----BEGIN RSA PRIVATE KEY-----");
        byte[] keyDer = rawBytes;

        if (text.contains("-----BEGIN")) {
            String base64 = text
                .replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                .replaceAll("-----END [A-Z0-9 ]+-----", "")
                .replaceAll("\\s+", "");
            keyDer = Base64.decode(base64, Base64.DEFAULT);
        }

        if (isPkcs1Rsa) {
            keyDer = wrapPkcs1ToPkcs8(keyDer);
        }

        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
        } catch (Exception e1) {
            try {
                return KeyFactory.getInstance("DSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
            } catch (Exception e2) {
                return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(wrapPkcs1ToPkcs8(keyDer)));
            }
        }
    }

    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1Bytes) throws IOException {
        byte[] algId = new byte[] {
            0x30, 0x0D,
            0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01,
            0x05, 0x00
        };
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        byte[] octetHeader = encodeDerLength((byte) 0x04, pkcs1Bytes.length);
        int innerLen = version.length + algId.length + octetHeader.length + pkcs1Bytes.length;
        byte[] seqHeader = encodeDerLength((byte) 0x30, innerLen);

        ByteArrayOutputStream out = new ByteArrayOutputStream(seqHeader.length + innerLen);
        out.write(seqHeader);
        out.write(version);
        out.write(algId);
        out.write(octetHeader);
        out.write(pkcs1Bytes);
        return out.toByteArray();
    }

    private static byte[] derInt(byte[] val) throws IOException {
        if (val.length == 0) val = new byte[] { 1 };
        if ((val[0] & 0x80) != 0) {
            byte[] padded = new byte[val.length + 1];
            System.arraycopy(val, 0, padded, 1, val.length);
            val = padded;
        }
        return derWrap((byte) 0x02, val);
    }

    private static byte[] derWrap(byte tag, byte[] content) throws IOException {
        byte[] hdr = encodeDerLength(tag, content.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(hdr.length + content.length);
        out.write(hdr);
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] encodeDerLength(byte tag, int len) {
        if (len < 128) {
            return new byte[] { tag, (byte) len };
        } else if (len <= 0xFF) {
            return new byte[] { tag, (byte) 0x81, (byte) len };
        } else {
            return new byte[] { tag, (byte) 0x82, (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF) };
        }
    }

    private static byte[] concat(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.write(a);
        out.write(b);
        return out.toByteArray();
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
        int padLen = 4 - rem;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + padLen);
        out.write(data);
        for (int i = 0; i < padLen; i++) out.write(0);
        return out.toByteArray();
    }

    private static byte[] intToLe(int val) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(val).array();
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

