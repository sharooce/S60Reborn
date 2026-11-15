package com.s60reborn.app.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

/**
 * Konstanta Tag TLV & Helper Biner Symbian OS 9.x (.sis / .sisx)
 * Sesuai spesifikasi resmi EKA2L1 (sis_fields.h / CSISFieldRoot::TFieldType)[span_1](start_span)[span_1](end_span)
 */
public final class SisField {

    private SisField() {}

    // Magic UIDs[span_2](start_span)[span_2](end_span)
    public static final int SISX_UID1_MAGIC       = 0x10201A7A; // Symbian OS 9.x (.sis / .sisx)[span_3](start_span)[span_3](end_span)
    public static final int LEGACY_SIS_UID2_MAGIC = 0x10000419; // Symbian OS 6/7/8 (.sis)[span_4](start_span)[span_4](end_span)

    // Tag TLV Utama (CSISFieldRoot::TFieldType)[span_5](start_span)[span_5](end_span)
    public static final int FIELD_STRING                    = 1;  // 0x01[span_6](start_span)[span_6](end_span)
    public static final int FIELD_ARRAY                     = 2;  // 0x02[span_7](start_span)[span_7](end_span)
    public static final int FIELD_COMPRESSED                = 3;  // 0x03[span_8](start_span)[span_8](end_span)
    public static final int FIELD_VERSION                   = 4;  // 0x04[span_9](start_span)[span_9](end_span)
    public static final int FIELD_VERSION_RANGE             = 5;  // 0x05[span_10](start_span)[span_10](end_span)
    public static final int FIELD_DATE                      = 6;  // 0x06[span_11](start_span)[span_11](end_span)
    public static final int FIELD_TIME                      = 7;  // 0x07[span_12](start_span)[span_12](end_span)
    public static final int FIELD_DATE_TIME                 = 8;  // 0x08[span_13](start_span)[span_13](end_span)
    public static final int FIELD_UID                       = 9;  // 0x09[span_14](start_span)[span_14](end_span)
    public static final int FIELD_DEPENDENCY                = 10; // 0x0A[span_15](start_span)[span_15](end_span)
    public static final int FIELD_SUPPORTED_LANGUAGE        = 11; // 0x0B[span_16](start_span)[span_16](end_span)
    public static final int FIELD_CONTENTS                  = 12; // 0x0C[span_17](start_span)[span_17](end_span)
    public static final int FIELD_CONTROLLER                = 13; // 0x0D[span_18](start_span)[span_18](end_span)
    public static final int FIELD_INFO                      = 14; // 0x0E[span_19](start_span)[span_19](end_span)
    public static final int FIELD_SUPPORTED_LANGUAGES       = 15; // 0x0F[span_20](start_span)[span_20](end_span)
    public static final int FIELD_SUPPORTED_OPTIONS         = 16; // 0x10[span_21](start_span)[span_21](end_span)
    public static final int FIELD_PREREQUISITES             = 17; // 0x11[span_22](start_span)[span_22](end_span)
    public static final int FIELD_PROPERTY                  = 18; // 0x12[span_23](start_span)[span_23](end_span)
    public static final int FIELD_PROPERTIES                = 19; // 0x13[span_24](start_span)[span_24](end_span)
    public static final int FIELD_SUPPORTED_OPTION          = 20; // 0x14[span_25](start_span)[span_25](end_span)
    public static final int FIELD_FILE_DESCRIPTION          = 24; // 0x18[span_26](start_span)[span_26](end_span)
    public static final int FIELD_HASH                      = 25; // 0x19[span_27](start_span)[span_27](end_span)
    public static final int FIELD_IF                        = 26; // 0x1A[span_28](start_span)[span_28](end_span)
    public static final int FIELD_ELSE_IF                   = 27; // 0x1B[span_29](start_span)[span_29](end_span)
    public static final int FIELD_INSTALL_BLOCK             = 28; // 0x1C[span_30](start_span)[span_30](end_span)
    public static final int FIELD_EXPRESSION                = 29; // 0x1D[span_31](start_span)[span_31](end_span)
    public static final int FIELD_DATA                      = 30; // 0x1E[span_32](start_span)[span_32](end_span)
    public static final int FIELD_DATA_UNIT                 = 31; // 0x1F[span_33](start_span)[span_33](end_span)
    public static final int FIELD_FILE_DATA                 = 32; // 0x20[span_34](start_span)[span_34](end_span)
    public static final int FIELD_LOGO                      = 33; // 0x21[span_35](start_span)[span_35](end_span)
    public static final int FIELD_CONTROLLER_CHECKSUM       = 34; // 0x22[span_36](start_span)[span_36](end_span)
    public static final int FIELD_DATA_CHECKSUM             = 35; // 0x23[span_37](start_span)[span_37](end_span)
    public static final int FIELD_SIGNATURE                 = 36; // 0x24[span_38](start_span)[span_38](end_span)
    public static final int FIELD_BLOB                      = 37; // 0x25[span_39](start_span)[span_39](end_span)
    public static final int FIELD_SIGNATURE_ALGORITHM       = 38; // 0x26[span_40](start_span)[span_40](end_span)
    public static final int FIELD_SIGNATURE_CERT_CHAIN      = 39; // 0x27[span_41](start_span)[span_41](end_span)
    public static final int FIELD_DATA_INDEX                = 40; // 0x28[span_42](start_span)[span_42](end_span)
    public static final int FIELD_CAPABILITIES              = 41; // 0x29[span_43](start_span)[span_43](end_span)
    public static final int FIELD_CERTIFICATE_CHAIN         = 42; // 0x2A[span_44](start_span)[span_44](end_span)
    public static final int FIELD_SIGNATURES                = 43; // 0x2B[span_45](start_span)[span_45](end_span)

    public static byte[] wrapTlv(int type, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + align4(data.length));
        out.write(intToLe(type));
        out.write(intToLe(data.length));
        out.write(pad4(data));
        return out.toByteArray();
    }

    public static byte[] wrapStringUtf16(String str) throws IOException {
        return wrapTlv(FIELD_STRING, (str != null ? str : "").getBytes(StandardCharsets.UTF_16LE));
    }

    public static byte[] wrapSingleStringArray(String str) throws IOException {
        byte[] sBytes = (str != null ? str : "").getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream arr = new ByteArrayOutputStream();
        arr.write(intToLe(FIELD_STRING));
        arr.write(intToLe(sBytes.length));
        arr.write(pad4(sBytes));
        return wrapTlv(FIELD_ARRAY, arr.toByteArray());
    }

    public static byte[] buildCompressedTlv(int algorithm, long uncompressedSize, byte[] deflatedBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(12 + deflatedBytes.length);
        body.write(intToLe(algorithm));
        ByteBuffer uSize = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(uncompressedSize);
        body.write(uSize.array());
        body.write(deflatedBytes);
        return wrapTlv(FIELD_COMPRESSED, body.toByteArray());
    }

    public static byte[] deflateBytes(byte[] raw) {
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

    public static byte[] pad4(byte[] data) throws IOException {
        int rem = data.length % 4;
        if (rem == 0) return data;
        int pad = 4 - rem;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + pad);
        out.write(data);
        for (int i = 0; i < pad; i++) out.write(0);
        return out.toByteArray();
    }

    public static int align4(int off) {
        int rem = off % 4;
        return (rem == 0) ? off : off + (4 - rem);
    }

    public static byte[] intToLe(int val) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(val).array();
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

    public static int crc16Ccitt(byte[] data) {
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
}

