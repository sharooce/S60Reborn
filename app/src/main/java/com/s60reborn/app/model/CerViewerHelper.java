package com.s60reborn.app.model;

import android.app.AlertDialog;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Locale;
import com.s60reborn.app.R;

public class CerViewerHelper
{
	public static void showCertificateInfoDialog(Context ctx, File cerFile) {
		try {
			FileInputStream fis = new FileInputStream(cerFile);
			X509Certificate cert;
			try {
				CertificateFactory cf = CertificateFactory.getInstance("X.509");
				cert = (X509Certificate) cf.generateCertificate(fis);
			} finally {
				fis.close();
			}

			SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault());

			// Hitung SHA-1 Fingerprint sertifikat
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			byte[] digest = sha1.digest(cert.getEncoded());
			StringBuilder fp = new StringBuilder();
			for (int i = 0; i < digest.length; i++) {
				if (i > 0) fp.append(":");
				fp.append(String.format("%02X", digest[i] & 0xFF));
			}

			String infoText =
                "Subjek (Pemilik):\n" + cert.getSubjectX500Principal().getName() + "\n\n" +
                "Penerbit (Issuer):\n" + cert.getIssuerX500Principal().getName() + "\n\n" +
                "Masa Berlaku:\n" + sdf.format(cert.getNotBefore()) + " s/d " + sdf.format(cert.getNotAfter()) + "\n\n" +
                "Nomor Seri:\n" + cert.getSerialNumber().toString(16).toUpperCase() + "\n\n" +
                "Algoritma Tanda Tangan:\n" + cert.getSigAlgName() + "\n\n" +
                "SHA-1 Fingerprint:\n" + fp.toString();

			new AlertDialog.Builder(ctx)
                .setTitle("Detail Sertifikat (" + cerFile.getName() + ")")
                .setMessage(infoText)
                .setPositiveButton("Tutup", null)
				.setIcon(R.drawable.ic_launcher_round)
                .show();

		} catch (Exception e) {
			new AlertDialog.Builder(ctx)
                .setTitle("Gagal Membaca Sertifikat")
                .setMessage("File sertifikat tidak valid atau rusak:\n" + e.getMessage())
                .setPositiveButton("OK", null)
                .show();
		}
	}
}
