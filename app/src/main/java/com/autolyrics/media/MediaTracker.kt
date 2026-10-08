package com.autolyrics.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autolyrics.lyrics.ChineseConverter
import com.autolyrics.lyrics.LyricsCache
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.MetadataCleaner
import com.autolyrics.lyrics.providers.LyricsProviders
import com.autolyrics.lyrics.providers.LyricsResult
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AlbumColorExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
    private val lyricsCache = LyricsCache(context)

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null
    private var prefetchJob: Job? = null
    private var artJob: Job? = null
    private var translationJob: Job? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    private var trackChangePending = false
    private var lyricsOffsetMs: Long = 0L

    init {
        lyricsOffsetMs = prefs.getLong("lyrics_offset_ms", 0L)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
    }

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentPosition()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val trackChangeRunnable = Runnable {
        trackChangePending = false
        val track = pendingTrack ?: return@Runnable
        val art = pendingArt
        val current = _state.value.track
        if (track.title == current?.title && track.artist == current.artist) return@Runnable

        translationJob?.cancel()
        _state.value = _state.value.copy(
            track = track,
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            albumArt = art,
            albumColors = null,
            translatedLines = null,
            detectedLanguage = null
        )
        fetchLyrics(track)
        extractAlbumColors(art)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    fun adjustOffset(deltaMs: Long) {
        lyricsOffsetMs += deltaMs
        prefs.edit().putLong("lyrics_offset_ms", lyricsOffsetMs).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resetOffset() {
        lyricsOffsetMs = 0L
        prefs.edit().putLong("lyrics_offset_ms", 0L).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resumePlayback() {
        activeController?.transportControls?.play()
    }

    fun setOffset(ms: Long) {
        lyricsOffsetMs = ms
        prefs.edit().putLong("lyrics_offset_ms", lyricsOffsetMs).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun getCurrentPositionMs(): Long {
        val basePos = if (!_state.value.isPlaying) {
            lastPositionMs
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            lastPositionMs + (elapsed * playbackSpeed).toLong()
        }
        return basePos + lyricsOffsetMs
    }

    private fun updateCurrentPosition() {
        val currentState = _state.value
        val lines = currentState.lines
        if (lines.isEmpty() || currentState.status != LyricsStatus.FOUND) return

        val posMs = getCurrentPositionMs()

        var newLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newLineIndex = i
            } else {
                break
            }
        }

        var newWordIndex = -1
        if (newLineIndex >= 0) {
            val words = lines[newLineIndex].words
            if (words.isNotEmpty()) {
                for (i in words.indices) {
                    if (words[i].timeMs <= posMs) {
                        newWordIndex = i
                    } else {
                        break
                    }
                }
            }
        }

        if (newLineIndex != currentState.currentIndex || newWordIndex != currentState.currentWordIndex) {
            _state.value = currentState.copy(
                currentIndex = newLineIndex,
                currentWordIndex = newWordIndex
            )
        }
    }

    fun onMediaSessionChanged(controller: MediaController?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            handler.removeCallbacks(trackChangeRunnable)
            trackChangePending = false
            artJob?.cancel()
            _state.value = LyricsState(offsetMs = lyricsOffsetMs)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val title = MetadataCleaner.cleanTitle(rawTitle)
        val artist = MetadataCleaner.cleanArtist(rawArtist)
        val album = MetadataCleaner.cleanAlbum(rawAlbum)

        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val newTrack = TrackInfo(title, artist, album, duration)
        val current = _state.value.track

        // Some players (e.g. YT Music patched with Morphe "Third-party lyrics") write the
        // current lyric line into the title field. Same duration/album while the song
        // keeps playing means it is still the same song, so ignore the "new" title.
        if (prefs.getBoolean(PREF_TITLE_GUARD, true)) {
            val pending = pendingTrack
            if (pending != null && trackChangePending &&
                (newTrack.title != pending.title || newTrack.artist != pending.artist) &&
                isSameSong(newTrack, pending)
            ) {
                if (art != null && pendingArt == null) pendingArt = art
                return
            }
            if (current != null && (newTrack.title != current.title || newTrack.artist != current.artist) &&
                isSameSong(newTrack, current) &&
                rawPositionMs() > GUARD_MIN_POSITION_MS
            ) {
                if (art != null && _state.value.albumArt == null) {
                    _state.value = _state.value.copy(albumArt = art)
                    extractAlbumColors(art)
                }
                return
            }
        }

        if (current != null && newTrack.title == current.title && newTrack.artist == current.artist) {
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        handler.removeCallbacks(trackChangeRunnable)
        trackChangePending = true
        handler.postDelayed(trackChangeRunnable, 600)
    }

    /**
     * Lyric-in-title players may also move the song name into the artist field,
     * so only the duration (to the millisecond) and, when both have one, the
     * album are compared. Two different songs practically never share both.
     */
    private fun isSameSong(a: TrackInfo, b: TrackInfo): Boolean {
        if (a.durationMs <= 0 || a.durationMs != b.durationMs) return false
        if (a.album.isNotBlank() && b.album.isNotBlank() && a.album != b.album) return false
        return true
    }

    /** Player position without the user's lyric offset. */
    private fun rawPositionMs(): Long = getCurrentPositionMs() - lyricsOffsetMs

    private fun extractAlbumColors(bitmap: Bitmap?) {
        artJob?.cancel()
        if (bitmap == null) return
        artJob = scope.launch(Dispatchers.Default) {
            val colors = AlbumColorExtractor.extract(bitmap)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(albumColors = colors)
            }
        }
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        lastPositionMs = pbState.position
        lastPositionUpdateTime = pbState.lastPositionUpdateTime
        if (lastPositionUpdateTime == 0L) {
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val cached = lyricsCache.get(track.title, track.artist)
                if (cached != null) {
                    val (rawLines, status, source) = cached
                    val lines = finalizeLines(rawLines)
                    withContext(Dispatchers.Main) {
                        if (_state.value.track != track) return@withContext
                        _state.value = _state.value.copy(
                            lines = lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = status,
                            source = "$source (cached)"
                        )
                        if (status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(lines, track)
                    }

                    val cacheAge = lyricsCache.getAge(track.title, track.artist)
                    if (cacheAge < CACHE_REFRESH_MS) {
                        prefetchNextSong()
                        return@launch
                    }
                }

                val result = fetchFromProviders(track)
                val displayLines = result?.let { finalizeLines(it.lines) }

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track) return@withContext

                    if (result != null && displayLines != null) {
                        lyricsCache.put(track.title, track.artist, result.lines, result.status, result.source)
                        _state.value = _state.value.copy(
                            lines = displayLines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = result.status,
                            source = result.source
                        )
                        if (result.status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(displayLines, track)
                    } else if (cached == null) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.NOT_FOUND,
                            source = ""
                        )
                    }
                }

                prefetchNextSong()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track && _state.value.status == LyricsStatus.LOADING) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.ERROR,
                            source = ""
                        )
                    }
                }
            }
        }
    }

    private fun prefetchNextSong() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                val controller = activeController ?: return@launch
                val queue = controller.queue ?: return@launch
                val currentTitle = _state.value.track?.title ?: return@launch
                val currentArtist = _state.value.track?.artist ?: return@launch

                var foundCurrent = false
                for (item in queue) {
                    val desc = item.description
                    val title = MetadataCleaner.cleanTitle(desc.title?.toString() ?: continue)
                    val artist = MetadataCleaner.cleanArtist(desc.subtitle?.toString() ?: "")

                    if (!foundCurrent) {
                        if (title.equals(currentTitle, ignoreCase = true) &&
                            artist.equals(currentArtist, ignoreCase = true)
                        ) {
                            foundCurrent = true
                        }
                        continue
                    }

                    if (lyricsCache.get(title, artist) != null) return@launch

                    val nextTrack = TrackInfo(title, artist, "", 0)
                    val result = fetchFromProviders(nextTrack)
                    if (result != null) {
                        lyricsCache.put(title, artist, result.lines, result.status, result.source)
                    }
                    return@launch
                }
            } catch (_: Exception) {
                // prefetch failures are non-fatal
            }
        }
    }

    /** Asks every enabled lyrics source, in the user's order. Raw (unconverted) lines. */
    private fun fetchFromProviders(track: TrackInfo): LyricsResult? =
        LyricsProviders.fetch(prefs, track)

    /** Applies display-time conversions (Simplified → Traditional Chinese). */
    private fun finalizeLines(lines: List<LyricLine>): List<LyricLine> =
        if (prefs.getBoolean(PREF_TRADITIONAL, true)) ChineseConverter.linesToTraditional(lines) else lines

    /** Forgets the current lyrics and fetches them again (used after settings change). */
    fun refetchCurrent() {
        val track = _state.value.track ?: return
        lyricsCache.remove(track.title, track.artist)
        translationJob?.cancel()
        _state.value = _state.value.copy(
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            translatedLines = null,
            detectedLanguage = null
        )
        fetchLyrics(track)
    }

    fun clearCache() {
        lyricsCache.clear()
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean("translation_enabled", true)) return
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                val result = LyricsTranslator.translateLines(lines) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track) {
                        _state.value = _state.value.copy(
                            translatedLines = result.translatedLines,
                            detectedLanguage = result.detectedLanguage
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val CACHE_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        const val PREF_TRADITIONAL = "convert_traditional"
        const val PREF_TITLE_GUARD = "title_change_guard"
        private const val GUARD_MIN_POSITION_MS = 3_000L

        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
