# S60Reborn

**S60Reborn** adalah utilitas native Android (Java) untuk membongkar (*unpack*), menginspeksi metadata, memodifikasi, dan merakit ulang (*repack*) paket instalasi **Symbian OS** (`.sis` / `.sisx`) langsung di perangkat mobile tanpa ketergantungan PC atau toolchain desktop yang berat.

---

## Fitur yang Sudah Berjalan (Completed)

- **Inspeksi & Unpack `.sisx` (Symbian OS 9.x / S60v3, S60v5, Symbian^3 / Belle):**
  - Membaca header UID1/UID2/UID3 beserta verifikasi checksum UID Symbian.
  - Dekompresi stream Zlib (`FIELD_COMPRESSED`) untuk `SISController` dan `SISData` secara otomatis.
  - Pemisahan otomatis aset biner berdasarkan *magic bytes* (`.mbm`, `.mif`, `.skn`, `.ini`, `.exe`, `.dll`, `.rsc`).
  - Dukungan paket multi-varian (`varian_1/`, `varian_2/`, dst.) untuk mencegah penimpaan file pada cabang kondisi `IF / ELSEIF`.
  - Deteksi status sertifikat tanda tangan digital (*Signed Certificate Chain*).
- **Unpack Legacy `.sis` (Symbian OS 6/7/8 - S60v1 / S60v2):**
  - Parsing header EPOC legacy, tabel bahasa, serta ekstraksi record file.
- **Theme Editor (Editor Tema Symbian):**
  - Membongkar dan memodifikasi aset tema S60 (`.mbm`, `.mif`, `.skn`, dan konfigurasi `.ini`) langsung dari hasil ekstrak paket tema.
  - Penggantian ikon, wallpaper, dan elemen antarmuka tema secara terstruktur sebelum dirakit ulang.
- **Language Editing (Editor Bahasa & Lokalisasi):**
  - Mengedit file resource bahasa Symbian (`.rsc`, `.r01`, `.rXX`, dan string lokalisasi) untuk menerjemahkan atau menyesuaikan teks antarmuka aplikasi maupun tema S60.
- **Auto-Generate Script `.pkg`:**
  - Menghasilkan file `project.pkg` otomatis lengkap dengan header UID3, versi aplikasi, nama vendor, target `Series60ProductID`, dan pemetaan path relatif ke direktori target Symbian (`!:\sys\bin\...`, `!:\resource\apps\...`).
- **Repack Legacy `.sis` (S60v1 / S60v2):**
  - Membangun ulang paket instalasi legacy dari `project.pkg` lengkap dengan kalkulasi CRC-16 CCITT UID checksum.
- **Ekstraksi Kapabilitas E32Image:**
  - Membaca 8-byte bitmask kapabilitas langsung dari offset `0x80` pada header biner EPOC (`.exe` / `.dll`).

---

## Status Pengembangan & Known Issues (Work in Progress)

Bagian berikut sedang dalam tahap penyempurnaan dan akan diperbarui pada commit selanjutnya:

- **Repack `.sisx` Modern (Symbian OS 9.x):**
  - Penyelarasan struktur hierarki TLV (`FIELD_CONTENTS` -> `FIELD_CONTROLLER` / `FIELD_DATA` -> `FIELD_COMPRESSED`) agar 100% kompatibel dengan parser resmi perangkat Nokia S60 (seperti Nokia E72 / N86 / N95) tanpa memicu pesan *File Corrupted* saat instalasi.
  - Penyesuaian deduplikasi blok data berbasis SHA-1 (`FIELD_HASH` & `FIELD_DATA_INDEX`) pada paket multi-file.
- **Penandatanganan Paket (Self-Signing SISX):**
  - Integrasi penandatanganan sertifikat devcert / self-signed langsung setelah proses repack selesai.

---

## Struktur Modul Utama

- `com.s60reborn.app.core.SisxCoreParser` — Parser biner & extractor untuk format `.sisx` (OS 9.x) dan legacy `.sis` (OS 6/7/8), serta generator `project.pkg`.
- `com.s60reborn.app.core.SisxPacker` — Compiler script `.pkg` dan builder struktur biner TLV Symbian OS beserta kalkulator UID checksum.
- `com.s60reborn.app.model.*` — Model data untuk representasi paket (`SisPackageInfo`) dan entri file (`SisFileEntry`).

---

## Lisensi

Proyek ini dirilis di bawah lisensi [MIT License](LICENSE).
