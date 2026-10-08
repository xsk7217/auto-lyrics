package com.autolyrics.lyrics.providers

import android.content.SharedPreferences
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo

/**
 * The list of lyrics sources, in the order the user arranged them, and which
 * ones are enabled. Saved in SharedPreferences as "id:1,id:0,…".
 */
object LyricsProviders {

    const val PREF_KEY = "lyrics_providers"

    /** Default order: Chinese sources first, then global ones. All enabled. */
    val all: List<LyricsProvider> = listOf(
        NetEaseProvider,
        QQMusicProvider,
        KuGouProvider,
        LrcLibProvider,
        SyncLrcProvider,
        MusixmatchProvider,
        YouTubeMusicProvider
    )

    data class Entry(val provider: LyricsProvider, val enabled: Boolean)

    fun defaults(): List<Entry> = all.map { Entry(it, true) }

    fun load(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(PREF_KEY, null) ?: return defaults()
        val byId = all.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val entries = raw.split(',').mapNotNull { token ->
            val parts = token.split(':')
            val provider = byId[parts.getOrNull(0)] ?: return@mapNotNull null
            if (!seen.add(provider.id)) return@mapNotNull null
            Entry(provider, parts.getOrNull(1) != "0")
        }.toMutableList()
        // Providers added in a later version are appended, enabled.
        all.filter { it.id !in seen }.forEach { entries += Entry(it, true) }
        return entries
    }

    fun save(prefs: SharedPreferences, entries: List<Entry>) {
        val raw = entries.joinToString(",") { "${it.provider.id}:${if (it.enabled) 1 else 0}" }
        prefs.edit().putString(PREF_KEY, raw).apply()
    }

    /**
     * Tries enabled providers in order. Returns the first synced result; if
     * only plain lyrics are found anywhere, returns the first plain one.
     * When nothing at all is found, retries with cleaned-up / split titles
     * (uploaded videos like "歌手 - 歌名『歌詞』").
     */
    fun fetch(prefs: SharedPreferences, track: TrackInfo): LyricsResult? {
        val entries = load(prefs)
        fetchOnce(entries, track)?.let { return it }
        var plain: LyricsResult? = null
        for (variant in TitleVariants.of(track)) {
            val result = fetchOnce(entries, variant) ?: continue
            if (result.status == LyricsStatus.FOUND) return result
            if (plain == null) plain = result
        }
        return plain
    }

    private fun fetchOnce(entries: List<Entry>, track: TrackInfo): LyricsResult? {
        var plain: LyricsResult? = null
        for (entry in entries) {
            if (!entry.enabled) continue
            val result = try {
                entry.provider.fetch(track)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: continue
            if (result.status == LyricsStatus.FOUND) return result
            if (plain == null) plain = result
        }
        return plain
    }
}
