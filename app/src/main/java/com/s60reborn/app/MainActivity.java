package com.s60reborn.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.s60reborn.app.core.MbmConverter;
import com.s60reborn.app.core.MifConverter;
import com.s60reborn.app.core.RscParser;
import com.s60reborn.app.core.SisxCoreParser;
import com.s60reborn.app.core.SisxPacker;
import com.s60reborn.app.core.SisxSigner;
import com.s60reborn.app.core.SknParser;
import com.s60reborn.app.core.SymbianCapabilityManager;
import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;
import com.s60reborn.app.model.SisProjectConfig;
import com.s60reborn.app.ui.NokiaThemePreviewView;
import com.s60reborn.app.ui.SisAdvancedSettingsDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import com.s60reborn.app.ui.*;
import android.widget.*;
import android.content.*;
import com.s60reborn.app.core.*;

public class MainActivity extends Activity {

    private static final int REQ_PICK_SIS = 2001;
    private static final int REQ_ADD_ASSET_FILE = 2002;
    private static final int REQ_REPLACE_MBM_IMAGE = 2003;
    private static final int REQ_REPLACE_MIF_ICON = 2004;

    private File pendingEditAssetFile;
    private int pendingEditAssetIndex = -1;

    private EditText etAppName, etVendorName, etVerMajor, etVerMinor, etVerBuild, etUid3, etInstallNote;
    private TextView tvLog, tvActiveFile, tvFileListHeader;
    private ListView llFileItemsContainer;
    private ScrollView svLog;

    private Button fabMain, btnCloseProject,btnDeleteCache;
    private LinearLayout fabMenuContainer;
    private View fabBackdrop;
    private boolean isFabOpen = false;
	
	private android.widget.ListView lvFileItems;
	private com.s60reborn.app.ui.SisFileListAdapter fileListAdapter;
	private final ExecutorService cacheExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private ProgressBar loadingIndicator;
	

    private File baseWorkDir;
    private File certDir;
    private File activeInputSisFile;
    private File activeProjectDir;
    private SisPackageInfo currentPkgInfo = new SisPackageInfo();
    private SisProjectConfig config = new SisProjectConfig();

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
        llFileItemsContainer = findViewById(R.id.lvFileItems);
        svLog = findViewById(R.id.svLog);

        fabMain = findViewById(R.id.fabMain);
        fabMenuContainer = findViewById(R.id.fabMenuContainer);
        fabBackdrop = findViewById(R.id.fabBackdrop);

        baseWorkDir = getExternalFilesDir(null);
        if (baseWorkDir == null) baseWorkDir = getFilesDir();
        certDir = new File(baseWorkDir, "Certs");
        activeProjectDir = new File(baseWorkDir, "Projects/CustomProject");

        initWorkFolders();
        setupCloseProjectButton();
        setupClickListeners();
		
		
		lvFileItems = findViewById(R.id.lvFileItems);
		fileListAdapter = new com.s60reborn.app.ui.SisFileListAdapter(
			this,
			currentPkgInfo.files,
			activeProjectDir,
			new com.s60reborn.app.ui.SisFileListAdapter.OnFileItemActionListener() {
				@Override
				public void onDeleteItem(int position, SisFileEntry entry, String fileNameOnly) {
					currentPkgInfo.files.remove(position);
					refreshFileListUI();
					log("Dihapus dari daftar: " + fileNameOnly);
				}

				@Override
				public void onOpenThemeEditor(File physicalFile, String extension) {
					if (!physicalFile.exists()) {
						Toast.makeText(MainActivity.this, "Unpack paket dulu agar file fisik tersedia.", Toast.LENGTH_SHORT).show();
						return;
					}
					openEditTheme();
					//openThemeVariantPicker(activeProjectDir, extension);
				}

				@Override
				public void onOpenRscEditor(File physicalFile) {
					if (!physicalFile.exists()) {
						Toast.makeText(MainActivity.this, "Unpack paket dulu.", Toast.LENGTH_SHORT).show();
						return;
					}
					showRscLanguageEditorDialog(physicalFile);
				}

				@Override
				public void onOpenCapabilityEditor(File physicalFile, TextView tvTargetSubtitle) {
					if (!physicalFile.exists()) {
						Toast.makeText(MainActivity.this, "Unpack paket dulu agar file fisik tersedia.", Toast.LENGTH_SHORT).show();
						return;
					}
					showCapabilityEditorDialog(physicalFile, tvTargetSubtitle);
				}

				@Override
				public void onOpenCertificateInfo(File physicalFile) {
					if (physicalFile.exists()) {
						SisxSigner.showCertificateInfoDialog(MainActivity.this, physicalFile);
					}
				}
			}
		);
		lvFileItems.setAdapter(fileListAdapter);
		loadingIndicator = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        loadingIndicator.setIndeterminate(true);

        // Atur posisi di tengah layar (Center)
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, 
            RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        params.addRule(RelativeLayout.CENTER_IN_PARENT);

        // Ambil root layout dari activity (pastikan root layout di XML Anda adalah RelativeLayout atau FrameLayout)
        // Atau Anda bisa menggunakan ViewGroup lain sesuai layout utama Anda
        ViewGroup rootView = findViewById(R.layout.main); // Ganti dengan ID root layout XML Anda
        if (rootView != null) {
            rootView.addView(loadingIndicator, params);
        }
		restoreCacheInBackground();
		btnDeleteCache=findViewById(R.id.btnDeleteCache);
		btnDeleteCache.setOnClickListener(new View.OnClickListener(){
			public void onClick(View v){
				showSelectProjectToDeleteDialog();
			}
		});
		
    }
	
	public void openEditTheme(){
		Intent intent = new Intent(MainActivity.this, ThemeAssetEditorActivity.class);
		intent.putExtra("project_dir", activeProjectDir.getAbsolutePath());
		startActivity(intent);
		
	}
	private void showSelectProjectToDeleteDialog() {
		File projectsRootDir = new File(baseWorkDir, "projects");
		File tempDir = new File(baseWorkDir, "temp"); // Folder temp Anda

		final List<File> itemsToDelete = new ArrayList<>();
		final List<CharSequence> displayNames = new ArrayList<>();

		// 1. Masukkan folder proyek yang memiliki project.pkg
		if (projectsRootDir.exists() && projectsRootDir.isDirectory()) {
			File[] subDirs = projectsRootDir.listFiles();
			if (subDirs != null) {
				for (File dir : subDirs) {
					if (dir.isDirectory()) {
						File pkgFile = new File(dir, "project.pkg");
						if (pkgFile.exists() && pkgFile.isFile()) {
							itemsToDelete.add(dir);
							displayNames.add("📁 Proyek: " + dir.getName());
						}
					}
				}
			}
		}

		// 2. Masukkan folder temp jika ada isinya
		if (tempDir.exists() && tempDir.isDirectory()) {
			File[] tempFiles = tempDir.listFiles();
			if (tempFiles != null && tempFiles.length > 0) {
				itemsToDelete.add(tempDir);
				displayNames.add("🧹 Folder Temp (Cache Sementara)");
			}
		}

		if (itemsToDelete.isEmpty()) {
			Toast.makeText(MainActivity.this, "Tidak ada proyek atau folder temp yang perlu dibersihkan.", Toast.LENGTH_SHORT).show();
			return;
		}

		final CharSequence[] projectNames = displayNames.toArray(new CharSequence[0]);

		// --- DIALOG KUSTOM GLASSMORPHISM ---
		final Dialog dialog = new Dialog(this);
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		}

		final float d = getResources().getDisplayMetrics().density;
		LinearLayout rootLayout = new LinearLayout(this);
		rootLayout.setOrientation(LinearLayout.VERTICAL);
		rootLayout.setBackgroundResource(R.drawable.dialog_glass_bg);

		int pad = (int) (16 * d);
		rootLayout.setPadding(pad, pad, pad, pad);

		// Judul Dialog
		TextView titleView = new TextView(this);
		titleView.setText("🗑️ Pilih Item untuk Dihapus");
		titleView.setTextColor(getResources().getColor(R.color.accent_cyan));
		titleView.setTextSize(14f);
		titleView.setTypeface(null, Typeface.BOLD);
		titleView.setPadding(0, 0, 0, (int)(10 * d));
		rootLayout.addView(titleView);

		// ListView Daftar Item
		final ListView listView = new ListView(this);
		listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
		listView.setDividerHeight((int)(4 * d));

		ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(
			this, android.R.layout.simple_list_item_1, projectNames
		) {
			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				View view = super.getView(position, convertView, parent);
				TextView tv = (TextView) view.findViewById(android.R.id.text1);
				tv.setTextColor(getResources().getColor(R.color.text_primary));
				tv.setBackgroundResource(R.drawable.input_modern);
				tv.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
				return view;
			}
		};
		listView.setAdapter(adapter);

		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
				@Override
				public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					dialog.dismiss();
					final File selectedTarget = itemsToDelete.get(position);
					confirmAndDeleteTarget(selectedTarget);
				}
			});

		rootLayout.addView(listView, new LinearLayout.LayoutParams(
							   LinearLayout.LayoutParams.MATCH_PARENT, (int)(220 * d)
						   ));

		// Tombol Batal
		Button btnCancel = new Button(this);
		btnCancel.setText("Batal");
		btnCancel.setAllCaps(false);
		btnCancel.setTextColor(getResources().getColor(R.color.text_primary));
		btnCancel.setBackgroundColor(getResources().getColor(R.color.bg_btn_neutral));
		LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, (int)(38 * d)
		);
		btnLp.setMargins(0, (int)(12 * d), 0, 0);
		btnCancel.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});
		rootLayout.addView(btnCancel, btnLp);

		dialog.setContentView(rootLayout);
		dialog.show();

		if (dialog.getWindow() != null) {
			int screenW = getResources().getDisplayMetrics().widthPixels;
			dialog.getWindow().setLayout((int) (screenW * 0.90f), LinearLayout.LayoutParams.WRAP_CONTENT);
		}
	}
	
	private void confirmAndDeleteTarget(final File targetDir) {
		final boolean isTemp = targetDir.getName().equals("temp");
		final String labelName = isTemp ? "folder temp (file sementara)" : "proyek \"" + targetDir.getName() + "\"";

		final Dialog confirmDialog = new Dialog(this);
		confirmDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		if (confirmDialog.getWindow() != null) {
			confirmDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		}

		float d = getResources().getDisplayMetrics().density;
		LinearLayout rootLayout = new LinearLayout(this);
		rootLayout.setOrientation(LinearLayout.VERTICAL);
		rootLayout.setBackgroundResource(R.drawable.dialog_glass_bg);

		int pad = (int) (16 * d);
		rootLayout.setPadding(pad, pad, pad, pad);

		TextView titleView = new TextView(this);
		titleView.setText("⚠️ Konfirmasi Hapus");
		titleView.setTextColor(getResources().getColor(R.color.accent_orange));
		titleView.setTextSize(14f);
		titleView.setTypeface(null, Typeface.BOLD);
		titleView.setPadding(0, 0, 0, (int)(8 * d));
		rootLayout.addView(titleView);

		TextView msgView = new TextView(this);
		msgView.setText("Hapus seluruh isi dari " + labelName + "?");
		msgView.setTextColor(getResources().getColor(R.color.text_secondary));
		msgView.setTextSize(12f);
		msgView.setPadding(0, 0, 0, (int)(14 * d));
		rootLayout.addView(msgView);

		LinearLayout btnRow = new LinearLayout(this);
		btnRow.setOrientation(LinearLayout.HORIZONTAL);

		Button btnCancel = new Button(this);
		btnCancel.setText("Batal");
		btnCancel.setAllCaps(false);
		btnCancel.setTextColor(getResources().getColor(R.color.text_primary));
		btnCancel.setBackgroundColor(getResources().getColor(R.color.bg_btn_neutral));

		Button btnDelete = new Button(this);
		btnDelete.setText("Hapus");
		btnDelete.setAllCaps(false);
		btnDelete.setTextColor(Color.WHITE);
		btnDelete.setBackgroundColor(getResources().getColor(R.color.accent_red));
		btnDelete.setTypeface(null, Typeface.BOLD);

		LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(0, (int)(38 * d), 1f);
		lpb.setMargins(0, 0, (int)(6 * d), 0);
		btnRow.addView(btnCancel, lpb);

		LinearLayout.LayoutParams lpb2 = new LinearLayout.LayoutParams(0, (int)(38 * d), 1f);
		btnRow.addView(btnDelete, lpb2);

		rootLayout.addView(btnRow);

		btnCancel.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					confirmDialog.dismiss();
				}
			});

		btnDelete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					confirmDialog.dismiss();

					if (isTemp) {
						// Bersihkan isi folder temp tapi biarkan foldernya tetap ada
						File[] files = targetDir.listFiles();
						if (files != null) {
							for (File f : files) {
								deleteRecursive(f);
							}
						}
						Toast.makeText(MainActivity.this, "Folder temp berhasil dibersihkan!", Toast.LENGTH_SHORT).show();
					} else {
						// Hapus folder proyek secara total
						deleteRecursive(targetDir);

						if (activeProjectDir != null && activeProjectDir.equals(targetDir)) {
							activeProjectDir = null;
							currentPkgInfo = null;
							if (llFileItemsContainer != null) {
								llFileItemsContainer.setAdapter(null);
							}
						}
						Toast.makeText(MainActivity.this, "Proyek berhasil dihapus!", Toast.LENGTH_SHORT).show();
					}
				}
			});

		confirmDialog.setContentView(confirmDialog.getWindow() != null ? rootLayout : rootLayout);
		confirmDialog.setContentView(rootLayout);
		confirmDialog.show();

		if (confirmDialog.getWindow() != null) {
			int screenW = getResources().getDisplayMetrics().widthPixels;
			confirmDialog.getWindow().setLayout((int) (screenW * 0.88f), LinearLayout.LayoutParams.WRAP_CONTENT);
		}
	}
	

	private void deleteRecursive(File fileOrDirectory) {
		if (fileOrDirectory.isDirectory()) {
			File[] children = fileOrDirectory.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursive(child);
				}
			}
		}
		fileOrDirectory.delete();
	}
	
	private void restoreCacheInBackground() {
		// 1. Tampilkan indikator loading (jika ada)
		if (loadingIndicator != null) {
			loadingIndicator.setVisibility(View.VISIBLE);
		}

		// 2. Jalankan pembacaan cache di Background Thread
		cacheExecutor.execute(new Runnable() {
				@Override
				public void run() {
					// Cek apakah data cache sesi sebelumnya valid
					final boolean hasValidData = (currentPkgInfo != null && currentPkgInfo.files != null && !currentPkgInfo.files.isEmpty());

					// 3. Kembalikan hasil ke UI Thread
					uiHandler.post(new Runnable() {
							@Override
							public void run() {
								// Sembunyikan loading
								if (loadingIndicator != null) {
									loadingIndicator.setVisibility(View.GONE);
								}
								populateFormFromModel(currentPkgInfo);
								restoreLastSessionIfAvailable();

							}
						});
				}
			});
	}
	
	

    private void setupClickListeners() {
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

        findViewById(R.id.btnAddFile).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					openFileChooser("Pilih File yang Akan Ditambahkan", REQ_ADD_ASSET_FILE);
				}
			});

        findViewById(R.id.btnBrowse).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					openFileChooser("Pilih File .SIS / .SISX", REQ_PICK_SIS);
				}
			});

        findViewById(R.id.btnUnpack).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					runUnpack();
				}
			});

        findViewById(R.id.btnRepack).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					runRepack();
				}
			});

        findViewById(R.id.btnSign).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					runSign();
				}
			});

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					showAdvanceSettings();
				}
			});

        findViewById(R.id.btnAbout).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					closeFabMenu();
					showAboutDialog();
				}
			});
			
		
		
    }
	

    private void openFileChooser(String title, int reqCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, title), reqCode);
    }

    private Dialog createGlassDialog(int layoutResId) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setContentView(layoutResId);
        return dialog;
    }

    private void showAboutDialog() {
        final Dialog dialog = createGlassDialog(R.layout.dialog_about);
        dialog.findViewById(R.id.btnAboutClose).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});
        dialog.show();
    }

    public void showAdvanceSettings() {
        SisAdvancedSettingsDialog.show(this, activeProjectDir, currentPkgInfo.files, config,
			new SisAdvancedSettingsDialog.OnConfigSavedListener() {
				@Override
				public void onConfigSaved(SisProjectConfig updatedConfig) {
					config = updatedConfig;
					try {
						config.saveToFile(activeProjectDir);
					} catch (Exception ignored) {}
					Toast.makeText(MainActivity.this, "Konfigurasi SISX diperbarui!", Toast.LENGTH_SHORT).show();
				}
			});
    }

    private void initWorkFolders() {
        new File(baseWorkDir, "Output").mkdirs();
        new File(baseWorkDir, "Temp").mkdirs();
        if (!activeProjectDir.exists()) activeProjectDir.mkdirs();
        if (!certDir.exists()) certDir.mkdirs();
    }

    private void populateFormFromModel(SisPackageInfo info) {
        currentPkgInfo = info;
        etAppName.setText(info.appName);
        etVendorName.setText(info.vendorName);
        etVerMajor.setText(String.valueOf(info.majorVersion));
        etVerMinor.setText(String.valueOf(info.minorVersion));
        etVerBuild.setText(String.valueOf(info.buildVersion));
        etUid3.setText(info.uid3 != 0 ? info.getFormattedUid3() : "0x20001234");
        updateCloseProjectButtonVisibility();
        refreshFileListUI();
    }

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
        currentPkgInfo.uid3 = (uidStr.startsWith("0x") || uidStr.startsWith("0X"))
			? (int) Long.parseLong(uidStr.substring(2), 16)
			: Integer.parseInt(uidStr);

        String noteText = etInstallNote.getText().toString().trim();
        File pkgFile = new File(activeProjectDir, "project.pkg");

        StringBuilder sb = new StringBuilder();
        sb.append("; Generated by S60Reborn Visual Builder\n&EN\n\n");
        sb.append("#{\"").append(currentPkgInfo.appName).append("\"},(")
			.append(currentPkgInfo.getFormattedUid3()).append("),")
			.append(currentPkgInfo.getFormattedVersion()).append(",TYPE=SA\n\n");
        sb.append("%{\"").append(currentPkgInfo.vendorName).append("\"}\n");
        sb.append(":\"").append(currentPkgInfo.vendorName).append("\"\n\n");
        sb.append("[0x101F7961], 0, 0, 0, {\"Series60ProductID\"}\n");
        sb.append("[0x1028315F], 0, 0, 0, {\"Series60ProductID\"}\n\n");

        if (!noteText.isEmpty()) {
            File noteFile = new File(activeProjectDir, "install_note.txt");
            try (FileOutputStream fos = new FileOutputStream(noteFile)) {
                fos.write(noteText.getBytes(StandardCharsets.UTF_8));
            }
            sb.append("\"install_note.txt\"-\"!:\\private\\")
				.append(String.format("%08x", currentPkgInfo.uid3))
				.append("\\install_note.txt\", FT, TC\n\n");
        }

        for (SisFileEntry f : currentPkgInfo.files) {
            sb.append("\"").append(f.localRelPath.replace('/', '\\')).append("\"-\"")
				.append(f.targetPath).append("\"\n");
        }

        try (FileOutputStream fos = new FileOutputStream(pkgFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return pkgFile;
    }
	
	private void refreshFileListUI() {
		tvFileListHeader.setText("ISI FILE PAKET (" + currentPkgInfo.files.size() + ")");
		if (fileListAdapter != null) {
			fileListAdapter.updateData(currentPkgInfo.files, activeProjectDir);
		}
	}
	

    private void resetCurrentProject() {
        activeInputSisFile = null;
        activeProjectDir = new File(baseWorkDir, "Projects/CustomProject");
        activeProjectDir.mkdirs();

        currentPkgInfo = new SisPackageInfo();
        config = new SisProjectConfig();
        tvActiveFile.setText(R.string.no_package_selected);
        etInstallNote.setText("");

        populateFormFromModel(currentPkgInfo);
        getSharedPreferences("s60reborn_prefs", MODE_PRIVATE).edit()
			.remove("last_project_dir").remove("last_sis_file").apply();

        if (btnCloseProject != null) btnCloseProject.setVisibility(View.GONE);
        log("Proyek ditutup. Workspace direset ke awal.");
        Toast.makeText(this, "Proyek ditutup & direset", Toast.LENGTH_SHORT).show();
    }

    private void setupCloseProjectButton() {
        if (btnCloseProject != null || tvActiveFile == null || !(tvActiveFile.getParent() instanceof LinearLayout)) return;
        LinearLayout parentRow = (LinearLayout) tvActiveFile.getParent();
        float d = getResources().getDisplayMetrics().density;

        LinearLayout.LayoutParams tvParams = (LinearLayout.LayoutParams) tvActiveFile.getLayoutParams();
        tvParams.width = 0;
        tvParams.weight = 1f;
        tvActiveFile.setLayoutParams(tvParams);
        tvActiveFile.setMaxLines(1);
        tvActiveFile.setEllipsize(TextUtils.TruncateAt.END);

        btnCloseProject = new Button(this);
        btnCloseProject.setText(R.string.btn_close_project);
        btnCloseProject.setTextColor(getResources().getColor(R.color.accent_red_soft));
        btnCloseProject.setTextSize(10.5f);
        btnCloseProject.setTypeface(null, Typeface.BOLD);
        btnCloseProject.setAllCaps(false);
        btnCloseProject.setMinHeight(0);
        btnCloseProject.setMinimumHeight(0);
        btnCloseProject.setPadding((int)(12 * d), (int)(5 * d), (int)(12 * d), (int)(5 * d));
        btnCloseProject.setBackgroundResource(R.drawable.btn_close_glass);

        btnCloseProject.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					animateClickBounce(v);
					new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Tutup Proyek Aktif?")
                        .setMessage("Form, daftar file, dan sesi proyek aktif akan direset ke awal. Lanjutkan?")
                        .setPositiveButton("Tutup & Reset", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                resetCurrentProject();
                            }
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
				}
			});

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, (int)(32 * d));
        lp.setMargins((int)(8 * d), 0, 0, 0);
        parentRow.addView(btnCloseProject, lp);
    }

    private void updateCloseProjectButtonVisibility() {
        if (btnCloseProject == null) return;
        boolean hasActiveProject = (activeInputSisFile != null && activeInputSisFile.exists())
			|| !currentPkgInfo.files.isEmpty()
			|| (activeProjectDir != null && !"CustomProject".equals(activeProjectDir.getName()));
        btnCloseProject.setVisibility(hasActiveProject ? View.VISIBLE : View.GONE);
    }

    private void showAddFileTargetDialog(final Uri fileUri, final String fileName) {
        final Dialog dialog = createGlassDialog(R.layout.dialog_add_file_target);
        TextView tvFile = dialog.findViewById(R.id.tvAddFileName);
        tvFile.setText("File: " + fileName);

        final String[] presetFolders = getResources().getStringArray(R.array.symbian_target_folders);
        final Spinner spinner = dialog.findViewById(R.id.spTargetFolder);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presetFolders));

        final EditText etCustomTarget = dialog.findViewById(R.id.etCustomTarget);

        dialog.findViewById(R.id.btnAddCancel).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        dialog.findViewById(R.id.btnAddConfirm).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					try {
						String custom = etCustomTarget.getText().toString().trim();
						String targetFullPath = !custom.isEmpty()
                            ? custom
                            : presetFolders[spinner.getSelectedItemPosition()].split(" \\(")[0] + fileName;

						String relLocal = targetFullPath.replace('\\', '/').replaceFirst("^[!a-zA-Z]:/", "");
						if (!activeProjectDir.exists()) activeProjectDir.mkdirs();
						File destFile = new File(activeProjectDir, relLocal);
						if (destFile.getParentFile() != null) destFile.getParentFile().mkdirs();
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

        dialog.show();
    }

    private void showPackageInspectDialog(String fileName, SisPackageInfo info) {
        final Dialog dialog = createGlassDialog(R.layout.dialog_package_inspect);

        TextView tvTitle = dialog.findViewById(R.id.tvInspectTitle);
        TextView tvBadge = dialog.findViewById(R.id.tvInspectBadge);
        TextView tvBody = dialog.findViewById(R.id.tvInspectBody);

        tvTitle.setText("📦 " + fileName);
        tvBadge.setText(info.isSigned ? "🔒 STATUS: SIGNED" : "🔓 STATUS: UNSIGNED");
        tvBadge.setTextColor(getResources().getColor(info.isSigned ? R.color.accent_orange : R.color.accent_cyan));

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
        tvBody.setText(details.toString());

        dialog.findViewById(R.id.btnInspectClose).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        dialog.findViewById(R.id.btnInspectUnpack).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
					runUnpack();
				}
			});

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
						config = SisProjectConfig.loadFromFile(activeProjectDir);
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
        final boolean isLegacyV2 = config.isLegacySisV1V2() || currentPkgInfo.formatLabel.contains("Legacy SIS");

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

						if (isLegacyV2) {
							SisxPacker.packLegacyV2FromPkg(pkgFile, outSis);
						} else {
							SisxPacker.packFromPkg(pkgFile, outSis);
						}

						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									log("SUKSES BUILD: " + outSis.getName());
									log("  Lokasi : " + outSis.getAbsolutePath());
									log("  Ukuran : " + outSis.length() + " bytes");
									if (isLegacyV2) {
										log("  Info   : Format S60v1/v2 tidak perlu di-Sign, siap langsung install!");
									} else if (getResources().getBoolean(R.bool.default_auto_sign) || config.signMode > 0) {
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
        if (config.isLegacySisV1V2()) {
            log("INFO: Paket SIS S60v1/v2 tidak memerlukan sertifikat/signing.");
            return;
        }
        final File unsignedSis = new File(baseWorkDir, "Output/" + activeProjectDir.getName() + "_repacked.sis");
        if (!unsignedSis.exists()) {
            log("ERROR: Jalankan Build/Repack (.SIS) terlebih dahulu.");
            return;
        }

        boolean useCustom = (config.signMode == 2);
        executeSignProcess(unsignedSis, useCustom);
    }

    private void executeSignProcess(final File unsignedSis, final boolean useCustomCert) {
        final File signedSisx = new File(baseWorkDir, "Output/" + activeProjectDir.getName() + "_signed.sisx");

        if (useCustomCert) {
            final File customCer = new File(config.customCerPath);
            final File customKey = new File(config.customKeyPath);

            if (config.customCerPath.isEmpty() || config.customKeyPath.isEmpty()
				|| !customCer.exists() || !customKey.exists()) {
                log("PERHATIAN: File .CER / .KEY custom belum dipilih di Pengaturan.");
                showAdvanceSettings();
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
										log("SUKSES SIGNING (CUSTOM): " + signedSisx.getAbsolutePath());
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
										log("SUKSES SIGNING (DEFAULT ASSETS): " + signedSisx.getAbsolutePath());
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

    // =========================================================================
    // EDITOR E32 CAPABILITIES, RSC, SKN, MBM, MIF (TETAP DIPERTAHANKAN)
    // =========================================================================

    private void showCapabilityEditorDialog(final File e32File, final TextView targetRowSubtitle) {
        try {
            long initialMask = SymbianCapabilityManager.readCapabilitiesFromE32(e32File);
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
            tvTitle.setTextColor(getResources().getColor(R.color.accent_coral));
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);

            final TextView tvMaskInfo = new TextView(this);
            tvMaskInfo.setTextColor(getResources().getColor(R.color.accent_teal));
            tvMaskInfo.setTextSize(11f);
            tvMaskInfo.setTypeface(Typeface.MONOSPACE);
            tvMaskInfo.setPadding(0, (int)(4*d), 0, (int)(8*d));
            root.addView(tvMaskInfo);

            final String[] capNames = SymbianCapabilityManager.CAPABILITY_NAMES;
            final CheckBox[] checkBoxes = new CheckBox[capNames.length];

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

            LinearLayout presetRow = new LinearLayout(this);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.setPadding(0, 0, 0, (int)(8*d));

            Button btnNone = new Button(this);
            btnNone.setText("None (0)");
            btnNone.setTextSize(10f);
            btnNone.setTextColor(getResources().getColor(R.color.text_primary));
            btnNone.setBackgroundColor(getResources().getColor(R.color.bg_btn_neutral));
            btnNone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						workingMask[0] = SymbianCapabilityManager.PRESET_NONE;
						syncCheckboxesFromMask.run();
					}
				});

            Button btnSelfSign = new Button(this);
            btnSelfSign.setText("Self-Sign Safe");
            btnSelfSign.setTextSize(10f);
            btnSelfSign.setTextColor(getResources().getColor(R.color.text_on_accent));
            btnSelfSign.setBackgroundColor(getResources().getColor(R.color.accent_cyan));
            btnSelfSign.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						workingMask[0] = SymbianCapabilityManager.PRESET_SELF_SIGNED_MAX;
						syncCheckboxesFromMask.run();
					}
				});

            Button btnAll20 = new Button(this);
            btnAll20.setText("All 20 (Hack)");
            btnAll20.setTextSize(10f);
            btnAll20.setTextColor(getResources().getColor(R.color.text_on_accent));
            btnAll20.setBackgroundColor(getResources().getColor(R.color.accent_orange));
            btnAll20.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						workingMask[0] = SymbianCapabilityManager.PRESET_ALL_20_CAPS;
						syncCheckboxesFromMask.run();
					}
				});

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, (int)(34*d), 1f);
            pLp.setMargins(0, 0, (int)(4*d), 0);
            presetRow.addView(btnNone, pLp);
            presetRow.addView(btnSelfSign, pLp);
            presetRow.addView(btnAll20, new LinearLayout.LayoutParams(0, (int)(34*d), 1f));
            root.addView(presetRow);

            ScrollView scroll = new ScrollView(this);
            LinearLayout listCol = new LinearLayout(this);
            listCol.setOrientation(LinearLayout.VERTICAL);

            for (int i = 0; i < capNames.length; i++) {
                final int bitIdx = i;
                CheckBox cb = new CheckBox(this);
                boolean isBasicSelfSign = (i == 13 || i == 14 || i == 15 || i == 16 || i == 19);
                cb.setText(capNames[i] + (isBasicSelfSign ? "  [Self-Sign]" : "  [System/DevCert]"));
                cb.setTextColor(getResources().getColor(isBasicSelfSign ? R.color.accent_cyan : R.color.text_secondary));
                cb.setTextSize(11.5f);
                cb.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (((CheckBox) v).isChecked()) {
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
            root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d)));

            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, (int)(12*d), 0, 0);

            Button btnCancel = new Button(this);
            btnCancel.setText(R.string.btn_cancel);
            btnCancel.setTextColor(getResources().getColor(R.color.text_primary));
            btnCancel.setBackgroundColor(getResources().getColor(R.color.bg_btn_neutral));
            btnCancel.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});

            Button btnSave = new Button(this);
            btnSave.setText("PATCH & SIMPAN");
            btnSave.setTextColor(getResources().getColor(R.color.text_on_accent));
            btnSave.setBackgroundColor(getResources().getColor(R.color.accent_green));
            btnSave.setTypeface(null, Typeface.BOLD);
            btnSave.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						try {
							SymbianCapabilityManager.writeCapabilitiesToE32(e32File, workingMask[0]);
							int newCount = SymbianCapabilityManager.getCapabilityNamesList(workingMask[0]).size();
							targetRowSubtitle.setText("🛡️ Ketuk untuk Edit Capabilities (" + newCount + " aktif • 0x"
													  + Long.toHexString(workingMask[0]).toUpperCase() + ")");
							log("Capabilities " + e32File.getName() + " dipatch -> 0x" + String.format("%08X", workingMask[0]));
							Toast.makeText(MainActivity.this, "Capabilities berhasil diperbarui!", Toast.LENGTH_SHORT).show();
							dialog.dismiss();
						} catch (Exception ex) {
							Toast.makeText(MainActivity.this, "Gagal patch: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
						}
					}
				});

            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp1.setMargins(0, 0, (int)(8*d), 0);
            btnRow.addView(btnCancel, lp1);
            btnRow.addView(btnSave, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
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

    private void showRscLanguageEditorDialog(final File rscFile) {
        try {
            final List<RscParser.RscStringEntry> strings = RscParser.parseRscStrings(rscFile);
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
            tvTitle.setTextColor(getResources().getColor(R.color.accent_cyan));
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);

            ScrollView scroll = new ScrollView(this);
            LinearLayout listContainer = new LinearLayout(this);
            listContainer.setOrientation(LinearLayout.VERTICAL);

            for (final RscParser.RscStringEntry item : strings) {
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
                tvStr.setTextColor(getResources().getColor(R.color.text_primary));
                tvStr.setTextSize(12f);
                row.addView(tvStr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button btnEdit = new Button(this);
                btnEdit.setText("Edit");
                btnEdit.setTextColor(getResources().getColor(R.color.text_on_accent));
                btnEdit.setBackgroundColor(getResources().getColor(R.color.accent_green));
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
            root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (310 * d)));

            Button btnDone = new Button(this);
            btnDone.setText("SELESAI");
            btnDone.setTextColor(getResources().getColor(R.color.text_primary));
            btnDone.setBackgroundColor(getResources().getColor(R.color.bg_btn_neutral));
            btnDone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            root.addView(btnDone);

            dialog.setContentView(root);
            dialog.show();
        } catch (Exception e) {
            log("Gagal membuka RSC: " + e.getMessage());
        }
    }

    private void showSingleStringTranslatePrompt(final File rscFile,
                                                 final RscParser.RscStringEntry item,
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

        final EditText etInput = new EditText(this);
        etInput.setText(item.text);
        etInput.setTextColor(getResources().getColor(R.color.text_primary));
        etInput.setBackgroundResource(R.drawable.input_modern);
        etInput.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
        box.addView(etInput);

        Button btnSave = new Button(this);
        btnSave.setText("SIMPAN PERUBAHAN");
        btnSave.setTextColor(getResources().getColor(R.color.text_on_accent));
        btnSave.setBackgroundColor(getResources().getColor(R.color.accent_green));
        btnSave.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					String updated = etInput.getText().toString();
					if (!updated.isEmpty()) {
						try {
							RscParser.updateRscString(rscFile, item, updated);
							item.text = updated;
							targetRowLabel.setText("\"" + updated + "\"");
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
	
	private void showInteractiveNokiaThemeDialog(final File sknFile) {
		File variantRoot = sknFile.getParentFile();
		File cursor = variantRoot;
		while (cursor != null) {
			if (cursor.getName().startsWith("varian_") || cursor.equals(activeProjectDir)) {
				variantRoot = cursor;
				break;
			}
			cursor = cursor.getParentFile();
		}
		com.s60reborn.app.ui.NokiaThemeStudioDialog.show(this, sknFile, variantRoot);
	}
	

    private void showInteractiveNokiaThemeDialog2(final File sknFile) {
        try {
            final SknParser.SknThemeData sknData = SknParser.parseSkn(sknFile);
            Bitmap wpBmp = null, menuBmp = null;
            List<Bitmap> allThemeBitmaps = new ArrayList<>();

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
                allThemeBitmaps = MbmConverter.loadBitmapsFromMbm(mbmFile);
                if (!allThemeBitmaps.isEmpty()) wpBmp = allThemeBitmaps.get(0);
                if (allThemeBitmaps.size() > 1) menuBmp = allThemeBitmaps.get(1);
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

            final NokiaThemePreviewView previewView = new NokiaThemePreviewView(this);
            previewView.setThemeAssets(sknData.skinName, wpBmp, menuBmp, allThemeBitmaps,
									   sknData.idleTextColor, sknData.menuTextColor, sknData.softkeyTextColor, sknData.highlightColor);
            root.addView(previewView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (350 * d)));

            Button btnDone = new Button(this);
            btnDone.setText("SIMPAN & TUTUP");
            btnDone.setTextColor(getResources().getColor(R.color.text_on_accent));
            btnDone.setBackgroundColor(getResources().getColor(R.color.accent_green));
            btnDone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            root.addView(btnDone);

            dialog.setContentView(root);
            dialog.show();
        } catch (Exception e) {
            log("Gagal membuka SKN Theme Studio: " + e.getMessage());
        }
    }

    private void showMbmEditorDialog(final File mbmFile) {
        try {
            final List<Bitmap> bitmaps = MbmConverter.loadBitmapsFromMbm(mbmFile);
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

            ScrollView scroll = new ScrollView(this);
            LinearLayout listContainer = new LinearLayout(this);
            listContainer.setOrientation(LinearLayout.VERTICAL);

            for (int i = 0; i < bitmaps.size(); i++) {
                final int idx = i;
                final Bitmap bmp = bitmaps.get(i);
                LinearLayout itemRow = new LinearLayout(this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setBackgroundResource(R.drawable.input_modern);
                itemRow.setPadding((int)(8*d), (int)(8*d), (int)(8*d), (int)(8*d));

                ImageView iv = new ImageView(this);
                iv.setBackgroundColor(getResources().getColor(R.color.bg_thumb_dark));
                if (bmp != null) iv.setImageBitmap(bmp);
                itemRow.addView(iv, new LinearLayout.LayoutParams((int)(64*d), (int)(64*d)));

                TextView tvIdx = new TextView(this);
                tvIdx.setText("  Gambar #" + idx);
                tvIdx.setTextColor(getResources().getColor(R.color.text_primary));
                itemRow.addView(tvIdx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button btnReplace = new Button(this);
                btnReplace.setText("Ganti");
                btnReplace.setTextColor(getResources().getColor(R.color.text_on_accent));
                btnReplace.setBackgroundColor(getResources().getColor(R.color.accent_green));
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
                itemRow.addView(btnReplace);
                listContainer.addView(itemRow);
            }

            scroll.addView(listContainer);
            root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (300 * d)));

            Button btnClose = new Button(this);
            btnClose.setText("SELESAI");
            btnClose.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            root.addView(btnClose);

            dialog.setContentView(root);
            dialog.show();
        } catch (Exception e) {
            log("Gagal membuka MBM: " + e.getMessage());
        }
    }

    private void showMifEditorDialog(final File mifFile) {
        try {
            final List<MifConverter.MifIconEntry> allEntries = MifConverter.parseMif(mifFile);
            final List<MifConverter.MifIconEntry> validEntries = new ArrayList<>();
            for (MifConverter.MifIconEntry e : allEntries) {
                if (e.data != null && e.data.length > 0) validEntries.add(e);
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

            final LruCache<Integer, Bitmap> thumbCache = new LruCache<>(120);
            ListView listView = new ListView(this);
            listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
            listView.setDividerHeight((int)(6 * d));

            listView.setAdapter(new BaseAdapter() {
					@Override public int getCount() { return validEntries.size(); }
					@Override public Object getItem(int position) { return validEntries.get(position); }
					@Override public long getItemId(int position) { return validEntries.get(position).index; }

					@Override
					public View getView(int position, View convertView, ViewGroup parent) {
						final MifConverter.MifIconEntry item = validEntries.get(position);
						LinearLayout row = new LinearLayout(MainActivity.this);
						row.setOrientation(LinearLayout.HORIZONTAL);
						row.setGravity(Gravity.CENTER_VERTICAL);
						row.setBackgroundResource(R.drawable.input_modern);
						row.setPadding((int)(8*d), (int)(8*d), (int)(8*d), (int)(8*d));

						ImageView ivThumb = new ImageView(MainActivity.this);
						ivThumb.setBackgroundColor(getResources().getColor(R.color.bg_thumb_dark));
						Bitmap bmp = thumbCache.get(item.index);
						if (bmp == null) {
							bmp = MifConverter.renderMifIconToBitmap(item.data, (int)(48*d));
							if (bmp != null) thumbCache.put(item.index, bmp);
						}
						ivThumb.setImageBitmap(bmp);
						row.addView(ivThumb, new LinearLayout.LayoutParams((int)(46*d), (int)(46*d)));

						TextView t1 = new TextView(MainActivity.this);
						t1.setText("  Slot Ikon #" + item.index + " (" + item.data.length + " B)");
						t1.setTextColor(getResources().getColor(R.color.text_primary));
						row.addView(t1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

						Button btnRep = new Button(MainActivity.this);
						btnRep.setText("Ganti SVG");
						btnRep.setTextColor(getResources().getColor(R.color.text_on_accent));
						btnRep.setBackgroundColor(getResources().getColor(R.color.accent_cyan));
						btnRep.setOnClickListener(new View.OnClickListener() {
								@Override
								public void onClick(View v) {
									pendingEditAssetFile = mifFile;
									pendingEditAssetIndex = item.index;
									dialog.dismiss();
									openFileChooser("Pilih File Ikon .SVG / .SVGB", REQ_REPLACE_MIF_ICON);
								}
							});
						row.addView(btnRep);
						return row;
					}
				});

            root.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (320 * d)));

            Button btnClose = new Button(this);
            btnClose.setText("SELESAI");
            btnClose.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});
            root.addView(btnClose);

            dialog.setContentView(root);
            dialog.show();
        } catch (Exception e) {
            log("Gagal membuka MIF: " + e.getMessage());
        }
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
                tempDir.mkdirs();
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
											log("Inspeksi: " + fileName + " (" + info.files.size() + " file)");
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
            showAddFileTargetDialog(uri, fileName);
        } else if (requestCode == REQ_REPLACE_MBM_IMAGE && pendingEditAssetFile != null) {
            try{
				InputStream is = getContentResolver().openInputStream(uri);
                Bitmap pickedBmp = BitmapFactory.decodeStream(is);
                if (pickedBmp != null) {
                    MbmConverter.replaceBitmapInMbm(pendingEditAssetFile, pendingEditAssetIndex, pickedBmp);
                    Toast.makeText(this, "Gambar tema berhasil diganti!", Toast.LENGTH_SHORT).show();
                    showMbmEditorDialog(pendingEditAssetFile);
                }
            } catch (Exception e) {
                log("Gagal ganti gambar MBM: " + e.getMessage());
            }
        } else if (requestCode == REQ_REPLACE_MIF_ICON && pendingEditAssetFile != null) {
            try{
				InputStream is = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                MifConverter.replaceMifEntry(pendingEditAssetFile, pendingEditAssetIndex, bos.toByteArray());
                Toast.makeText(this, "Ikon MIF berhasil diganti!", Toast.LENGTH_SHORT).show();
                showMifEditorDialog(pendingEditAssetFile);
            } catch (Exception e) {
                log("Gagal ganti ikon MIF: " + e.getMessage());
            }
        }
        updateCloseProjectButtonVisibility();
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

    public void log(final String msg) {
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
        getSharedPreferences("s60reborn_prefs", MODE_PRIVATE).edit()
			.putString("last_project_dir", activeProjectDir != null ? activeProjectDir.getAbsolutePath() : "")
			.putString("last_sis_file", (activeInputSisFile != null && activeInputSisFile.exists())
					   ? activeInputSisFile.getAbsolutePath() : "")
			.apply();
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
		updateCloseProjectButtonVisibility();

    }
	
	
    private void openThemeVariantPicker(File projectDir, final String targetExt) {
        final List<File> targetFiles = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        File[] subDirs = projectDir.listFiles();
        if (subDirs != null) {
            Arrays.sort(subDirs);
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

        if (targetFiles.size() <= 1) {
            File single = targetFiles.isEmpty() ? findFileByExtension(projectDir, targetExt) : targetFiles.get(0);
            if (single != null) dispatchEditorByExtension(single, targetExt);
            return;
        }

        new AlertDialog.Builder(this)
			.setTitle("Pilih Varian " + targetExt.toUpperCase())
			.setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					dispatchEditorByExtension(targetFiles.get(which), targetExt);
				}
			})
			.show();
    }

    private void dispatchEditorByExtension(File targetFile, String targetExt) {
        if (".skn".equalsIgnoreCase(targetExt)) showInteractiveNokiaThemeDialog(targetFile);
        else if (".mif".equalsIgnoreCase(targetExt)) showMifEditorDialog(targetFile);
        else if (".mbm".equalsIgnoreCase(targetExt)) showMbmEditorDialog(targetFile);
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

