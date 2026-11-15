package com.s60reborn.app.model;

import java.util.ArrayList;
import java.util.List;

public class SisPackageInfo {
    public int uid1;
    public int uid2;
    public int uid3;
    public int uidChecksum;

    public String appName = "UnknownApp";
    public String vendorName = "UnknownVendor";
    public int majorVersion = 1;
    public int minorVersion = 0;
    public int buildVersion = 0;
    public int installType = 0; 
	public String formatLabel = "SISX (S60v3/v5/Belle)";
    public boolean isSigned = false;
    public String signerInfo = "Unsigned";
	public String installNote = ""; 
	

    public List<SisFileEntry> files = new ArrayList<>();
	public java.util.List<String> embeddedSisPaths = new java.util.ArrayList<>();
	

    public String getFormattedUid3() {
        return String.format("0x%08X", uid3);
    }

    public String getFormattedVersion() {
        return majorVersion + "," + minorVersion + "," + buildVersion;
    }
}

