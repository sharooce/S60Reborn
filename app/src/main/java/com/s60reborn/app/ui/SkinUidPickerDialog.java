package com.s60reborn.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.s60reborn.app.R;
import com.s60reborn.app.core.SknParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SkinUidPickerDialog {

    public interface OnTargetUidPickedListener {
        /**
         * Dipanggil saat user selesai memilih Target UID untuk gambar PNG/SVG manualnya
         * @param uidKey Format "MAJOR:MINOR" (misal: "10005A26:00001001")
         * @param elementName Nama elemen (misal: "Idle Background")
         * @param manualImageFile File PNG/SVG manual yang dipilih user (bisa null jika dipilih belakangan)
         */
        void onUidPicked(String uidKey, String elementName, File manualImageFile);
    }

    public static class UidChoiceItem {
        public String uidKey;
        public String name;

        public UidChoiceItem(String uidKey, String name) {
            this.uidKey = uidKey;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + "\n[" + uidKey + "]";
        }
    }

    /**
     * Menampilkan Mode Pick UID dari database SISContents untuk dipasangkan ke PNG/SVG manual
     */
    public static void show(
		final Context ctx,
		final String assetDbName,
		final File pickedImageFile,
		final OnTargetUidPickedListener listener) {

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final float d = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
		
		

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_glass_bg);
        root.setPadding(pad, pad, pad, pad);
		
		

        // 1. Judul & Info File Gambar Manual
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("Pilih Target UID Elemen Tema");
        tvTitle.setTextColor(0xFF4FC3F7);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvFileInfo = new TextView(ctx);
        String imgInfo = (pickedImageFile != null)
			? "Gambar Manual: " + pickedImageFile.getName()
			: "Mode Pick: Pilih target UID untuk aset gambar baru";
        tvFileInfo.setText(imgInfo);
        tvFileInfo.setTextColor(0xFFB0BEC5);
        tvFileInfo.setTextSize(12);
        tvFileInfo.setPadding(0, (int) (4 * d), 0, (int) (10 * d));
        root.addView(tvFileInfo);

        // 2. Kotak Pencarian Real-time (Nama Elemen atau Hex UID)
        final EditText etSearch = new EditText(ctx);
        etSearch.setHint("Cari nama (misal: Wallpaper, Calendar) atau UID...");
        etSearch.setTextColor(Color.WHITE);
        etSearch.setHintTextColor(0xFF78909C);
        etSearch.setSingleLine(true);
        etSearch.setBackgroundResource(R.drawable.input_modern);
        etSearch.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        root.addView(etSearch);

        // 3. Muat Data dari Database SISContents (Assets + Custom DB)
        final List<UidChoiceItem> allItems = new ArrayList<UidChoiceItem>();
        try {
            Map<String, String> dbMap = SknParser.loadBinarySisContentsDbFromAssets(ctx, assetDbName);
            for (Map.Entry<String, String> entry : dbMap.entrySet()) {
                allItems.add(new UidChoiceItem(entry.getKey(), entry.getValue()));
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Gagal memuat database UID: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        final List<UidChoiceItem> filteredItems = new ArrayList<UidChoiceItem>(allItems);

        // 4. ListView Daftar Target UID
        ListView listView = new ListView(ctx);
        listView.setDivider(new ColorDrawable(0x22FFFFFF));
        listView.setDividerHeight(1);

        final ArrayAdapter<UidChoiceItem> adapter = new ArrayAdapter<UidChoiceItem>(
			ctx, android.R.layout.simple_list_item_1, filteredItems) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(13);
                tv.setPadding((int) (8 * d), (int) (10 * d), (int) (8 * d), (int) (10 * d));
                return tv;
            }
        };
        listView.setAdapter(adapter);

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d));
        listParams.topMargin = (int) (8 * d);
        listParams.bottomMargin = (int) (10 * d);
        root.addView(listView, listParams);

        // Filter Pencarian Live
        etSearch.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					String q = s.toString().toLowerCase(Locale.getDefault()).trim();
					filteredItems.clear();
					if (q.isEmpty()) {
						filteredItems.addAll(allItems);
					} else {
						for (int i = 0; i < allItems.size(); i++) {
							UidChoiceItem item = allItems.get(i);
							if (item.name.toLowerCase(Locale.getDefault()).contains(q) ||
								item.uidKey.toLowerCase(Locale.getDefault()).contains(q)) {
								filteredItems.add(item);
							}
						}
					}
					adapter.notifyDataSetChanged();
				}

				@Override
				public void afterTextChanged(Editable s) {}
			});

        // Klik Item untuk Langsung Memilih Target UID
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
				@Override
				public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					UidChoiceItem chosen = filteredItems.get(position);
					dialog.dismiss();
					if (listener != null) {
						listener.onUidPicked(chosen.uidKey, chosen.name, pickedImageFile);
					}
				}
			});

        // 5. Tombol Bawah: Input UID Manual (Untuk App Pihak ke-3) & Batal
        LinearLayout bottomRow = new LinearLayout(ctx);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnManualUid = new Button(ctx);
        btnManualUid.setText("+ Input UID Manual");
        btnManualUid.setTextColor(0xFF4FC3F7);
        btnManualUid.setBackgroundColor(Color.TRANSPARENT);
        btnManualUid.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
					showManualUidInputDialog(ctx, pickedImageFile, listener);
				}
			});

        Button btnCancel = new Button(ctx);
        btnCancel.setText("Batal");
        btnCancel.setTextColor(0xFFEF5350);
        btnCancel.setBackgroundColor(Color.TRANSPARENT);
        btnCancel.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        bottomRow.addView(btnManualUid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bottomRow.addView(btnCancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bottomRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f),
										 LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }
	

    /**
     * Sub-dialog jika user ingin memasukkan UID aplikasi pihak ke-3 secara manual
     */
    private static void showManualUidInputDialog(
		final Context ctx,
		final File pickedImageFile,
		final OnTargetUidPickedListener listener) {

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 24, 40, 10);

        final EditText etAppName = new EditText(ctx);
        etAppName.setHint("Nama Aplikasi (misal: Opera Mini)");
        box.addView(etAppName);

        final EditText etMinorUid = new EditText(ctx);
        etMinorUid.setHint("UID Aplikasi 8-digit Hex (misal: 2001ABCD)");
        box.addView(etMinorUid);

        new android.app.AlertDialog.Builder(ctx)
			.setTitle("Target UID Kustom (App Pihak Ke-3)")
			.setView(box)
			.setPositiveButton("Pilih & Simpan", new android.content.DialogInterface.OnClickListener() {
				@Override
				public void onClick(android.content.DialogInterface d, int w) {
					String name = etAppName.getText().toString().trim();
					String rawUid = etMinorUid.getText().toString().trim().replace("0x", "").replace("0X", "").toUpperCase();
					if (rawUid.length() != 8) {
						Toast.makeText(ctx, "UID harus 8 digit Hex!", Toast.LENGTH_SHORT).show();
						return;
					}
					if (name.isEmpty()) name = "Custom App (0x" + rawUid + ")";
					// Major UID standar ikon aplikasi S60: 101F84B9
					String fullUidKey = "101F84B9:" + rawUid;
					SknParser.saveCustomElementName(ctx, fullUidKey, name);

					if (listener != null) {
						listener.onUidPicked(fullUidKey, name, pickedImageFile);
					}
				}
			})
			.setNegativeButton("Batal", null)
			.show();
    }
}

