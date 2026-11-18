package com.s60reborn.app.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Definisi struktur, offset header, dan konstanta format SIS Legacy
 * (Symbian OS 6.0 / 7.0s / 8.x — S60v1, S60v2, UIQ 2.x, N-Gage).
 */
public final class LegacySisField {

    private LegacySisField() {}

    // =========================================================================
    // 1. MAGIC UID HEADER EPOC (S60v1 / S60v2)
    // =========================================================================
    public static final int UID1_EPOC_V1             = 0x10003A12; // Symbian OS 6.0 (S60v1)
    public static final int UID1_EPOC_V2             = 0x10201ED3; // Symbian OS 7.0s / 8.x (S60v2 / N-Gage)
    public static final int UID2_SIS_LEGACY          = 0x10000419; // KSisComponentUidValue

    // =========================================================================
    // 2. OFFSET HEADER DASAR SIS LEGACY
    // =========================================================================
    public static final int HEADER_SIZE_V1           = 64; // 0x40
    public static final int HEADER_SIZE_V2           = 96; // 0x60 (dengan extended fields)
    public static final int HEADER_SIZE              = HEADER_SIZE_V1;

    public static final int OFF_UID1                 = 0x00; // uint32
    public static final int OFF_UID2                 = 0x04; // uint32
    public static final int OFF_UID3_V1              = 0x08; // uint32 (UID3 aplikasi pada v1)
    public static final int OFF_UID_CHECKSUM         = 0x0C; // uint32
    public static final int OFF_UID3_V2              = 0x10; // uint32 (UID3 aplikasi pada v2 / 0x10201ED3)
    public static final int OFF_NUM_LANGS            = 0x12; // uint16
    public static final int OFF_NUM_FILES            = 0x14; // uint16
    public static final int OFF_NUM_REQS             = 0x16; // uint16
    public static final int OFF_SELECTED_LANG        = 0x18; // uint16
    public static final int OFF_INSTALL_DRIVE        = 0x1A; // uint16
    public static final int OFF_NUM_CAPS             = 0x1C; // uint16
    public static final int OFF_INSTALL_TYPE         = 0x1E; // uint8
    public static final int OFF_OPTIONS              = 0x20; // uint16
    public static final int OFF_TYPE                 = 0x22; // uint16
    public static final int OFF_MAJOR_VER            = 0x24; // uint16
    public static final int OFF_MINOR_VER            = 0x26; // uint16
    public static final int OFF_BUILD_VER            = 0x28; // uint16

    // Base offset tabel pointer (tambahkan +4 jika UID1 == UID1_EPOC_V2)
    public static final int OFF_PTR_LANGS_BASE       = 0x2C; // v1: 0x2C, v2: 0x30
    public static final int OFF_PTR_FILES_BASE       = 0x30; // v1: 0x30, v2: 0x34
    public static final int OFF_PTR_REQS_BASE        = 0x34; // v1: 0x34, v2: 0x38
    public static final int OFF_PTR_CERTS_BASE       = 0x38; // v1: 0x38, v2: 0x3C
    public static final int OFF_PTR_COMP_BASE        = 0x3C; // v1: 0x3C, v2: 0x40

    // Extended Header Fields khusus S60v2 (0x40..0x5F)
    public static final int OFF_V2_EXT_COMP_PTR      = 0x40; // uint32 (pointer component name)
    public static final int OFF_V2_EXT_DATA_END      = 0x44; // uint32 (batas akhir blok data)
    public static final int OFF_V2_EXT_VENDOR_ALT1   = 0x4C; // uint32 (opsional pointer vendor name)
    public static final int OFF_V2_EXT_VENDOR_ALT2   = 0x54; // uint32 (opsional pointer vendor name)

    // =========================================================================
    // 3. STRUKTUR FILE RECORD (S60v1 vs S60v2)
    // =========================================================================
    // Pada S60v2 (0x10201ED3), record diawali recType (uint16) + flags (uint16) + fileType (uint32) + reserved (uint32)
    // diikuti srcLen, srcPtr, dstLen, dstPtr (16B) + [dataLen, dataPtr, origLen]*langCount + mimeLen, mimePtr (8B)
    public static final int REC_V2_FIXED_PREFIX      = 28;   // 12B header + 16B src/dst ptrs
    public static final int REC_V2_FIXED_SUFFIX      = 8;    // mimeLen (4B) + mimePtr (4B)
    public static final int REC_V2_SINGLE_LANG_SIZE  = 48;   // 28 + 12*1 + 8 = 48 byte (0x30)

    public static final int REC_V1_FIXED_PREFIX      = 28;   // 12B header + 16B src/dst ptrs
    public static final int REC_V1_SINGLE_LANG_SIZE  = 40;   // 28 + 12*1 = 40 byte (0x28)

    // =========================================================================
    // 4. PACKAGE OPTIONS BITMASK (OFF_OPTIONS @ 0x20)
    // =========================================================================
    public static final int OPT_UNICODE              = 0x01;
    public static final int OPT_DISTRIBUTABLE        = 0x02;
    public static final int OPT_NO_COMPRESS          = 0x08;
    public static final int OPT_SHUTDOWN_APPS        = 0x10;
    public static final int OPT_NON_REMOVABLE        = 0x20;

    // =========================================================================
    // 5. PACKAGE INSTALL TYPE (OFF_INSTALL_TYPE @ 0x1E)
    // =========================================================================
    public static final int TYPE_SA                  = 0;
    public static final int TYPE_SY                  = 1;
    public static final int TYPE_OP                  = 2;
    public static final int TYPE_CO                  = 3;
    public static final int TYPE_PU                  = 4;
    public static final int TYPE_PA                  = 5;

    // =========================================================================
    // 6. FILE RECORD TYPES (Struktur Blok di ptrFiles)
    // =========================================================================
    public static final int REC_SINGLE_LANG_FILE     = 0; // File 1 bahasa
    public static final int REC_MULTI_LANG_FILE      = 1; // File multi-bahasa
    public static final int REC_IF                   = 2; // Blok IF
    public static final int REC_ELSE_IF              = 3; // Blok ELSEIF
    public static final int REC_ELSE                 = 4; // Blok ELSE
    public static final int REC_ENDIF                = 5; // Blok ENDIF

    // =========================================================================
    // 7. FILE OPERATION / SUB-TYPE
    // =========================================================================
    public static final int FILE_TYPE_STANDARD       = 0; // File install normal (FF)
    public static final int FILE_TYPE_TEXT           = 1; // Teks pop-up saat install (FT)
    public static final int FILE_TYPE_COMPONENT      = 2; // Embedded SIS (@"sub.sis")
    public static final int FILE_TYPE_RUN            = 3; // Jalankan EXE setelah install (FR)
    public static final int FILE_TYPE_NULL           = 4; // Hapus file saat uninstall (FN)
    public static final int FILE_TYPE_MIME  = 5; // MIME handler (FM)
	
	// Alias kompatibilitas untuk SisxCoreParser.java
    public static final int OFF_UID3                 = OFF_UID3_V1;
    public static final int OFF_PTR_FILES            = OFF_PTR_FILES_BASE;
    public static final int OFF_PTR_COMP_NAMES       = OFF_PTR_COMP_BASE;
	
	
	

    // =========================================================================
    // 8. HELPER VALIDASI & SHIFT HEADER
    // =========================================================================
    public static boolean isLegacySis(int uid1, int uid2) {
        return uid2 == UID2_SIS_LEGACY
            || uid1 == UID1_EPOC_V1
            || uid1 == UID1_EPOC_V2;
    }

    public static boolean isLegacySisBytes(byte[] data) {
        if (data == null || data.length < HEADER_SIZE_V1) return false;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return isLegacySis(b.getInt(OFF_UID1), b.getInt(OFF_UID2));
    }

    public static int getHeaderShift(int uid1) {
        return (uid1 == UID1_EPOC_V2) ? 4 : 0;
    }

    public static int getRecordSize(boolean isExtendedV2, int langCount) {
        int lc = Math.max(1, langCount);
        return isExtendedV2
            ? (REC_V2_FIXED_PREFIX + (12 * lc) + REC_V2_FIXED_SUFFIX)
            : (REC_V1_FIXED_PREFIX + (12 * lc));
    }
}

