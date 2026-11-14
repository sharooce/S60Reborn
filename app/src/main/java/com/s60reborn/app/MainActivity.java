package com.s60reborn.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.s60reborn.app.core.SisxCoreParser;
import com.s60reborn.app.core.SisxPacker;
import com.s60reborn.app.core.SisxSigner;
import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_PICK_SIS = 2001;
    private static final int REQ_ADD_ASSET_FILE = 2002;
	private static final int REQ_REPLACE_MBM_IMAGE = 2003;
    private static final int REQ_REPLACE_MIF_ICON = 2004;
	private static final int REQ_PICK_CUSTOM_CER = 301;
    private static final int REQ_PICK_CUSTOM_KEY = 302;
	private static final int REQ_PICK_INSTALL_ACTION_FILE = 303;
	
	

    private File pendingEditAssetFile;
    private int pendingEditAssetIndex = -1;
	

    private EditText etAppName, etVendorName, etVerMajor, etVerMinor, etVerBuild, etUid3, etInstallNote;
    private TextView tvLog, tvActiveFile, tvFileListHeader;
    private LinearLayout llFileItemsContainer;
    private ScrollView svLog;

    private Button fabMain;
    private LinearLayout fabMenuContainer;
    private View fabBackdrop;
    private boolean isFabOpen = false;

    private File baseWorkDir;
    private File certDir;
    private File activeInputSisFile;
    private File activeProjectDir;
    private SisPackageInfo currentPkgInfo = new SisPackageInfo();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        etAppName = findViewById(R.id.etAppName);
        etVendorName = findViewById(R.id.etVendorName);
        etVerMajor = findViewById(R.id.etVerMajor);
        etVerMinor = findViewById(R.id.etVerMinor);
        etVerBuild = findViewById(R.id.etVerBuild);
        etUid3 = findViewById(R.id.etUid3);
        etInstallNote = findViewById(R.id.etInstallNote);

        tvLog = findViewById(R.id.tvLog);
        tvActiveFile = findViewById(R.id.tvActiveFile);
        tvFileListHeader = findViewById(R.id.tvFileListHeader);
        llFileItemsContainer = findViewById(R.id.llFileItemsContainer);
        svLog = findViewById(R.id.svLog);

        fabMain = findViewById(R.id.fabMain);
        fabMenuContainer = findViewById(R.id.fabMenuContainer);
        fabBackdrop = findViewById(R.id.fabBackdrop);

        Button btnAddFile = findViewById(R.id.btnAddFile);
        Button btnBrowse = findViewById(R.id.btnBrowse);
        Button btnUnpack = findViewById(R.id.btnUnpack);
        Button btnRepack = findViewById(R.id.btnRepack);
        Button btnSign = findViewById(R.id.btnSign);

        baseWorkDir = getExternalFilesDir(null);
        if (baseWorkDir == null) baseWorkDir = getFilesDir();
        certDir = new File(baseWorkDir, "Certs");
        activeProjectDir = new File(baseWorkDir, "Projects/CustomProject");

        initWorkFolders();
        populateFormFromModel(currentPkgInfo);

        fabMain.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					toggleFabMenu();
				}
			});

        fabBackdrop.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (isFabOpen) toggleFabMenu();
				}
			});

        btnAddFile.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
					intent.setType("*/*");
					intent.addCategory(Intent.CATEGORY_OPENABLE);
					startActivityForResult(Intent.createChooser(intent, "Pilih File yang Akan Ditambahkan"), REQ_ADD_ASSET_FILE);
				}
			});

        btnBrowse.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
					intent.setType("*/*");
					intent.addCategory(Intent.CATEGORY_OPENABLE);
					startActivityForResult(Intent.createChooser(intent, "Pilih File .SIS / .SISX"), REQ_PICK_SIS);
				}
			});

        btnUnpack.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					runUnpack();
				}
			});

        btnRepack.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					runRepack();
				}
			});

        btnSign.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					runSign();
				}
			});
		
		Button btnSettings = findViewById(R.id.btnSettings);
        Button btnAbout = findViewById(R.id.btnAbout);

        btnSettings.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					showSettingsDialog();
				}
			});

        btnAbout.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					showAboutDialog();
				}
			});
		
		populateFormFromModel(currentPkgInfo);
		restoreLastSessionIfAvailable();
		
    }
	
	/**
     * Dialog Settings Modern (Dark Glass) menggunakan SharedPreferences
     */
    private void showSettingsDialog() {
        final android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

		float d = getResources().getDisplayMetrics().density;
        ScrollView scrollContainer = new ScrollView(this);
        scrollContainer.setBackgroundResource(R.drawable.dialog_glass_bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * d);
        root.setPadding(pad, pad, pad, pad);
        scrollContainer.addView(root);
		

        TextView tvTitle = new TextView(this);
        tvTitle.setText("⚙️ Settings S60Reborn");
        tvTitle.setTextColor(0xFF00E676);
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        // 1. Default Author
        TextView lblAuthor = new TextView(this);
        lblAuthor.setText("Default Author / Modder:");
        lblAuthor.setTextColor(0xFFB0BEC5);
        lblAuthor.setTextSize(12f);
        lblAuthor.setPadding(0, (int)(12*d), 0, (int)(4*d));
        root.addView(lblAuthor);

        final EditText etDefAuthor = new EditText(this);
        etDefAuthor.setText(prefs.getString("default_author", "S60Reborn"));
        etDefAuthor.setTextColor(0xFFFFFFFF);
        etDefAuthor.setBackgroundResource(R.drawable.input_modern);
        etDefAuthor.setTextSize(13f);
        etDefAuthor.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
        root.addView(etDefAuthor);

        // 2. Target Platform Preset
        TextView lblPlatform = new TextView(this);
        lblPlatform.setText("Target Platform Bawaan (.PKG):");
        lblPlatform.setTextColor(0xFFB0BEC5);
        lblPlatform.setTextSize(12f);
        lblPlatform.setPadding(0, (int)(10*d), 0, (int)(4*d));
        root.addView(lblPlatform);
		
		// 3B. Aksi Otomatis Saat Install (Teks / PNG / XML / Logo)
        TextView lblAction = new TextView(this);
        lblAction.setText("Aksi Otomatis Saat / Setelah Install:");
        lblAction.setTextColor(0xFFB0BEC5);
        lblAction.setTextSize(12f);
        lblAction.setPadding(0, (int)(10*d), 0, (int)(4*d));
        root.addView(lblAction);

        final String[] actionModes = new String[]{
            "Tidak Ada (Default)",
            "Tampilkan Pop-up Teks Notifikasi (FT, TC)",
            "Buka Gambar PNG / JPG Otomatis (FR, RI)",
            "Buka File XML / HTML Otomatis (FR, RI)",
            "Pasang Banner Logo Selama Install (= Logo)"
        };

        final Spinner spActionMode = new Spinner(this);
        ArrayAdapter<String> actionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, actionModes);
        spActionMode.setAdapter(actionAdapter);
        spActionMode.setBackgroundColor(0xFF263238);
        spActionMode.setSelection(prefs.getInt("install_action_mode", 0));
        root.addView(spActionMode);

        String savedAssetPath = prefs.getString("install_action_file_path", "");
        String savedAssetName = savedAssetPath.isEmpty() ? "(Belum ada file dipilih)" : new File(savedAssetPath).getName();

        final TextView tvActionFile = new TextView(this);
        tvActionFile.setText("File Aksi: " + savedAssetName);
        tvActionFile.setTextColor(0xFF81D4FA);
        tvActionFile.setTextSize(11f);
        tvActionFile.setPadding(0, (int)(6*d), 0, (int)(4*d));
        root.addView(tvActionFile);

        Button btnPickActionFile = new Button(this);
        btnPickActionFile.setText("📂 Pilih File PNG / JPG / XML");
        btnPickActionFile.setTextColor(0xFF121417);
        btnPickActionFile.setBackgroundColor(0xFF4FC3F7);
        btnPickActionFile.setTextSize(11f);
        btnPickActionFile.setTypeface(null, Typeface.BOLD);
        btnPickActionFile.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					prefs.edit().putInt("install_action_mode", spActionMode.getSelectedItemPosition()).apply();
					dialog.dismiss();
					Intent i = new Intent(Intent.ACTION_GET_CONTENT);
					i.setType("*/*");
					i.addCategory(Intent.CATEGORY_OPENABLE);
					startActivityForResult(Intent.createChooser(i, "Pilih File PNG / JPG / XML"), REQ_PICK_INSTALL_ACTION_FILE);
				}
			});
        root.addView(btnPickActionFile);
		

		final String[] platforms = new String[]{
			"Universal (S60v3 + S60v5 + Belle)",
			"S60 3rd Edition Only (0x101F7961)",
			"S60 5th Edition Only (0x1028315F)",
			"Symbian^3 / Anna / Belle (0x2003A678)",
			"S60 1st / 2nd Edition (Legacy .SIS v2)"
        };
		
        final Spinner spPlatform = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, platforms);
        spPlatform.setAdapter(adapter);
        spPlatform.setBackgroundColor(0xFF263238);
        spPlatform.setSelection(prefs.getInt("target_platform_idx", 0));
        root.addView(spPlatform);

        // 3. Checkbox Auto-Sign setelah Build
        final android.widget.CheckBox cbAutoSign = new android.widget.CheckBox(this);
        cbAutoSign.setText("Otomatis Self-Sign (.SISX) setelah Build");
        cbAutoSign.setTextColor(0xFFE0F2F1);
        cbAutoSign.setTextSize(12f);
        cbAutoSign.setChecked(prefs.getBoolean("auto_sign", false));
        cbAutoSign.setPadding(0, (int)(10*d), 0, (int)(6*d));
        root.addView(cbAutoSign);

        // 4. Info Folder Sertifikat & Tombol Bersihkan Cache
        TextView tvCertInfo = new TextView(this);
        tvCertInfo.setText("Folder Certs:\n" + certDir.getAbsolutePath());
        tvCertInfo.setTextColor(0xFF78909C);
        tvCertInfo.setTextSize(10f);
        tvCertInfo.setTypeface(Typeface.MONOSPACE);
        tvCertInfo.setPadding(0, (int)(4*d), 0, (int)(10*d));
        root.addView(tvCertInfo);

        Button btnClearTemp = new Button(this);
        btnClearTemp.setText("🧹 Bersihkan Folder Temp");
        btnClearTemp.setTextColor(0xFFFFB74D);
        btnClearTemp.setBackgroundColor(0xFF263238);
        btnClearTemp.setTextSize(11f);
        btnClearTemp.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					File tempDir = new File(baseWorkDir, "Temp");
					int deleted = 0;
					if (tempDir.exists() && tempDir.listFiles() != null) {
						for (File f : tempDir.listFiles()) {
							if (f.delete()) deleted++;
						}
					}
					log("Cache Temp dibersihkan (" + deleted + " file dihapus).");
					prefs.edit().remove("last_project_dir").remove("last_sis_file").apply();
					Toast.makeText(MainActivity.this, deleted + " file sementara dihapus", Toast.LENGTH_SHORT).show();
				}
			});
        root.addView(btnClearTemp);

        // Baris Tombol Simpan & Tutup
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(14*d), 0, 0);

        Button btnCancel = new Button(this);
        btnCancel.setText("Tutup");
        btnCancel.setTextColor(0xFFFFFFFF);
        btnCancel.setBackgroundColor(0xFF37474F);
        btnCancel.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        Button btnSave = new Button(this);
        btnSave.setText("SIMPAN");
        btnSave.setTextColor(0xFF121417);
        btnSave.setBackgroundColor(0xFF00E676);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					String defAuth = etDefAuthor.getText().toString().trim();
					prefs.edit()
                        .putString("default_author", defAuth.isEmpty() ? "S60Reborn" : defAuth)
                        .putInt("target_platform_idx", spPlatform.getSelectedItemPosition())
                        .putInt("install_action_mode", spActionMode.getSelectedItemPosition())
                        .putBoolean("auto_sign", cbAutoSign.isChecked())
                        .apply();
					
					if (etVendorName.getText().toString().trim().isEmpty() || etVendorName.getText().toString().equals("UnknownVendor")) {
						etVendorName.setText(defAuth);
					}
					log("Pengaturan disimpan.");
					dialog.dismiss();
				}
			});

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, (int)(8*d), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);

        btnRow.addView(btnCancel, lp1);
        btnRow.addView(btnSave, lp2);
        root.addView(btnRow);

        dialog.setContentView(scrollContainer);
        dialog.show();
    }

    /**
     * Dialog About Modern (Dark Glass)
     */
    private void showAboutDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundResource(R.drawable.dialog_glass_bg);
        int pad = (int) (22 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView tvLogo = new TextView(this);
        tvLogo.setText("⚡ S60Reborn Studio");
        tvLogo.setTextColor(0xFF00E676);
        tvLogo.setTextSize(19f);
        tvLogo.setTypeface(null, Typeface.BOLD);
        root.addView(tvLogo);

        TextView tvVer = new TextView(this);
        tvVer.setText("Version 1.0.0 • Pure Java Engine");
        tvVer.setTextColor(0xFF4FC3F7);
        tvVer.setTextSize(12f);
        tvVer.setPadding(0, (int)(4*d), 0, (int)(12*d));
        root.addView(tvVer);

        View divider = new View(this);
        divider.setBackgroundColor(0xFF263238);
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(1*d)));

        TextView tvDesc = new TextView(this);
        tvDesc.setText(
			"Alat manajemen paket Symbian OS generasi baru untuk Android.\n\n" +
			"Fitur Utama:\n" +
			"• Unpack & Inspect SIS / SISX (S60v2 - Belle)\n" +
			"• Visual PKG Builder & Installer Notif Editor\n" +
			"• Zlib Repacker & X.509 Self-Signer\n\n" +
			"Dirancang ringan dan kompatibel penuh dengan emulator EKA2L1 maupun perangkat Symbian klasik."
        );
        tvDesc.setTextColor(0xFFE0F2F1);
        tvDesc.setTextSize(12f);
        tvDesc.setPadding(0, (int)(12*d), 0, (int)(16*d));
        root.addView(tvDesc);

        Button btnClose = new Button(this);
        btnClose.setText("TUTUP");
        btnClose.setTextColor(0xFF121417);
        btnClose.setBackgroundColor(0xFF00E676);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});
        root.addView(btnClose, new LinearLayout.LayoutParams(
						 LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        dialog.show();
    }
	

    private void initWorkFolders() {
        File out = new File(baseWorkDir, "Output");
        File temp = new File(baseWorkDir, "Temp");
        if (!activeProjectDir.exists()) activeProjectDir.mkdirs();
        if (!out.exists()) out.mkdirs();
        if (!temp.exists()) temp.mkdirs();
        if (!certDir.exists()) certDir.mkdirs();
    }

    /**
     * Mengisi Form UI dari data model SisPackageInfo
     */
    private void populateFormFromModel(SisPackageInfo info) {
        currentPkgInfo = info;
        etAppName.setText(info.appName);
        etVendorName.setText(info.vendorName);
        etVerMajor.setText(String.valueOf(info.majorVersion));
        etVerMinor.setText(String.valueOf(info.minorVersion));
        etVerBuild.setText(String.valueOf(info.buildVersion));
        etUid3.setText(info.uid3 != 0 ? info.getFormattedUid3() : "0x20001234");
        refreshFileListUI();
    }
	
	private void copyFileToFile(File srcFile, File destFile) throws Exception {
        if (srcFile == null || !srcFile.exists()) return;
        if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
            destFile.getParentFile().mkdirs();
        }
        try (java.io.FileInputStream fis = new java.io.FileInputStream(srcFile);
		java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }
	

    /**
     * Membaca input dari Form UI dan menyusun ulang project.pkg siap kompilasi
     */
    private File syncFormAndWritePkg() throws Exception {
        if (!activeProjectDir.exists()) activeProjectDir.mkdirs();

		
		
        String appName = etAppName.getText().toString().trim();
        String vendor = etVendorName.getText().toString().trim();
        currentPkgInfo.appName = appName.isEmpty() ? "MySymbianApp" : appName;
        currentPkgInfo.vendorName = vendor.isEmpty() ? "S60Reborn" : vendor;

        try {
            currentPkgInfo.majorVersion = Integer.parseInt(etVerMajor.getText().toString().trim());
            currentPkgInfo.minorVersion = Integer.parseInt(etVerMinor.getText().toString().trim());
            currentPkgInfo.buildVersion = Integer.parseInt(etVerBuild.getText().toString().trim());
        } catch (Exception e) {
            currentPkgInfo.majorVersion = 1;
            currentPkgInfo.minorVersion = 0;
            currentPkgInfo.buildVersion = 0;
        }

        String uidStr = etUid3.getText().toString().trim();
        currentPkgInfo.uid3 = uidStr.startsWith("0x") || uidStr.startsWith("0X")
			? (int) Long.parseLong(uidStr.substring(2), 16)
			: Integer.parseInt(uidStr);

        String noteText = etInstallNote.getText().toString().trim();
        File pkgFile = new File(activeProjectDir, "project.pkg");

        StringBuilder sb = new StringBuilder();
        sb.append("; Generated by S60Reborn Visual Builder\n");
        sb.append("&EN\n\n");
        sb.append("#{\"").append(currentPkgInfo.appName).append("\"},(")
			.append(currentPkgInfo.getFormattedUid3()).append("),")
			.append(currentPkgInfo.getFormattedVersion()).append(",TYPE=SA\n\n");
        sb.append("%{\"").append(currentPkgInfo.vendorName).append("\"}\n");
        sb.append(":\"").append(currentPkgInfo.vendorName).append("\"\n\n");
        sb.append("[0x101F7961], 0, 0, 0, {\"Series60ProductID\"}\n");
        sb.append("[0x1028315F], 0, 0, 0, {\"Series60ProductID\"}\n\n");
		
		android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);
		int actionMode = prefs.getInt("install_action_mode", 0);
		String customAssetPath = prefs.getString("install_action_file_path", "");

		if (actionMode == 1 && !noteText.isEmpty()) {
			// Mode 1: Pop-up Teks (FT, TC)
			File noteFile = new File(activeProjectDir, "install_note.txt");
			try (FileOutputStream fos = new FileOutputStream(noteFile)) {
				fos.write(noteText.getBytes(StandardCharsets.UTF_8));
			}
			sb.append("\"install_note.txt\"-\"!:\\private\\")
				.append(String.format("%08x", currentPkgInfo.uid3))
				.append("\\install_note.txt\", FT, TC\n\n");

		} else if ((actionMode == 2 || actionMode == 3) && !customAssetPath.isEmpty()) {
			// Mode 2 & 3: Buka Gambar PNG/JPG atau XML otomatis saat/setelah install (FR, RI)
			File srcAsset = new File(customAssetPath);
			if (srcAsset.exists()) {
				File destAsset = new File(activeProjectDir, srcAsset.getName());
				copyFileToFile(srcAsset, destAsset);
				sb.append("\"").append(srcAsset.getName()).append("\"-\"!:\\data\\")
					.append(srcAsset.getName()).append("\", FR, RI\n\n");
			}

		} else if (actionMode == 4 && !customAssetPath.isEmpty()) {
			// Mode 4: Logo Banner selama bilah progres instalasi
			File srcLogo = new File(customAssetPath);
			if (srcLogo.exists()) {
				File destLogo = new File(activeProjectDir, srcLogo.getName());
				copyFileToFile(srcLogo, destLogo);
				String mime = srcLogo.getName().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
				sb.append("=\"").append(srcLogo.getName()).append("\", \"").append(mime).append("\"\n\n");
			}
		}
		

        // Jika pengguna mengisi pesan notif saat install, buat file .txt dan tambahkan flag FT, TC
        if (!noteText.isEmpty()) {
            File noteFile = new File(activeProjectDir, "install_note.txt");
            try (FileOutputStream fos = new FileOutputStream(noteFile)) {
                fos.write(noteText.getBytes(StandardCharsets.UTF_8));
            }
            sb.append("; Pesan Notif Instalasi\n");
            sb.append("\"install_note.txt\"-\"!:\\private\\").append(String.format("%08x", currentPkgInfo.uid3))
				.append("\\install_note.txt\", FT, TC\n\n");
        }

        sb.append("; Daftar File Paket\n");
        for (SisFileEntry f : currentPkgInfo.files) {
            String winRelPath = f.localRelPath.replace('/', '\\');
            sb.append("\"").append(winRelPath).append("\"-\"")
				.append(f.targetPath).append("\"\n");
        }

        try (FileOutputStream fos = new FileOutputStream(pkgFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        return pkgFile;
    }

    /**
     * Menampilkan daftar file dalam bentuk kartu modern yang mudah dibaca & dihapus
     */
    private void refreshFileListUI() {
        llFileItemsContainer.removeAllViews();
        tvFileListHeader.setText("ISI FILE PAKET (" + currentPkgInfo.files.size() + ")");

        if (currentPkgInfo.files.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("Belum ada file. Buka paket .sis/.sisx atau klik + Tambah File.");
            emptyTv.setTextColor(0xFF546E7A);
            emptyTv.setTextSize(12f);
            emptyTv.setPadding(0, 12, 0, 12);
            llFileItemsContainer.addView(emptyTv);
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < currentPkgInfo.files.size(); i++) {
            final int index = i;
            final SisFileEntry entry = currentPkgInfo.files.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.input_modern);
            row.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, (int)(6*d));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
			

            final String fileNameOnly = new File(entry.localRelPath).getName();
			String variantTag = "";
			if (entry.localRelPath != null && entry.localRelPath.startsWith("varian_")) {
				int slashIdx = entry.localRelPath.indexOf('/');
				if (slashIdx > 0) {
					variantTag = "[" + entry.localRelPath.substring(0, slashIdx).toUpperCase().replace("_", " ") + "] ";
				}
			}

			final TextView tvName = new TextView(this);
			tvName.setText("📄 " + variantTag + fileNameOnly);
			
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setTextSize(12f);
            tvName.setTypeface(null, Typeface.BOLD);

            final TextView tvTarget = new TextView(this);
            tvTarget.setText("Tujuan: " + entry.targetPath);
            tvTarget.setTextColor(0xFF80CBC4);
            tvTarget.setTextSize(11f);
            tvTarget.setTypeface(Typeface.MONOSPACE);

            textCol.addView(tvName);
            textCol.addView(tvTarget);
			
			final File physicalFile = new File(activeProjectDir, entry.localRelPath);
            final String lowerName = fileNameOnly.toLowerCase();
            if (lowerName.endsWith(".mbm") || lowerName.endsWith(".mif")) {
                tvTarget.setText("🎨 Ketuk untuk Edit Tema/Ikon (" + entry.targetPath + ")");
                tvTarget.setTextColor(0xFF00E676);
                textCol.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (!physicalFile.exists()) {
								Toast.makeText(MainActivity.this, "Unpack paket dulu agar file fisik tersedia.", Toast.LENGTH_SHORT).show();
								return;
							}
							if (lowerName.endsWith(".mbm")) {
								openThemeVariantPicker(activeProjectDir, ".mbm");
							} else {
								openThemeVariantPicker(activeProjectDir, ".mif");
							}
						}
					});
            }
			

            TextView btnDel = new TextView(this);
            btnDel.setText("✕");
            btnDel.setTextColor(0xFFEF5350);
            btnDel.setTextSize(15f);
            btnDel.setTypeface(null, Typeface.BOLD);
            btnDel.setPadding((int)(12*d), (int)(4*d), (int)(4*d), (int)(4*d));
            btnDel.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						currentPkgInfo.files.remove(index);
						refreshFileListUI();
						log("Dihapus dari daftar: " + fileNameOnly);
					}
				});

            row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(btnDel);
            llFileItemsContainer.addView(row, rowLp);
			
			if (lowerName.endsWith(".skn")) {
                tvTarget.setText("📱 Ketuk untuk Preview Nokia & Edit Warna (.SKN)");
                tvTarget.setTextColor(0xFFFFB74D);
                textCol.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (!physicalFile.exists()) {
								Toast.makeText(MainActivity.this, "Unpack paket dulu.", Toast.LENGTH_SHORT).show();
								return;
							}
							openThemeVariantPicker(activeProjectDir, ".skn");
						}
					});
            }
			if (lowerName.endsWith(".rsc") || lowerName.matches(".*\\.r\\d{2}$")) {
                tvTarget.setText("🌐 Ketuk untuk Edit Bahasa / Teks Aplikasi (.RSC)");
                tvTarget.setTextColor(0xFF81D4FA);
                textCol.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (!physicalFile.exists()) {
								Toast.makeText(MainActivity.this, "Unpack paket dulu.", Toast.LENGTH_SHORT).show();
								return;
							}
							showRscLanguageEditorDialog(physicalFile);
						}
					});
            }
			if (isSymbianExecutableBinary(physicalFile, lowerName)) {
                long capMask = 0L;
                int capCount = 0;
                if (physicalFile.exists()) {
                    try {
                        capMask = com.s60reborn.app.core.SymbianCapabilityManager.readCapabilitiesFromE32(physicalFile);
                        capCount = com.s60reborn.app.core.SymbianCapabilityManager.getCapabilityNamesList(capMask).size();
                    } catch (Exception ignored) {
                    }
                }

                final TextView tvTargetRef = tvTarget;
                tvTarget.setText("🛡️ Ketuk untuk Edit Capabilities (" + capCount + " aktif • 0x"
								 + Long.toHexString(capMask).toUpperCase() + ")");
                tvTarget.setTextColor(0xFFFF8A65);

                textCol.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (!physicalFile.exists()) {
								Toast.makeText(MainActivity.this, "Unpack paket dulu agar file fisik tersedia.", Toast.LENGTH_SHORT).show();
								return;
							}
							showCapabilityEditorDialog(physicalFile, tvTargetRef);
						}
					});
            }
			
			
			
        }
    }
	
	private boolean isSymbianExecutableBinary(File file, String lowerName) {
        if (lowerName.endsWith(".exe") || lowerName.endsWith(".dll")
			|| lowerName.endsWith(".app") || lowerName.endsWith(".ldd")
			|| lowerName.endsWith(".pdd") || lowerName.endsWith(".csy")
			|| lowerName.endsWith(".tsy") || lowerName.endsWith(".agt")
			|| lowerName.endsWith(".fep") || lowerName.endsWith(".mdl")
			|| lowerName.endsWith(".ani")) {
            return true;
        }
        // Cek header EPOC (offset 0x10) untuk plugin dengan ekstensi lain
        if (file != null && file.exists() && file.length() >= 0x88) {
            java.io.FileInputStream fis = null;
            try {
                fis = new java.io.FileInputStream(file);
                byte[] hdr = new byte[20];
                if (fis.read(hdr) == 20) {
                    return hdr[16] == 'E' && hdr[17] == 'P' && hdr[18] == 'O' && hdr[19] == 'C';
                }
            } catch (Exception ignored) {
            } finally {
                if (fis != null) try { fis.close(); } catch (Exception ignored) {}
            }
        }
        return false;
    }
	
	private void showCapabilityEditorDialog(final File e32File, final TextView targetRowSubtitle) {
        try {
            long initialMask = com.s60reborn.app.core.SymbianCapabilityManager.readCapabilitiesFromE32(e32File);
            final long[] workingMask = new long[]{ initialMask };

            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            float d = getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_glass_bg);
            int pad = (int) (16 * d);
            root.setPadding(pad, pad, pad, pad);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("🛡️ Capabilities: " + e32File.getName());
            tvTitle.setTextColor(0xFFFF8A65);
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);

            final TextView tvMaskInfo = new TextView(this);
            tvMaskInfo.setTextColor(0xFF80CBC4);
            tvMaskInfo.setTextSize(11f);
            tvMaskInfo.setTypeface(Typeface.MONOSPACE);
            tvMaskInfo.setPadding(0, (int)(4*d), 0, (int)(8*d));
            root.addView(tvMaskInfo);

            final String[] capNames = com.s60reborn.app.core.SymbianCapabilityManager.CAPABILITY_NAMES;
            final android.widget.CheckBox[] checkBoxes = new android.widget.CheckBox[capNames.length];

            final Runnable syncCheckboxesFromMask = new Runnable() {
                @Override
                public void run() {
                    int activeCount = 0;
                    for (int i = 0; i < capNames.length; i++) {
                        boolean checked = (workingMask[0] & (1L << i)) != 0;
                        if (checked) activeCount++;
                        if (checkBoxes[i] != null) checkBoxes[i].setChecked(checked);
                    }
                    tvMaskInfo.setText("Bitmask: 0x" + String.format("%08X", workingMask[0])
									   + " (" + activeCount + " / 20 aktif)");
                }
            };

            // Baris Tombol Preset Cepat
            LinearLayout presetRow = new LinearLayout(this);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.setPadding(0, 0, 0, (int)(8*d));

            Button btnNone = new Button(this);
            btnNone.setText("None (0)");
            btnNone.setTextSize(10f);
            btnNone.setTextColor(0xFFFFFFFF);
            btnNone.setBackgroundColor(0xFF37474F);
            btnNone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						workingMask[0] = com.s60reborn.app.core.SymbianCapabilityManager.PRESET_NONE;
						syncCheckboxesFromMask.run();
					}
				});

            Button btnSelfSign = new Button(this);
            btnSelfSign.setText("Self-Sign Safe");
            btnSelfSign.setTextSize(10f);
            btnSelfSign.setTextColor(0xFF121417);
            btnSelfSign.setBackgroundColor(0xFF4FC3F7);
            btnSelfSign.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						workingMask[0] = com.s60reborn.app.core.SymbianCapabilityManager.PRESET_SELF_SIGNED_MAX;
						syncCheckboxesFromMask.run();
					}
				});

            Button btnAll20 = new Button(this);
            btnAll20.setText("All 20 (Hack)");
            btnAll20.setTextSize(10f);
            btnAll20.setTextColor(0xFF121417);
            btnAll20.setBackgroundColor(0xFFFFB74D);
            btnAll20.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						workingMask[0] = com.s60reborn.app.core.SymbianCapabilityManager.PRESET_ALL_20_CAPS;
						syncCheckboxesFromMask.run();
					}
				});

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, (int)(34*d), 1f);
            pLp.setMargins(0, 0, (int)(4*d), 0);
            presetRow.addView(btnNone, pLp);
            presetRow.addView(btnSelfSign, pLp);
            presetRow.addView(btnAll20, new LinearLayout.LayoutParams(0, (int)(34*d), 1f));
            root.addView(presetRow);

            // Daftar 20 Checkbox Capability
            ScrollView scroll = new ScrollView(this);
            LinearLayout listCol = new LinearLayout(this);
            listCol.setOrientation(LinearLayout.VERTICAL);

            for (int i = 0; i < capNames.length; i++) {
                final int bitIdx = i;
                android.widget.CheckBox cb = new android.widget.CheckBox(this);
                boolean isBasicSelfSign = (i == 13 || i == 14 || i == 15 || i == 16 || i == 19);
                cb.setText(capNames[i] + (isBasicSelfSign ? "  [Self-Sign]" : "  [System/DevCert]"));
                cb.setTextColor(isBasicSelfSign ? 0xFF81D4FA : 0xFFE0F2F1);
                cb.setTextSize(11.5f);
                cb.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (((android.widget.CheckBox) v).isChecked()) {
								workingMask[0] |= (1L << bitIdx);
							} else {
								workingMask[0] &= ~(1L << bitIdx);
							}
							syncCheckboxesFromMask.run();
						}
					});
                checkBoxes[i] = cb;
                listCol.addView(cb);
            }
            syncCheckboxesFromMask.run();

            scroll.addView(listCol);
            root.addView(scroll, new LinearLayout.LayoutParams(
							 LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d)));

            // Tombol Batal & Simpan
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, (int)(12*d), 0, 0);

            Button btnCancel = new Button(this);
            btnCancel.setText("Batal");
            btnCancel.setTextColor(0xFFFFFFFF);
            btnCancel.setBackgroundColor(0xFF37474F);
            btnCancel.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});

            Button btnSave = new Button(this);
            btnSave.setText("PATCH & SIMPAN");
            btnSave.setTextColor(0xFF121417);
            btnSave.setBackgroundColor(0xFF00E676);
            btnSave.setTypeface(null, Typeface.BOLD);
            btnSave.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						try {
							com.s60reborn.app.core.SymbianCapabilityManager.writeCapabilitiesToE32(e32File, workingMask[0]);
							int newCount = com.s60reborn.app.core.SymbianCapabilityManager.getCapabilityNamesList(workingMask[0]).size();
							targetRowSubtitle.setText("🛡️ Ketuk untuk Edit Capabilities (" + newCount + " aktif • 0x"
													  + Long.toHexString(workingMask[0]).toUpperCase() + ")");
							log("Capabilities " + e32File.getName() + " dipatch -> 0x"
                                + String.format("%08X", workingMask[0]) + " (Header CRC32 diperbarui)");
							Toast.makeText(MainActivity.this, "Capabilities berhasil diperbarui!", Toast.LENGTH_SHORT).show();
							dialog.dismiss();
						} catch (Exception ex) {
							Toast.makeText(MainActivity.this, "Gagal patch: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
						}
					}
				});

            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp1.setMargins(0, 0, (int)(8*d), 0);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f);

            btnRow.addView(btnCancel, lp1);
            btnRow.addView(btnSave, lp2);
            root.addView(btnRow);

            dialog.setContentView(root);
            dialog.show();
            if (dialog.getWindow() != null) {
                int screenW = getResources().getDisplayMetrics().widthPixels;
                dialog.getWindow().setLayout((int) (screenW * 0.92f), LinearLayout.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            log("Gagal membaca capabilities: " + e.getMessage());
        }
    }
	
	
	/**
     * Dialog Editor Bahasa (.RSC / .R01) untuk menerjemahkan teks aplikasi & tema Symbian
     */
    private void showRscLanguageEditorDialog(final File rscFile) {
        try {
            final java.util.List<com.s60reborn.app.core.RscParser.RscStringEntry> strings =
				com.s60reborn.app.core.RscParser.parseRscStrings(rscFile);

            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            float d = getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_glass_bg);
            int pad = (int) (16 * d);
            root.setPadding(pad, pad, pad, pad);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("🌐 Editor Bahasa: " + rscFile.getName());
            tvTitle.setTextColor(0xFF4FC3F7);
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);

            TextView tvSub = new TextView(this);
            tvSub.setText("Ditemukan " + strings.size() + " teks bahasa. Ketuk teks untuk menerjemahkan:");
            tvSub.setTextColor(0xFFB0BEC5);
            tvSub.setTextSize(11f);
            tvSub.setPadding(0, (int)(4*d), 0, (int)(10*d));
            root.addView(tvSub);

            ScrollView scroll = new ScrollView(this);
            LinearLayout listContainer = new LinearLayout(this);
            listContainer.setOrientation(LinearLayout.VERTICAL);

            for (final com.s60reborn.app.core.RscParser.RscStringEntry item : strings) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.input_modern);
                row.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, (int)(6*d));

                final TextView tvStr = new TextView(this);
                tvStr.setText("\"" + item.text + "\"");
                tvStr.setTextColor(0xFFFFFFFF);
                tvStr.setTextSize(12f);
                row.addView(tvStr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button btnEdit = new Button(this);
                btnEdit.setText("Edit");
                btnEdit.setTextColor(0xFF121417);
                btnEdit.setBackgroundColor(0xFF00E676);
                btnEdit.setTextSize(11f);
                btnEdit.setTypeface(null, Typeface.BOLD);
                btnEdit.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							showSingleStringTranslatePrompt(rscFile, item, tvStr);
						}
					});
                row.addView(btnEdit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(34*d)));
                listContainer.addView(row, lp);
            }

            scroll.addView(listContainer);
            root.addView(scroll, new LinearLayout.LayoutParams(
							 LinearLayout.LayoutParams.MATCH_PARENT, (int) (310 * d)));

            Button btnDone = new Button(this);
            btnDone.setText("SELESAI");
            btnDone.setTextColor(0xFFFFFFFF);
            btnDone.setBackgroundColor(0xFF37474F);
            btnDone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            doneLp.setMargins(0, (int)(10*d), 0, 0);
            root.addView(btnDone, doneLp);

            dialog.setContentView(root);
            dialog.show();
            if (dialog.getWindow() != null) {
                int screenW = getResources().getDisplayMetrics().widthPixels;
                dialog.getWindow().setLayout((int) (screenW * 0.92f), LinearLayout.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            log("Gagal membuka RSC: " + e.getMessage());
        }
    }

    private void showSingleStringTranslatePrompt(final File rscFile,
                                                 final com.s60reborn.app.core.RscParser.RscStringEntry item,
                                                 final TextView targetRowLabel) {
        final Dialog dPrompt = new Dialog(this);
        dPrompt.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dPrompt.getWindow() != null) {
            dPrompt.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_glass_bg);
        int pad = (int) (18 * d);
        box.setPadding(pad, pad, pad, pad);

        TextView lbl = new TextView(this);
        lbl.setText("Terjemahkan / Ubah Teks:");
        lbl.setTextColor(0xFF00E676);
        lbl.setTextSize(14f);
        lbl.setTypeface(null, Typeface.BOLD);
        box.addView(lbl);

        final EditText etInput = new EditText(this);
        etInput.setText(item.text);
        etInput.setTextColor(0xFFFFFFFF);
        etInput.setBackgroundResource(R.drawable.input_modern);
        etInput.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.setMargins(0, (int)(10*d), 0, (int)(12*d));
        box.addView(etInput, etLp);

        Button btnSave = new Button(this);
        btnSave.setText("SIMPAN PERUBAHAN");
        btnSave.setTextColor(0xFF121417);
        btnSave.setBackgroundColor(0xFF00E676);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					String updated = etInput.getText().toString();
					if (!updated.isEmpty()) {
						try {
							com.s60reborn.app.core.RscParser.updateRscString(rscFile, item, updated);
							item.text = updated;
							targetRowLabel.setText("\"" + updated + "\"");
							log("Teks RSC diperbarui -> \"" + updated + "\"");
							dPrompt.dismiss();
						} catch (Exception ex) {
							Toast.makeText(MainActivity.this, "Gagal simpan: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
						}
					}
				}
			});
        box.addView(btnSave);

        dPrompt.setContentView(box);
        dPrompt.show();
    }
	
	
	/**
     * Interactive Nokia Nseries / Eseries Theme Studio untuk file .SKN
     */
    private void showInteractiveNokiaThemeDialog(final File sknFile) {
        try {
            final com.s60reborn.app.core.SknParser.SknThemeData sknData =
				com.s60reborn.app.core.SknParser.parseSkn(sknFile);

            // Cari file .mbm di folder proyek yang sama untuk wallpaper preview otomatis
			android.graphics.Bitmap wpBmp = null;
            android.graphics.Bitmap menuBmp = null;
            java.util.List<android.graphics.Bitmap> allThemeBitmaps = new java.util.ArrayList<>();

            File variantRoot = sknFile.getParentFile();
            File cursor = variantRoot;
            while (cursor != null) {
                if (cursor.getName().startsWith("varian_") || cursor.equals(activeProjectDir)) {
                    variantRoot = cursor;
                    break;
                }
                cursor = cursor.getParentFile();
            }

            File mbmFile = (variantRoot != null) ? findFileByExtension(variantRoot, ".mbm") : null;
            if (mbmFile != null && mbmFile.exists()) {
                try {
                    allThemeBitmaps = com.s60reborn.app.core.MbmConverter.loadBitmapsFromMbm(mbmFile);
                    if (!allThemeBitmaps.isEmpty()) wpBmp = allThemeBitmaps.get(0);
                    if (allThemeBitmaps.size() > 1) menuBmp = allThemeBitmaps.get(1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
			

            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            float d = getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_glass_bg);
            int pad = (int) (14 * d);
            root.setPadding(pad, pad, pad, pad);

            // Header + Switcher Nseries / Eseries
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            final TextView tvTitle = new TextView(this);
            tvTitle.setText("📱 Theme Studio");
            tvTitle.setTextColor(0xFF00E676);
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, Typeface.BOLD);

            final Button btnSwitchPhone = new Button(this);
            btnSwitchPhone.setText("E72 ⇄ N95");
            btnSwitchPhone.setTextColor(0xFF121417);
            btnSwitchPhone.setBackgroundColor(0xFF4FC3F7);
            btnSwitchPhone.setTextSize(11f);
            btnSwitchPhone.setTypeface(null, Typeface.BOLD);

            topBar.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            topBar.addView(btnSwitchPhone, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(32*d)));
            root.addView(topBar);

            final TextView tvHint = new TextView(this);
            tvHint.setText("Geser layar HP <- -> untuk cek Idle/Menu/Popup • Ketuk elemen untuk edit");
            tvHint.setTextColor(0xFF90A4AE);
            tvHint.setTextSize(10f);
            tvHint.setPadding(0, (int)(4*d), 0, (int)(6*d));
            root.addView(tvHint);

            // Interactive Nokia Preview View
            final com.s60reborn.app.ui.NokiaThemePreviewView previewView =
				new com.s60reborn.app.ui.NokiaThemePreviewView(this);
			previewView.setThemeAssets(sknData.skinName, wpBmp, menuBmp, allThemeBitmaps,
									   sknData.idleTextColor, sknData.menuTextColor, sknData.softkeyTextColor, sknData.highlightColor);
			
            btnSwitchPhone.setText("Model: E72 (Ganti ke N95)");
            btnSwitchPhone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (previewView.getPhoneModel() == com.s60reborn.app.ui.NokiaThemePreviewView.MODEL_E72) {
							previewView.setPhoneModel(com.s60reborn.app.ui.NokiaThemePreviewView.MODEL_N95);
							btnSwitchPhone.setText("Model: N95 (Ganti ke E72)");
							tvTitle.setText("📱 Nokia N95 Studio");
						} else {
							previewView.setPhoneModel(com.s60reborn.app.ui.NokiaThemePreviewView.MODEL_E72);
							btnSwitchPhone.setText("Model: E72 (Ganti ke N95)");
							tvTitle.setText("📱 Nokia E72 Studio");
						}
						previewView.invalidate();
					}
				});

            previewView.setOnElementTapListener(new com.s60reborn.app.ui.NokiaThemePreviewView.OnElementTapListener() {
					@Override
					public void onTapWallpaper() {
						Toast.makeText(MainActivity.this, "Ketuk file .MBM di daftar untuk mengganti Wallpaper", Toast.LENGTH_SHORT).show();
					}

					@Override
					public void onTapTextColor(int pageIndex) {
						Toast.makeText(MainActivity.this, "Pilih warna teks dari daftar di bawah", Toast.LENGTH_SHORT).show();
					}

					@Override
					public void onTapHighlightColor() {
						Toast.makeText(MainActivity.this, "Pilih warna highlight dari daftar di bawah", Toast.LENGTH_SHORT).show();
					}

					@Override
					public void onPageChanged(int newPage) {
						String[] names = {"Layar Utama (Idle)", "Grid Menu 3x3", "Pop-up Dialog"};
						tvHint.setText("Tampilan: " + names[newPage] + " (Geser <- -> untuk ganti)");
					}
				});

            root.addView(previewView, new LinearLayout.LayoutParams(
							 LinearLayout.LayoutParams.MATCH_PARENT, (int) (350 * d)));
			

            // Daftar Warna SKN yang bisa langsung diubah
            ScrollView colorScroll = new ScrollView(this);
            LinearLayout colorList = new LinearLayout(this);
            colorList.setOrientation(LinearLayout.VERTICAL);

            final int[] presetPalette = {0xFFFFFFFF, 0xFF000000, 0xFF00E676, 0xFF4FC3F7, 0xFFFFB74D, 0xFFEF5350};
            final String[] paletteNames = {"Putih", "Hitam", "Hijau Neon", "Biru Symbian", "Oranye", "Merah"};

            for (int i = 0; i < sknData.extractedColors.size(); i++) {
                final int cIdx = i;
                final com.s60reborn.app.core.SknParser.SknColorItem item = sknData.extractedColors.get(i);

				java.util.Map<String, String> skinDb =
                    com.s60reborn.app.core.SknParser.loadBinarySisContentsDbFromAssets(this, "items.db");
				java.util.List<com.s60reborn.app.core.SknParser.SknAssetMapping> assetMappings =
                    com.s60reborn.app.core.SknParser.extractAssetMappings(sknFile, skinDb);

				for (com.s60reborn.app.core.SknParser.SknAssetMapping mapItem : assetMappings) {
					LinearLayout mRow = new LinearLayout(this);
					mRow.setOrientation(LinearLayout.VERTICAL);
					mRow.setBackgroundResource(R.drawable.input_modern);
					mRow.setPadding((int)(8*d), (int)(6*d), (int)(8*d), (int)(6*d));
					LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
					mlp.setMargins(0, (int)(4*d), 0, 0);

					TextView tvElem = new TextView(this);
					tvElem.setText("🧩 " + mapItem.elementName);
					tvElem.setTextColor(0xFF81D4FA);
					tvElem.setTextSize(11f);
					tvElem.setTypeface(null, Typeface.BOLD);

					TextView tvMeta = new TextView(this);
					tvMeta.setText("UID: " + mapItem.uidKey + "  →  Asset Index #" + mapItem.assetIndex
								   + (mapItem.maskIndex >= 0 ? " (Mask #" + mapItem.maskIndex + ")" : ""));
					tvMeta.setTextColor(0xFFB0BEC5);
					tvMeta.setTextSize(10f);

					mRow.addView(tvElem);
					mRow.addView(tvMeta);
					colorList.addView(mRow, mlp);
				}
				
				
                LinearLayout cRow = new LinearLayout(this);
                cRow.setOrientation(LinearLayout.HORIZONTAL);
                cRow.setGravity(Gravity.CENTER_VERTICAL);
                cRow.setBackgroundResource(R.drawable.input_modern);
                cRow.setPadding((int)(8*d), (int)(6*d), (int)(8*d), (int)(6*d));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, (int)(4*d), 0, 0);

                final View colorBox = new View(this);
                colorBox.setBackgroundColor(item.colorArgb);
                cRow.addView(colorBox, new LinearLayout.LayoutParams((int)(22*d), (int)(22*d)));

                TextView tvLbl = new TextView(this);
                tvLbl.setText("  " + item.label);
                tvLbl.setTextColor(0xFFFFFFFF);
                tvLbl.setTextSize(11f);
                cRow.addView(tvLbl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button btnCycleColor = new Button(this);
                btnCycleColor.setText("Ubah Warna");
                btnCycleColor.setTextColor(0xFF121417);
                btnCycleColor.setBackgroundColor(0xFFFFB74D);
                btnCycleColor.setTextSize(10f);
                btnCycleColor.setOnClickListener(new View.OnClickListener() {
						int pIdx = 0;
						@Override
						public void onClick(View v) {
							pIdx = (pIdx + 1) % presetPalette.length;
							int newCol = presetPalette[pIdx];
							item.colorArgb = newCol;
							colorBox.setBackgroundColor(newCol);
							try {
								com.s60reborn.app.core.SknParser.patchColorInSkn(sknFile, item.offsetInFile, newCol);
								if (cIdx == 0) sknData.idleTextColor = newCol;
								if (cIdx == 1) sknData.menuTextColor = newCol;
								if (cIdx == 2) sknData.softkeyTextColor = newCol;
								if (cIdx == 3) sknData.highlightColor = newCol;
								previewView.updateColors(sknData.idleTextColor, sknData.menuTextColor,
														 sknData.softkeyTextColor, sknData.highlightColor);
								log("Warna SKN diubah (" + paletteNames[pIdx] + "): " + item.label);
							} catch (Exception ex) {
								log("Gagal patch SKN: " + ex.getMessage());
							}
						}
					});
                cRow.addView(btnCycleColor, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(30*d)));
                colorList.addView(cRow, clp);
            }

            colorScroll.addView(colorList);
            root.addView(colorScroll, new LinearLayout.LayoutParams(
							 LinearLayout.LayoutParams.MATCH_PARENT, (int) (130 * d)));

            Button btnDone = new Button(this);
            btnDone.setText("SIMPAN & TUTUP");
            btnDone.setTextColor(0xFF121417);
            btnDone.setBackgroundColor(0xFF00E676);
            btnDone.setTypeface(null, Typeface.BOLD);
            btnDone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            doneLp.setMargins(0, (int)(8*d), 0, 0);
            root.addView(btnDone, doneLp);

            dialog.setContentView(root);
            dialog.show();
			if (dialog.getWindow() != null) {
				int screenW = getResources().getDisplayMetrics().widthPixels;
				dialog.getWindow().setLayout((int) (screenW * 0.94f), LinearLayout.LayoutParams.WRAP_CONTENT);
			}
        } catch (Exception e) {
            log("Gagal membuka SKN Theme Studio: " + e.getMessage());
        }
    }
	
	
	/**
     * Dialog Galeri Theme Editor untuk file .MBM (Preview Thumbnail + Ganti Gambar dari Galeri)
     */
    private void showMbmEditorDialog(final File mbmFile) {
        try {
            final java.util.List<android.graphics.Bitmap> bitmaps = com.s60reborn.app.core.MbmConverter.loadBitmapsFromMbm(mbmFile);

            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            float d = getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_glass_bg);
            int pad = (int) (16 * d);
            root.setPadding(pad, pad, pad, pad);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("🎨 MBM Theme Editor: " + mbmFile.getName());
            tvTitle.setTextColor(0xFF00E676);
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);

            TextView tvSub = new TextView(this);
            tvSub.setText("Total " + bitmaps.size() + " gambar. Ketuk 'Ganti' untuk menimpa gambar tema:");
            tvSub.setTextColor(0xFFB0BEC5);
            tvSub.setTextSize(11f);
            tvSub.setPadding(0, (int)(4*d), 0, (int)(10*d));
            root.addView(tvSub);

            ScrollView scroll = new ScrollView(this);
            LinearLayout listContainer = new LinearLayout(this);
            listContainer.setOrientation(LinearLayout.VERTICAL);

            for (int i = 0; i < bitmaps.size(); i++) {
                final int idx = i;
                final android.graphics.Bitmap bmp = bitmaps.get(i);

                LinearLayout itemRow = new LinearLayout(this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setBackgroundResource(R.drawable.input_modern);
                itemRow.setPadding((int)(8*d), (int)(8*d), (int)(8*d), (int)(8*d));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, (int)(8*d));

                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setBackgroundColor(0xFF090B0E);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                if (bmp != null) iv.setImageBitmap(bmp);
                itemRow.addView(iv, new LinearLayout.LayoutParams((int)(64*d), (int)(64*d)));

                LinearLayout infoCol = new LinearLayout(this);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                infoCol.setPadding((int)(10*d), 0, (int)(6*d), 0);

                TextView tvIdx = new TextView(this);
                tvIdx.setText("Gambar #" + idx);
                tvIdx.setTextColor(0xFFFFFFFF);
                tvIdx.setTextSize(13f);
                tvIdx.setTypeface(null, Typeface.BOLD);

                TextView tvDim = new TextView(this);
                tvDim.setText(bmp != null ? (bmp.getWidth() + " x " + bmp.getHeight() + " px") : "Format tidak didukung");
                tvDim.setTextColor(0xFF4FC3F7);
                tvDim.setTextSize(11f);

                infoCol.addView(tvIdx);
                infoCol.addView(tvDim);
                itemRow.addView(infoCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button btnReplace = new Button(this);
                btnReplace.setText("Ganti");
                btnReplace.setTextColor(0xFF121417);
                btnReplace.setBackgroundColor(0xFF00E676);
                btnReplace.setTextSize(11f);
                btnReplace.setTypeface(null, Typeface.BOLD);
                btnReplace.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							pendingEditAssetFile = mbmFile;
							pendingEditAssetIndex = idx;
							dialog.dismiss();
							Intent pickImg = new Intent(Intent.ACTION_GET_CONTENT);
							pickImg.setType("image/*");
							startActivityForResult(Intent.createChooser(pickImg, "Pilih Gambar PNG/JPG Baru"), REQ_REPLACE_MBM_IMAGE);
						}
					});
                itemRow.addView(btnReplace, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(36*d)));

                listContainer.addView(itemRow, lp);
            }

            scroll.addView(listContainer);
            root.addView(scroll, new LinearLayout.LayoutParams(
							 LinearLayout.LayoutParams.MATCH_PARENT, (int) (300 * d)));

            Button btnClose = new Button(this);
            btnClose.setText("SELESAI");
            btnClose.setTextColor(0xFFFFFFFF);
            btnClose.setBackgroundColor(0xFF37474F);
            btnClose.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            closeLp.setMargins(0, (int)(10*d), 0, 0);
            root.addView(btnClose, closeLp);

            dialog.setContentView(root);
            dialog.show();
        } catch (Exception e) {
            log("Gagal membuka MBM: " + e.getMessage());
        }
    }

    /**
     * Dialog MIF Icon Editor untuk mengganti ikon vektor SVGB/SVG di dalam .mif
     */
	/**
     * Dialog MIF Icon Editor dengan ListView (View Recycling 60fps) + Thumbnail Preview SVGB
     */
    private void showMifEditorDialog(final File mifFile) {
        try {
            final java.util.List<com.s60reborn.app.core.MifConverter.MifIconEntry> allEntries =
				com.s60reborn.app.core.MifConverter.parseMif(mifFile);

            // Saring hanya slot ikon yang berisi data (abaikan slot Mask/Empty 0 byte agar rapi)
            final java.util.List<com.s60reborn.app.core.MifConverter.MifIconEntry> validEntries = new java.util.ArrayList<>();
            for (com.s60reborn.app.core.MifConverter.MifIconEntry e : allEntries) {
                if (e.data != null && e.data.length > 0) {
                    validEntries.add(e);
                }
            }

            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            final float d = getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_glass_bg);
            int pad = (int) (16 * d);
            root.setPadding(pad, pad, pad, pad);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("💠 MIF Icon Editor: " + mifFile.getName());
            tvTitle.setTextColor(0xFF4FC3F7);
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);

            TextView tvSub = new TextView(this);
            tvSub.setText("Menampilkan " + validEntries.size() + " ikon aktif (dari total " + allEntries.size() + " slot):");
            tvSub.setTextColor(0xFFB0BEC5);
            tvSub.setTextSize(11f);
            tvSub.setPadding(0, (int)(4*d), 0, (int)(10*d));
            root.addView(tvSub);

            // Cache thumbnail ringan di memori agar scrolling super mulus
            final android.util.LruCache<Integer, android.graphics.Bitmap> thumbCache =
				new android.util.LruCache<Integer, android.graphics.Bitmap>(120);

            android.widget.ListView listView = new android.widget.ListView(this);
            listView.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            listView.setDividerHeight((int)(6 * d));
            listView.setCacheColorHint(0);
            final java.util.Map<Integer, String> mifNameMap = buildMifIndexNameMap(mifFile);
			
            listView.setAdapter(new android.widget.BaseAdapter() {
					@Override
					public int getCount() {
						return validEntries.size();
					}

					@Override
					public Object getItem(int position) {
						return validEntries.get(position);
					}

					@Override
					public long getItemId(int position) {
						return validEntries.get(position).index;
					}

					@Override
					public View getView(int position, View convertView, android.view.ViewGroup parent) {
						final com.s60reborn.app.core.MifConverter.MifIconEntry item = validEntries.get(position);

						LinearLayout row;
						android.widget.ImageView ivThumb;
						TextView t1, t2;
						Button btnRep;

						if (convertView == null) {
							row = new LinearLayout(MainActivity.this);
							row.setOrientation(LinearLayout.HORIZONTAL);
							row.setGravity(Gravity.CENTER_VERTICAL);
							row.setBackgroundResource(R.drawable.input_modern);
							row.setPadding((int)(8*d), (int)(8*d), (int)(8*d), (int)(8*d));

							ivThumb = new android.widget.ImageView(MainActivity.this);
							ivThumb.setId(101);
							ivThumb.setBackgroundColor(0xFF090B0E);
							ivThumb.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
							row.addView(ivThumb, new LinearLayout.LayoutParams((int)(46*d), (int)(46*d)));

							LinearLayout col = new LinearLayout(MainActivity.this);
							col.setOrientation(LinearLayout.VERTICAL);
							col.setPadding((int)(10*d), 0, (int)(6*d), 0);

							t1 = new TextView(MainActivity.this);
							t1.setId(102);
							t1.setTextColor(0xFFFFFFFF);
							t1.setTextSize(12f);
							t1.setTypeface(null, Typeface.BOLD);
							String mappedName = mifNameMap.get(item.index);
							if (mappedName != null) {
								t1.setText("#" + item.index + " • " + mappedName);
								t1.setTextColor(0xFF00E676);
							} else {
								t1.setText("Slot Ikon #" + item.index);
								t1.setTextColor(0xFFFFFFFF);
							}
							

							t2 = new TextView(MainActivity.this);
							t2.setId(103);
							t2.setTextColor(0xFF80CBC4);
							t2.setTextSize(10.5f);

							col.addView(t1);
							col.addView(t2);
							row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

							btnRep = new Button(MainActivity.this);
							btnRep.setId(104);
							btnRep.setText("Ganti SVG");
							btnRep.setTextColor(0xFF121417);
							btnRep.setBackgroundColor(0xFF4FC3F7);
							btnRep.setTextSize(10.5f);
							btnRep.setTypeface(null, Typeface.BOLD);
							row.addView(btnRep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(34*d)));
						} else {
							row = (LinearLayout) convertView;
							ivThumb = row.findViewById(101);
							t1 = row.findViewById(102);
							t2 = row.findViewById(103);
							btnRep = row.findViewById(104);
						}

						t1.setText("Slot Ikon #" + item.index);
						t2.setText(item.typeLabel + " (" + item.data.length + " B)");

						android.graphics.Bitmap bmp = thumbCache.get(item.index);
						if (bmp == null) {
							bmp = com.s60reborn.app.core.MifConverter.renderMifIconToBitmap(item.data, (int)(48*d));
							if (bmp != null) thumbCache.put(item.index, bmp);
						}
						ivThumb.setImageBitmap(bmp);

						btnRep.setOnClickListener(new View.OnClickListener() {
								@Override
								public void onClick(View v) {
									pendingEditAssetFile = mifFile;
									pendingEditAssetIndex = item.index;
									dialog.dismiss();
									Intent pickSvg = new Intent(Intent.ACTION_GET_CONTENT);
									pickSvg.setType("*/*");
									startActivityForResult(Intent.createChooser(pickSvg, "Pilih File Ikon .SVG / .SVGB"), REQ_REPLACE_MIF_ICON);
								}
							});

						return row;
					}
				});

            root.addView(listView, new LinearLayout.LayoutParams(
							 LinearLayout.LayoutParams.MATCH_PARENT, (int) (320 * d)));

            Button btnClose = new Button(this);
            btnClose.setText("SELESAI");
            btnClose.setTextColor(0xFFFFFFFF);
            btnClose.setBackgroundColor(0xFF37474F);
            btnClose.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            closeLp.setMargins(0, (int)(10*d), 0, 0);
            root.addView(btnClose, closeLp);

            dialog.setContentView(root);
            dialog.show();
            if (dialog.getWindow() != null) {
                int screenW = getResources().getDisplayMetrics().widthPixels;
                dialog.getWindow().setLayout((int) (screenW * 0.92f), LinearLayout.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            log("Gagal membuka MIF: " + e.getMessage());
        }
    }
	
	private java.util.Map<Integer, String> buildMifIndexNameMap(File anyFileInProject) {
        java.util.Map<Integer, String> indexToName = new java.util.HashMap<>();
        try {
            // Cari root folder varian (misal: .../namatema/varian_1) atau akar proyek
            File searchRoot = anyFileInProject.getParentFile();
            File cursor = searchRoot;
            while (cursor != null) {
                if (cursor.getName().startsWith("varian_") || cursor.equals(activeProjectDir)) {
                    searchRoot = cursor;
                    break;
                }
                cursor = cursor.getParentFile();
            }
            if (searchRoot == null) return indexToName;

            // Cari file .skn di dalam folder varian yang sama
            File sknFile = findFileByExtension(searchRoot, ".skn");
            if (sknFile == null) return indexToName;

            java.util.Map<String, String> skinDb =
				com.s60reborn.app.core.SknParser.loadBinarySisContentsDbFromAssets(this, "items.db");
            java.util.List<com.s60reborn.app.core.SknParser.SknAssetMapping> mappings =
				com.s60reborn.app.core.SknParser.extractAssetMappings(sknFile, skinDb);

            for (com.s60reborn.app.core.SknParser.SknAssetMapping m : mappings) {
                if (!indexToName.containsKey(m.assetIndex)) {
                    indexToName.put(m.assetIndex, m.elementName + " [" + m.uidKey + "]");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return indexToName;
    }
	

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        final String fileName = getFileNameFromUri(uri);

        if (requestCode == REQ_PICK_SIS) {
            try {
                File tempDir = new File(baseWorkDir, "Temp");
                if (!tempDir.exists()) tempDir.mkdirs();
                final File tempCache = new File(tempDir, fileName);

                copyUriToFile(uri, tempCache);
                activeInputSisFile = tempCache;

                String cleanName = fileName.replaceAll("(?i)\\.sisx?$", "").replaceAll("[^a-zA-Z0-9_-]", "_");
                activeProjectDir = new File(baseWorkDir, "Projects/" + cleanName);
                tvActiveFile.setText(fileName);

                new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								final SisPackageInfo info = SisxCoreParser.inspectSisx(tempCache);
								runOnUiThread(new Runnable() {
										@Override
										public void run() {
											log("Inspeksi: " + fileName + " (" + info.files.size() + " file, " + (info.isSigned ? "SIGNED" : "UNSIGNED") + ")");
											showPackageInspectDialog(fileName, info);
										}
									});
							} catch (final Exception ex) {
								runOnUiThread(new Runnable() {
										@Override
										public void run() {
											log("Info file: " + ex.getMessage());
										}
									});
							}
						}
					}).start();
            } catch (Exception e) {
                log("Gagal memuat paket: " + e.getMessage());
            }
        } else if (requestCode == REQ_ADD_ASSET_FILE) {
            // Pengguna memilih file baru untuk ditambahkan ke paket -> Tampilkan Dialog Pilih Folder Tujuan!
            showAddFileTargetDialog(uri, fileName);
		} else if (requestCode == REQ_REPLACE_MBM_IMAGE && pendingEditAssetFile != null) {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                android.graphics.Bitmap pickedBmp = android.graphics.BitmapFactory.decodeStream(is);
                if (is != null) is.close();

                if (pickedBmp != null) {
                    com.s60reborn.app.core.MbmConverter.replaceBitmapInMbm(
						pendingEditAssetFile, pendingEditAssetIndex, pickedBmp);
                    log("SUKSES Ganti Gambar #" + pendingEditAssetIndex + " di " + pendingEditAssetFile.getName());
                    Toast.makeText(this, "Gambar tema berhasil diganti!", Toast.LENGTH_SHORT).show();
                    showMbmEditorDialog(pendingEditAssetFile);
                }
            } catch (Exception e) {
                log("Gagal ganti gambar MBM: " + e.getMessage());
            }
        } else if (requestCode == REQ_REPLACE_MIF_ICON && pendingEditAssetFile != null) {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                if (is != null) is.close();

                com.s60reborn.app.core.MifConverter.replaceMifEntry(
					pendingEditAssetFile, pendingEditAssetIndex, bos.toByteArray());
                log("SUKSES Ganti Ikon #" + pendingEditAssetIndex + " di " + pendingEditAssetFile.getName());
                Toast.makeText(this, "Ikon MIF berhasil diganti!", Toast.LENGTH_SHORT).show();
                showMifEditorDialog(pendingEditAssetFile);
            } catch (Exception e) {
                log("Gagal ganti ikon MIF: " + e.getMessage());
            }
        }
		
		else if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (requestCode == REQ_PICK_CUSTOM_CER || requestCode == REQ_PICK_CUSTOM_KEY) {
                try {
                    File certDir = new File(baseWorkDir, "Certs");
                    if (!certDir.exists()) certDir.mkdirs();
                    String destName = (requestCode == REQ_PICK_CUSTOM_CER) ? "user_custom.cer" : "user_custom.key";
                    File destFile = new File(certDir, destName);

                    java.io.InputStream is = getContentResolver().openInputStream(data.getData());
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile);
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    is.close();
                    fos.close();

                    android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);
                    String prefKey = (requestCode == REQ_PICK_CUSTOM_CER) ? "custom_cer_path" : "custom_key_path";
                    prefs.edit().putString(prefKey, destFile.getAbsolutePath()).putBoolean("use_custom_cert", true).apply();

                    log("Sertifikat custom tersimpan: " + destName);
                    showCertificateSettingsDialog(); // Buka ulang dialog agar status file terbarui
                } catch (Exception e) {
                    log("Gagal memuat sertifikat: " + e.getMessage());
                }
            }
		} else if (requestCode == REQ_PICK_INSTALL_ACTION_FILE) {
            try {
                if (!certDir.exists()) certDir.mkdirs();
                File destActionFile = new File(certDir, "action_" + fileName);
                copyUriToFile(uri, destActionFile);

                android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);
                prefs.edit().putString("install_action_file_path", destActionFile.getAbsolutePath()).apply();

                log("File aksi instalasi tersimpan: " + fileName);
                showSettingsDialog();
            } catch (Exception e) {
                log("Gagal memuat file aksi: " + e.getMessage());
            }
        }
		
		
		
		
    }
	
	private void showCertificateSettingsDialog() {
        final android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);
        boolean useCustom = prefs.getBoolean("use_custom_cert", false);
        String cerPath = prefs.getString("custom_cer_path", "");
        String keyPath = prefs.getString("custom_key_path", "");

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_glass_bg);
        int pad = (int) (18 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🔐 Pengaturan Sertifikat Auto-Sign");
        tvTitle.setTextColor(0xFF00E676);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        rg.setPadding(0, (int)(10*d), 0, (int)(10*d));

        final android.widget.RadioButton rbDefault = new android.widget.RadioButton(this);
        rbDefault.setText("Sertifikat Bawaan (assets/sign.cer)");
        rbDefault.setTextColor(0xFFFFFFFF);
        rbDefault.setChecked(!useCustom);

        final android.widget.RadioButton rbCustom = new android.widget.RadioButton(this);
        rbCustom.setText("Sertifikat Custom (.CER & .KEY User)");
        rbCustom.setTextColor(0xFFFFFFFF);
        rbCustom.setChecked(useCustom);

        rg.addView(rbDefault);
        rg.addView(rbCustom);
        root.addView(rg);

        // Info file custom yang tersimpan
        TextView tvInfo = new TextView(this);
        String cerName = cerPath.isEmpty() ? "(Belum dipilih)" : new File(cerPath).getName();
        String keyName = keyPath.isEmpty() ? "(Belum dipilih)" : new File(keyPath).getName();
        tvInfo.setText("File .CER : " + cerName + "\nFile .KEY : " + keyName);
        tvInfo.setTextColor(0xFF81D4FA);
        tvInfo.setTextSize(11f);
        tvInfo.setPadding(0, 0, 0, (int)(10*d));
        root.addView(tvInfo);

        // Tombol Pilih .CER dan .KEY
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnPickCer = new Button(this);
        btnPickCer.setText("Pilih .CER");
        btnPickCer.setTextColor(0xFF121417);
        btnPickCer.setBackgroundColor(0xFF4FC3F7);
        btnPickCer.setTextSize(11f);
        btnPickCer.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					prefs.edit().putBoolean("use_custom_cert", true).apply();
					dialog.dismiss();
					Intent i = new Intent(Intent.ACTION_GET_CONTENT);
					i.setType("*/*");
					startActivityForResult(Intent.createChooser(i, "Pilih File Sertifikat (.cer)"), REQ_PICK_CUSTOM_CER);
				}
			});

        Button btnPickKey = new Button(this);
        btnPickKey.setText("Pilih .KEY");
        btnPickKey.setTextColor(0xFF121417);
        btnPickKey.setBackgroundColor(0xFFFFB74D);
        btnPickKey.setTextSize(11f);
        btnPickKey.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					prefs.edit().putBoolean("use_custom_cert", true).apply();
					dialog.dismiss();
					Intent i = new Intent(Intent.ACTION_GET_CONTENT);
					i.setType("*/*");
					startActivityForResult(Intent.createChooser(i, "Pilih File Private Key (.key)"), REQ_PICK_CUSTOM_KEY);
				}
			});

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, (int)(38*d), 1f);
        lp1.setMargins(0, 0, (int)(6*d), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, (int)(38*d), 1f);
        btnRow.addView(btnPickCer, lp1);
        btnRow.addView(btnPickKey, lp2);
        root.addView(btnRow);

        Button btnSave = new Button(this);
        btnSave.setText("SIMPAN PILIHAN");
        btnSave.setTextColor(0xFF121417);
        btnSave.setBackgroundColor(0xFF00E676);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					prefs.edit().putBoolean("use_custom_cert", rbCustom.isChecked()).apply();
					log("Mode Auto-Sign: " + (rbCustom.isChecked() ? "Custom (.CER/.KEY)" : "Bawaan (assets/sign.cer)"));
					dialog.dismiss();
				}
			});
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(0, (int)(12*d), 0, 0);
        root.addView(btnSave, saveLp);

        dialog.setContentView(root);
        dialog.show();
    }
	

    /**
     * Dialog Modern ramah awam untuk memilih folder tujuan di Symbian saat menambahkan file baru
     */
    private void showAddFileTargetDialog(final Uri fileUri, final String fileName) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_glass_bg);
        int pad = (int) (18 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("➕ Tambah File ke Paket");
        tvTitle.setTextColor(0xFF00E676);
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvFile = new TextView(this);
        tvFile.setText("File: " + fileName);
        tvFile.setTextColor(0xFFFFFFFF);
        tvFile.setTextSize(13f);
        tvFile.setPadding(0, (int)(8*d), 0, (int)(12*d));
        root.addView(tvFile);

        TextView tvLabel = new TextView(this);
        tvLabel.setText("Pilih Folder Tujuan Instalasi di HP Symbian:");
        tvLabel.setTextColor(0xFFB0BEC5);
        tvLabel.setTextSize(12f);
        root.addView(tvLabel);

        final String[] presetFolders = new String[]{
			"!:\\sys\\bin\\ (File Aplikasi .exe / .dll)",
			"!:\\resource\\apps\\ (Ikon .mif / Bahasa .rsc)",
			"!:\\private\\10003a3f\\import\\apps\\ (Registrasi _reg.rsc)",
			"!:\\data\\ (Data / Konfigurasi Umum)",
			"C:\\data\\ (Memori Telepon)",
			"E:\\data\\ (Kartu Memori)",
			"Custom Path (Ketik Manual)"
        };

        final Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presetFolders);
        spinner.setAdapter(adapter);
        spinner.setBackgroundColor(0xFF263238);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spLp.setMargins(0, (int)(6*d), 0, (int)(8*d));
        root.addView(spinner, spLp);

        final EditText etCustomTarget = new EditText(this);
        etCustomTarget.setHint("Atau ketik path penuh, misal: !:\\data\\config.ini");
        etCustomTarget.setTextColor(0xFFFFFFFF);
        etCustomTarget.setHintTextColor(0xFF607D8B);
        etCustomTarget.setBackgroundResource(R.drawable.input_modern);
        etCustomTarget.setTextSize(12f);
        etCustomTarget.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
        root.addView(etCustomTarget);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(14*d), 0, 0);

        Button btnCancel = new Button(this);
        btnCancel.setText("Batal");
        btnCancel.setTextColor(0xFFFFFFFF);
        btnCancel.setBackgroundColor(0xFF37474F);
        btnCancel.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        Button btnAdd = new Button(this);
        btnAdd.setText("TAMBAHKAN");
        btnAdd.setTextColor(0xFF121417);
        btnAdd.setBackgroundColor(0xFF00E676);
        btnAdd.setTypeface(null, Typeface.BOLD);
        btnAdd.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					try {
						String custom = etCustomTarget.getText().toString().trim();
						String targetFullPath;
						if (!custom.isEmpty()) {
							targetFullPath = custom;
						} else {
							String selected = presetFolders[spinner.getSelectedItemPosition()];
							String folderPrefix = selected.split(" \\(")[0];
							targetFullPath = folderPrefix + fileName;
						}

						// Salin file ke dalam folder proyek
						String relLocal = targetFullPath.replace('\\', '/').replaceFirst("^[!a-zA-Z]:/", "");
						if (!activeProjectDir.exists()) activeProjectDir.mkdirs();
						File destFile = new File(activeProjectDir, relLocal);
						if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
							destFile.getParentFile().mkdirs();
						}
						copyUriToFile(fileUri, destFile);

						SisFileEntry newEntry = new SisFileEntry();
						newEntry.targetPath = targetFullPath;
						newEntry.localRelPath = relLocal;
						newEntry.uncompressedSize = destFile.length();
						currentPkgInfo.files.add(newEntry);

						refreshFileListUI();
						log("Ditambahkan: " + fileName + " -> " + targetFullPath);
						saveLastSession();
						dialog.dismiss();
					} catch (Exception ex) {
						Toast.makeText(MainActivity.this, "Gagal menambah file: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
					}
				}
			});

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, (int)(8*d), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);

        btnRow.addView(btnCancel, lp1);
        btnRow.addView(btnAdd, lp2);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    private void showPackageInspectDialog(String fileName, SisPackageInfo info) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_glass_bg);
        int pad = (int) (18 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📦 " + fileName);
        tvTitle.setTextColor(0xFF00E676);
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvBadge = new TextView(this);
        tvBadge.setText(info.isSigned ? "🔒 STATUS: SIGNED" : "🔓 STATUS: UNSIGNED");
        tvBadge.setTextColor(info.isSigned ? 0xFFFFB74D : 0xFF4FC3F7);
        tvBadge.setTextSize(12f);
        tvBadge.setTypeface(null, Typeface.BOLD);
        tvBadge.setPadding(0, (int)(6*d), 0, (int)(8*d));
        root.addView(tvBadge);

        StringBuilder details = new StringBuilder();
        details.append("Format : ").append(info.formatLabel).append("\n");
        details.append("Nama   : ").append(info.appName).append("\n");
        details.append("Author : ").append(info.vendorName).append("\n");
        details.append("UID3   : ").append(info.getFormattedUid3()).append("\n");
        details.append("Total  : ").append(info.files.size()).append(" file di dalam paket\n\n");
        details.append("Daftar File:\n");
        for (SisFileEntry f : info.files) {
            details.append("  • ").append(f.targetPath).append("\n");
        }

        ScrollView scroll = new ScrollView(this);
        TextView tvBody = new TextView(this);
        tvBody.setText(details.toString());
        tvBody.setTextColor(0xFFE0F2F1);
        tvBody.setTextSize(12f);
        tvBody.setTypeface(Typeface.MONOSPACE);
        scroll.addView(tvBody);

        root.addView(scroll, new LinearLayout.LayoutParams(
						 LinearLayout.LayoutParams.MATCH_PARENT, (int) (180 * d)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(14*d), 0, 0);

        Button btnClose = new Button(this);
        btnClose.setText("Tutup");
        btnClose.setTextColor(0xFFFFFFFF);
        btnClose.setBackgroundColor(0xFF37474F);
        btnClose.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        Button btnUnpackNow = new Button(this);
        btnUnpackNow.setText("UNPACK SEKARANG");
        btnUnpackNow.setTextColor(0xFF121417);
        btnUnpackNow.setBackgroundColor(0xFF00E676);
        btnUnpackNow.setTypeface(null, Typeface.BOLD);
        btnUnpackNow.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
					runUnpack();
				}
			});

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, (int)(8*d), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);

        btnRow.addView(btnClose, lp1);
        btnRow.addView(btnUnpackNow, lp2);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    private void runUnpack() {
        if (activeInputSisFile == null || !activeInputSisFile.exists()) {
            log("PERHATIAN: Pilih file .sis / .sisx dulu lewat tombol +");
            return;
        }

        log("Membongkar: " + activeInputSisFile.getName() + "...");
        new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						final SisPackageInfo info = SisxCoreParser.unpackSisx(activeInputSisFile, activeProjectDir);
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									populateFormFromModel(info);
									log("SUKSES UNPACK! " + info.files.size() + " file siap diedit.");
									saveLastSession();
								}
							});
					} catch (final Exception e) {
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									log("GAGAL UNPACK: " + e.getMessage());
								}
							});
					}
				}
			}).start();
    }

	private void runRepack() {
        final android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);
        final int platformIdx = prefs.getInt("target_platform_idx", 0);
        final boolean isLegacyV2 = (platformIdx == 4) || currentPkgInfo.formatLabel.contains("Legacy SIS");

        log(isLegacyV2
			? "Menyusun paket Legacy S60v1/v2 (.SIS)..."
			: "Menyusun paket Symbian 9.x / S60v3-Belle (.SIS)...");

        new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						final File pkgFile = syncFormAndWritePkg();
						final String suffix = isLegacyV2 ? "_S60v2.sis" : "_repacked.sis";
						final File outSis = new File(baseWorkDir, "Output/" + activeProjectDir.getName() + suffix);

						final SisPackageInfo info = isLegacyV2
                            ? SisxPacker.packLegacyV2FromPkg(pkgFile, outSis)
                            : SisxPacker.packFromPkg(pkgFile, outSis);

						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									log("SUKSES BUILD: " + outSis.getName());
									log("  Lokasi : " + outSis.getAbsolutePath());
									log("  Ukuran : " + outSis.length() + " bytes");
									if (isLegacyV2) {
										log("  Info   : Format S60v2 tidak perlu di-Sign, siap langsung install!");
									} else if (prefs.getBoolean("auto_sign", false)) {
										runSign();
									}
								}
							});
					} catch (final Exception e) {
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									log("GAGAL BUILD: " + e.getMessage());
								}
							});
					}
				}
			}).start();
    }
	

	private void runSign() {
        final File unsignedSis = new File(baseWorkDir, "Output/" + activeProjectDir.getName() + "_repacked.sis");
        if (!unsignedSis.exists()) {
            log("ERROR: Jalankan Build/Repack (.SIS) terlebih dahulu.");
            return;
        }

        final String[] options = new String[]{
            "🔐 Default Self-Sign (Bawaan Assets)",
            "📂 Gunakan Sertifikat Custom (.CER & .KEY)",
            "⚙️ Atur / Pilih File .CER & .KEY Custom"
        };

        new android.app.AlertDialog.Builder(this)
            .setTitle("Pilih Metode Signing (.SISX)")
            .setItems(options, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    if (which == 0) {
                        executeSignProcess(unsignedSis, false);
                    } else if (which == 1) {
                        executeSignProcess(unsignedSis, true);
                    } else {
                        showCertificateSettingsDialog();
                    }
                }
            })
            .show();
    }

    private void executeSignProcess(final File unsignedSis, final boolean useCustomCert) {
        final File signedSisx = new File(baseWorkDir, "Output/" + activeProjectDir.getName() + "_signed.sisx");
        final android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);

        if (useCustomCert) {
            String cerPath = prefs.getString("custom_cer_path", "");
            String keyPath = prefs.getString("custom_key_path", "");
            final File customCer = new File(cerPath);
            final File customKey = new File(keyPath);

            if (cerPath.isEmpty() || keyPath.isEmpty() || !customCer.exists() || !customKey.exists()) {
                log("PERHATIAN: File .CER / .KEY custom belum dipilih.");
                showCertificateSettingsDialog();
                return;
            }

            log("Menandatangani dengan Sertifikat Custom...");
            new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							SisxSigner.signSisFile(unsignedSis, customCer, customKey, signedSisx);
							runOnUiThread(new Runnable() {
									@Override
									public void run() {
										log("SUKSES SIGNING (CUSTOM)!");
										log("  Siap Install: " + signedSisx.getAbsolutePath());
									}
								});
						} catch (final Exception e) {
							runOnUiThread(new Runnable() {
									@Override
									public void run() {
										log("GAGAL SIGNING: " + e.getMessage());
									}
								});
						}
					}
				}).start();
        } else {
            log("Menandatangani dengan Sertifikat Bawaan (Assets)...");
            new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							SisxSigner.signSisFromAssets(MainActivity.this, unsignedSis, signedSisx);
							runOnUiThread(new Runnable() {
									@Override
									public void run() {
										log("SUKSES SIGNING (DEFAULT ASSETS)!");
										log("  Siap Install: " + signedSisx.getAbsolutePath());
									}
								});
						} catch (final Exception e) {
							runOnUiThread(new Runnable() {
									@Override
									public void run() {
										log("GAGAL SIGNING ASSETS: " + e.getMessage());
									}
								});
						}
					}
				}).start();
        }
    }
	

    private void animateClickBounce(View v) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(v, "scaleX", 0.88f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(v, "scaleY", 0.88f, 1.0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY);
        set.setDuration(180);
        set.setInterpolator(new OvershootInterpolator());
        set.start();
    }

    private void toggleFabMenu() {
        if (isFabOpen) {
            closeFabMenu();
        } else {
            isFabOpen = true;
            fabBackdrop.setVisibility(View.VISIBLE);
            fabMenuContainer.setVisibility(View.VISIBLE);
            ObjectAnimator.ofFloat(fabMain, "rotation", 0f, 45f).setDuration(220).start();
            fabMenuContainer.setAlpha(0f);
            fabMenuContainer.setTranslationY(40f);
            fabMenuContainer.animate().alpha(1f).translationY(0f).setDuration(220)
				.setInterpolator(new OvershootInterpolator()).start();
        }
    }

    private void closeFabMenu() {
        if (!isFabOpen) return;
        isFabOpen = false;
        fabBackdrop.setVisibility(View.GONE);
        ObjectAnimator.ofFloat(fabMain, "rotation", 45f, 0f).setDuration(200).start();
        fabMenuContainer.animate().alpha(0f).translationY(30f).setDuration(160)
			.withEndAction(new Runnable() {
				@Override
				public void run() {
					fabMenuContainer.setVisibility(View.GONE);
				}
			}).start();
    }

    private void copyUriToFile(Uri uri, File destFile) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri);
		FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private void log(final String msg) {
        tvLog.append(msg + "\n");
        svLog.post(new Runnable() {
				@Override
				public void run() {
					svLog.fullScroll(View.FOCUS_DOWN);
				}
			});
    }

    private String getFileNameFromUri(Uri uri) {
        String result = "package.sisx";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        } else if (uri.getPath() != null) {
            result = new File(uri.getPath()).getName();
        }
        return result;
    }
	
	private void saveLastSession() {
        android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = prefs.edit();
        ed.putString("last_project_dir", activeProjectDir != null ? activeProjectDir.getAbsolutePath() : "");
        ed.putString("last_sis_file", (activeInputSisFile != null && activeInputSisFile.exists())
					 ? activeInputSisFile.getAbsolutePath() : "");
        ed.apply();
    }

	private void restoreLastSessionIfAvailable() {
        android.content.SharedPreferences prefs = getSharedPreferences("s60reborn_prefs", MODE_PRIVATE);
        String savedProjPath = prefs.getString("last_project_dir", "");
        String savedSisPath = prefs.getString("last_sis_file", "");

        File savedProjDir = !savedProjPath.isEmpty() ? new File(savedProjPath) : null;

        // Fallback: jika prefs kosong, cek apakah ada folder proyek terakhir di direktori Projects/
        if (savedProjDir == null || !savedProjDir.exists()) {
            File projectsRoot = new File(baseWorkDir, "Projects");
            File[] dirs = projectsRoot.listFiles();
            if (dirs != null) {
                long latestMod = 0;
                for (File d : dirs) {
                    if (d.isDirectory() && new File(d, "project.pkg").exists() && d.lastModified() > latestMod) {
                        latestMod = d.lastModified();
                        savedProjDir = d;
                    }
                }
            }
        }

        if (savedProjDir == null || !savedProjDir.exists()) return; // Memang belum ada cache, skip!

        File savedPkgFile = new File(savedProjDir, "project.pkg");
        if (!savedPkgFile.exists()) return;

        try {
            activeProjectDir = savedProjDir;
            if (!savedSisPath.isEmpty() && new File(savedSisPath).exists()) {
                activeInputSisFile = new File(savedSisPath);
                tvActiveFile.setText(activeInputSisFile.getName());
            } else {
                tvActiveFile.setText(savedProjDir.getName() + " (Tersimpan)");
            }

            SisPackageInfo restoredInfo = SisxPacker.parsePkgFile(savedPkgFile, savedProjDir);
            populateFormFromModel(restoredInfo);
            log("Cache proyek dimuat: " + restoredInfo.appName + " (" + restoredInfo.files.size() + " file)");
        } catch (Exception e) {
            log("Skip cache lama: " + e.getMessage());
        }
    }
	
	private void openThemeVariantPicker(File projectDir, final String targetExt) {
        final java.util.List<File> targetFiles = new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();

        File[] subDirs = projectDir.listFiles();
        if (subDirs != null) {
            java.util.Arrays.sort(subDirs);
            for (File dir : subDirs) {
                if (dir.isDirectory() && dir.getName().startsWith("varian_")) {
                    File found = findFileByExtension(dir, targetExt);
                    if (found != null) {
                        targetFiles.add(found);
                        long kb = Math.max(1, found.length() / 1024);
                        labels.add(dir.getName().toUpperCase() + "  (" + found.getName() + " • " + kb + " KB)");
                    }
                }
            }
        }

        // Jika cuma ada 1 varian (atau struktur folder biasa), langsung buka
        if (targetFiles.size() <= 1) {
            File single = targetFiles.isEmpty()
				? findFileByExtension(projectDir, targetExt)
				: targetFiles.get(0);
            if (single != null) {
                dispatchEditorByExtension(single, targetExt);
            } else {
                Toast.makeText(this, "File " + targetExt + " tidak ditemukan di proyek ini", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // Jika ada banyak varian (varian_1, varian_2, dst.), tampilkan pilihan
        new android.app.AlertDialog.Builder(this)
			.setTitle("Pilih Varian " + targetExt.toUpperCase() + " (" + targetFiles.size() + " Ditemukan)")
			.setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
				@Override
				public void onClick(android.content.DialogInterface dialog, int which) {
					dispatchEditorByExtension(targetFiles.get(which), targetExt);
				}
			})
			.show();
    }

    private void dispatchEditorByExtension(File targetFile, String targetExt) {
        if (".skn".equalsIgnoreCase(targetExt)) {
            showInteractiveNokiaThemeDialog(targetFile);
        } else if (".mif".equalsIgnoreCase(targetExt)) {
            showMifEditorDialog(targetFile);
        } else if (".mbm".equalsIgnoreCase(targetExt)) {
            showMbmEditorDialog(targetFile); // Sesuaikan dengan nama method MBM Editor milikmu
        }
    }
	

    private File findFileByExtension(File root, String ext) {
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File sub = findFileByExtension(f, ext);
                if (sub != null) return sub;
            } else if (f.getName().toLowerCase().endsWith(ext)) {
                return f;
            }
        }
        return null;
    }
	
	
	
}

