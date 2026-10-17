package com.autolyrics.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

/** Colors of one palette in one mode (dark or light). */
data class Palette(
    val dark: Boolean,
    val bgTop: Int,
    val bgMid: Int,
    val bgBottom: Int,
    val aurora1: Int,
    val aurora2: Int,
    /** Main text. */
    val text: Int,
    /** Secondary text (artist, labels). */
    val sub: Int,
    /** Lyric lines that are not playing, translations. */
    val dim: Int,
    /** Frosted glass fill and its 1dp edge. */
    val card: Int,
    val border: Int,
    /** The line being sung. */
    val accent: Int,
    val accentSub: Int,
    /** Selected chip / switch. */
    val chip: Int,
    val chipBorder: Int,
    /** Breathing glow behind the album art. */
    val halo: Int,
    /** Small hints (translation status). */
    val hint: Int
)

/**
 * App appearance: mode (深色 / 淺色 / 跟隨系統) and palette (靛紫 / 深海 / 夜空 / 石墨),
 * both chosen in the phone settings panel.
 */
object Themes {

    const val PREF_MODE = "ui_mode"
    const val PREF_PALETTE = "ui_palette"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"
    const val MODE_SYSTEM = "system"

    val MODES = linkedMapOf(MODE_DARK to "深色", MODE_LIGHT to "淺色", MODE_SYSTEM to "跟隨系統")
    val PALETTES = linkedMapOf("indigo" to "靛紫", "ocean" to "深海", "midnight" to "夜空", "graphite" to "石墨")

    private fun prefs(context: Context) =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)

    fun mode(context: Context): String = prefs(context).getString(PREF_MODE, null) ?: MODE_DARK
    fun paletteId(context: Context): String = prefs(context).getString(PREF_PALETTE, null) ?: "indigo"

    /** Tells AppCompat (dialogs, switches) which mode to use; activities recreate on change. */
    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(context)) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(PREF_MODE, mode).apply()
        applyNightMode(context)
    }

    fun setPalette(context: Context, id: String) {
        prefs(context).edit().putString(PREF_PALETTE, id).apply()
    }

    /** The palette for this context's current (resolved) night mode. */
    fun current(context: Context): Palette {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode(context)) {
            MODE_DARK -> true
            MODE_LIGHT -> false
            else -> night
        }
        return palette(paletteId(context), dark)
    }

    private fun c(hex: String) = Color.parseColor(hex)
    private fun c(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255).toInt(), r, g, b)
    private val WHITE_TEXT = c(255, 255, 255, 0.92f)
    private val WHITE_SUB = c(255, 255, 255, 0.66f)
    private val WHITE_DIM = c(255, 255, 255, 0.46f)

    fun palette(id: String, dark: Boolean): Palette = when (id) {
        "ocean" -> if (dark) Palette(
            true, c("#06182A"), c("#0A2C44"), c("#0F3E4E"),
            c(110, 215, 220, 0.26f), c(110, 160, 255, 0.20f),
            WHITE_TEXT, WHITE_SUB, WHITE_DIM, c(255, 255, 255, 0.07f), c(255, 255, 255, 0.15f),
            c("#F3DFAE"), c(243, 223, 174, 0.82f), c(130, 220, 225, 0.22f), c(130, 220, 225, 0.55f),
            c(130, 220, 225, 0.50f), c("#EAD9AE")
        ) else Palette(
            false, c("#F0F8F9"), c("#DFF0F2"), c("#CFE5EA"),
            c(60, 180, 190, 0.20f), c(80, 130, 230, 0.14f),
            c("#0C2C3A"), c(12, 44, 58, 0.72f), c(12, 44, 58, 0.55f), c(255, 255, 255, 0.55f), c(255, 255, 255, 0.90f),
            c("#0F6F7A"), c(15, 111, 122, 0.85f), c(15, 111, 122, 0.12f), c(15, 111, 122, 0.45f),
            c(60, 170, 180, 0.35f), c("#7A5A14")
        )
        "midnight" -> if (dark) Palette(
            true, c("#090D22"), c("#121A3C"), c("#1B2853"),
            c(140, 170, 255, 0.26f), c(200, 215, 240, 0.14f),
            WHITE_TEXT, WHITE_SUB, WHITE_DIM, c(255, 255, 255, 0.07f), c(255, 255, 255, 0.15f),
            c("#EEF3FF"), c(200, 215, 250, 0.85f), c(169, 200, 255, 0.22f), c(169, 200, 255, 0.55f),
            c(160, 185, 255, 0.50f), c("#C9D3EA")
        ) else Palette(
            false, c("#F3F5FB"), c("#E5EAF6"), c("#D8DFF1"),
            c(110, 140, 240, 0.20f), c(160, 180, 220, 0.18f),
            c("#141C3C"), c(20, 28, 60, 0.72f), c(20, 28, 60, 0.55f), c(255, 255, 255, 0.55f), c(255, 255, 255, 0.90f),
            c("#2F4FB0"), c(47, 79, 176, 0.85f), c(47, 79, 176, 0.12f), c(47, 79, 176, 0.45f),
            c(110, 140, 240, 0.35f), c("#3D4A78")
        )
        "graphite" -> if (dark) Palette(
            true, c("#111216"), c("#1A1C21"), c("#24262C"),
            c(255, 200, 140, 0.14f), c(150, 200, 220, 0.10f),
            WHITE_TEXT, WHITE_SUB, WHITE_DIM, c(255, 255, 255, 0.06f), c(255, 255, 255, 0.13f),
            c("#F2D9A0"), c(242, 217, 160, 0.82f), c(240, 215, 160, 0.18f), c(240, 215, 160, 0.50f),
            c(240, 210, 150, 0.45f), c(255, 255, 255, 0.62f)
        ) else Palette(
            false, c("#F7F6F3"), c("#EEECE7"), c("#E3E1DB"),
            c(230, 170, 100, 0.16f), c(120, 170, 190, 0.12f),
            c("#24262C"), c(36, 38, 44, 0.72f), c(36, 38, 44, 0.55f), c(255, 255, 255, 0.60f), c(255, 255, 255, 0.95f),
            c("#8A5A12"), c(138, 90, 18, 0.85f), c(138, 90, 18, 0.12f), c(138, 90, 18, 0.45f),
            c(220, 170, 100, 0.35f), c("#5A4A30")
        )
        else -> if (dark) Palette(
            true, c("#140F3A"), c("#24185A"), c("#3B1D6E"),
            c(180, 150, 255, 0.30f), c(120, 230, 210, 0.20f),
            WHITE_TEXT, WHITE_SUB, WHITE_DIM, c(255, 255, 255, 0.08f), c(255, 255, 255, 0.16f),
            c("#F6DFA4"), c(246, 223, 164, 0.82f), c(196, 176, 255, 0.26f), c(196, 176, 255, 0.60f),
            c(200, 180, 255, 0.50f), c("#E8D7A8")
        ) else Palette(
            false, c("#F6F3FD"), c("#EBE4F8"), c("#DED4F3"),
            c(160, 130, 240, 0.22f), c(100, 200, 190, 0.16f),
            c("#241A4A"), c(36, 26, 74, 0.72f), c(36, 26, 74, 0.55f), c(255, 255, 255, 0.55f), c(255, 255, 255, 0.90f),
            c("#5A3CC0"), c(90, 60, 192, 0.85f), c(120, 90, 220, 0.14f), c(120, 90, 220, 0.50f),
            c(150, 120, 240, 0.40f), c("#7A5A14")
        )
    }
}
