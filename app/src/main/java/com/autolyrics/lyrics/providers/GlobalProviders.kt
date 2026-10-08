package com.autolyrics.lyrics.providers

import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.SyncLrcClient
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** LRCLIB — open community database (the original source of this app). */
object LrcLibProvider : LyricsProvider {
    override val id = "lrclib"
    override val displayName = "LRCLIB"
    override val description = "開源社群歌詞庫，官方公開 API，英日韓歌較多"

    override fun fetch(track: TrackInfo): LyricsResult? {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0
        val result = try {
            LrcLibClient.getLyrics(track.title, track.artist, track.album, durationSec)
        } catch (_: Exception) {
            null
        } ?: return null

        result.syncedLyrics?.let { synced ->
            val lines = LrcParser.parse(synced)
            if (LyricsResults.hasRealText(lines)) return LyricsResult(lines, LyricsStatus.FOUND, "LRCLIB · Synced")
        }
        return LyricsResults.fromPlain(result.plainLyrics, "LRCLIB")
    }
}

/** SyncLRC — third-party aggregator with word-by-word (karaoke) timing. */
object SyncLrcProvider : LyricsProvider {
    override val id = "synclrc"
    override val displayName = "SyncLRC"
    override val description = "逐字（卡拉 OK）歌詞，原版 Auto Lyrics 內建來源"

    override fun fetch(track: TrackInfo): LyricsResult? {
        val result = try {
            SyncLrcClient.getLyrics(track.title, track.artist)
        } catch (_: Exception) {
            null
        } ?: return null

        return when (result.type) {
            SyncLrcClient.LyricsType.KARAOKE -> {
                val lines = LrcParser.parseKaraoke(result.lyrics)
                if (LyricsResults.hasRealText(lines)) LyricsResult(lines, LyricsStatus.FOUND, "SyncLRC · Karaoke") else null
            }
            SyncLrcClient.LyricsType.SYNCED -> {
                val lines = LrcParser.parse(result.lyrics)
                if (LyricsResults.hasRealText(lines)) LyricsResult(lines, LyricsStatus.FOUND, "SyncLRC · Synced") else null
            }
            SyncLrcClient.LyricsType.PLAIN -> LyricsResults.fromPlain(result.lyrics, "SyncLRC")
        }
    }
}

/**
 * Musixmatch via its desktop-app endpoint. Needs an anonymous token that
 * Musixmatch sometimes refuses (captcha); when that happens the provider
 * backs off for a while and returns null.
 */
object MusixmatchProvider : LyricsProvider {
    override val id = "musixmatch"
    override val displayName = "Musixmatch"
    override val description = "全球最大歌詞庫，常被限流（非官方介面）"

    private const val BASE = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val TOKEN_TTL_MS = 10 * 60 * 1000L
    private const val BACKOFF_MS = 15 * 60 * 1000L

    private val cookies = mutableMapOf<String, Cookie>()
    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(this@MusixmatchProvider.cookies) {
                    cookies.forEach { this@MusixmatchProvider.cookies[it.name] = it }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(this@MusixmatchProvider.cookies) { this@MusixmatchProvider.cookies.values.toList() }
        })
        .build()

    private val headers = mapOf(
        "Authority" to "apic-desktop.musixmatch.com",
        "Cookie" to "AWSELBCORS=0; AWSELB=0"
    )

    private var token: String? = null
    private var tokenTime = 0L
    private var blockedUntil = 0L

    @Synchronized
    private fun getToken(): String? {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return null
        token?.let { if (now - tokenTime < TOKEN_TTL_MS) return it }

        val url = "${BASE}token.get".toHttpUrl().newBuilder()
            .addQueryParameter("app_id", APP_ID)
            .addQueryParameter("user_language", "en")
            .addQueryParameter("t", now.toString())
            .build()
        val msg = Http.jsonObject(Http.get(url, headers, client))?.optJSONObject("message")
        val status = msg?.optJSONObject("header")?.optInt("status_code") ?: 0
        val t = msg?.optJSONObject("body")?.optString("user_token")
        if (status != 200 || t.isNullOrBlank() || t.startsWith("UpgradeOnly")) {
            blockedUntil = now + BACKOFF_MS
            token = null
            return null
        }
        token = t
        tokenTime = now
        return t
    }

    override fun fetch(track: TrackInfo): LyricsResult? {
        val tok = getToken() ?: return null
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toString() else ""
        val url = "${BASE}macro.subtitles.get".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("namespace", "lyrics_richsynched")
            .addQueryParameter("subtitle_format", "mxm")
            .addQueryParameter("app_id", APP_ID)
            .addQueryParameter("q_album", track.album)
            .addQueryParameter("q_artist", track.artist)
            .addQueryParameter("q_artists", track.artist)
            .addQueryParameter("q_track", track.title)
            .addQueryParameter("q_duration", durationSec)
            .addQueryParameter("f_subtitle_length", durationSec)
            .addQueryParameter("usertoken", tok)
            .build()

        val root = Http.jsonObject(Http.get(url, headers, client))?.optJSONObject("message") ?: return null
        val rootStatus = root.optJSONObject("header")?.optInt("status_code") ?: 0
        if (rootStatus == 401) {
            synchronized(this) { token = null; blockedUntil = System.currentTimeMillis() + BACKOFF_MS }
            return null
        }
        val calls = root.optJSONObject("body")?.optJSONObject("macro_calls") ?: return null

        // Make sure Musixmatch matched the right song.
        val matched = calls.optJSONObject("matcher.track.get")?.optJSONObject("message")
            ?.optJSONObject("body")?.optJSONObject("track") ?: return null
        val candidate = Candidate(
            title = matched.optString("track_name"),
            artists = listOf(matched.optString("artist_name")),
            durationMs = matched.optLong("track_length") * 1000,
            payload = Unit
        )
        if (TrackMatcher.rank(track, listOf(candidate)).isEmpty()) return null
        if (matched.optInt("instrumental") == 1) return null

        val subtitleBody = calls.optJSONObject("track.subtitles.get")?.optJSONObject("message")
            ?.optJSONObject("body")?.optJSONArray("subtitle_list").objects().firstOrNull()
            ?.optJSONObject("subtitle")?.optString("subtitle_body")
        if (!subtitleBody.isNullOrBlank()) {
            val lines = try {
                val arr = JSONArray(subtitleBody)
                arr.objects().map { o ->
                    val ms = (o.optJSONObject("time")?.optDouble("total", 0.0) ?: 0.0) * 1000
                    LyricLine(ms.toLong(), o.optString("text").ifBlank { "♪" })
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (LyricsResults.hasRealText(lines)) return LyricsResult(lines, LyricsStatus.FOUND, "Musixmatch · Synced")
        }

        val plain = calls.optJSONObject("track.lyrics.get")?.optJSONObject("message")
            ?.optJSONObject("body")?.optJSONObject("lyrics")?.optString("lyrics_body")
            ?.substringBefore("*******")
        return LyricsResults.fromPlain(plain, displayName)
    }
}

/**
 * YouTube Music's own lyrics through the public web client API.
 * These are usually plain (not synced) and come from the same licensed
 * text the YT Music app shows.
 */
object YouTubeMusicProvider : LyricsProvider {
    override val id = "ytmusic"
    override val displayName = "YouTube Music"
    override val description = "YT Music 內建歌詞，多半不同步（非官方介面）"

    private const val API = "https://music.youtube.com/youtubei/v1/"
    private val headers = mapOf(
        "Origin" to "https://music.youtube.com",
        "Referer" to "https://music.youtube.com/",
        "X-Goog-Api-Format-Version" to "1"
    )

    private fun context(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20250310.01.00")
            .put("hl", "zh-TW")
    )

    private fun call(endpoint: String, body: JSONObject): JSONObject? {
        body.put("context", context())
        return Http.jsonObject(Http.postJson("$API$endpoint?prettyPrint=false", body.toString(), headers))
    }

    override fun fetch(track: TrackInfo): LyricsResult? {
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val search = call("search", JSONObject().put("query", query)) ?: return null

        val candidates = mutableListOf<Candidate<String>>()
        collect(search, "musicResponsiveListItemRenderer").forEach { r ->
            val videoId = findString(r, "videoId") ?: return@forEach
            val columns = r.optJSONArray("flexColumns").objects()
            val texts = columns.map { col -> runsText(col) }
            val title = texts.getOrNull(0).orEmpty()
            val meta = texts.drop(1).joinToString(" • ")
            if (title.isNotBlank()) candidates += Candidate(title, listOf(meta), 0L, videoId)
        }
        val best = TrackMatcher.rank(track, candidates).firstOrNull() ?: return null

        val next = call("next", JSONObject().put("videoId", best.payload)) ?: return null
        val browseId = collectStrings(next, "browseId").firstOrNull { it.startsWith("MPLY") } ?: return null
        val browse = call("browse", JSONObject().put("browseId", browseId)) ?: return null
        val shelf = collect(browse, "musicDescriptionShelfRenderer").firstOrNull() ?: return null
        val text = shelf.optJSONObject("description")?.let { runsText(it) }
        return LyricsResults.fromPlain(text, displayName)
    }

    /** Concatenates every `runs[].text` found under [obj]. */
    private fun runsText(obj: JSONObject): String {
        val out = StringBuilder()
        walk(obj) { key, value ->
            if (key == "runs" && value is JSONArray) {
                value.objects().forEach { out.append(it.optString("text")) }
                true
            } else false
        }
        return out.toString()
    }

    /** Depth-first search for every object stored under [key]. */
    private fun collect(root: Any, key: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        walk(root) { k, v ->
            if (k == key && v is JSONObject) { out += v; true } else false
        }
        return out
    }

    private fun collectStrings(root: Any, key: String): List<String> {
        val out = mutableListOf<String>()
        walk(root) { k, v -> if (k == key && v is String) { out += v; true } else false }
        return out
    }

    private fun findString(root: Any, key: String): String? = collectStrings(root, key).firstOrNull()

    /** Visits every (key, value) pair; when [visit] returns true the value is not descended into. */
    private fun walk(node: Any?, visit: (String, Any) -> Boolean) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k) ?: continue
                    if (!visit(k, v)) walk(v, visit)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }
}
