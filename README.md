# S60Reborn

**S60Reborn** adalah utilitas native Android (Java) untuk membongkar (*unpack*), menginspeksi metadata, memodifikasi, dan merakit ulang (*repack*) paket instalasi **Symbian OS** (`.sis` / `.sisx`) langsung di perangkat Android tanpa ketergantungan PC atau toolchain desktop yang berat.

---

## Fitur yang Sudah Berjalan

- **Unpack & Repack `.sisx` (S60v3, S60v5, Symbian^3 / Anna / Belle):**
  - Membongkar dan merakit ulang paket instalasi Symbian OS 9.x beserta dekompresi stream Zlib otomatis.
  - Sudah teruji dan berjalan mulus saat diinstal langsung di perangkat asli.
- **Unpack & Repack Legacy `.sis` (S60v1 / S60v2):**
  - Parsing header EPOC legacy, tabel bahasa, prerequisite, dan record file, serta perakitan ulang (repack) struktur biner `.sis` S60v2 (*berjalan normal, belum diuji langsung di installer perangkat fisik*).
- **SKN Editor & Theme Preview:**
  - Membaca database `.skn` tema Symbian.
  - Mendukung pratinjau tampilan tema (*Theme Preview*) bergaya **Nokia E72** dan **Nokia N95**.
- **AlterE32 (Editor Kapabilitas Biner):**
  - Membaca dan mengedit bitmask kapabilitas langsung pada file biner EPOC (`.exe`, `.dll`, `.pyd`).
- **Editor Metadata & Opsi Paket Lengkap:**
  - Mengedit informasi paket (*App Name*, versi Major/Minor/Build, *Vendor*, UID3, dll.).
  - Mendukung *Advanced Options* saat instalasi.
  - Mendukung notifikasi teks (`FT`) maupun gambar/logo saat proses instalasi.
  - Mendukung opsi *checkbox* pilihan komponen sebelum instalasi.
  - Mendukung penyisipan paket *embedded SIS* (`@"sub.sis"`).
  - Mendukung *Multiple Target Device* (`Series60ProductID` untuk berbagai versi S60/Symbian).
  - Serta berbagai utilitas pendukung lainnya.

---

## Known Issues (Masih dalam Penyempurnaan)

- **Sign `.sisx`:** Proses penandatanganan (*signing*) sertifikat pada `.sisx` masih memiliki bug dan belum berjalan sempurna.
- **MIF & MBM Editor:** Pengelolaan dan pengeditan aset `.mif` maupun `.mbm` belum sepenuhnya sempurna dan masih terdapat beberapa bug.

---

## Rencana Perbaikan di Commit Selanjutnya

- Penyempurnaan **MIF Editor** dan **MBM Editor**.
- Perbaikan bug pada proses **Sign `.sisx`** serta stabilitas keseluruhan aplikasi.
- Penambahan fitur-fitur lanjutan lainnya jika memungkinkan.

---

## Struktur Modul Utama

- `com.s60reborn.app.core.SisxCoreParser` & `SisxPacker` — Parser, extractor, dan compiler paket modern `.sisx` (Symbian OS 9.x).
- `com.s60reborn.app.core.SisLegacyParser` & `SisLegacyPacker` — Parser dan builder biner paket legacy `.sis` (S60v1 / S60v2).
- `com.s60reborn.app.model.*` — Model data paket (`SisPackageInfo`), entri file (`SisFileEntry`), dependensi, dan konfigurasi proyek (`SisProjectConfig`).

---

## Lisensi

Proyek ini dirilis di bawah lisensi [MIT License](LICENSE).
