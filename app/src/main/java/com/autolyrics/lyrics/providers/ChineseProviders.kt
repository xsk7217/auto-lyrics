package com.autolyrics.lyrics.providers

import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64

/*
 * These sources use unofficial, community-documented endpoints.
 * They may stop working or be rate limited at any time; failures just
 * fall through to the next provider in the user's list.
 */

/** NetEase Cloud Music (網易雲音樂). */
object NetEaseProvider : LyricsProvider {
    override val id = "netease"
    override val displayName = "網易雲音樂"
    override val description = "華語歌收錄多，同步歌詞（非官方介面）"

    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "appver=2.0.2; os=pc"
    )

    override fun fetch(track: TrackInfo): LyricsResult? {
        var plain: LyricsResult? = null
        for (query in TrackMatcher.queries(track)) {
            // cloudsearch/pc returns plain JSON outside mainland China too
            // (search/get/web returns an encrypted "abroad" payload there).
            val searchUrl = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .addQueryParameter("type", "1")
                .addQueryParameter("offset", "0")
                .addQueryParameter("limit", "15")
                .build()
            val songs = Http.jsonObject(Http.get(searchUrl, headers))?.optJSONObject("result")?.optJSONArray("songs")
            val candidates = songs.objects().map { s ->
                val artists = (s.optJSONArray("ar") ?: s.optJSONArray("artists")).strings("name")
                val duration = if (s.has("dt")) s.optLong("dt") else s.optLong("duration")
                Candidate(
                    title = s.optString("name"),
                    artists = artists,
                    durationMs = duration,
                    payload = s.optLong("id")
                )
            }
            for (c in TrackMatcher.rank(track, candidates).take(3)) {
                val url = "https://music.163.com/api/song/lyric".toHttpUrl().newBuilder()
                    .addQueryParameter("id", c.payload.toString())
                    .addQueryParameter("lv", "-1")
                    .addQueryParameter("kv", "-1")
                    .addQueryParameter("tv", "-1")
                    .build()
                val json = Http.jsonObject(Http.get(url, headers)) ?: continue
                val lrc = json.optJSONObject("lrc")?.optString("lyric")
                val r = LyricsResults.fromLrcOrPlain(lrc, displayName) ?: continue
                if (r.status == LyricsStatus.FOUND) return r
                if (plain == null) plain = r
            }
            if (candidates.isNotEmpty() && plain != null) break
        }
        return plain
    }
}

/** KuGou Music (酷狗音樂). */
object KuGouProvider : LyricsProvider {
    override val id = "kugou"
    override val displayName = "酷狗音樂"
    override val description = "華語歌收錄多，同步歌詞（非官方介面）"

    override fun fetch(track: TrackInfo): LyricsResult? {
        for (query in TrackMatcher.queries(track)) {
            val searchUrl = "https://songsearch.kugou.com/song_search_v2".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", "1")
                .addQueryParameter("pagesize", "15")
                .addQueryParameter("platform", "WebFilter")
                .build()
            val list = Http.jsonObject(Http.get(searchUrl))?.optJSONObject("data")?.optJSONArray("lists")
            val candidates = list.objects().map { s ->
                Candidate(
                    title = TrackMatcher.stripTags(s.optString("SongName")),
                    artists = listOf(TrackMatcher.stripTags(s.optString("SingerName"))),
                    durationMs = s.optLong("Duration") * 1000,
                    payload = s.optString("FileHash")
                )
            }
            for (c in TrackMatcher.rank(track, candidates).take(3)) {
                if (c.payload.isBlank()) continue
                val r = lyricsForHash(c.payload, c.durationMs) ?: continue
                return r
            }
        }
        return null
    }

    /** KuGou's lyric hosts are not always reachable over HTTPS; fall back to HTTP. */
    private fun getJson(path: String, params: List<Pair<String, String>>): org.json.JSONObject? {
        for (scheme in listOf("https", "http")) {
            val builder = "$scheme://$path".toHttpUrl().newBuilder()
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            val json = Http.jsonObject(Http.get(builder.build()))
            if (json != null) return json
        }
        return null
    }

    private fun lyricsForHash(hash: String, durationMs: Long): LyricsResult? {
        val cand = getJson(
            "krcs.kugou.com/search",
            listOf(
                "ver" to "1", "man" to "yes", "client" to "mobi", "keyword" to "",
                "duration" to durationMs.toString(), "hash" to hash
            )
        )?.optJSONArray("candidates").objects().firstOrNull() ?: return null

        val content = getJson(
            "lyrics.kugou.com/download",
            listOf(
                "ver" to "1", "client" to "pc", "id" to cand.optString("id"),
                "accesskey" to cand.optString("accesskey"), "fmt" to "lrc", "charset" to "utf8"
            )
        )?.optString("content")
        if (content.isNullOrBlank()) return null
        val lrc = try {
            String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        return LyricsResults.fromLrcOrPlain(lrc, displayName)
    }
}

/** QQ Music (QQ 音樂). */
object QQMusicProvider : LyricsProvider {
    override val id = "qqmusic"
    override val displayName = "QQ 音樂"
    override val description = "華語歌收錄多，同步歌詞（非官方介面）"

    private val headers = mapOf("Referer" to "https://y.qq.com/portal/player.html")
    private val ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")

    override fun fetch(track: TrackInfo): LyricsResult? {
        for (query in TrackMatcher.queries(track)) {
            val candidates = search(query)
            for (c in TrackMatcher.rank(track, candidates).take(3)) {
                val r = lyricsForMid(c.payload) ?: continue
                return r
            }
        }
        return null
    }

    private fun search(query: String): List<Candidate<String>> {
        // Newer unified endpoint
        val payload = """
            {"req":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop",
             "param":{"query":${org.json.JSONObject.quote(query)},"num_per_page":15,"page_num":1,"search_type":0}}}
        """.trimIndent()
        val modern = Http.jsonObject(Http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", payload, headers))
            ?.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("song")?.optJSONArray("list")
        val fromModern = modern.objects().map { s ->
            Candidate(
                title = s.optString("title").ifBlank { s.optString("name") },
                artists = s.optJSONArray("singer").strings("name"),
                durationMs = s.optLong("interval") * 1000,
                payload = s.optString("mid")
            )
        }.filter { it.payload.isNotBlank() }
        if (fromModern.isNotEmpty()) return fromModern

        // Legacy endpoint as a fallback
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("w", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("p", "1")
            .addQueryParameter("n", "15")
            .build()
        val list = Http.jsonObject(Http.get(url, headers))?.optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("list")
        return list.objects().map { s ->
            Candidate(
                title = s.optString("songname"),
                artists = s.optJSONArray("singer").strings("name"),
                durationMs = s.optLong("interval") * 1000,
                payload = s.optString("songmid")
            )
        }.filter { it.payload.isNotBlank() }
    }

    private fun lyricsForMid(mid: String): LyricsResult? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("songmid", mid)
            .addQueryParameter("format", "json")
            .addQueryParameter("nobase64", "1")
            .addQueryParameter("g_tk", "5381")
            .build()
        val raw = Http.jsonObject(Http.get(url, headers))?.optString("lyric")
        if (raw.isNullOrBlank()) return null
        val text = if (!raw.contains('[')) {
            try { String(Base64.getMimeDecoder().decode(raw), Charsets.UTF_8) } catch (_: Exception) { raw }
        } else raw
        return LyricsResults.fromLrcOrPlain(decodeEntities(text), displayName)
    }

    private fun decodeEntities(s: String): String {
        val numeric = ENTITY.replace(s) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        return numeric
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
