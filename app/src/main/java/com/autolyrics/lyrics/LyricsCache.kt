package com.autolyrics.lyrics

import android.content.Context
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * Lyrics stored on disk per (title, artist). Files are named by a SHA-1 of the
 * key and the key is stored inside, so two keys never share a file.
 */
class LyricsCache(context: Context) {

    private val cacheDir = File(context.filesDir, "lyrics_cache_v2")
    private val gson = Gson()

    init {
        cacheDir.mkdirs()
        // The old cache (32-bit hash names, no duration) is dropped.
        try { File(context.filesDir, "lyrics_cache").deleteRecursively() } catch (_: Exception) { }
    }

    data class CachedResult(
        val key: String?,
        val lines: List<CachedLine>,
        val status: String,
        val source: String,
        val timestamp: Long,
        /** False for prefetched lyrics: not yet confirmed while the song played. */
        val checked: Boolean = true
    )

    data class CachedLine(
        val timeMs: Long,
        val text: String,
        val words: List<CachedWord>
    )

    data class CachedWord(
        val timeMs: Long,
        val text: String
    )

    data class Entry(
        val lines: List<LyricLine>,
        val status: LyricsStatus,
        val source: String,
        val ageMs: Long,
        val checked: Boolean
    )

    fun get(title: String, artist: String): Entry? {
        val key = key(title, artist)
        val file = cacheFile(key)
        if (!file.exists()) return null

        return try {
            val cached = gson.fromJson(file.readText(), CachedResult::class.java)
            if (cached.key != key) return null
            val status = try {
                LyricsStatus.valueOf(cached.status)
            } catch (_: Exception) {
                return null
            }
            val lines = cached.lines.map { cl ->
                LyricLine(
                    timeMs = cl.timeMs,
                    text = cl.text,
                    words = cl.words.map { cw -> LyricWord(cw.timeMs, cw.text) }
                )
            }
            Entry(lines, status, cached.source, System.currentTimeMillis() - cached.timestamp, cached.checked)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun put(
        title: String,
        artist: String,
        lines: List<LyricLine>,
        status: LyricsStatus,
        source: String,
        checked: Boolean = true
    ) {
        try {
            val key = key(title, artist)
            val cached = CachedResult(
                key = key,
                lines = lines.map { line ->
                    CachedLine(
                        timeMs = line.timeMs,
                        text = line.text,
                        words = line.words.map { w -> CachedWord(w.timeMs, w.text) }
                    )
                },
                status = status.name,
                source = source,
                timestamp = System.currentTimeMillis(),
                checked = checked
            )
            cacheFile(key).writeText(gson.toJson(cached))
        } catch (_: Exception) {
            // cache write failures are non-fatal
        }
    }

    fun remove(title: String, artist: String) {
        try { cacheFile(key(title, artist)).delete() } catch (_: Exception) { }
    }

    fun clear() {
        try { cacheDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
    }

    private fun key(title: String, artist: String) = "${title.lowercase().trim()}|${artist.lowercase().trim()}"

    private fun cacheFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }
}
