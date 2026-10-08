package com.autolyrics.lyrics.providers

import com.autolyrics.lyrics.ChineseConverter
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Result of a lyrics lookup from any provider. */
data class LyricsResult(
    val lines: List<LyricLine>,
    val status: LyricsStatus,
    val source: String
)

/** A single lyrics source the user can enable / reorder. */
interface LyricsProvider {
    /** Stable key saved in preferences — never change it. */
    val id: String
    val displayName: String
    val description: String

    /** Blocking network call; run on Dispatchers.IO. Returns null when nothing usable is found. */
    fun fetch(track: TrackInfo): LyricsResult?
}

internal object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun get(url: HttpUrl, headers: Map<String, String> = emptyMap(), c: OkHttpClient = client): String? {
        val req = Request.Builder().url(url).header("User-Agent", BROWSER_UA)
        headers.forEach { (k, v) -> req.header(k, v) }
        return execute(req.build(), c)
    }

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String? {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).post(body).header("User-Agent", BROWSER_UA)
        headers.forEach { (k, v) -> req.header(k, v) }
        return execute(req.build(), client)
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? {
        val req = Request.Builder().url(url).post(json.toRequestBody(JSON_TYPE))
            .header("User-Agent", BROWSER_UA)
        headers.forEach { (k, v) -> req.header(k, v) }
        return execute(req.build(), client)
    }

    private fun execute(request: Request, c: OkHttpClient): String? = try {
        c.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    /** Parses a JSON object, tolerating JSONP wrappers like `callback({...})`. */
    fun jsonObject(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }
}

/** Builds [LyricsResult]s from raw LRC / plain text. */
internal object LyricsResults {

    private val ANY_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Rewrites timestamps like [0:5.1] or [00:05:10] into the [mm:ss.xxx] form LrcParser expects. */
    fun normalizeLrc(lrc: String): String = ANY_TIMESTAMP.replace(lrc) { m ->
        val min = m.groupValues[1].toLongOrNull() ?: 0
        val sec = m.groupValues[2].toLongOrNull() ?: 0
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        if (min > 99) m.value else "[%02d:%02d.%03d]".format(min, sec, ms)
    }

    fun hasRealText(lines: List<LyricLine>) = lines.any { it.text != "♪" && it.text.isNotBlank() }

    /** Synced if the text has timestamps, otherwise plain (or null when empty). */
    fun fromLrcOrPlain(text: String?, label: String): LyricsResult? {
        if (text.isNullOrBlank()) return null
        val synced = LrcParser.parse(normalizeLrc(text))
        if (hasRealText(synced)) return LyricsResult(synced, LyricsStatus.FOUND, "$label · Synced")
        return fromPlain(text.replace(ANY_TIMESTAMP, ""), label)
    }

    fun fromPlain(text: String?, label: String): LyricsResult? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("""^\[[a-zA-Z]+:.*]$""")) }
            .map { LyricLine(0L, it) }
        return if (lines.isNotEmpty()) LyricsResult(lines, LyricsStatus.PLAIN_ONLY, "$label · Plain") else null
    }
}

/** A search hit from a provider, used to make sure we fetch the right song. */
internal data class Candidate<T>(
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
    val payload: T
)

internal object TrackMatcher {

    private val BRACKETS = Regex("""[(\[（【「].*?[)\]）】」]""")
    private val NOISE = Regex("""[\p{P}\p{S}\s]+""")
    private val ARTIST_SPLIT = Regex("""\s*(?:,|、|&|/|;|，|\bfeat\.?|\bft\.?|\bx\b|\band\b|和|與|与)\s*""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("""<[^>]+>""")

    fun stripTags(s: String) = TAGS.replace(s, "")

    fun norm(s: String): String {
        val simplified = ChineseConverter.toSimplified(stripTags(s)).lowercase()
        val noBrackets = BRACKETS.replace(simplified, "")
        val base = NOISE.replace(noBrackets, "")
        return base.ifBlank { NOISE.replace(simplified, "") }
    }

    private fun titleOk(trackTitle: String, candTitle: String): Boolean {
        val a = norm(trackTitle)
        val b = norm(candTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // Allow small extras ("太陽與地球" vs "太陽與地球 鋼琴版") but not a different,
        // shorter song name ("太陽" vs "太陽與地球").
        val shorter = minOf(a.length, b.length)
        val longer = maxOf(a.length, b.length)
        return shorter >= 2 && shorter * 10 >= longer * 7 && (a.contains(b) || b.contains(a))
    }

    private fun artistOk(trackArtist: String, candArtists: List<String>): Boolean {
        if (trackArtist.isBlank()) return true
        val wanted = trackArtist.split(ARTIST_SPLIT).map { norm(it) }.filter { it.isNotEmpty() }
        val whole = norm(trackArtist)
        val have = candArtists.flatMap { it.split(ARTIST_SPLIT) }.map { norm(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty() || have.isEmpty()) return false
        return have.any { h -> wanted.any { w -> w == h || w.contains(h) || h.contains(w) } || whole.contains(h) }
    }

    /** Returns acceptable candidates, best first. */
    fun <T> rank(track: TrackInfo, candidates: List<Candidate<T>>): List<Candidate<T>> {
        val scored: List<Triple<Candidate<T>, Boolean, Long>> = candidates.mapNotNull { c ->
            if (!titleOk(track.title, c.title)) return@mapNotNull null
            val bothKnown = track.durationMs > 0 && c.durationMs > 0
            val diff = if (bothKnown) abs(track.durationMs - c.durationMs) else 0L
            if (bothKnown && diff > 6_000) return@mapNotNull null
            val artist = artistOk(track.artist, c.artists)
            // Without an artist match, only accept when the duration agrees closely.
            if (!artist && !(bothKnown && diff <= 2_000)) return@mapNotNull null
            Triple(c, artist, diff)
        }
        return scored
            .sortedWith(compareBy({ if (it.second) 0 else 1 }, { it.third }))
            .map { it.first }
    }

    /** Search queries to try: original, then Simplified Chinese if different. */
    fun queries(track: TrackInfo): List<String> {
        val q = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val simplified = ChineseConverter.toSimplified(q)
        return if (simplified != q) listOf(q, simplified) else listOf(q)
    }
}

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun JSONArray?.strings(key: String): List<String> =
    objects().mapNotNull { it.optString(key).takeIf { s -> s.isNotBlank() } }
