package com.autolyrics.model

import android.graphics.Bitmap

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

data class LyricWord(
    val timeMs: Long,
    val text: String
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class AlbumColors(
    val dominant: Int,
    val dominantDark: Int,
    val vibrant: Int,
    val textPrimary: Int,
    val textDim: Int
)

enum class LyricsStatus {
    NO_MEDIA,
    LOADING,
    FOUND,
    PLAIN_ONLY,
    NOT_FOUND,
    ERROR
}

data class LyricsState(
    val track: TrackInfo? = null,
    val lines: List<LyricLine> = emptyList(),
    val currentIndex: Int = -1,
    val currentWordIndex: Int = -1,
    val isPlaying: Boolean = false,
    val status: LyricsStatus = LyricsStatus.NO_MEDIA,
    val source: String = "",
    val offsetMs: Long = 0,
    val albumArt: Bitmap? = null,
    val albumColors: AlbumColors? = null,
    val translatedLines: List<String>? = null,
    val detectedLanguage: String? = null,
    /** "換來源重抓" is running. */
    val switchingSource: Boolean = false,
    /** Outcome of the last "換來源重抓" for this track, e.g. "已改用 QQ 音樂". */
    val switchMessage: String? = null,
    /** Phone hint about translation, e.g. "翻譯：日文 → 繁體中文". */
    val translationStatus: String? = null
)
