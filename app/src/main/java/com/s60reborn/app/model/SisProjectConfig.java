package com.s60reborn.app.model;

import com.s60reborn.app.core.SisField;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.s60reborn.app.core.*;

public class SisProjectConfig {
    // 0 = SISX Modern (Symbian OS 9.x / S60v3 / S60v5 / Belle - Requires Signing)
    // 1 = SIS Legacy (Symbian OS 6/7/8 / S60v1 / S60v2 - EPOC Tanpa Sertifikat)
    public int packageFormat = 0;

    // 1. Metadata & InstallType (SISInfo)
    public int installType = 0; // 0=SA, 1=SP, 2=PU, 3=PA, 4=PP
    public boolean flagShutdownApps = false; // 0x01
    public boolean flagNonRemovable = false; // 0x02
    public boolean flagRomUpgrade   = false; // 0x04
    public int targetPlatformUid    = 0x101F7961; // S60v3 Default

    // 1b. Multi-Target Devices & Software Dependencies (Tag 17)
    public List<SisPackageInfo.DependencyItem> targetDevices = new ArrayList<SisPackageInfo.DependencyItem>();
    public List<SisPackageInfo.DependencyItem> dependencies = new ArrayList<SisPackageInfo.DependencyItem>();

    // 2. Checkbox Opsi Saat Install (SISSupportedOptions + SISIf)
    public List<InstallOption> installOptions = new ArrayList<InstallOption>();

    // 3. Kondisi Pertengahan Install (SISIf)
    public List<InstallConditionRule> conditionRules = new ArrayList<InstallConditionRule>();

    // 4. Embedded Sub-SIS (@"sub.sis")
    public List<String> embeddedSisPaths = new ArrayList<String>();

    // 5. Signing & Certificate
    public int signMode = 1; // 0=Unsigned, 1=SelfSigned, 2=CustomCerKey
    public String customCerPath = "";
    public String customKeyPath = "";
    public String selfSignCn = "S60Reborn Dev";
    public boolean rebuildWithCert = false;

    public boolean isLegacySisV1V2() {
        return packageFormat == 1;
    }

    public byte getInstallFlagsByte() {
        byte flags = 0;
        if (flagShutdownApps) flags |= 0x01;
        if (flagNonRemovable) flags |= 0x02;
        if (flagRomUpgrade)   flags |= 0x04;
        return flags;
    }

    public boolean hasTargetDevice(int uid) {
        for (SisPackageInfo.DependencyItem item : targetDevices) {
            if (item.uid == uid) return true;
        }
        return targetDevices.isEmpty() && targetPlatformUid == uid;
    }

    public boolean hasDependency(int uid) {
        for (SisPackageInfo.DependencyItem item : dependencies) {
            if (item.uid == uid) return true;
        }
        return false;
    }

    public static class InstallOption {
        public String label;
        public List<String> fileRelPaths = new ArrayList<String>();

        public InstallOption(String label) {
            this.label = label;
        }
    }

    public static class InstallConditionRule {
        public int ruleType; // 0 = IF NOT EXISTS("path"), 1 = IF package(0xUID)
        public String conditionParam;
        public List<String> fileRelPaths = new ArrayList<String>();
        public List<String> targetFileRelPaths = new ArrayList<String>();
    }

    public void saveToFile(File projectDir) throws IOException {
        if (projectDir == null) return;
        if (!projectDir.exists()) projectDir.mkdirs();
        File cfgFile = new File(projectDir, "project_config.ini");

        PrintWriter pw = new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(cfgFile), StandardCharsets.UTF_8));
        try {
            pw.println("PACKAGE_FORMAT=" + packageFormat);
            pw.println("INSTALL_TYPE=" + installType);
            pw.println("FLAG_SHUTDOWN=" + flagShutdownApps);
            pw.println("FLAG_NON_REMOVABLE=" + flagNonRemovable);
            pw.println("FLAG_ROM_UPGRADE=" + flagRomUpgrade);
            pw.printf("PLATFORM_UID=0x%08X%n", targetPlatformUid);
            pw.println("SIGN_MODE=" + signMode);
            pw.println("CUSTOM_CER=" + (customCerPath != null ? customCerPath : ""));
            pw.println("CUSTOM_KEY=" + (customKeyPath != null ? customKeyPath : ""));
            pw.println("SELF_SIGN_CN=" + (selfSignCn != null ? selfSignCn : "S60Reborn Dev"));

            // Simpan Multi-Target Devices
            for (SisPackageInfo.DependencyItem dev : targetDevices) {
                String dName = (dev.name != null && !dev.name.trim().isEmpty()) ? dev.name.trim() : "Series60ProductID";
                pw.printf("TARGET_DEV=0x%08X|%d|%d|%d|%s%n", dev.uid, dev.major, dev.minor, dev.build, dName);
            }

            // Simpan Software Dependencies (Qt, QtMobility, OpenC, dll.)
            for (SisPackageInfo.DependencyItem dep : dependencies) {
                String depName = (dep.name != null && !dep.name.trim().isEmpty()) ? dep.name.trim() : "Dependency";
                pw.printf("SOFT_DEP=0x%08X|%d|%d|%d|%s%n", dep.uid, dep.major, dep.minor, dep.build, depName);
            }

            // Simpan Checkbox Opsi Install
            for (InstallOption opt : installOptions) {
                StringBuilder filesJoined = new StringBuilder();
                for (int i = 0; i < opt.fileRelPaths.size(); i++) {
                    if (i > 0) filesJoined.append(",");
                    filesJoined.append(opt.fileRelPaths.get(i));
                }
                pw.println("OPTION=" + opt.label + "|" + filesJoined.toString());
            }

            // Simpan Kondisi IF
            for (InstallConditionRule rule : conditionRules) {
                StringBuilder filesJoined = new StringBuilder();
                for (int i = 0; i < rule.fileRelPaths.size(); i++) {
                    if (i > 0) filesJoined.append(",");
                    filesJoined.append(rule.fileRelPaths.get(i));
                }
                pw.println("COND_IF=" + rule.ruleType + "|" + rule.conditionParam + "|" + filesJoined.toString());
            }

            // Simpan Embedded Sub-SIS
            for (String sub : embeddedSisPaths) {
                pw.println("SUB_SIS=" + sub);
            }
        } finally {
            pw.close();
        }
    }

    public static SisProjectConfig loadFromFile(File projectDir) {
        SisProjectConfig cfg = new SisProjectConfig();
        if (projectDir == null) return cfg;
        File cfgFile = new File(projectDir, "project_config.ini");
        if (!cfgFile.exists()) return cfg;

        try {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(cfgFile), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] kv = line.split("=", 2);
                    if (kv.length < 2) continue;

                    String k = kv[0].trim();
                    String v = kv[1].trim();

                    if ("PACKAGE_FORMAT".equals(k)) cfg.packageFormat = Integer.parseInt(v);
                    else if ("INSTALL_TYPE".equals(k)) cfg.installType = Integer.parseInt(v);
                    else if ("FLAG_SHUTDOWN".equals(k)) cfg.flagShutdownApps = Boolean.parseBoolean(v);
                    else if ("FLAG_NON_REMOVABLE".equals(k)) cfg.flagNonRemovable = Boolean.parseBoolean(v);
                    else if ("FLAG_ROM_UPGRADE".equals(k)) cfg.flagRomUpgrade = Boolean.parseBoolean(v);
                    else if ("PLATFORM_UID".equals(k)) {
                        cfg.targetPlatformUid = (int) Long.parseLong(v.replace("0x", "").replace("0X", ""), 16);
                    }
                    else if ("SIGN_MODE".equals(k)) cfg.signMode = Integer.parseInt(v);
                    else if ("CUSTOM_CER".equals(k)) cfg.customCerPath = v;
                    else if ("CUSTOM_KEY".equals(k)) cfg.customKeyPath = v;
                    else if ("SELF_SIGN_CN".equals(k)) cfg.selfSignCn = v;
                    else if ("TARGET_DEV".equals(k)) {
                        String[] p = v.split("\\|", 5);
                        if (p.length >= 4) {
                            int uid = (int) Long.parseLong(p[0].replace("0x", "").replace("0X", ""), 16);
                            String name = (p.length >= 5 && !p[4].isEmpty()) ? p[4] : "Series60ProductID";
                            cfg.targetDevices.add(new SisPackageInfo.DependencyItem(
													  uid, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), name));
                        }
                    } else if ("SOFT_DEP".equals(k)) {
                        String[] p = v.split("\\|", 5);
                        if (p.length >= 4) {
                            int uid = (int) Long.parseLong(p[0].replace("0x", "").replace("0X", ""), 16);
                            String name = (p.length >= 5 && !p[4].isEmpty()) ? p[4] : "Dependency";
                            cfg.dependencies.add(new SisPackageInfo.DependencyItem(
													 uid, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), name));
                        }
                    } else if ("OPTION".equals(k)) {
                        String[] p = v.split("\\|", 2);
                        InstallOption opt = new InstallOption(p[0]);
                        if (p.length > 1 && !p[1].isEmpty()) {
                            for (String f : p[1].split(",")) opt.fileRelPaths.add(f.trim());
                        }
                        cfg.installOptions.add(opt);
                    } else if ("COND_IF".equals(k)) {
                        String[] p = v.split("\\|", 3);
                        if (p.length >= 2) {
                            InstallConditionRule r = new InstallConditionRule();
                            r.ruleType = Integer.parseInt(p[0]);
                            r.conditionParam = p[1];
                            if (p.length > 2 && !p[2].isEmpty()) {
                                for (String f : p[2].split(",")) r.fileRelPaths.add(f.trim());
                            }
                            cfg.conditionRules.add(r);
                        }
                    } else if ("SUB_SIS".equals(k)) {
                        if (!v.isEmpty()) cfg.embeddedSisPaths.add(v);
                    }
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {}
        return cfg;
    }

	public static void syncConfigFromUnpackedInfo(SisPackageInfo info, SisProjectConfig config) {
        if (info == null || config == null) return;

        boolean isLegacy = (info.uid2 == SisField.LEGACY_SIS_UID2_MAGIC)
            || (info.uid1 == LegacySisField.UID1_EPOC_V2)
            || (info.uid1 == LegacySisField.UID1_EPOC_V1);

        config.packageFormat = isLegacy ? 1 : 0;
        config.installType = info.installType;
        config.flagShutdownApps = (info.installFlags & 0x01) != 0;
        config.flagNonRemovable = (info.installFlags & 0x02) != 0;
        config.flagRomUpgrade   = (info.installFlags & 0x04) != 0;
        config.signMode = isLegacy ? 0 : (info.isSigned ? 2 : 0);

        config.targetDevices.clear();
        if (info.targetDevices != null && !info.targetDevices.isEmpty()) {
            config.targetDevices.addAll(info.targetDevices);
            config.targetPlatformUid = info.targetDevices.get(0).uid;
        } else if (isLegacy) {
            config.targetPlatformUid = 0x101F7960; // Default S60 2nd Edition
        }

        config.dependencies.clear();
        if (info.dependencies != null) {
            config.dependencies.addAll(info.dependencies);
        }
    }
	
}

