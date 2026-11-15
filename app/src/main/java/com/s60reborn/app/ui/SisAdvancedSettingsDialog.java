package com.s60reborn.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.s60reborn.app.R;
import com.s60reborn.app.core.E32ImagePatcher;
import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisProjectConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SisAdvancedSettingsDialog {

    public interface OnConfigSavedListener {
        void onConfigSaved(SisProjectConfig updatedConfig);
    }

    public static void show(
		final Context ctx,
		final File projectDir,
		final List<SisFileEntry> projectFiles,
		final SisProjectConfig config,
		final OnConfigSavedListener listener) {

        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_advanced_settings, null);
        final AlertDialog dialog = new AlertDialog.Builder(ctx).setView(root).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final LinearLayout sectionCaps = root.findViewById(R.id.sectionExeCapabilities);
        final LinearLayout sectionCert = root.findViewById(R.id.sectionCertificateSigning);
        final Spinner spPackageFormat = root.findViewById(R.id.spPackageFormat);
        final Spinner spInstallType = root.findViewById(R.id.spInstallType);
        final Spinner spPlatform = root.findViewById(R.id.spPlatformTarget);
        final Spinner spSign = root.findViewById(R.id.spSignMode);
        final CheckBox cbShutdown = root.findViewById(R.id.cbFlagShutdownApps);
        final CheckBox cbRom = root.findViewById(R.id.cbFlagRomUpgrade);
        final TextView tvOptionsSummary = root.findViewById(R.id.tvOptionsSummary);

        // 0. Format Paket: SISX v3/v5/Belle vs SIS Legacy v1/v2
        String[] formats = {
            "SISX Modern (S60v3 / S60v5 / Belle - Signed)",
            "SIS Legacy (S60v1 / S60v2 - EPOC Tanpa Sertifikat)"
        };
        spPackageFormat.setAdapter(new ArrayAdapter<String>(
									   ctx, android.R.layout.simple_spinner_dropdown_item, formats));
        spPackageFormat.setSelection(config.packageFormat);

        // 1. InstallType
        String[] types = {
            "SA - Standard Application (Aplikasi Penuh)",
            "SP - System Patch (Patch Aplikasi)",
            "PU - Partial Upgrade (Update Sebagian)",
            "PA - Preinstalled App",
            "PP - Preinstalled Patch"
        };
        spInstallType.setAdapter(new ArrayAdapter<String>(
									 ctx, android.R.layout.simple_spinner_dropdown_item, types));
        spInstallType.setSelection(Math.min(config.installType, types.length - 1));

        // 2. Target Platform
        final int[] platUids = { 0x101F7961, 0x102032BE, 0x102752AE, 0x1028315F, 0x20022E6D };
        String[] plats = {
            "S60 3rd Edition (0x101F7961)",
            "S60 3rd FP1 (0x102032BE)",
            "S60 3rd FP2 (0x102752AE)",
            "S60 5th Edition (0x1028315F)",
            "Symbian^3 / Anna / Belle (0x20022E6D)"
        };
        spPlatform.setAdapter(new ArrayAdapter<String>(
								  ctx, android.R.layout.simple_spinner_dropdown_item, plats));
        for (int i = 0; i < platUids.length; i++) {
            if (platUids[i] == config.targetPlatformUid) spPlatform.setSelection(i);
        }

        cbShutdown.setChecked(config.flagShutdownApps);
        cbRom.setChecked(config.flagRomUpgrade);
        updateSummaryLabel(tvOptionsSummary, config);

        // 3. Sign Mode
        String[] signModes = {
            "Unsigned (Tanpa Tanda Tangan)",
            "Self-Signed (Auto RSA 1024-bit)",
            "Custom Certificate (.CER + .KEY)"
        };
        spSign.setAdapter(new ArrayAdapter<String>(
							  ctx, android.R.layout.simple_spinner_dropdown_item, signModes));
        spSign.setSelection(config.signMode);

        // Auto-Hide Pengaturan Sertifikat & Capabilities saat SIS v1/v2 dipilih
        final Runnable applyFormatVisibility = new Runnable() {
            @Override
            public void run() {
                boolean isV1V2 = (spPackageFormat.getSelectedItemPosition() == 1);
                int vis = isV1V2 ? View.GONE : View.VISIBLE;

                sectionCert.setVisibility(vis);
                sectionCaps.setVisibility(vis);
                spPlatform.setVisibility(vis);
                cbShutdown.setVisibility(vis);
                cbRom.setVisibility(vis);

                if (isV1V2) {
                    spSign.setSelection(0);
                }
            }
        };

        spPackageFormat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
				@Override
				public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
					applyFormatVisibility.run();
				}

				@Override
				public void onNothingSelected(AdapterView<?> parent) {}
			});
        applyFormatVisibility.run();

        // Tombol Editor 20 Capabilities (.exe)
        root.findViewById(R.id.btnEditExeCapabilities).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showExeCapabilityEditor(ctx, projectDir, projectFiles);
				}
			});

        // Tombol Tambah Checkbox Opsi
        root.findViewById(R.id.btnAddInstallCheckbox).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showAddCheckboxOptionDialog(ctx, projectFiles, config, tvOptionsSummary);
				}
			});

        // Tombol Tambah Kondisi IF
        root.findViewById(R.id.btnAddConditionIf).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showAddConditionIfDialog(ctx, projectFiles, config, tvOptionsSummary);
				}
			});

        // Tombol Batal & Simpan
        root.findViewById(R.id.btnCancelAdv).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        root.findViewById(R.id.btnSaveAdv).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					config.packageFormat = spPackageFormat.getSelectedItemPosition();
					config.installType = spInstallType.getSelectedItemPosition();
					config.targetPlatformUid = platUids[spPlatform.getSelectedItemPosition()];
					config.flagShutdownApps = cbShutdown.isChecked();
					config.flagRomUpgrade = cbRom.isChecked();
					config.signMode = config.isLegacySisV1V2() ? 0 : spSign.getSelectedItemPosition();

					if (listener != null) listener.onConfigSaved(config);
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private static void showExeCapabilityEditor(final Context ctx, final File projectDir, List<SisFileEntry> files) {
        final List<File> exeFiles = new ArrayList<File>();
        for (SisFileEntry f : files) {
            String low = f.localRelPath.toLowerCase();
            if (low.endsWith(".exe") || low.endsWith(".dll")) {
                File phys = new File(projectDir, f.localRelPath);
                if (phys.exists()) exeFiles.add(phys);
            }
        }
        if (exeFiles.isEmpty()) {
            Toast.makeText(ctx, "Tidak ada file .exe / .dll di proyek ini.", Toast.LENGTH_SHORT).show();
            return;
        }

        final File targetExe = exeFiles.get(0);
        try {
            E32ImagePatcher.E32Info info = E32ImagePatcher.inspectE32(targetExe);
            final boolean[] checked = new boolean[E32ImagePatcher.CAP_NAMES.length];
            for (int i = 0; i < checked.length; i++) {
                checked[i] = ((info.capabilities >> i) & 1) == 1;
            }

            new AlertDialog.Builder(ctx)
				.setTitle("Capabilities: " + targetExe.getName())
				.setMultiChoiceItems(E32ImagePatcher.CAP_NAMES, checked, new DialogInterface.OnMultiChoiceClickListener() {
					@Override
					public void onClick(DialogInterface d, int which, boolean isChecked) {
						checked[which] = isChecked;
					}
				})
				.setNeutralButton("All - TCB (20 Caps)", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface d, int w) {
						try {
							E32ImagePatcher.patchE32File(targetExe, E32ImagePatcher.MASK_ALL_20_CAPS, null, null);
							Toast.makeText(ctx, "20 Capabilities diterapkan ke " + targetExe.getName(), Toast.LENGTH_SHORT).show();
						} catch (Exception e) {
							Toast.makeText(ctx, "Gagal: " + e.getMessage(), Toast.LENGTH_SHORT).show();
						}
					}
				})
				.setPositiveButton("Terapkan", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface d, int w) {
						int newMask = 0;
						for (int i = 0; i < checked.length; i++) {
							if (checked[i]) newMask |= (1 << i);
						}
						try {
							E32ImagePatcher.patchE32File(targetExe, newMask, null, null);
							Toast.makeText(ctx, String.format("Capabilities updated: 0x%08X", newMask), Toast.LENGTH_SHORT).show();
						} catch (Exception e) {
							Toast.makeText(ctx, "Gagal: " + e.getMessage(), Toast.LENGTH_SHORT).show();
						}
					}
				})
				.setNegativeButton("Batal", null)
				.show();
        } catch (Exception e) {
            Toast.makeText(ctx, "Error baca E32: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void showAddCheckboxOptionDialog(
		final Context ctx, final List<SisFileEntry> files,
		final SisProjectConfig config, final TextView summaryLabel) {

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 24, 40, 10);

        final EditText etLabel = new EditText(ctx);
        etLabel.setHint("Teks Checkbox (misal: Install Mod Icon)");
        box.addView(etLabel);

        final String[] fileNames = new String[files.size()];
        final boolean[] selected = new boolean[files.size()];
        for (int i = 0; i < files.size(); i++) {
            fileNames[i] = files.get(i).localRelPath;
        }

        new AlertDialog.Builder(ctx)
			.setTitle("Tambah Checkbox Pilihan Install")
			.setView(box)
			.setMultiChoiceItems(fileNames, selected, new DialogInterface.OnMultiChoiceClickListener() {
				@Override
				public void onClick(DialogInterface d, int which, boolean isChecked) {
					selected[which] = isChecked;
				}
			})
			.setPositiveButton("Simpan Opsi", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int w) {
					String lbl = etLabel.getText().toString().trim();
					if (lbl.isEmpty()) lbl = "Optional Component " + (config.installOptions.size() + 1);
					SisProjectConfig.InstallOption opt = new SisProjectConfig.InstallOption(lbl);
					for (int i = 0; i < selected.length; i++) {
						if (selected[i]) opt.fileRelPaths.add(files.get(i).localRelPath);
					}
					config.installOptions.add(opt);
					updateSummaryLabel(summaryLabel, config);
				}
			})
			.setNegativeButton("Batal", null)
			.show();
    }

    private static void showAddConditionIfDialog(
		final Context ctx, final List<SisFileEntry> files,
		final SisProjectConfig config, final TextView summaryLabel) {

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 24, 40, 10);

        final Spinner spCondType = new Spinner(ctx);
        spCondType.setAdapter(new ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item,
													   new String[]{ "IF NOT EXISTS (Cek File)", "IF package(0xUID) (Cek App Terinstal)" }));
        box.addView(spCondType);

        final EditText etParam = new EditText(ctx);
        etParam.setHint("Path (c:\\sys\\bin\\lib.dll) atau UID (0x20001234)");
        box.addView(etParam);

        new AlertDialog.Builder(ctx)
			.setTitle("Tambah Kondisi Pertengahan Install")
			.setView(box)
			.setPositiveButton("Tambah", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int w) {
					String param = etParam.getText().toString().trim();
					if (param.isEmpty()) return;
					SisProjectConfig.InstallConditionRule rule = new SisProjectConfig.InstallConditionRule();
					rule.ruleType = spCondType.getSelectedItemPosition();
					rule.conditionParam = param;
					config.conditionRules.add(rule);
					updateSummaryLabel(summaryLabel, config);
				}
			})
			.setNegativeButton("Batal", null)
			.show();
    }

    private static void updateSummaryLabel(TextView tv, SisProjectConfig cfg) {
        tv.setText(cfg.installOptions.size() + " Checkbox Opsi | "
				   + cfg.conditionRules.size() + " Kondisi IF | "
				   + cfg.embeddedSisPaths.size() + " Sub-SIS");
    }
}

