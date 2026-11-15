package com.s60reborn.app.core;

import com.s60reborn.app.model.SisProjectConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

class SisAdvancedBuilder {

    // Expression Operator Constants (Symbian OS 9.x SISExpression)
    private static final int E_PRIM_TYPE_STRING = 10;
    private static final int E_PRIM_TYPE_OPTION = 11;
    private static final int E_PRIM_TYPE_NUMBER = 12;
    private static final int E_UNARY_OP_NOT     = 7;
    private static final int E_FUNC_PACKAGE     = 1000;
    private static final int E_FUNC_EXISTS      = 1001;

    /**
     * Membangun TLV SISSupportedOptions (Tag 16) untuk daftar checkbox opsi saat instalasi.
     */
    public static byte[] buildSupportedOptionsTlv(List<SisProjectConfig.InstallOption> options) throws IOException {
        ByteArrayOutputStream arrBody = new ByteArrayOutputStream();
        arrBody.write(SisField.intToLe(SisField.FIELD_SUPPORTED_OPTION)); // Tag 20

        if (options != null) {
            for (SisProjectConfig.InstallOption opt : options) {
                // SISSupportedOption berisi FIELD_ARRAY dari FIELD_STRING (nama opsi per bahasa)
                byte[] optItem = SisField.wrapSingleStringArray(opt.label);
                arrBody.write(SisField.intToLe(optItem.length));
                arrBody.write(SisField.pad4(optItem));
            }
        }
        return SisField.wrapTlv(SisField.FIELD_SUPPORTED_OPTIONS,
								SisField.wrapTlv(SisField.FIELD_ARRAY, arrBody.toByteArray()));
    }

    /**
     * Membangun SISExpression (Tag 29) untuk kondisi Checkbox Option ke-N (optionIndex = 0, 1, ...)
     */
    public static byte[] buildOptionCheckExpression(int optionIndex) throws IOException {
        ByteArrayOutputStream expr = new ByteArrayOutputStream();
        expr.write(SisField.intToLe(E_PRIM_TYPE_OPTION));
        expr.write(SisField.intToLe(optionIndex));
        return SisField.wrapTlv(SisField.FIELD_EXPRESSION, expr.toByteArray());
    }

    /**
     * Membangun SISExpression (Tag 29) untuk: IF NOT EXISTS("c:\sys\bin\...")
     */
    public static byte[] buildNotExistsExpression(String symbianPath) throws IOException {
        ByteArrayOutputStream strExpr = new ByteArrayOutputStream();
        strExpr.write(SisField.intToLe(E_PRIM_TYPE_STRING));
        strExpr.write(SisField.intToLe(0));
        strExpr.write(SisField.wrapStringUtf16(symbianPath));
        byte[] tlvStr = SisField.wrapTlv(SisField.FIELD_EXPRESSION, strExpr.toByteArray());

        ByteArrayOutputStream existsExpr = new ByteArrayOutputStream();
        existsExpr.write(SisField.intToLe(E_FUNC_EXISTS));
        existsExpr.write(SisField.intToLe(0));
        existsExpr.write(tlvStr);
        byte[] tlvExists = SisField.wrapTlv(SisField.FIELD_EXPRESSION, existsExpr.toByteArray());

        ByteArrayOutputStream notExpr = new ByteArrayOutputStream();
        notExpr.write(SisField.intToLe(E_UNARY_OP_NOT));
        notExpr.write(SisField.intToLe(0));
        notExpr.write(tlvExists);
        return SisField.wrapTlv(SisField.FIELD_EXPRESSION, notExpr.toByteArray());
    }

    /**
     * Membangun SISExpression (Tag 29) untuk: IF package(0xUID)
     */
    public static byte[] buildPackageExistsExpression(int targetUid) throws IOException {
        ByteArrayOutputStream numExpr = new ByteArrayOutputStream();
        numExpr.write(SisField.intToLe(E_PRIM_TYPE_NUMBER));
        numExpr.write(SisField.intToLe(targetUid));
        byte[] tlvNum = SisField.wrapTlv(SisField.FIELD_EXPRESSION, numExpr.toByteArray());

        ByteArrayOutputStream pkgExpr = new ByteArrayOutputStream();
        pkgExpr.write(SisField.intToLe(E_FUNC_PACKAGE));
        pkgExpr.write(SisField.intToLe(0));
        pkgExpr.write(tlvNum);
        return SisField.wrapTlv(SisField.FIELD_EXPRESSION, pkgExpr.toByteArray());
    }
}

