package com.s60reborn.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.s60reborn.app.R;
import com.s60reborn.app.core.SymbianCapabilityManager;
import com.s60reborn.app.model.SisFileEntry;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SisFileListAdapter extends BaseAdapter {

    public interface OnFileItemActionListener {
        void onDeleteItem(int position, SisFileEntry entry, String fileNameOnly);
        void onOpenThemeEditor(File physicalFile, String extension);
        void onOpenRscEditor(File physicalFile);
        void onOpenCapabilityEditor(File physicalFile, TextView tvTargetSubtitle);
        void onOpenCertificateInfo(File physicalFile);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private List<SisFileEntry> items;
    private File projectDir;
    private final OnFileItemActionListener listener;

    // Cache hasil pembacaan capability .exe agar tidak baca disk berulang saat scroll
    private final Map<String, String> exeCapCache = new HashMap<String, String>();
	

	public void updateData(List<SisFileEntry> newItems, File newProjectDir) {
		this.items = newItems;
		this.projectDir = newProjectDir;
		this.exeCapCache.clear();
		notifyDataSetChanged();
	}
	

    public SisFileListAdapter(Context context, List<SisFileEntry> items, File projectDir, OnFileItemActionListener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.items = items;
        this.projectDir = projectDir;
        this.listener = listener;
    }

    public void setProjectDir(File projectDir) {
        this.projectDir = projectDir;
        this.exeCapCache.clear();
    }

    public void clearCache() {
        this.exeCapCache.clear();
    }

    @Override
    public int getCount() {
        return items != null ? items.size() : 0;
    }

    @Override
    public SisFileEntry getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static class ViewHolder {
        LinearLayout colFileInfo;
        TextView tvFileName;
        TextView tvFileTarget;
        TextView btnDelete;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        final ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_file_row, parent, false);
            holder = new ViewHolder();
            holder.colFileInfo = convertView.findViewById(R.id.colFileInfo);
            holder.tvFileName = convertView.findViewById(R.id.tvItemFileName);
            holder.tvFileTarget = convertView.findViewById(R.id.tvItemFileTarget);
            holder.btnDelete = convertView.findViewById(R.id.btnItemDelete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final SisFileEntry entry = getItem(position);
        final String fileNameOnly = new File(entry.localRelPath).getName();

        String variantTag = "";
        if (entry.localRelPath != null && entry.localRelPath.startsWith("varian_")) {
            int slashIdx = entry.localRelPath.indexOf('/');
            if (slashIdx > 0) {
                variantTag = "[" + entry.localRelPath.substring(0, slashIdx).toUpperCase().replace("_", " ") + "] ";
            }
        }

        holder.tvFileName.setText("📄 " + variantTag + fileNameOnly);
        holder.tvFileTarget.setText("Tujuan: " + entry.targetPath);
        holder.tvFileTarget.setTextColor(context.getResources().getColor(R.color.accent_teal));
        holder.colFileInfo.setOnClickListener(null);
        holder.colFileInfo.setClickable(false);

        final File physicalFile = new File(projectDir, entry.localRelPath);
        final String lowerName = fileNameOnly.toLowerCase();

        if (lowerName.endsWith(".mbm") || lowerName.endsWith(".mif")) {
            holder.tvFileTarget.setText("🎨 Ketuk untuk Edit Tema/Ikon (" + entry.targetPath + ")");
            holder.tvFileTarget.setTextColor(context.getResources().getColor(R.color.accent_green));
            holder.colFileInfo.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (listener != null) {
							listener.onOpenThemeEditor(physicalFile, lowerName.endsWith(".mbm") ? ".mbm" : ".mif");
						}
					}
				});
        } else if (lowerName.endsWith(".skn")) {
            holder.tvFileTarget.setText("📱 Ketuk untuk Preview Nokia & Edit Warna (.SKN)");
            holder.tvFileTarget.setTextColor(context.getResources().getColor(R.color.accent_orange));
            holder.colFileInfo.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (listener != null) listener.onOpenThemeEditor(physicalFile, ".skn");
					}
				});
        } else if (lowerName.endsWith(".rsc") || lowerName.matches(".*\\.r\\d{2}$")) {
            holder.tvFileTarget.setText("🌐 Ketuk untuk Edit Bahasa / Teks Aplikasi (.RSC)");
            holder.tvFileTarget.setTextColor(context.getResources().getColor(R.color.accent_cyan));
            holder.colFileInfo.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (listener != null) listener.onOpenRscEditor(physicalFile);
					}
				});
        } else if (lowerName.endsWith(".cer")) {
            holder.tvFileTarget.setText("🔐 Ketuk untuk Lihat Detail Sertifikat (.CER)");
            holder.tvFileTarget.setTextColor(context.getResources().getColor(R.color.accent_blue));
            holder.colFileInfo.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (listener != null) listener.onOpenCertificateInfo(physicalFile);
					}
				});
        } else if (isSymbianExecutableBinary(physicalFile, lowerName)) {
            String cachedSummary = exeCapCache.get(entry.localRelPath);
            if (cachedSummary == null) {
                long capMask = 0L;
                int capCount = 0;
                if (physicalFile.exists()) {
                    try {
                        capMask = SymbianCapabilityManager.readCapabilitiesFromE32(physicalFile);
                        capCount = SymbianCapabilityManager.getCapabilityNamesList(capMask).size();
                    } catch (Exception ignored) {}
                }
                cachedSummary = "🛡️ Ketuk untuk Edit Capabilities (" + capCount + " aktif • 0x"
					+ Long.toHexString(capMask).toUpperCase() + ")";
                exeCapCache.put(entry.localRelPath, cachedSummary);
            }

            holder.tvFileTarget.setText(cachedSummary);
            holder.tvFileTarget.setTextColor(context.getResources().getColor(R.color.accent_coral));
            holder.colFileInfo.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						exeCapCache.remove(entry.localRelPath); // Refresh cache setelah di-patch
						if (listener != null) listener.onOpenCapabilityEditor(physicalFile, holder.tvFileTarget);
					}
				});
        }

        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (listener != null) listener.onDeleteItem(position, entry, fileNameOnly);
				}
			});

        return convertView;
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
        if (file != null && file.exists() && file.length() >= 0x88) {
            try{
				FileInputStream fis = new FileInputStream(file);
                byte[] hdr = new byte[20];
                if (fis.read(hdr) == 20) {
                    return hdr[16] == 'E' && hdr[17] == 'P' && hdr[18] == 'O' && hdr[19] == 'C';
                }
            } catch (Exception ignored) {}
        }
        return false;
    }
}

