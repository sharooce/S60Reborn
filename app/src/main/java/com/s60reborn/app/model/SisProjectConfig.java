package com.s60reborn.app.model;

import java.util.ArrayList;
import java.util.List;

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
    }
	
	public void saveToFile(java.io.File projectDir) throws java.io.IOException {
        if (projectDir == null) return;
        if (!projectDir.exists()) projectDir.mkdirs();
        java.io.File cfgFile = new java.io.File(projectDir, "project_config.ini");

        java.io.PrintWriter pw = new java.io.PrintWriter(
			new java.io.OutputStreamWriter(new java.io.FileOutputStream(cfgFile), java.nio.charset.StandardCharsets.UTF_8));
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

    public static SisProjectConfig loadFromFile(java.io.File projectDir) {
        SisProjectConfig cfg = new SisProjectConfig();
        if (projectDir == null) return cfg;
        java.io.File cfgFile = new java.io.File(projectDir, "project_config.ini");
        if (!cfgFile.exists()) return cfg;

        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
				new java.io.InputStreamReader(new java.io.FileInputStream(cfgFile), java.nio.charset.StandardCharsets.UTF_8));
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
                    else if ("PLATFORM_UID".equals(k)) cfg.targetPlatformUid = (int) Long.parseLong(v.replace("0x", ""), 16);
                    else if ("SIGN_MODE".equals(k)) cfg.signMode = Integer.parseInt(v);
                    else if ("CUSTOM_CER".equals(k)) cfg.customCerPath = v;
                    else if ("CUSTOM_KEY".equals(k)) cfg.customKeyPath = v;
                    else if ("SELF_SIGN_CN".equals(k)) cfg.selfSignCn = v;
                    else if ("OPTION".equals(k)) {
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
	
}

