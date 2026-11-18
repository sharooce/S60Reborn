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
	public long installFlags = 0;
	

    public List<SisFileEntry> files = new ArrayList<>();
	public java.util.List<String> embeddedSisPaths = new java.util.ArrayList<>();
	

    public String getFormattedUid3() {
        return String.format("0x%08X", uid3);
    }

    public String getFormattedVersion() {
        return majorVersion + "," + minorVersion + "," + buildVersion;
    }
	public static class DependencyItem {
        public int uid;
        public int major, minor, build;
        public String name;

        public DependencyItem(int uid, int major, int minor, int build, String name) {
            this.uid = uid;
            this.major = major;
            this.minor = minor;
            this.build = build;
            this.name = name;
        }
    }

    public List<DependencyItem> targetDevices = new ArrayList<DependencyItem>();
    public List<DependencyItem> dependencies = new ArrayList<DependencyItem>();
	
}

