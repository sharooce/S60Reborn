package com.s60reborn.app.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

/**
 * Konstanta Tag TLV & Helper Biner Symbian OS 9.x (.sis / .sisx)
 */
public final class SisField {

    private SisField() {}

    public static final int SISX_UID1_MAGIC       = 0x10201A7A;
    public static final int LEGACY_SIS_UID2_MAGIC = 0x10000419;

    public static final int FIELD_STRING                    = 1;
    public static final int FIELD_ARRAY                     = 2;
    public static final int FIELD_COMPRESSED                = 3;
    public static final int FIELD_VERSION                   = 4;
    public static final int FIELD_VERSION_RANGE             = 5;
    public static final int FIELD_DATE                      = 6;
    public static final int FIELD_TIME                      = 7;
    public static final int FIELD_DATE_TIME                 = 8;
    public static final int FIELD_UID                       = 9;
    public static final int FIELD_DEPENDENCY                = 10;
    public static final int FIELD_SUPPORTED_LANGUAGE        = 11;
    public static final int FIELD_CONTENTS                  = 12;
    public static final int FIELD_CONTROLLER                = 13;
    public static final int FIELD_INFO                      = 14;
    public static final int FIELD_SUPPORTED_LANGUAGES       = 15;
    public static final int FIELD_SUPPORTED_OPTIONS         = 16;
    public static final int FIELD_PREREQUISITES             = 17;
    public static final int FIELD_PROPERTY                  = 18;
    public static final int FIELD_PROPERTIES                = 19;
    public static final int FIELD_SUPPORTED_OPTION          = 20;
    public static final int FIELD_FILE_DESCRIPTION          = 24;
    public static final int FIELD_HASH                      = 25;
    public static final int FIELD_IF                        = 26;
    public static final int FIELD_ELSE_IF                   = 27;
    public static final int FIELD_INSTALL_BLOCK             = 28;
    public static final int FIELD_EXPRESSION                = 29;
    public static final int FIELD_DATA                      = 30;
    public static final int FIELD_DATA_UNIT                 = 31;
    public static final int FIELD_FILE_DATA                 = 32;
    public static final int FIELD_LOGO                      = 33;
    public static final int FIELD_CONTROLLER_CHECKSUM       = 34;
    public static final int FIELD_DATA_CHECKSUM             = 35;
    public static final int FIELD_SIGNATURE                 = 36;
    public static final int FIELD_BLOB                      = 37;
    public static final int FIELD_SIGNATURE_ALGORITHM       = 38;
    public static final int FIELD_SIGNATURE_CERT_CHAIN      = 39;
    public static final int FIELD_DATA_INDEX                = 40;
    public static final int FIELD_CAPABILITIES              = 41;
    public static final int FIELD_CERTIFICATE_CHAIN         = 42;
    public static final int FIELD_SIGNATURES                = 43;

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

