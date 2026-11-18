package com.s60reborn.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.s60reborn.app.R;
import com.s60reborn.app.core.SisxCoreParser;
import com.s60reborn.app.core.SisxPacker;
import com.s60reborn.app.model.SisFileEntry;
import com.s60reborn.app.model.SisPackageInfo;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import android.app.*;
import android.view.*;
import android.widget.*;

public class SisxExplorerActivity extends Activity {

    private static final int REQUEST_CODE_REPLACE = 2001;

    private File sourceSisFile;
    private SisPackageInfo packageInfo;
    private ListView lvFiles;
    private TextView tvStatus;

    private List<SisFileEntry> fileEntries;
    private CompactFileAdapter adapter;

    private SisFileEntry currentlySelectedEntry;
    private boolean isModified = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sisx_explorer);

        tvStatus = (TextView) findViewById(R.id.tvExplorerStatus);
        lvFiles = (ListView) findViewById(R.id.lvExplorerFiles);
        Button btnBack = (Button) findViewById(R.id.btnExplorerBack);
        Button btnRebuild = (Button) findViewById(R.id.btnRebuildFromExplorer);

        btnBack.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					onBackPressed();
				}
			});

        btnRebuild.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					confirmAndRebuild();
				}
			});

        String path = getIntent().getStringExtra("SIS_PATH");
        if (path != null) {
            sourceSisFile = new File(path);
            loadSisInMemory(sourceSisFile);
        }

        lvFiles.setOnItemClickListener(new AdapterView.OnItemClickListener() {
				@Override
				public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					if (fileEntries != null && position < fileEntries.size()) {
						currentlySelectedEntry = fileEntries.get(position);
						showFileOptionsDialog(currentlySelectedEntry);
					}
				}
			});
    }

    private void loadSisInMemory(final File file) {
        tvStatus.setText("Membaca struktur file dalam memori...");
        new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						packageInfo = SisxCoreParser.inspectSisx(file,null);
						fileEntries = packageInfo != null ? packageInfo.files : new ArrayList<SisFileEntry>();

						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									String uidHex = String.format("0x%08X", packageInfo.uid3);
									tvStatus.setText("Total: " + fileEntries.size() + " file | UID3: " + uidHex);

									adapter = new CompactFileAdapter();
									lvFiles.setAdapter(adapter);
								}
							});
					} catch (final Exception e) {
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									tvStatus.setText("Error: " + e.getMessage());
								}
							});
					}
				}
			}).start();
    }
	public String size2String(long size){
		if(size>1024){
			return (size/1024)+"KB";
		}
		else if(size>(1024*1024)){
			return (size/(1024*1024))+"MB";
		}
		else if(size>(1024*1024*1024)){
			return size/(1024*1024*1024)+"GB";
		}else{
			return "B";
		}
	}

    // Custom Adapter dengan Teks Kecil dan Compact
    private class CompactFileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return fileEntries != null ? fileEntries.size() : 0;
        }

        @Override
        public Object getItem(int position) {
            return fileEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView tvPath, tvInfo;
            float d = getResources().getDisplayMetrics().density;

            if (convertView == null) {
                row = new LinearLayout(SisxExplorerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackgroundResource(R.drawable.input_modern);
                int p = (int) (6 * d);
                row.setPadding(p, p, p, p);

                tvPath = new TextView(SisxExplorerActivity.this);
                tvPath.setTextColor(0xFFE0E0E0);
                tvPath.setTextSize(11.5f); // Ukuran path kecil
                tvPath.setSingleLine(true);
                row.addView(tvPath);

                tvInfo = new TextView(SisxExplorerActivity.this);
                tvInfo.setTextColor(0xFF78909C);
                tvInfo.setTextSize(9.5f); // Ukuran ukuran file & status lebih kecil lagi
                row.addView(tvInfo);

                row.setTag(new ViewHolder(tvPath, tvInfo));
            } else {
                row = (LinearLayout) convertView;
                ViewHolder holder = (ViewHolder) row.getTag();
                tvPath = holder.tvPath;
                tvInfo = holder.tvInfo;
            }

            SisFileEntry f = fileEntries.get(position);
            String path = f.targetPath != null ? f.targetPath : f.localRelPath;
            String status = f.isModified ? " [MODIFIED]" : "";

            tvPath.setText(path);
            tvInfo.setText(size2String(f.uncompressedSize)+ status);

            return row;
        }
    }

    private static class ViewHolder {
        TextView tvPath, tvInfo;
        ViewHolder(TextView p, TextView i) {
            this.tvPath = p;
            this.tvInfo = i;
        }
    }


	private void showFileInfo(SisFileEntry entry) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        float d = getResources().getDisplayMetrics().density;
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundResource(R.drawable.dialog_glass_bg);

        int pad = (int) (14 * d);
        rootLayout.setPadding(pad, pad, pad, pad);

        // Judul Dialog
        TextView titleView = new TextView(this);
        titleView.setText("📄 Informasi File");
        titleView.setTextColor(0xFF00E676); // Hijau aksen
        titleView.setTextSize(14f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, (int)(10 * d));
        rootLayout.addView(titleView);

        // Isi Informasi File
        String info = "Target: " + (entry.targetPath != null ? entry.targetPath : entry.localRelPath) + "\n\n" +
			"Ukuran Asli: " + size2String(entry.uncompressedSize)+"\n" +
			"Ukuran Kompresi: " + size2String(entry.compressedSize)+"\n" +
			"Kondisi IF: " + (entry.ifCondition != null ? entry.ifCondition : "Tidak ada") + "\n" +
			"Status: " + (entry.isModified ? "Telah dimodifikasi" : "Original");

        TextView msgView = new TextView(this);
        msgView.setText(info);
        msgView.setTextColor(0xFFE0E0E0); // Teks putih terang
        msgView.setTextSize(12f);
        msgView.setPadding(0, 0, 0, (int)(14 * d));
        rootLayout.addView(msgView);

        // Tombol Tutup
        Button btnClose = new Button(this);
        btnClose.setText("Tutup");
        btnClose.setAllCaps(false);
        btnClose.setTextColor(0xFFFFFFFF);
        btnClose.setBackgroundColor(0xFF263238);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(36 * d)
        );
        btnClose.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});
        rootLayout.addView(btnClose, btnLp);

        dialog.setContentView(rootLayout);
        dialog.show();

        if (dialog.getWindow() != null) {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            dialog.getWindow().setLayout((int) (screenW * 0.88f), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }
	

    private void openTextEditor(final SisFileEntry entry) {
        if (entry.rawData == null) {
            Toast.makeText(this, "Data biner kosong.", Toast.LENGTH_SHORT).show();
            return;
        }

        String pathLower = entry.targetPath.toLowerCase();
        if (pathLower.endsWith(".txt") || pathLower.endsWith(".py") || pathLower.endsWith(".cfg") || 
            pathLower.endsWith(".rsc") || pathLower.endsWith(".ini") || pathLower.endsWith(".pkg") || pathLower.endsWith(".script")) {

            try {
                final String content = new String(entry.rawData, "UTF-8");

                final EditText etEditor = new EditText(this);
                etEditor.setText(content);
                etEditor.setHorizontallyScrolling(true);
                etEditor.setTypeface(Typeface.MONOSPACE);
                etEditor.setTextColor(0xFFFFFFFF);
                etEditor.setBackgroundColor(0xFF14171C);
                etEditor.setTextSize(12f);
                etEditor.setPadding(12, 12, 12, 12);
				
				

                new AlertDialog.Builder(this)
                    .setTitle("Editor: " + new File(entry.targetPath).getName())
                    .setView(etEditor)
                    .setPositiveButton("Simpan", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                String newText = etEditor.getText().toString();
                                byte[] newBytes = newText.getBytes("UTF-8");

                                entry.rawData = newBytes;
                                entry.uncompressedSize = newBytes.length;
                                entry.isModified = true;
                                isModified = true;

                                if (adapter != null) adapter.notifyDataSetChanged();
                                Toast.makeText(SisxExplorerActivity.this, "Disimpan ke memori!", Toast.LENGTH_SHORT).show();
                            } catch (Exception ex) {
                                Toast.makeText(SisxExplorerActivity.this, "Gagal: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal membuka teks.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Bukan file teks.", Toast.LENGTH_SHORT).show();
        }
    }
	
	private void showFileOptionsDialog(final SisFileEntry entry) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        final float d = getResources().getDisplayMetrics().density;
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundResource(R.drawable.dialog_glass_bg);

        int pad = (int) (14 * d);
        rootLayout.setPadding(pad, pad, pad, pad);

        // Judul Dialog
        TextView titleView = new TextView(this);
        titleView.setText("⚙️ Opsi File");
        titleView.setTextColor(0xFF00E676); // Hijau aksen
        titleView.setTextSize(14f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, (int)(8 * d));
        rootLayout.addView(titleView);

        // Subjudul nama file yang diklik (agar informatif)
        TextView subTitle = new TextView(this);
        String fileNameOnly = entry.targetPath != null ? new File(entry.targetPath).getName() : "File";
        subTitle.setText(fileNameOnly);
        subTitle.setTextColor(0xFF90A4AE);
        subTitle.setTextSize(11f);
        subTitle.setPadding(0, 0, 0, (int)(10 * d));
        rootLayout.addView(subTitle);

        // Daftar Pilihan Menu
        final String[] options = {
            "📄 Informasi File",
            "✏️ Lihat / Edit (Text Editor)",
            "🔄 Ganti File (Replace)",
            "📦 Ekstrak File Ini"
        };

        ListView listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight((int)(4 * d));

        ArrayAdapter<String> menuAdapter = new ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, options
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = (TextView) view.findViewById(android.R.id.text1);
                tv.setTextColor(0xFFFFFFFF); // Teks putih
                tv.setTextSize(12f);
                tv.setBackgroundResource(R.drawable.input_modern); // Latar belakang input modern semi-transparan
                int p = (int)(10 * d);
                tv.setPadding(p, p, p, p);
                return view;
            }
        };
        listView.setAdapter(menuAdapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
				@Override
				public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					dialog.dismiss();
					if (position == 0) showFileInfo(entry);
					else if (position == 1) openTextEditor(entry);
					else if (position == 2) triggerReplaceFile();
					else if (position == 3) extractSingleFile(entry);
				}
			});

        rootLayout.addView(listView, new LinearLayout.LayoutParams(
							   LinearLayout.LayoutParams.MATCH_PARENT, (int)(210 * d)
						   ));

        // Tombol Batal
        Button btnCancel = new Button(this);
        btnCancel.setText("Batal");
        btnCancel.setAllCaps(false);
        btnCancel.setTextColor(0xFFE0E0E0);
        btnCancel.setBackgroundColor(0xFF263238);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(36 * d)
        );
        btnLp.setMargins(0, (int)(10 * d), 0, 0);
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
            dialog.getWindow().setLayout((int) (screenW * 0.88f), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }
	

    private void triggerReplaceFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_REPLACE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_REPLACE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                byte[] newBytes = readBytesFromUri(uri);
                if (currentlySelectedEntry != null && newBytes != null) {
                    currentlySelectedEntry.rawData = newBytes;
                    currentlySelectedEntry.uncompressedSize = newBytes.length;
                    currentlySelectedEntry.isModified = true;
                    isModified = true;

                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(this, "File diganti di memori!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Gagal ganti file.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void extractSingleFile(SisFileEntry entry) {
        File outDir = new File(getExternalFilesDir(null), "extracted_single");
        File outFile = new File(outDir, new File(entry.targetPath).getName());
        boolean success = SisxCoreParser.extractSingleFileFromSisx(sourceSisFile, entry, outFile);
        if (success) {
            Toast.makeText(this, "Diekstrak ke:\n" + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Gagal ekstrak.", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmAndRebuild() {
        if (!isModified) {
            Toast.makeText(this, "Belum ada file yang dimodifikasi.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Rebuild SISX")
            .setMessage("Buat file .sisx baru dari perubahan di memori?")
            .setPositiveButton("Rebuild", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    performRebuild();
                }
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void performRebuild() {
        final File outFile = new File(getExternalFilesDir(null), "rebuilt_" + sourceSisFile.getName());
        tvStatus.setText("Sedang Rebuild...");
        new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						SisxPacker.packPackage(packageInfo, packageInfo.files, outFile, 2);
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									tvStatus.setText("Rebuild Sukses!");
									Toast.makeText(SisxExplorerActivity.this, "Disimpan:\n" + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
								}
							});
					} catch (final Exception e) {
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									tvStatus.setText("Rebuild Gagal!");
								}
							});
					}
				}
			}).start();
    }

    private byte[] readBytesFromUri(Uri uri) throws Exception {
        InputStream is = null;
        ByteArrayOutputStream bos = null;
        try {
            is = getContentResolver().openInputStream(uri);
            bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (is != null) is.close();
            if (bos != null) bos.close();
        }
    }

    @Override
    public void onBackPressed() {
        if (isModified) {
            new AlertDialog.Builder(this)
                .setTitle("Keluar")
                .setMessage("Ada perubahan belum di-rebuild. Keluar?")
                .setPositiveButton("Ya", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        SisxExplorerActivity.super.onBackPressed();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
        } else {
            super.onBackPressed();
        }
    }
}

