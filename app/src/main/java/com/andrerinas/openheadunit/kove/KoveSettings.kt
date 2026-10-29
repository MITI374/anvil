package com.andrerinas.openheadunit.kove

import android.content.Context

/**
 * How much of the TFT picture the motorcycle covers with its own instrument strips
 * (clock and gear at the top, rev counter, fuel and warning lights at the bottom).
 * Android Auto is laid out only in the part that stays visible.
 */
object KoveSettings {

    private const val PREFS = "kove"
    private const val KEY_TOP = "tft_hidden_top"
    private const val KEY_BOTTOM = "tft_hidden_bottom"
    private const val KEY_WIDTH = "tft_width_percent"

    // Estimated from photos of a Kove 800X; adjusted by the user in the calibration screen.
    const val DEFAULT_TOP = 100
    const val DEFAULT_BOTTOM = 130
    const val MAX_HIDDEN = 350

    /**
     * Below zero the bottom value cuts the bottom of Android Auto off instead: Android Auto still
     * lays out its widgets and app bar there, but they end below the display edge, so the map
     * gets that space. The phone keeps showing them.
     */
    const val MIN_BOTTOM = -400

    /**
     * How wide the picture looks on the TFT compared with the phone, in percent. Panels whose pixels
     * are not square make circles look like eggs; Android Auto is then laid out for the shape the
     * rider actually sees. 100 = square pixels.
     */
    const val DEFAULT_WIDTH = 100
    const val MIN_WIDTH = 75
    const val MAX_WIDTH = 135

    @Volatile var hiddenTop = DEFAULT_TOP
        private set
    @Volatile var hiddenBottom = DEFAULT_BOTTOM
        private set
    @Volatile var widthPercent = DEFAULT_WIDTH
        private set

    /** Horizontal stretch the TFT applies to a pixel, as a factor (1.0 = square pixels). */
    val pixelStretch: Float get() = widthPercent / 100f

    /** True while the calibration screen is open: the visible area is outlined on the TFT. */
    @Volatile var calibrating = false

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hiddenTop = p.getInt(KEY_TOP, DEFAULT_TOP).coerceIn(0, MAX_HIDDEN)
        hiddenBottom = p.getInt(KEY_BOTTOM, DEFAULT_BOTTOM).coerceIn(MIN_BOTTOM, MAX_HIDDEN)
        widthPercent = p.getInt(KEY_WIDTH, DEFAULT_WIDTH).coerceIn(MIN_WIDTH, MAX_WIDTH)
        badgeCorner = p.getInt(KEY_BADGE, 3).coerceIn(0, 4)
    }

    fun save(context: Context, top: Int, bottom: Int, width: Int) {
        hiddenTop = top.coerceIn(0, MAX_HIDDEN)
        hiddenBottom = bottom.coerceIn(MIN_BOTTOM, MAX_HIDDEN)
        widthPercent = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TOP, hiddenTop)
            .putInt(KEY_BOTTOM, hiddenBottom)
            .putInt(KEY_WIDTH, widthPercent)
            .apply()
    }

    private const val KEY_BADGE = "badge_corner"

    /** Range/consumption badge: 0 off, 1 top-left, 2 top-right, 3 bottom-left, 4 bottom-right. */
    @Volatile var badgeCorner = 3
        private set

    fun loadBadge(context: Context) {
        badgeCorner = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BADGE, 3).coerceIn(0, 4)
    }

    fun saveBadge(context: Context, corner: Int) {
        badgeCorner = corner.coerceIn(0, 4)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BADGE, badgeCorner).apply()
    }

    private const val KEY_SSID = "tft_wifi_ssid"

    /** Name of the motorcycle's Wi-Fi once the user has picked it, so it can be joined without asking. */
    fun tftSsid(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SSID, null)

    fun saveTftSsid(context: Context, ssid: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SSID, ssid).apply()
    }

    /** Rows of the TFT covered by its bottom strip (never negative). */
    val coveredBottom: Int get() = hiddenBottom.coerceAtLeast(0)

    /** Rows of Android Auto cut off below the display edge (the negative part of the bottom value). */
    val cutBottom: Int get() = (-hiddenBottom).coerceAtLeast(0)

    /**
     * Height Android Auto lays out for, in TFT rows: the visible band plus whatever is cut off below it.
     */
    fun visibleHeight(tftHeight: Int): Int = (tftHeight - hiddenTop - hiddenBottom).coerceAtLeast(tftHeight / 3)
}
