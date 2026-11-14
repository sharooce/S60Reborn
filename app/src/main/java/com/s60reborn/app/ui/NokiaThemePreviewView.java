package com.s60reborn.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class NokiaThemePreviewView extends View {

    public static final int MODEL_E72 = 0; // Nokia E72 (Bodi Portrait + LCD 320x240 4:3 + Navi Metalik + QWERTY)
    public static final int MODEL_N95 = 1; // Nokia N95 (Bodi Portrait + LCD 240x320 3:4 + D-Pad + Tombol N95)

    public static final int PAGE_IDLE = 0;
    public static final int PAGE_MENU = 1;
    public static final int PAGE_POPUP = 2;
	
	private List<Bitmap> themeIconBitmaps = new ArrayList<>();

    public interface OnElementTapListener {
        void onTapWallpaper();
        void onTapTextColor(int pageIndex);
        void onTapHighlightColor();
        void onPageChanged(int newPage);
    }

    private int phoneModel = MODEL_E72;
    private int currentPage = PAGE_MENU; // Default langsung tampil Menu Grid biar ikon besar terlihat

    private Bitmap wallpaperBitmap;
    private Bitmap menuBgBitmap;
    private String themeTitle = "S60Reborn";
    private int idleTextColor = Color.WHITE;
    private int menuTextColor = Color.WHITE;
    private int softkeyTextColor = Color.WHITE;
    private int highlightColor = 0xFF00E676;

    private OnElementTapListener tapListener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screenRect = new RectF();
    private final RectF highlightRect = new RectF();
    private final RectF softkeyRect = new RectF();

    private float touchDownX, touchDownY;

    public NokiaThemePreviewView(Context context) {
        super(context);
    }

    public void setOnElementTapListener(OnElementTapListener listener) {
        this.tapListener = listener;
    }

	public void setPhoneModel(int model) {
        this.phoneModel = model;
        requestLayout();
        invalidate();
    }
	

    public int getPhoneModel() {
        return phoneModel;
    }

    public void setThemeAssets(String title, Bitmap wallpaper, Bitmap menuBg,
                               List<Bitmap> iconBitmaps,
                               int idleColor, int menuColor, int softkeyColor, int hlColor) {
        this.themeTitle = title;
        this.wallpaperBitmap = wallpaper;
        this.menuBgBitmap = menuBg;
        this.themeIconBitmaps = (iconBitmaps != null) ? iconBitmaps : new ArrayList<Bitmap>();
        this.idleTextColor = idleColor;
        this.menuTextColor = menuColor;
        this.softkeyTextColor = softkeyColor;
        this.highlightColor = hlColor;
        invalidate();
    }

    public void updateColors(int idleColor, int menuColor, int softkeyColor, int hlColor) {
        this.idleTextColor = idleColor;
        this.menuTextColor = menuColor;
        this.softkeyTextColor = softkeyColor;
        this.highlightColor = hlColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (phoneModel == MODEL_E72) {
            drawNokiaE72Body(canvas, w, h);
        } else {
            drawNokiaN95Body(canvas, w, h);
        }

        drawSymbianScreenContent(canvas);
    }

    /**
     * Proporsi otentik Nokia E72: Bodi portrait ramping, LCD 4:3 lebar,
     * panel navigasi metalik (Home/Cal + Optical Trackpad + Contacts/Mail), & QWERTY bawah
     */
    private void drawNokiaE72Body(Canvas canvas, float w, float h) {
        float bodyH = h * 0.97f;
        float bodyW = Math.min(w * 0.88f, bodyH * 0.64f);
        float left = (w - bodyW) / 2f;
        float top = (h - bodyH) / 2f;
        RectF body = new RectF(left, top, left + bodyW, top + bodyH);

        // Bezel luar krom + bodi Topaz/Zodium Black
        paint.setColor(0xFF1B1E24);
        canvas.drawRoundRect(body, 36f, 36f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xFFB0BEC5);
        canvas.drawRoundRect(body, 36f, 36f, paint);
        paint.setStyle(Paint.Style.FILL);

        // Earpiece slot krom & tulisan NOKIA + E72
        float earW = bodyW * 0.24f;
        paint.setColor(0xFF90A4AE);
        canvas.drawRoundRect(new RectF(left + (bodyW - earW) / 2f, top + 10f, left + (bodyW + earW) / 2f, top + 17f), 4f, 4f, paint);

        paint.setColor(0xFFE0E0E0);
        paint.setTextSize(12f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("N O K I A", left + bodyW / 2f, top + 31f, paint);

        paint.setColor(0xFF90A4AE);
        paint.setTextSize(9.5f);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("E72", left + bodyW - bodyW * 0.08f, top + 31f, paint);

        // LCD Asli Nokia E72 (Rasio 320x240 = 4:3, diperbesar maksimal)
        float scrMargin = bodyW * 0.065f;
        float scrLeft = left + scrMargin;
        float scrRight = left + bodyW - scrMargin;
        float scrW = scrRight - scrLeft;
        float scrH = scrW * 0.75f; // Rasio 4:3 persis E72!
        float scrTop = top + 38f;
        screenRect.set(scrLeft, scrTop, scrRight, scrTop + scrH);

        // Panel Navigasi Metalik E72 tepat di bawah LCD
        float navTop = screenRect.bottom + 6f;
        float navH = bodyH * 0.16f;
        RectF navPanel = new RectF(scrLeft, navTop, scrRight, navTop + navH);
        paint.setColor(0xFF37414A);
        canvas.drawRoundRect(navPanel, 8f, 8f, paint);

        // Garis tombol Softkey kiri & kanan (aksen oranye/putih khas E72)
        paint.setColor(0xFFFFB74D);
        canvas.drawRoundRect(new RectF(scrLeft + 10f, navTop + 5f, scrLeft + scrW * 0.25f, navTop + 9f), 2f, 2f, paint);
        canvas.drawRoundRect(new RectF(scrRight - scrW * 0.25f, navTop + 5f, scrRight - 10f, navTop + 9f), 2f, 2f, paint);

        // Optical Navi Key kotak membulat di tengah panel metalik
        float cx = navPanel.centerX();
        float cy = navPanel.centerY();
        float naviSize = navH * 0.72f;
        paint.setColor(0xFFCFD8DC);
        canvas.drawRoundRect(new RectF(cx - naviSize / 2f, cy - naviSize / 2f, cx + naviSize / 2f, cy + naviSize / 2f), 10f, 10f, paint);
        paint.setColor(0xFF15181E);
        canvas.drawRoundRect(new RectF(cx - naviSize * 0.28f, cy - naviSize * 0.28f, cx + naviSize * 0.28f, cy + naviSize * 0.28f), 6f, 6f, paint);

        // Tombol QWERTY E72 di bagian bawah
        float qTop = navPanel.bottom + 6f;
        float qBottom = body.bottom - 12f;
        if (qBottom - qTop > 16f) {
            int cols = 10, rows = 4;
            float gap = 3f;
            float keyW = (scrW - (cols - 1) * gap) / cols;
            float keyH = ((qBottom - qTop) - (rows - 1) * gap) / rows;

            String[][] qwertyLabels = {
				{"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
				{"A", "S", "D", "F", "G", "H", "J", "K", "L", "⌫"},
				{"Z", "X", "C", "V", "B", "N", "M", ",", ".", "↵"},
				{"⇧", "Ctrl", "Chr", "☺", "", "", "Sym", "←", "↓", "→"}
            };

            // Angka dialpad putih di tengah QWERTY khas Nokia E72 (1-9, *, 0, #)
            String[][] numBadges = {
				{"", "", "", "1", "2", "3", "*", "", "", ""},
				{"", "", "", "4", "5", "6", "#", "", "", ""},
				{"", "", "", "7", "8", "9", "0", "", "", ""},
				{"", "", "", "", "", "", "", "", "", ""}
            };

            paint.setTextAlign(Paint.Align.CENTER);
            float keyFontSize = Math.max(7.5f, Math.min(keyW * 0.44f, keyH * 0.48f));

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (r == 3 && (c == 4 || c == 5)) continue; // Spacebar tengah

                    float kx = scrLeft + c * (keyW + gap);
                    float ky = qTop + r * (keyH + gap);
                    RectF keyRect = new RectF(kx, ky, kx + keyW, ky + keyH);

                    String num = numBadges[r][c];
                    // Tombol angka dialpad berwarna sedikit lebih terang khas E72
                    paint.setColor(num.isEmpty() ? 0xFF14171D : 0xFF232832);
                    canvas.drawRoundRect(keyRect, 4f, 4f, paint);

                    String lbl = qwertyLabels[r][c];
                    if (!lbl.isEmpty()) {
                        paint.setColor(num.isEmpty() ? 0xFFB0BEC5 : 0xFFFFD54F);
                        paint.setTextSize(keyFontSize);
                        canvas.drawText(lbl, keyRect.centerX(), keyRect.centerY() + keyFontSize * 0.35f, paint);
                    }
                }
            }

            // Spacebar E72 dengan ikon spasi
            float spLeft = scrLeft + 4 * (keyW + gap);
            float spRight = spLeft + 2 * keyW + gap;
            float spTop = qTop + 3 * (keyH + gap);
            RectF spRect = new RectF(spLeft, spTop, spRight, spTop + keyH);
            paint.setColor(0xFF14171D);
            canvas.drawRoundRect(spRect, 4f, 4f, paint);
            paint.setColor(0xFF78909C);
            paint.setTextSize(keyFontSize);
            canvas.drawText("␣", spRect.centerX(), spRect.centerY() + keyFontSize * 0.35f, paint);
        }
    }

    /**
     * Proporsi otentik Nokia N95: Bodi portrait tebal, LCD 240x320 (3:4) besar,
     * tombol Softkey + S60 Menu + Multimedia + D-Pad kotak ikonik N95
     */
	/**
     * Proporsi otentik Nokia N95: Bodi portrait silver/plum, LCD 240x320 (3:4),
     * dan Panel Kontrol N95 lengkap dengan ikon & label tombol aslinya!
     */
    private void drawNokiaN95Body(Canvas canvas, float w, float h) {
        float bodyH = h * 0.97f;
        float bodyW = Math.min(w * 0.84f, bodyH * 0.58f);
        float left = (w - bodyW) / 2f;
        float top = (h - bodyH) / 2f;
        RectF body = new RectF(left, top, left + bodyW, top + bodyH);

        // Bodi Silver/Plum khas Nokia N95
        paint.setColor(0xFF2B303A);
        canvas.drawRoundRect(body, 28f, 28f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xFF90A4AE);
        canvas.drawRoundRect(body, 28f, 28f, paint);
        paint.setStyle(Paint.Style.FILL);

        // Earpiece, Kamera Depan Video Call, & Logo NOKIA N95
        paint.setColor(0xFF121418);
        canvas.drawRoundRect(new RectF(left + bodyW * 0.36f, top + 10f, left + bodyW * 0.64f, top + 16f), 4f, 4f, paint);
        canvas.drawCircle(left + bodyW * 0.74f, top + 13f, 4f, paint); // Lensa kamera depan N95

        paint.setColor(0xFFE0E0E0);
        paint.setTextSize(11.5f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("N O K I A   N95", left + bodyW / 2f, top + 30f, paint);

        // LCD Asli Nokia N95 (Rasio 240x320 = 3:4 Portrait Besar)
        float scrMargin = bodyW * 0.065f;
        float scrLeft = left + scrMargin;
        float scrRight = left + bodyW - scrMargin;
        float scrW = scrRight - scrLeft;
        float scrH = Math.min(scrW * 1.24f, bodyH * 0.62f);
        float scrTop = top + 36f;
        screenRect.set(scrLeft, scrTop, scrRight, scrTop + scrH);

        // Panel Kontrol Bawah N95 (2 Baris Tombol di Kiri & Kanan + D-Pad Kotak Silver di Tengah)
        float padTop = screenRect.bottom + 8f;
        float padBottom = body.bottom - 12f;
        float padH = padBottom - padTop;
        float cx = left + bodyW / 2f;
        float cy = (padTop + padBottom) / 2f;
        float dpadSize = Math.min(bodyW * 0.30f, padH * 0.86f);

        // 1. D-Pad Kotak Membulat Khas N95 + Panah Navigasi Kiri/Kanan
        RectF dpadOuter = new RectF(cx - dpadSize / 2f, cy - dpadSize / 2f, cx + dpadSize / 2f, cy + dpadSize / 2f);
        paint.setColor(0xFFB0BEC5);
        canvas.drawRoundRect(dpadOuter, 12f, 12f, paint);

        // Tanda panah kecil pada cincin D-Pad
        paint.setColor(0xFF263238);
        paint.setTextSize(8.5f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("◀", dpadOuter.left + 7f, cy + 3f, paint);
        canvas.drawText("▶", dpadOuter.right - 7f, cy + 3f, paint);

        // Tombol Tengah (OK / Select)
        paint.setColor(0xFF1B1F26);
        canvas.drawRoundRect(new RectF(cx - dpadSize * 0.28f, cy - dpadSize * 0.28f, cx + dpadSize * 0.28f, cy + dpadSize * 0.28f), 7f, 7f, paint);

        // 2. Blok Tombol Kiri & Kanan (Masing-masing terbagi 2 tombol: atas & bawah)
        float sideW = (scrW - dpadSize) / 2f - 8f;
        float rowGap = 5f;
        float rowH = (padH - rowGap) / 2f;
        float subGap = 4f;
        float subBtnW = (sideW - subGap) / 2f;

        float leftBlockX = scrLeft;
        float rightBlockX = scrRight - sideW;

        // -- BARIS ATAS --
        // Kiri Atas: [Softkey Kiri (—)] & [Tombol S60 Menu (❖)]
        RectF rSoftLeft = new RectF(leftBlockX, padTop, leftBlockX + subBtnW, padTop + rowH);
        RectF rS60Menu = new RectF(leftBlockX + subBtnW + subGap, padTop, leftBlockX + sideW, padTop + rowH);

        // Kanan Atas: [Tombol Multimedia (♪)] & [Softkey Kanan (—)]
        RectF rMultiMedia = new RectF(rightBlockX, padTop, rightBlockX + subBtnW, padTop + rowH);
        RectF rSoftRight = new RectF(rightBlockX + subBtnW + subGap, padTop, rightBlockX + sideW, padTop + rowH);

        // -- BARIS BAWAH --
        float row2Top = padTop + rowH + rowGap;
        // Kiri Bawah: [Call Hijau (📞)] & [Tombol Edit (✎)]
        RectF rCall = new RectF(leftBlockX, row2Top, leftBlockX + subBtnW, padBottom);
        RectF rEdit = new RectF(leftBlockX + subBtnW + subGap, row2Top, leftBlockX + sideW, padBottom);

        // Kanan Bawah: [Tombol Clear (C)] & [End Merah (📵)]
        RectF rClear = new RectF(rightBlockX, row2Top, rightBlockX + subBtnW, padBottom);
        RectF rEnd = new RectF(rightBlockX + subBtnW + subGap, row2Top, rightBlockX + sideW, padBottom);

        // Gambar latar belakang seluruh tombol panel
        paint.setColor(0xFF3A414C);
        RectF[] allBtns = {rSoftLeft, rS60Menu, rMultiMedia, rSoftRight, rCall, rEdit, rClear, rEnd};
        for (RectF btn : allBtns) {
            canvas.drawRoundRect(btn, 5f, 5f, paint);
        }

        // Gambar ikon & label warna khas Nokia N95 di atas tiap tombol
        float btnFont = Math.max(8.5f, Math.min(subBtnW * 0.42f, rowH * 0.48f));
        paint.setTextSize(btnFont);
        paint.setTextAlign(Paint.Align.CENTER);

        // Garis Softkey Putih
        paint.setColor(0xFFECEFF1);
        canvas.drawText("—", rSoftLeft.centerX(), rSoftLeft.centerY() + btnFont * 0.35f, paint);
        canvas.drawText("—", rSoftRight.centerX(), rSoftRight.centerY() + btnFont * 0.35f, paint);

        // Ikon S60 Menu (Biru/Putih) & Multimedia (Oranye)
        paint.setColor(0xFF4FC3F7);
        canvas.drawText("❖", rS60Menu.centerX(), rS60Menu.centerY() + btnFont * 0.35f, paint);
        paint.setColor(0xFFFFB74D);
        canvas.drawText("♪", rMultiMedia.centerX(), rMultiMedia.centerY() + btnFont * 0.35f, paint);

        // Tombol Call (Hijau), Edit (✎), Clear (C), End (Merah)
        paint.setColor(0xFF00E676);
        canvas.drawText("📞", rCall.centerX(), rCall.centerY() + btnFont * 0.35f, paint);
        paint.setColor(0xFFCFD8DC);
        canvas.drawText("✎", rEdit.centerX(), rEdit.centerY() + btnFont * 0.35f, paint);
        canvas.drawText("C", rClear.centerX(), rClear.centerY() + btnFont * 0.35f, paint);
        paint.setColor(0xFFFF5252);
        canvas.drawText("📵", rEnd.centerX(), rEnd.centerY() + btnFont * 0.35f, paint);
    }
	

    private void drawSymbianScreenContent(Canvas canvas) {
        canvas.save();
        canvas.clipRect(screenRect);

        // 1. Gambar Wallpaper / Background Menu
        Bitmap activeBg = (currentPage == PAGE_MENU && menuBgBitmap != null) ? menuBgBitmap : wallpaperBitmap;
        if (activeBg != null && !activeBg.isRecycled()) {
            canvas.drawBitmap(activeBg, null, screenRect, paint);
        } else {
            paint.setColor(0xFF14284B);
            canvas.drawRect(screenRect, paint);
        }

        // 2. Top Status Bar Symbian (Seperti foto E72: Offline/Operator + Tanggal + Baterai)
        float statusH = 22f;
        paint.setColor(0x55000000);
        canvas.drawRect(screenRect.left, screenRect.top, screenRect.right, screenRect.top + statusH, paint);

        paint.setColor(idleTextColor);
        paint.setTextSize(11f);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(themeTitle, screenRect.left + 8f, screenRect.top + 15f, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("13/11/2026  🔋", screenRect.right - 8f, screenRect.top + 15f, paint);

        // 3. Halaman Layar
        if (currentPage == PAGE_IDLE) {
            // Layout Otentik Active Standby Nokia E72 / N95 (Seperti foto E72!)
            float topRowY = screenRect.top + statusH + 6f;
            float clockBoxSize = 44f;

            // Kotak Jam Analog di Kiri Atas
            RectF clockBox = new RectF(screenRect.left + 8f, topRowY, screenRect.left + 8f + clockBoxSize, topRowY + clockBoxSize);
            paint.setColor(0x66000000);
            canvas.drawRoundRect(clockBox, 6f, 6f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(idleTextColor);
            canvas.drawCircle(clockBox.centerX(), clockBox.centerY(), 16f, paint);
            canvas.drawLine(clockBox.centerX(), clockBox.centerY(), clockBox.centerX() + 8f, clockBox.centerY() - 8f, paint);
            canvas.drawLine(clockBox.centerX(), clockBox.centerY(), clockBox.centerX(), clockBox.centerY() - 11f, paint);
            paint.setStyle(Paint.Style.FILL);

            // Shortcut Bar Horizontal di Samping Jam
            float scLeft = clockBox.right + 8f;
            float scWidth = (screenRect.right - 8f - scLeft) / 5f;
            int[] scColors = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA};
            for (int i = 0; i < 5; i++) {
                float ix = scLeft + i * scWidth + 3f;
                float iy = topRowY + 6f;
                RectF icRect = new RectF(ix, iy, ix + scWidth - 6f, iy + 30f);
                if (i == 3) {
                    highlightRect.set(icRect);
                    paint.setColor(highlightColor);
                    canvas.drawRoundRect(icRect, 6f, 6f, paint);
                }
                paint.setColor(scColors[i]);
                canvas.drawCircle(icRect.centerX(), icRect.centerY(), 11f, paint);
            }

            // Daftar Baris Active Standby (Set up e-mail, Calendar, WLAN, Search)
            String[] standbyLines = {
				"@  Set up e-mail",
				"📅  No calendar entries today",
				"📶  WLAN scanning off",
				"🔍  Search"
            };
            float lineY = clockBox.bottom + 8f;
            float lineH = (screenRect.bottom - 24f - lineY) / 4f;
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(11.5f);
            for (int i = 0; i < standbyLines.length; i++) {
                float yTop = lineY + i * lineH;
                paint.setColor(0x33FFFFFF);
                canvas.drawLine(screenRect.left + 8f, yTop, screenRect.right - 8f, yTop, paint);
                paint.setColor(idleTextColor);
                canvas.drawText(standbyLines[i], screenRect.left + 12f, yTop + lineH * 0.65f, paint);
            }

        } else if (currentPage == PAGE_MENU) {
            // Grid Menu dengan Ikon Vektor Besar & Tajam!
            // E72 (Landscape 320x240) = 4 kolom x 3 baris (12 ikon, persis foto E72!)
            // N95 (Portrait 240x320)  = 3 kolom x 4 baris (12 ikon)
            int cols = (phoneModel == MODEL_E72) ? 4 : 3;
            int rows = (phoneModel == MODEL_E72) ? 3 : 4;

            String[] labels = {
				"Calendar", "Contacts", "Log", "Apps",
				"Messaging", "Internet", "Media", "Store",
				"Office", "Email", "Ctrl. panel", "Help"
            };
            int[] badgeColors = {
				0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFF3949AB,
				0xFFFDD835, 0xFF00ACC1, 0xFFD81B60, 0xFF039BE5,
				0xFFFB8C00, 0xFF8E24AA, 0xFFE53935, 0xFF1E88E5
            };

            float gridTop = screenRect.top + statusH + 4f;
            float gridH = screenRect.height() - statusH - 28f;
            float cellW = screenRect.width() / cols;
            float cellH = gridH / rows;

			for (int idx = 0; idx < 12; idx++) {
                int r = idx / cols;
                int c = idx % cols;
                float cx = screenRect.left + c * cellW;
                float cy = gridTop + r * cellH;
                RectF cell = new RectF(cx + 4f, cy + 3f, cx + cellW - 4f, cy + cellH - 3f);

                // Sorotan ikon terpilih (Messaging)
                if (idx == 4) {
                    highlightRect.set(cell);
                    paint.setColor(highlightColor);
                    canvas.drawRoundRect(cell, 10f, 10f, paint);
                }

                float iconSize = Math.min(cellW * 0.54f, cellH * 0.56f);
                float iconLeft = cell.centerX() - iconSize / 2f;
                float iconTop = cell.top + 4f;
                RectF iconBox = new RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);

                // Cek apakah ada Bitmap asli dari .MBM untuk slot ikon ke-idx ini
                // (Indeks 0 & 1 biasanya Wallpaper & MenuBG, jadi ikon dimulai dari indeks 2 dst.)
                int bmpIndex = idx + 2;
                Bitmap realIcon = (bmpIndex < themeIconBitmaps.size()) ? themeIconBitmaps.get(bmpIndex) : null;

                if (realIcon != null && !realIcon.isRecycled()) {
                    canvas.drawBitmap(realIcon, null, iconBox, paint);
                } else {
                    // Fallback jika jumlah gambar di .mbm kurang dari 14
                    paint.setColor(badgeColors[idx]);
                    canvas.drawRoundRect(iconBox, 9f, 9f, paint);

                    paint.setColor(Color.WHITE);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(iconSize * 0.46f);
                    String[] shortGlyphs = {"12", "👤", "↕", "❖", "✉", "🌐", "♪", "🛍", "📄", "@", "⚙", "?"};
                    canvas.drawText(shortGlyphs[idx], iconBox.centerX(), iconBox.centerY() + iconSize * 0.17f, paint);
                }

                // Label Teks Menu di bawah ikon
                paint.setColor(idx == 4 ? Color.BLACK : menuTextColor);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(10f);
                canvas.drawText(labels[idx], cell.centerX(), cell.bottom - 4f, paint);
            }
			

        } else {
            // Halaman 3: Pop-up Dialog Symbian
            float dlgW = screenRect.width() * 0.84f;
            float dlgH = screenRect.height() * 0.48f;
            float dl = screenRect.centerX() - dlgW / 2f;
            float dt = screenRect.centerY() - dlgH / 2f;
            highlightRect.set(dl, dt, dl + dlgW, dt + dlgH);

            paint.setColor(0xEE181D26);
            canvas.drawRoundRect(highlightRect, 12f, 12f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(highlightColor);
            canvas.drawRoundRect(highlightRect, 12f, 12f, paint);
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(idleTextColor);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(13f);
            canvas.drawText("Pratinjau Dialog Tema", highlightRect.centerX(), highlightRect.top + 30f, paint);
            paint.setTextSize(11f);
            canvas.drawText("Geser layar <- -> untuk pindah", highlightRect.centerX(), highlightRect.top + 52f, paint);
        }

        // 4. Bottom Softkey Bar (Options | 13:35 | Exit)
        float skH = 22f;
        softkeyRect.set(screenRect.left, screenRect.bottom - skH, screenRect.right, screenRect.bottom);
        paint.setColor(0x66000000);
        canvas.drawRect(softkeyRect, paint);

        paint.setColor(softkeyTextColor);
        paint.setTextSize(11.5f);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(currentPage == PAGE_IDLE ? "Menu" : "Options", softkeyRect.left + 8f, softkeyRect.bottom - 6f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("13:35  (" + (currentPage + 1) + "/3)", softkeyRect.centerX(), softkeyRect.bottom - 6f, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(currentPage == PAGE_IDLE ? "Bluetooth" : "Exit", softkeyRect.right - 8f, softkeyRect.bottom - 6f, paint);

        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;

                if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy)) {
                    if (dx < 0) {
                        currentPage = (currentPage + 1) % 3;
                    } else {
                        currentPage = (currentPage + 2) % 3;
                    }
                    invalidate();
                    if (tapListener != null) tapListener.onPageChanged(currentPage);
                    return true;
                }

                if (Math.abs(dx) < 15 && Math.abs(dy) < 15 && screenRect.contains(event.getX(), event.getY())) {
                    if (tapListener != null) {
                        if (highlightRect.contains(event.getX(), event.getY())) {
                            tapListener.onTapHighlightColor();
                        } else if (softkeyRect.contains(event.getX(), event.getY())) {
                            tapListener.onTapTextColor(currentPage);
                        } else {
                            tapListener.onTapWallpaper();
                        }
                    }
                    return true;
                }
                break;
        }
        return super.onTouchEvent(event);
    }
}

