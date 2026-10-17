package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** What happened to a translation request; [message] is shown on the phone. */
sealed class TranslationOutcome(val message: String) {
    class Done(val translatedLines: List<String>, val detectedLanguage: String, message: String) :
        TranslationOutcome(message)
    class Skipped(message: String) : TranslationOutcome(message)
    class Failed(message: String) : TranslationOutcome(message)
}

object LyricsTranslator {

    /** Target languages the user can pick: tag → name shown in settings. */
    val TARGETS = linkedMapOf(
        "zh-TW" to "繁體中文",
        "zh-CN" to "簡體中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어"
    )
    const val DEFAULT_TARGET = "zh-TW"

    private const val SAMPLE_LINES = 15

    private val LANGUAGE_NAMES = mapOf(
        "zh" to "中文", "en" to "英文", "ja" to "日文", "ko" to "韓文", "es" to "西班牙文",
        "fr" to "法文", "de" to "德文", "it" to "義大利文", "pt" to "葡萄牙文", "ru" to "俄文",
        "th" to "泰文", "vi" to "越南文", "id" to "印尼文", "ms" to "馬來文", "tl" to "菲律賓文",
        "hi" to "印地文", "ar" to "阿拉伯文", "tr" to "土耳其文", "nl" to "荷蘭文"
    )

    fun targetName(tag: String) = TARGETS[tag] ?: tag

    private fun languageName(code: String) = LANGUAGE_NAMES[code.substringBefore('-')] ?: code

    /**
     * Detects the lyrics language and translates every line into [target]
     * (a key of [TARGETS]). [onProgress] reports steps for the phone hint.
     */
    suspend fun translateLines(
        lines: List<LyricLine>,
        target: String,
        onProgress: suspend (String) -> Unit
    ): TranslationOutcome {
        val sampleText = lines
            .map { it.text }
            .filter { it.isNotBlank() && it != "♪" }
            .take(SAMPLE_LINES)
            .joinToString("\n")
        if (sampleText.isBlank()) return TranslationOutcome.Skipped("沒有可翻譯的歌詞")

        onProgress("翻譯：偵測歌詞語言中…")
        val langCode = detectLanguage(sampleText)
            ?: return TranslationOutcome.Failed("翻譯：無法偵測歌詞語言")
        if (langCode == "und") return TranslationOutcome.Skipped("翻譯：無法判斷歌詞語言，不翻譯")

        val targetBase = target.substringBefore('-')
        if (langCode.substringBefore('-') == targetBase) {
            return TranslationOutcome.Skipped("翻譯：歌詞已經是${languageName(langCode)}，不翻譯")
        }

        val sourceLang = TranslateLanguage.fromLanguageTag(langCode.substringBefore('-'))
            ?: return TranslationOutcome.Skipped("翻譯：不支援${languageName(langCode)}")
        val targetLang = TranslateLanguage.fromLanguageTag(targetBase)
            ?: return TranslationOutcome.Failed("翻譯：不支援目標語言 ${targetName(target)}")

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
        )
        try {
            onProgress("翻譯：準備翻譯模型中…（第一次使用要下載，每種語言約 30MB）")
            val modelReady = suspendCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }
            if (!modelReady) return TranslationOutcome.Failed("翻譯：模型下載失敗，請檢查網路後換首歌再試")

            onProgress("翻譯：${languageName(langCode)} → ${targetName(target)} 翻譯中…")
            val translated = lines.map { line ->
                val text = line.text.trim()
                if (text.isBlank() || text == "♪") {
                    ""
                } else {
                    val out = suspendCoroutine { cont ->
                        translator.translate(text)
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { cont.resume(text) }
                    }
                    // ML Kit's Chinese output is Simplified.
                    if (target == "zh-TW") ChineseConverter.toTraditional(out) else out
                }
            }
            return TranslationOutcome.Done(
                translated, langCode,
                "翻譯：${languageName(langCode)} → ${targetName(target)}"
            )
        } finally {
            translator.close()
        }
    }

    private suspend fun detectLanguage(text: String): String? {
        val identifier = LanguageIdentification.getClient()
        return try {
            suspendCoroutine { cont ->
                identifier.identifyLanguage(text)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } finally {
            identifier.close()
        }
    }
}
