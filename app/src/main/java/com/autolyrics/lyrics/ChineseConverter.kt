package com.autolyrics.lyrics

import android.annotation.SuppressLint
import android.os.Build
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord

/**
 * Simplified <-> Traditional Chinese conversion using the ICU transliterator
 * that ships with Android (API 29+). Character-level only, so a few
 * one-to-many characters (e.g. 发 → 發/髮) may not always be perfect.
 * Falls back to returning the input unchanged when ICU is unavailable.
 */
object ChineseConverter {

    private val toTraditional: Any? by lazy { create("Hans-Hant") }
    private val toSimplified: Any? by lazy { create("Hant-Hans") }

    private val HAN = Regex("[㐀-鿿豈-﫿]")

    val isAvailable: Boolean
        get() = toTraditional != null

    @SuppressLint("NewApi")
    private fun create(id: String): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            android.icu.text.Transliterator.getInstance(id)
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("NewApi")
    private fun apply(transliterator: Any?, text: String): String {
        if (transliterator == null || text.isEmpty() || !HAN.containsMatchIn(text)) return text
        val t = transliterator as android.icu.text.Transliterator
        return try {
            synchronized(t) { t.transliterate(text) }
        } catch (_: Throwable) {
            text
        }
    }

    fun toTraditional(text: String): String = apply(toTraditional, text)

    fun toSimplified(text: String): String = apply(toSimplified, text)

    fun linesToTraditional(lines: List<LyricLine>): List<LyricLine> = lines.map { line ->
        line.copy(
            text = toTraditional(line.text),
            words = line.words.map { w -> LyricWord(w.timeMs, toTraditional(w.text)) }
        )
    }
}
