package com.s60reborn.app.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.s60reborn.app.R;
import com.s60reborn.app.core.MbmConverter;
import com.s60reborn.app.core.SknParser;
import com.s60reborn.app.core.SknParser.SknAssetMapping;
import com.s60reborn.app.core.SknParser.SknColorItem;
import com.s60reborn.app.core.SknParser.SknThemeData;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NokiaThemeStudioDialog {

    // Sesuaikan nama file database bawaan SISContents Anda di folder app/src/main/assets/
    public static final String ASSET_DB_FILENAME = "items.db";

    public static void show(final Context context, final File sknFile, final File variantRoot) {
        try {
            final SknThemeData sknData = SknParser.parseSkn(sknFile);
            final Map<String, String> sisDb = SknParser.loadBinarySisContentsDbFromAssets(context, ASSET_DB_FILENAME);
            final List<SknAssetMapping> mappings = SknParser.extractAssetMappings(sknFile, sisDb);

            final List<Bitmap> allThemeBitmaps = new ArrayList<>();
            File mbmFile = findFileByExt(variantRoot, ".mbm");
			if (mbmFile != null && mbmFile.exists()) {
				allThemeBitmaps.addAll(MbmConverter.loadBitmapsFromMbm(mbmFile));
			}
			File mifFile = findFileByExt(variantRoot, ".mif");
			if (mifFile != null && mifFile.exists()) {
				List<com.s60reborn.app.core.MifConverter.MifIconEntry> mifIcons =
					com.s60reborn.app.core.MifConverter.parseMif(mifFile);
				for (com.s60reborn.app.core.MifConverter.MifIconEntry entry : mifIcons) {
					if (entry.data != null && entry.data.length > 0) {
						Bitmap iconBmp = com.s60reborn.app.core.MifConverter.renderMifIconToBitmap(entry.data, 64);
						if (iconBmp != null) allThemeBitmaps.add(iconBmp);
					}
				}
			}
			

            Bitmap wpBmp = !allThemeBitmaps.isEmpty() ? allThemeBitmaps.get(0) : null;
            Bitmap menuBmp = allThemeBitmaps.size() > 1 ? allThemeBitmaps.get(1) : null;

            final Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            final float d = context.getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_glass_bg);
            int pad = (int) (14 * d);
            root.setPadding(pad, pad, pad, pad);

            // Header + Switch Model HP (E72 / N95)
            LinearLayout topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvTitle = new TextView(context);
            tvTitle.setText("🎨 Theme Studio: " + sknData.skinName);
            tvTitle.setTextColor(context.getResources().getColor(R.color.accent_orange));
            tvTitle.setTextSize(14f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            topBar.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final NokiaThemePreviewView previewView = new NokiaThemePreviewView(context);
            previewView.setThemeAssets(sknData.skinName, wpBmp, menuBmp, allThemeBitmaps,
									   sknData.idleTextColor, sknData.menuTextColor, sknData.softkeyTextColor, sknData.highlightColor);
            previewView.setAssetMappings(mappings);

            final Button btnSwitchModel = new Button(context);
            btnSwitchModel.setText("📱 E72");
            btnSwitchModel.setTextSize(10f);
            btnSwitchModel.setTextColor(context.getResources().getColor(R.color.text_on_accent));
            btnSwitchModel.setBackgroundColor(context.getResources().getColor(R.color.accent_cyan));
            btnSwitchModel.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						int next = (previewView.getPhoneModel() == NokiaThemePreviewView.MODEL_E72)
							? NokiaThemePreviewView.MODEL_N95 : NokiaThemePreviewView.MODEL_E72;
						previewView.setPhoneModel(next);
						btnSwitchModel.setText(next == NokiaThemePreviewView.MODEL_E72 ? "📱 E72" : "📱 N95");
					}
				});
            topBar.addView(btnSwitchModel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(30 * d)));
            root.addView(topBar);

            // Live Preview Nokia View
            root.addView(previewView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (250 * d)));

            // Bar Info Database SISContents + Tombol Tambah dari DB
            LinearLayout dbHeader = new LinearLayout(context);
            dbHeader.setOrientation(LinearLayout.HORIZONTAL);
            dbHeader.setGravity(Gravity.CENTER_VERTICAL);
            dbHeader.setPadding(0, (int)(6 * d), 0, (int)(6 * d));

            final TextView tvDbStat = new TextView(context);
            tvDbStat.setText("Elemen SKN (" + mappings.size() + ") • DB: " + sisDb.size() + " UID");
            tvDbStat.setTextColor(context.getResources().getColor(R.color.accent_teal));
            tvDbStat.setTextSize(11f);
            tvDbStat.setTypeface(null, Typeface.BOLD);
            dbHeader.addView(tvDbStat, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final BaseAdapter[] listAdapterRef = new BaseAdapter[1];

            Button btnAddFromDb = new Button(context);
            btnAddFromDb.setText("+ Tambah dari DB");
            btnAddFromDb.setTextSize(10f);
            btnAddFromDb.setAllCaps(false);
            btnAddFromDb.setTextColor(context.getResources().getColor(R.color.text_on_accent));
            btnAddFromDb.setBackgroundColor(context.getResources().getColor(R.color.accent_green));
            btnAddFromDb.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						showDatabasePickerDialog(context, sisDb, allThemeBitmaps, new OnDbItemPickedListener() {
								@Override
								public void onPicked(String uidKey, String elemName, int bmpIdx, int maskIdx) {
									try {
										SknParser.appendAssetMappingToSkn(sknFile, uidKey, bmpIdx, maskIdx);
										mappings.clear();
										mappings.addAll(SknParser.extractAssetMappings(sknFile, sisDb));
										previewView.setAssetMappings(mappings);
										tvDbStat.setText("Elemen SKN (" + mappings.size() + ") • DB: " + sisDb.size() + " UID");
										if (listAdapterRef[0] != null) listAdapterRef[0].notifyDataSetChanged();
										Toast.makeText(context, "Ditambahkan: " + elemName, Toast.LENGTH_SHORT).show();
									} catch (Exception ex) {
										Toast.makeText(context, "Gagal menambah: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
									}
								}
							});
					}
				});
            dbHeader.addView(btnAddFromDb, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(30 * d)));
            root.addView(dbHeader);

            // List Pemetaan Elemen SKN (Ketuk untuk ganti indeks gambar MBM)
            ListView lvMappings = new ListView(context);
            lvMappings.setDivider(new ColorDrawable(Color.TRANSPARENT));
            lvMappings.setDividerHeight((int)(5 * d));

            listAdapterRef[0] = new BaseAdapter() {
                @Override public int getCount() { return mappings.size(); }
                @Override public Object getItem(int position) { return mappings.get(position); }
                @Override public long getItemId(int position) { return position; }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    final SknAssetMapping item = mappings.get(position);
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackgroundResource(R.drawable.input_modern);
                    row.setPadding((int)(8 * d), (int)(6 * d), (int)(8 * d), (int)(6 * d));

                    ImageView iv = new ImageView(context);
                    iv.setBackgroundColor(context.getResources().getColor(R.color.bg_thumb_dark));
                    if (item.assetIndex >= 0 && item.assetIndex < allThemeBitmaps.size()) {
                        iv.setImageBitmap(allThemeBitmaps.get(item.assetIndex));
                    }
                    row.addView(iv, new LinearLayout.LayoutParams((int)(34 * d), (int)(34 * d)));

                    LinearLayout col = new LinearLayout(context);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setPadding((int)(8 * d), 0, (int)(6 * d), 0);

                    TextView tName = new TextView(context);
                    tName.setText(item.elementName);
                    tName.setTextColor(context.getResources().getColor(R.color.text_primary));
                    tName.setTextSize(11.5f);
                    tName.setTypeface(null, Typeface.BOLD);
                    col.addView(tName);

                    TextView tUid = new TextView(context);
                    tUid.setText(item.uidKey + "  •  MBM #" + item.assetIndex
								 + (item.maskIndex >= 0 ? " (Mask #" + item.maskIndex + ")" : ""));
                    tUid.setTextColor(context.getResources().getColor(R.color.accent_cyan));
                    tUid.setTextSize(10f);
                    tUid.setTypeface(Typeface.MONOSPACE);
                    col.addView(tUid);

                    row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                    Button btnEditIdx = new Button(context);
                    btnEditIdx.setText("Ganti");
                    btnEditIdx.setTextSize(10f);
                    btnEditIdx.setAllCaps(false);
                    btnEditIdx.setTextColor(context.getResources().getColor(R.color.text_on_accent));
                    btnEditIdx.setBackgroundColor(context.getResources().getColor(R.color.accent_orange));
                    btnEditIdx.setOnClickListener(new View.OnClickListener() {
							@Override
							public void onClick(View v) {
								showIndexPickerDialog(context, item, allThemeBitmaps, new Runnable() {
										@Override
										public void run() {
											try {
												SknParser.patchAssetIndexInSkn(sknFile, item.indexOffsetInSkn, item.assetIndex, item.maskIndex);
												previewView.setAssetMappings(mappings);
												listAdapterRef[0].notifyDataSetChanged();
												Toast.makeText(context, "Indeks diperbarui!", Toast.LENGTH_SHORT).show();
											} catch (Exception ex) {
												Toast.makeText(context, "Gagal patch SKN: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
											}
										}
									});
							}
						});
                    row.addView(btnEditIdx, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(28 * d)));

                    return row;
                }
            };
            lvMappings.setAdapter(listAdapterRef[0]);
            root.addView(lvMappings, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (175 * d)));

            // Baris Tombol Bawah (Edit Warna + Tutup)
            LinearLayout bottomBar = new LinearLayout(context);
            bottomBar.setOrientation(LinearLayout.HORIZONTAL);
            bottomBar.setPadding(0, (int)(8 * d), 0, 0);

            Button btnColors = new Button(context);
            btnColors.setText("🎨 Warna Teks/Sorotan");
            btnColors.setTextSize(10.5f);
            btnColors.setAllCaps(false);
            btnColors.setTextColor(context.getResources().getColor(R.color.text_primary));
            btnColors.setBackgroundColor(context.getResources().getColor(R.color.bg_btn_neutral));
            btnColors.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						showColorEditorDialog(context, sknFile, sknData, previewView);
					}
				});

            Button btnDone = new Button(context);
            btnDone.setText("SELESAI");
            btnDone.setTextSize(11f);
            btnDone.setTypeface(null, Typeface.BOLD);
            btnDone.setTextColor(context.getResources().getColor(R.color.text_on_accent));
            btnDone.setBackgroundColor(context.getResources().getColor(R.color.accent_green));
            btnDone.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				});

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, (int)(36 * d), 1f);
            bLp.setMargins(0, 0, (int)(6 * d), 0);
            bottomBar.addView(btnColors, bLp);
            bottomBar.addView(btnDone, new LinearLayout.LayoutParams(0, (int)(36 * d), 1f));
            root.addView(bottomBar);
            dialog.setContentView(root);
            dialog.show();
            if (dialog.getWindow() != null) {
                int screenW = context.getResources().getDisplayMetrics().widthPixels;
                dialog.getWindow().setLayout((int) (screenW * 0.94f), LinearLayout.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            Toast.makeText(context, "Gagal membuka Theme Studio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private interface OnDbItemPickedListener {
        void onPicked(String uidKey, String elemName, int bmpIdx, int maskIdx);
    }

    private static void showDatabasePickerDialog(final Context context,
                                                 final Map<String, String> sisDb,
                                                 final List<Bitmap> bitmaps,
                                                 final OnDbItemPickedListener listener) {
        final Dialog dPick = new Dialog(context);
        dPick.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dPick.getWindow() != null) {
            dPick.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        final float d = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_glass_bg);
        int pad = (int) (14 * d);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(context);
        title.setText("🗄️ Pilih Elemen Tema (Database)");
        title.setTextColor(context.getResources().getColor(R.color.accent_cyan));
        title.setTextSize(13.5f);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        final EditText etSearch = new EditText(context);
        etSearch.setHint("Cari nama ikon / wallpaper / UID...");
        etSearch.setHintTextColor(Color.parseColor("#607D8B"));
        etSearch.setTextColor(context.getResources().getColor(R.color.text_primary));
        etSearch.setTextSize(12f);
        etSearch.setSingleLine(true);
        etSearch.setBackgroundResource(R.drawable.input_modern);
        etSearch.setPadding((int)(10 * d), (int)(7 * d), (int)(10 * d), (int)(7 * d));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.setMargins(0, (int)(8 * d), 0, (int)(8 * d));
        box.addView(etSearch, sLp);

		
        final List<String> allKeys = new ArrayList<String>(sisDb.keySet());
		final List<String> filteredKeys = new ArrayList<String>(allKeys);
		

        ListView lv = new ListView(context);
        lv.setDivider(new ColorDrawable(Color.TRANSPARENT));
        lv.setDividerHeight((int)(5 * d));

		final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filteredKeys.size(); }
            @Override public Object getItem(int pos) { return filteredKeys.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                final String uidKey = filteredKeys.get(pos);
                final String elemName = sisDb.get(uidKey);

                LinearLayout r = new LinearLayout(context);
                r.setOrientation(LinearLayout.VERTICAL);
                r.setBackgroundResource(R.drawable.input_modern);
                r.setPadding((int)(10 * d), (int)(8 * d), (int)(10 * d), (int)(8 * d));

                TextView t1 = new TextView(context);
                t1.setText(elemName);
                t1.setTextColor(context.getResources().getColor(R.color.text_primary));
                t1.setTextSize(12f);
                t1.setTypeface(null, Typeface.BOLD);
                r.addView(t1);

                TextView t2 = new TextView(context);
                t2.setText("UID: " + uidKey);
                t2.setTextColor(context.getResources().getColor(R.color.accent_teal));
                t2.setTextSize(10.5f);
                t2.setTypeface(Typeface.MONOSPACE);
                r.addView(t2);

                r.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							dPick.dismiss();
							final SknAssetMapping temp = new SknAssetMapping();
							temp.uidKey = uidKey;
							temp.elementName = elemName;
							temp.assetIndex = 0;
							temp.maskIndex = -1;
							showIndexPickerDialog(context, temp, bitmaps, new Runnable() {
									@Override
									public void run() {
										listener.onPicked(temp.uidKey, temp.elementName, temp.assetIndex, temp.maskIndex);
									}
								});
						}
					});
                return r;
            }
        };
        lv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void afterTextChanged(Editable s) {}
				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					String q = s.toString().trim().toLowerCase();
					filteredKeys.clear();
					for (String key : allKeys) {
						String val = sisDb.get(key);
						if (q.isEmpty() || key.toLowerCase().contains(q) || (val != null && val.toLowerCase().contains(q))) {
							filteredKeys.add(key);
						}
					}
					adapter.notifyDataSetChanged();
				}
			});
		
        lv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void afterTextChanged(Editable s) {}
				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					String q = s.toString().trim().toLowerCase();
					filteredKeys.clear();
					for (String key : allKeys) {
						String val = sisDb.get(key);
						if (q.isEmpty() || key.toLowerCase().contains(q) || (val != null && val.toLowerCase().contains(q))) {
							filteredKeys.add(key);
						}
					}
					
					adapter.notifyDataSetChanged();
				}
			});

        box.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(280 * d)));

        dPick.setContentView(box);
        dPick.show();
        if (dPick.getWindow() != null) {
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            dPick.getWindow().setLayout((int) (screenW * 0.90f), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void showIndexPickerDialog(final Context context,
                                              final SknAssetMapping mapping,
                                              final List<Bitmap> bitmaps,
                                              final Runnable onConfirmed) {
        final Dialog dIdx = new Dialog(context);
        dIdx.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dIdx.getWindow() != null) {
            dIdx.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        final float d = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_glass_bg);
        int pad = (int) (14 * d);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(context);
        title.setText("Pilih Gambar MBM untuk: " + mapping.elementName);
        title.setTextColor(context.getResources().getColor(R.color.accent_orange));
        title.setTextSize(13f);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        ListView lv = new ListView(context);
        lv.setDivider(new ColorDrawable(Color.TRANSPARENT));
        lv.setDividerHeight((int)(5 * d));
        lv.setAdapter(new BaseAdapter() {
				@Override public int getCount() { return bitmaps.size(); }
				@Override public Object getItem(int pos) { return bitmaps.get(pos); }
				@Override public long getItemId(int pos) { return pos; }

				@Override
				public View getView(final int pos, View convertView, ViewGroup parent) {
					LinearLayout row = new LinearLayout(context);
					row.setOrientation(LinearLayout.HORIZONTAL);
					row.setGravity(Gravity.CENTER_VERTICAL);
					row.setBackgroundResource(R.drawable.input_modern);
					row.setPadding((int)(8 * d), (int)(6 * d), (int)(8 * d), (int)(6 * d));

					ImageView iv = new ImageView(context);
					iv.setBackgroundColor(context.getResources().getColor(R.color.bg_thumb_dark));
					if (bitmaps.get(pos) != null) iv.setImageBitmap(bitmaps.get(pos));
					row.addView(iv, new LinearLayout.LayoutParams((int)(44 * d), (int)(44 * d)));

					TextView tv = new TextView(context);
					int nextMask = (pos + 1 < bitmaps.size()) ? (pos + 1) : -1;
					tv.setText("  MBM Index #" + pos + (nextMask >= 0 ? "  (Auto Mask #" + nextMask + ")" : ""));
					tv.setTextColor(context.getResources().getColor(R.color.text_primary));
					tv.setTextSize(11.5f);
					row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

					row.setOnClickListener(new View.OnClickListener() {
							@Override
							public void onClick(View v) {
								mapping.assetIndex = pos;
								mapping.maskIndex = (pos + 1 < bitmaps.size()) ? (pos + 1) : -1;
								dIdx.dismiss();
								onConfirmed.run();
							}
						});
					return row;
				}
			});

        box.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(260 * d)));
        dIdx.setContentView(box);
        dIdx.show();
    }

    private static void showColorEditorDialog(final Context context,
                                              final File sknFile,
                                              final SknThemeData sknData,
                                              final NokiaThemePreviewView previewView) {
        final String[] presets = {"Putih (#FFFFFF)", "Hitam (#111111)", "Hijau Neon (#00E676)", "Cyan (#4FC3F7)", "Oranye (#FFB74D)", "Merah (#FF5252)"};
        final int[] presetVals = {0xFFFFFFFF, 0xFF111111, 0xFF00E676, 0xFF4FC3F7, 0xFFFFB74D, 0xFFFF5252};

        if (sknData.extractedColors.isEmpty()) {
            Toast.makeText(context, "Tidak ada tabel warna RGB terdeteksi di .SKN ini.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[sknData.extractedColors.size()];
        for (int i = 0; i < labels.length; i++) {
            SknColorItem c = sknData.extractedColors.get(i);
            labels[i] = c.label + String.format(" (#%06X)", (0xFFFFFF & c.colorArgb));
        }

        new AlertDialog.Builder(context)
            .setTitle("Pilih Komponen Warna .SKN")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, final int itemIdx) {
                    new AlertDialog.Builder(context)
                        .setTitle("Pilih Warna Baru")
                        .setItems(presets, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d2, int colorIdx) {
                                try {
                                    SknColorItem target = sknData.extractedColors.get(itemIdx);
                                    target.colorArgb = presetVals[colorIdx];
                                    SknParser.patchColorInSkn(sknFile, target.offsetInFile, target.colorArgb);
                                    if (itemIdx == 0) sknData.idleTextColor = target.colorArgb;
                                    else if (itemIdx == 1) sknData.menuTextColor = target.colorArgb;
                                    else if (itemIdx == 2) sknData.softkeyTextColor = target.colorArgb;
                                    else if (itemIdx == 3) sknData.highlightColor = target.colorArgb;
                                    previewView.updateColors(sknData.idleTextColor, sknData.menuTextColor,
															 sknData.softkeyTextColor, sknData.highlightColor);
                                    Toast.makeText(context, "Warna berhasil di-patch ke .SKN!", Toast.LENGTH_SHORT).show();
                                } catch (Exception ex) {
                                    Toast.makeText(context, "Gagal patch warna: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .show();
                }
            })
            .show();
    }

    private static File findFileByExt(File root, String ext) {
        if (root == null || !root.exists()) return null;
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File sub = findFileByExt(f, ext);
                if (sub != null) return sub;
            } else if (f.getName().toLowerCase().endsWith(ext)) {
                return f;
            }
        }
        return null;
    }
}

