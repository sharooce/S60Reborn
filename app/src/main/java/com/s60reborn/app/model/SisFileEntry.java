package com.s60reborn.app.model;

public class SisFileEntry {
    public String targetPath;      // Contoh: !:\sys\bin\myapp.exe
    public String localRelPath;    // Contoh: sys/bin/myapp.exe
    public int fileIndex;
    public int compressionType;    // 0 = None, 1 = Deflate (zlib)
    public long uncompressedSize;
    public long compressedSize;
    public byte[] rawData;
	public int operation = 1;           // 1 = EOpInstall, 2 = EOpText (FT), 4 = EOpRun (FR)
    public int operationOptions = 0;    // 1 = TC (TextContinue), 2 = RI (RunInstall)
    public String conditionNotExistsPath = null; // Misal: "z:\sys\bin\libc.dll"
	public String ifCondition; // Menyimpan ekspresi kondisi IF/ELSEIF
	public boolean isEmbeddedSis =false;
	public long embeddedUid3=0;
	public boolean isModified = false;
	
    public SisFileEntry() {}

    @Override
    public String toString() {
        return targetPath + " (" + uncompressedSize + " bytes)";
    }
}

