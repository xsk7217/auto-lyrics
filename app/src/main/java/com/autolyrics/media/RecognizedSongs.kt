package com.autolyrics.media

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Songs recognised by Pixel "Now Playing" (聽聲辨曲), read from its notification,
 * e.g. title "「Gratitude (Live)」，演出者：Brandon Lake". Newest first, at most 10.
 */
object RecognizedSongs {

    data class Song(val title: String, val artist: String, val timeMs: Long)

    private const val PREFS = "auto_lyrics_prefs"
    private const val KEY = "recognized_songs"
    private const val MAX = 10

    /** Android System Intelligence (Now Playing) and older/related Google packages. */
    private val NOW_PLAYING_PACKAGES = setOf(
        "com.google.android.as",
        "com.google.intelligence.sense",
        "com.google.android.googlequicksearchbox"
    )

    /** 「歌名」，演出者：歌手 (Chinese locales). Specific enough to accept from any app. */
    private val CJK_PATTERN = Regex("""^[「“"『](.+)[」”"』]\s*[，,]\s*(?:演出者|演唱者|歌手|藝人|艺人)\s*[：:]\s*(.+)$""")
    /** "Song by Artist" (English). Too generic, so only from Now Playing packages. */
    private val EN_PATTERN = Regex("""^(.+?)\s+by\s+(.+)$""")

    var listener: (() -> Unit)? = null

    fun onNotification(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == context.packageName) return
        val extras = sbn.notification?.extras ?: return
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString()?.trim() }
        val trusted = sbn.packageName in NOW_PLAYING_PACKAGES
        for (text in texts) {
            val song = parse(text, trusted) ?: continue
            add(context, song.first, song.second)
            return
        }
    }

    fun parse(text: String, trustedPackage: Boolean): Pair<String, String>? {
        CJK_PATTERN.matchEntire(text)?.let { m ->
            return m.groupValues[1].trim() to m.groupValues[2].trim()
        }
        if (trustedPackage) {
            EN_PATTERN.matchEntire(text)?.let { m ->
                return m.groupValues[1].trim() to m.groupValues[2].trim()
            }
        }
        return null
    }

    fun list(context: Context): List<Song> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Song(o.optString("title"), o.optString("artist"), o.optLong("time"))
                    .takeIf { it.title.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun add(context: Context, title: String, artist: String) {
        if (title.isBlank()) return
        val songs = list(context).filterNot { it.title == title && it.artist == artist }.toMutableList()
        songs.add(0, Song(title, artist, System.currentTimeMillis()))
        val arr = JSONArray()
        songs.take(MAX).forEach {
            arr.put(JSONObject().put("title", it.title).put("artist", it.artist).put("time", it.timeMs))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        listener?.invoke()
    }
}
