package com.s60reborn.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.s60reborn.app.R;
import com.s60reborn.app.core.MifCacheManager;
import com.s60reborn.app.core.SknParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.s60reborn.app.R;
import java.io.*;

public class ThemeAssetEditorActivity extends Activity {

    public static final int MODE_MIF = 0;
    public static final int MODE_MBM = 1;
    public static final int MODE_SKN = 2;

    private static final int ITEMS_PER_PAGE = 15; // 3 kolom x 5 baris = 1 layar penuh
    private static final int REQ_PICK_REPLACE_IMAGE = 301;
    private static final int REQ_PICK_ADD_IMAGE = 302;

    private int currentMode = MODE_MIF;
    private int currentPage = 0;

    private File projectDir;
    private File activeMifFile;
    private File activeMbmFile;
    private File activeSknFile;

    private File mifCacheDir;
    private File mbmCacheDir;
    private File sknCacheFile; // Salinan kerja .skn di cache

    private GridView gvAssetGrid;
    private TextView tvPageIndicator;
    private Button btnModeMif, btnModeMbm, btnModeSkn;

    private List<AssetGridItem> allModeItems = new ArrayList<AssetGridItem>();
    private List<AssetGridItem> currentPageItems = new ArrayList<AssetGridItem>();
    private GridAssetAdapter gridAdapter;

    private AssetGridItem selectedItemForReplace = null;
    private Map<String, String> skinDbMap;
	
	// Cache memori agar thumbnail SVG/SVGB yang sudah dirender tidak dirender ulang terus-menerus
    private android.util.LruCache<String, Bitmap> thumbCache = new android.util.LruCache<String, Bitmap>(120);
	

    public static class AssetGridItem {
        public int index;
        public String title;      // Nama file (12.svgb) atau Nama Elemen SKN
        public String subInfo;    // Indeks atau UID Hex (10005A26:00001001)
        public File cacheFile;    // File fisik di folder cache (untuk MIF/MBM)
        public SknParser.SknAssetMapping sknMapping; // Data mapping (untuk SKN)
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_asset_editor);

        String projPath = getIntent().getStringExtra("project_dir");
        projectDir = (projPath != null) ? new File(projPath) : new File(getExternalFilesDir(null), "projects/current");

        // Inisialisasi file target & folder staging cache
        activeMifFile = findFileByExt(projectDir, ".mif");
        activeMbmFile = findFileByExt(projectDir, ".mbm");
        activeSknFile = findFileByExt(projectDir, ".skn");

        File stagingRoot = new File(projectDir, ".editor_staging_cache");
        mifCacheDir = new File(stagingRoot, "mif_cache");
        mbmCacheDir = new File(stagingRoot, "mbm_cache");
        sknCacheFile = new File(stagingRoot, "working.skn");

        try {
            skinDbMap = SknParser.loadBinarySisContentsDbFromAssets(this, "siscontents.db");
        } catch (Exception ignored) {}

        initViews();
        prepareStagingCache();
        switchEditorMode(MODE_MIF);
    }

    private void initViews() {
        gvAssetGrid = (GridView) findViewById(R.id.gvAssetGrid);
        tvPageIndicator = (TextView) findViewById(R.id.tvPageIndicator);
        btnModeMif = (Button) findViewById(R.id.btnModeMif);
        btnModeMbm = (Button) findViewById(R.id.btnModeMbm);
        btnModeSkn = (Button) findViewById(R.id.btnModeSkn);

        gridAdapter = new GridAssetAdapter();
        gvAssetGrid.setAdapter(gridAdapter);

        // Deteksi Gesture Swipe Kiri / Kanan untuk pindah layar halaman
        final GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
				@Override
				public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
					if (e1 == null || e2 == null) return false;
					float diffX = e2.getX() - e1.getX();
					if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
						if (diffX < 0) {
							goToNextPage(); 
						} else {
							goToPrevPage(); 
						}
						return true;
					}
					return false;
				}
			});

        gvAssetGrid.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					return gestureDetector.onTouchEvent(event);
				}
			});

        // Klik pada item ikon di grid -> Munculkan Popup (Lihat Info / Ganti / Hapus)
        gvAssetGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
				@Override
				public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					AssetGridItem item = currentPageItems.get(position);
					showItemActionPopup(item);
				}
			});

        btnModeMif.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) { switchEditorMode(MODE_MIF); }
			});
        btnModeMbm.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) { switchEditorMode(MODE_MBM); }
			});
        btnModeSkn.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) { switchEditorMode(MODE_SKN); }
			});

        findViewById(R.id.btnPrevPage).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) { goToPrevPage(); }
			});
        findViewById(R.id.btnNextPage).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) { goToNextPage(); }
			});

        // Tombol Tambah UID Baru (Mode Pick)
        findViewById(R.id.btnAddNewAsset).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
					intent.setType("*/*");
					startActivityForResult(intent, REQ_PICK_ADD_IMAGE);
				}
			});

        // Tombol Batal -> Hapus seluruh cache staging
        findViewById(R.id.btnCancelClean).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					MifCacheManager.cleanMifCache(new File(projectDir, ".editor_staging_cache"));
					Toast.makeText(ThemeAssetEditorActivity.this, "Perubahan dibatalkan & cache dihapus.", Toast.LENGTH_SHORT).show();
					finish();
				}
			});

        // Tombol Repack -> Kompilasi semua perubahan di cache kembali ke file asli
        findViewById(R.id.btnRepackAll).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					executeRepackAndClean();
				}
			});
    }
	
	/**
     * 4. CLEAN: Menghapus seluruh file di dalam folder cache MIF beserta foldernya
     */
    public static boolean cleanMifCache(File cacheDir) {
        if (cacheDir == null || !cacheDir.exists()) {
            return true;
        }
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    cleanMifCache(files[i]);
                } else {
                    files[i].delete();
                }
            }
        }
        return cacheDir.delete();
    }

    /**
     * 5. PACK & CLEAN: Mengemas ulang seluruh isi folder cache menjadi .MIF,
     *    lalu otomatis membersihkan folder cache jika proses pack berhasil.
     */
 
    private void prepareStagingCache() {
        try {
            if (activeMifFile != null && activeMifFile.exists() && !mifCacheDir.exists()) {
                MifCacheManager.extractMifToCache(activeMifFile, mifCacheDir);
            }
            if (activeSknFile != null && activeSknFile.exists() && !sknCacheFile.exists()) {
                if (sknCacheFile.getParentFile() != null) sknCacheFile.getParentFile().mkdirs();
                copyFile(activeSknFile, sknCacheFile);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyiapkan cache: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void switchEditorMode(int mode) {
        currentMode = mode;
        currentPage = 0;

        // Update warna tab aktif
        btnModeMif.setBackgroundColor(mode == MODE_MIF ? 0xFF4FC3F7 : Color.TRANSPARENT);
        btnModeMif.setTextColor(mode == MODE_MIF ? 0xFF0D1117 : 0xFFB0BEC5);

        btnModeMbm.setBackgroundColor(mode == MODE_MBM ? 0xFF4FC3F7 : Color.TRANSPARENT);
        btnModeMbm.setTextColor(mode == MODE_MBM ? 0xFF0D1117 : 0xFFB0BEC5);

        btnModeSkn.setBackgroundColor(mode == MODE_SKN ? 0xFF4FC3F7 : Color.TRANSPARENT);
        btnModeSkn.setTextColor(mode == MODE_SKN ? 0xFF0D1117 : 0xFFB0BEC5);

        reloadAllItemsForCurrentMode();
        renderCurrentPage();
    }

    private void reloadAllItemsForCurrentMode() {
        allModeItems.clear();

        if (currentMode == MODE_MIF && mifCacheDir.exists()) {
            File[] files = mifCacheDir.listFiles();
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
						@Override
						public int compare(File a, File b) {
							return a.getName().compareToIgnoreCase(b.getName());
						}
					});
                for (int i = 0; i < files.length; i++) {
                    AssetGridItem item = new AssetGridItem();
                    item.index = i;
                    item.title = files[i].getName();
                    item.subInfo = "Index #" + i + " (" + (files[i].length() / 1024) + " KB)";
                    item.cacheFile = files[i];
                    allModeItems.add(item);
                }
            }
        } else if (currentMode == MODE_MBM && mbmCacheDir.exists()) {
            File[] files = mbmCacheDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    AssetGridItem item = new AssetGridItem();
                    item.index = i;
                    item.title = files[i].getName();
                    item.subInfo = "BMP #" + i;
                    item.cacheFile = files[i];
                    allModeItems.add(item);
                }
            }
        } else if (currentMode == MODE_SKN && sknCacheFile.exists()) {
            try {
                List<SknParser.SknAssetMapping> list = SknParser.extractAssetMappings(sknCacheFile, skinDbMap);
                for (int i = 0; i < list.size(); i++) {
                    SknParser.SknAssetMapping m = list.get(i);
                    AssetGridItem item = new AssetGridItem();
                    item.index = i;
                    item.title = m.elementName;
                    item.subInfo = m.uidKey + " -> Idx:" + m.assetIndex;
                    item.sknMapping = m;
                    allModeItems.add(item);
                }
            } catch (Exception ignored) {}
        }
    }

    private void renderCurrentPage() {
        currentPageItems.clear();
        int totalItems = allModeItems.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE));

        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, totalItems);

        for (int i = start; i < end; i++) {
            currentPageItems.add(allModeItems.get(i));
        }

        tvPageIndicator.setText("Halaman " + (currentPage + 1) + " / " + totalPages + " (" + totalItems + " Item)");
        gridAdapter.notifyDataSetChanged();
    }

	private void goToNextPage() {
        int totalPages = Math.max(1, (int) Math.ceil((double) allModeItems.size() / ITEMS_PER_PAGE));
        if (currentPage + 1 < totalPages) {
            currentPage++;
            animateGridSlide(true); 
        }
    }

    private void goToPrevPage() {
        if (currentPage > 0) {
            currentPage--;
            animateGridSlide(false); 
        }
    }

    /**
     * Animasi transisi geser halus (Slide + Fade) saat berpindah layar halaman
     */
    private void animateGridSlide(final boolean isNext) {
        final float screenWidth = gvAssetGrid.getWidth() > 0 ? gvAssetGrid.getWidth() : 600f;
        float exitX = isNext ? -screenWidth * 0.35f : screenWidth * 0.35f;

        gvAssetGrid.animate()
			.translationX(exitX)
			.alpha(0.2f)
			.setDuration(120)
			.withEndAction(new Runnable() {
				@Override
				public void run() {
					renderCurrentPage();
					gvAssetGrid.setTranslationX(isNext ? screenWidth * 0.35f : -screenWidth * 0.35f);
					gvAssetGrid.animate()
						.translationX(0f)
						.alpha(1.0f)
						.setDuration(160)
						.setInterpolator(new android.view.animation.DecelerateInterpolator())
						.start();
				}
			})
			.start();
    }
	
    /**
     * Popup Aksi Saat Item Ditekan: Langsung panggil render/convert untuk pratinjau + Ganti / Hapus
     */
    private void showItemActionPopup(final AssetGridItem item) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (20 * d);
        container.setPadding(pad, pad, pad, (int) (8 * d));

        // 1. Panggil fungsi convert/render ke Bitmap untuk pratinjau langsung saat diklik
        ImageView ivPreview = new ImageView(this);
        int previewSize = (int) (96 * d);
        ivPreview.setLayoutParams(new LinearLayout.LayoutParams(previewSize, previewSize));
        ivPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);

        Bitmap convertedPreview = null;
        if (item.cacheFile != null && item.cacheFile.exists()) {
            convertedPreview = com.s60reborn.app.core.SymbianSvgRenderer.renderToBitmap(item.cacheFile, previewSize);
        } else if (item.sknMapping != null && mifCacheDir.exists()) {
            // Jika di mode SKN, cari file MIF cache yang sesuai dengan assetIndex / 2
            int mifIdx = item.sknMapping.assetIndex / 2;
            File linkedMifFile = findCacheFileByIndex(mifCacheDir, mifIdx);
            if (linkedMifFile != null) {
                convertedPreview = com.s60reborn.app.core.SymbianSvgRenderer.renderToBitmap(linkedMifFile, previewSize);
            }
        }

        if (convertedPreview != null) {
            ivPreview.setImageBitmap(convertedPreview);
        } else {
            ivPreview.setImageResource(android.R.drawable.ic_menu_gallery);
            ivPreview.setColorFilter(0xFF4FC3F7);
        }
        container.addView(ivPreview);

        // 2. Informasi Detail File / Indeks / UID
        TextView tvDetail = new TextView(this);
        String detail = "File / Elemen: " + item.title + "\n" + item.subInfo;
        if (item.cacheFile != null && item.cacheFile.exists()) {
            detail += "\nUkuran: " + item.cacheFile.length() + " bytes";
        }
        tvDetail.setText(detail);
        tvDetail.setTextColor(Color.WHITE);
        tvDetail.setTextSize(13);
        tvDetail.setGravity(Gravity.CENTER);
        tvDetail.setPadding(0, (int) (12 * d), 0, (int) (4 * d));
        container.addView(tvDetail);

        // 3. Tampilkan Dialog dengan Tombol Aksi Langsung
        new AlertDialog.Builder(this)
			.setTitle("Pratinjau & Aksi Aset")
			.setView(container)
			.setPositiveButton("Ganti / Overwrite", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					selectedItemForReplace = item;
					Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
					intent.setType("*/*");
					startActivityForResult(intent, REQ_PICK_REPLACE_IMAGE);
				}
			})
			.setNeutralButton("Hapus", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					if (item.cacheFile != null && item.cacheFile.exists()) {
						item.cacheFile.delete();
					}
					thumbCache.evictAll();
					reloadAllItemsForCurrentMode();
					renderCurrentPage();
					Toast.makeText(ThemeAssetEditorActivity.this, "Dihapus dari cache.", Toast.LENGTH_SHORT).show();
				}
			})
			.setNegativeButton("Tutup", null)
			.show();
    }

    /**
     * Helper mencari file di folder cache berdasarkan nomor indeks awalnya (misal: 12.svgb / 12.svg)
     */
    private File findCacheFileByIndex(File cacheDir, int targetIndex) {
        File[] files = cacheDir.listFiles();
        if (files == null) return null;
        String prefix = targetIndex + ".";
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().startsWith(prefix)) {
                return files[i];
            }
        }
        return null;
    }
	

	@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQ_PICK_REPLACE_IMAGE && selectedItemForReplace != null) {
            // Otomatis convert & overwrite item yang dipilih di grid
            overwriteAssetInCache(uri, selectedItemForReplace);

            // Hapus cache thumbnail lama agar gambar baru langsung dirender ulang di grid
            thumbCache.evictAll();
            renderCurrentPage();

        } else if (requestCode == REQ_PICK_ADD_IMAGE) {
            try {
                // 1. Salin file dari URI ke temp
                File tempPicked = copyUriToTempFile(uri, "picked_manual_asset");
                if (tempPicked == null) return;

                // 2. AUTO-CONVERT langsung ke SVG (jika PNG/JPG) sebelum masuk ke Picker & Grid
                final File autoConvertedSvg = convertImageToSvgIfNeeded(tempPicked);

                // 3. Tampilkan Mode Pick UID dengan file yang sudah ter-convert
                SkinUidPickerDialog.show(this, "siscontents.db", autoConvertedSvg, new SkinUidPickerDialog.OnTargetUidPickedListener() {
						@Override
						public void onUidPicked(String uidKey, String elementName, File readyImageFile) {
							try {
								// Masukkan file hasil auto-convert ke urutan indeks berikutnya di mif_cache
								int newIdx = MifCacheManager.addNewAssetToCache(mifCacheDir, readyImageFile);

								// Daftarkan ke working.skn
								if (sknCacheFile.exists()) {
									SknParser.appendAssetMappingToSkn(sknCacheFile, uidKey, newIdx * 2, -1);
								}

								// Refresh grid & otomatis lompat ke halaman terakhir tempat ikon baru berada
								reloadAllItemsForCurrentMode();
								int totalPages = Math.max(1, (int) Math.ceil((double) allModeItems.size() / ITEMS_PER_PAGE));
								currentPage = totalPages - 1;
								renderCurrentPage();

								Toast.makeText(ThemeAssetEditorActivity.this,
											   "Auto-convert & masuk Grid #" + newIdx + " (" + elementName + ")", Toast.LENGTH_SHORT).show();
							} catch (Exception e) {
								Toast.makeText(ThemeAssetEditorActivity.this, "Gagal: " + e.getMessage(), Toast.LENGTH_SHORT).show();
							}
						}
					});
            } catch (Exception e) {
                Toast.makeText(this, "Gagal memproses gambar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
	

    /**
     * Mengonversi PNG/SVG ke format siap pakai dan menimpa (overwrite) file sesuai indeks di cache
     */
    private void overwriteAssetInCache(Uri pickedUri, AssetGridItem targetItem) {
        try {
            File tempInput = copyUriToTempFile(pickedUri, "replace_temp");
            if (tempInput == null) return;

            if (currentMode == MODE_MIF && targetItem.cacheFile != null) {
                // Konversi otomatis jika user memilih PNG agar valid di dalam .MIF
                File readySvgOrSvgb = convertImageToSvgIfNeeded(tempInput);

                // Overwrite tepat pada file indeks yang dipilih di folder cache
                copyFile(readySvgOrSvgb, targetItem.cacheFile);
                Toast.makeText(this, "Cache " + targetItem.cacheFile.getName() + " berhasil di-overwrite!", Toast.LENGTH_SHORT).show();
            } else if (currentMode == MODE_SKN && targetItem.sknMapping != null) {
                // Untuk mode SKN, tambahkan gambar ke cache MIF lalu update pointer indeks di working.skn
                File readySvg = convertImageToSvgIfNeeded(tempInput);
                int newMifIdx = MifCacheManager.addNewAssetToCache(mifCacheDir, readySvg);
                SknParser.patchAssetIndexInSkn(sknCacheFile, targetItem.sknMapping.indexOffsetInSkn, newMifIdx * 2, -1);
                Toast.makeText(this, "Pointer SKN diperbarui ke MIF #" + newMifIdx, Toast.LENGTH_SHORT).show();
            }

            reloadAllItemsForCurrentMode();
            renderCurrentPage();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal overwrite: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Jika file yang dipilih adalah PNG/JPG, bungkus ke dalam SVG standar S60v3/v5;
     * Jika sudah SVG atau SVGB, langsung gunakan apa adanya.
     */
    private File convertImageToSvgIfNeeded(File inputFile) throws Exception {
        byte[] raw = readAllBytes(inputFile);
        if (raw.length >= 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // Cek apakah sudah SVGB biner Symbian (0xCC 0x56 / 0xCD 0x56)
            if ((b0 == 0xCC || b0 == 0xCD || b0 == 0xCE) && b1 == 0x56) {
                return inputFile;
            }
        }
        String head = new String(raw, 0, Math.min(raw.length, 64), "UTF-8");
        if (head.contains("<svg") || head.contains("<?xml")) {
            return inputFile;
        }

        // Konversi PNG/Bitmap ke SVG Wrapper tersemat (Embedded Base64 SVG)
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        int w = (bmp != null) ? bmp.getWidth() : 64;
        int h = (bmp != null) ? bmp.getHeight() : 64;

        ByteArrayOutputStream pngBos = new ByteArrayOutputStream();
        if (bmp != null) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, pngBos);
        } else {
            pngBos.write(raw);
        }

        String b64 = Base64.encodeToString(pngBos.toByteArray(), Base64.NO_WRAP);
        String svgXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
			+ "width=\"" + w + "\" height=\"" + h + "\" viewBox=\"0 0 " + w + " " + h + "\">\n"
			+ "  <image width=\"" + w + "\" height=\"" + h + "\" xlink:href=\"data:image/png;base64," + b64 + "\"/>\n"
			+ "</svg>";

        File outSvg = new File(getCacheDir(), "converted_" + System.currentTimeMillis() + ".svg");
        FileOutputStream fos = new FileOutputStream(outSvg);
        fos.write(svgXml.getBytes("UTF-8"));
        fos.close();
        return outSvg;
    }

    /**
     * Mengemas ulang semua hasil edit di cache ke file asli proyek, lalu membersihkan cache
     */
    private void executeRepackAndClean() {
        try {
            if (activeMifFile != null && mifCacheDir.exists()) {
                MifCacheManager.packAndCleanMif(mifCacheDir, activeMifFile);
            }
            if (activeSknFile != null && sknCacheFile.exists()) {
                copyFile(sknCacheFile, activeSknFile);
            }
            MifCacheManager.cleanMifCache(new File(projectDir, ".editor_staging_cache"));
            Toast.makeText(this, "Repack selesai & cache dibersihkan!", Toast.LENGTH_LONG).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal repack: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Adapter GridView Dark Card untuk 1 layar halaman
     */
    private class GridAssetAdapter extends BaseAdapter {
        @Override
        public int getCount() { return currentPageItems.size(); }

        @Override
        public Object getItem(int position) { return currentPageItems.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AssetGridItem item = currentPageItems.get(position);
            float d = getResources().getDisplayMetrics().density;

            LinearLayout card = new LinearLayout(ThemeAssetEditorActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setBackgroundResource(R.drawable.card_dark);
            int p = (int) (8 * d);
            card.setPadding(p, p, p, p);

            ImageView ivThumb = new ImageView(ThemeAssetEditorActivity.this);
            int iconSize = (int) (42 * d);
            ivThumb.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
            ivThumb.setScaleType(ImageView.ScaleType.FIT_CENTER);

			// Tampilkan thumbnail nyata untuk PNG, SVG, maupun SVGB Symbian
            if (item.cacheFile != null && item.cacheFile.exists()) {
                String cacheKey = item.cacheFile.getAbsolutePath() + "_" + item.cacheFile.lastModified();
                Bitmap cachedBmp = thumbCache.get(cacheKey);
                if (cachedBmp == null) {
                    cachedBmp = com.s60reborn.app.core.SymbianSvgRenderer.renderToBitmap(item.cacheFile, iconSize);
                    if (cachedBmp != null) {
                        thumbCache.put(cacheKey, cachedBmp);
                    }
                }

                if (cachedBmp != null) {
                    ivThumb.setImageBitmap(cachedBmp);
                } else {
                    ivThumb.setImageResource(android.R.drawable.ic_menu_gallery);
                    ivThumb.setColorFilter(0xFF4FC3F7);
                }
            } else {
                ivThumb.setImageResource(android.R.drawable.ic_menu_gallery);
                ivThumb.setColorFilter(0xFF4FC3F7);
            }
			try{
				byte[] fileBytes = readAllBytes(item.cacheFile);
				Bitmap bmp = com.s60reborn.app.core.MifConverter.renderMifIconToBitmap(fileBytes, iconSize);

				if (bmp != null) {
					ivThumb.clearColorFilter();
					ivThumb.setImageBitmap(bmp);
				} else {
					ivThumb.setImageResource(android.R.drawable.ic_menu_gallery);
					ivThumb.setColorFilter(0xFF4FC3F7);
				}
			}catch(Exception e){

			}
            card.addView(ivThumb);

            TextView tvName = new TextView(ThemeAssetEditorActivity.this);
            tvName.setText(item.title);
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(11);
            tvName.setMaxLines(1);
            tvName.setGravity(Gravity.CENTER);
            tvName.setPadding(0, (int) (4 * d), 0, (int) (2 * d));
            card.addView(tvName);

            TextView tvSub = new TextView(ThemeAssetEditorActivity.this);
            tvSub.setText(item.subInfo);
            tvSub.setTextColor(0xFF90A4AE);
            tvSub.setTextSize(9);
            tvSub.setMaxLines(1);
            tvSub.setGravity(Gravity.CENTER);
            card.addView(tvSub);

            return card;
        }
    }

    // --- Helper File IO ---
    private File findFileByExt(File dir, String ext) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (int i = 0; i < files.length; i++) {
            if (files[i].isDirectory()) {
                File found = findFileByExt(files[i], ext);
                if (found != null) return found;
            } else if (files[i].getName().toLowerCase().endsWith(ext)) {
                return files[i];
            }
        }
        return null;
    }

    private File copyUriToTempFile(Uri uri, String prefix) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            File out = new File(getCacheDir(), prefix + "_" + System.currentTimeMillis());
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            is.close();
            fos.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
        fis.close();
        fos.close();
    }

    private byte[] readAllBytes(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
        fis.close();
        return bos.toByteArray();
    }
}

