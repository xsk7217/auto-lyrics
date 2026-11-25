package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LrcLibClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "AutoLyrics v1.0 (https://github.com/user/auto-lyrics)"

    data class LrcLibResponse(
        @SerializedName("id") val id: Int?,
        @SerializedName("trackName") val trackName: String?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("albumName") val albumName: String?,
        @SerializedName("duration") val duration: Double?,
        @SerializedName("instrumental") val instrumental: Boolean?,
        @SerializedName("plainLyrics") val plainLyrics: String?,
        @SerializedName("syncedLyrics") val syncedLyrics: String?
    )

    /**
     * [accept] checks that a result really is this song (title / artist /
     * duration); LRCLIB's search is fuzzy and returns other songs too.
     */
    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int,
        accept: (LrcLibResponse) -> Boolean = { true }
    ): LrcLibResponse? {
        val ok: (LrcLibResponse?) -> LrcLibResponse? = { r -> r?.takeIf(accept) }
        // Priority 1: exact match with synced lyrics
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            if (exact?.syncedLyrics != null) ok(exact)?.let { return it }
        }

        // Priority 2: search for synced lyrics (duration-guarded)
        val searchResults = searchAll(trackName, artistName)
        val syncedResult = searchResults.firstOrNull {
            it.syncedLyrics != null && withinDuration(it, durationSec) && accept(it)
        }
        if (syncedResult != null) return syncedResult

        // Priority 3: exact match without album for synced lyrics
        if (durationSec > 0) {
            val exactNoAlbum = getExact(trackName, artistName, "", durationSec)
            if (exactNoAlbum?.syncedLyrics != null) ok(exactNoAlbum)?.let { return it }
        }

        // Priority 4: exact match with any lyrics (including plain)
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            if (exact?.plainLyrics != null) ok(exact)?.let { return it }
        }

        // Priority 5: search result with plain lyrics (duration-guarded)
        val plainResult = searchResults.firstOrNull {
            it.plainLyrics != null && withinDuration(it, durationSec) && accept(it)
        }
        if (plainResult != null) return plainResult

        return null
    }

    private fun withinDuration(result: LrcLibResponse, durationSec: Int): Boolean {
        val d = result.duration ?: return true
        if (durationSec <= 0) return true
        return kotlin.math.abs(d - durationSec) <= 10
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val urlBuilder = "$BASE_URL/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .addQueryParameter("album_name", albumName)
            .addQueryParameter("duration", durationSec.toString())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), LrcLibResponse::class.java)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun searchAll(trackName: String, artistName: String): List<LrcLibResponse> {
        val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)

        if (artistName.isNotBlank()) {
            urlBuilder.addQueryParameter("artist_name", artistName)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val type = object : TypeToken<List<LrcLibResponse>>() {}.type
                    gson.fromJson(response.body?.string(), type) ?: emptyList()
                } else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
