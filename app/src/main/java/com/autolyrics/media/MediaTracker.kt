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
import com.autolyrics.lyrics.TranslationOutcome
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
    private var switchJob: Job? = null
    /** Sources "換來源重抓" already used for [triedForTrack] (provider ids). */
    private val triedSources = mutableSetOf<String>()
    private var triedForTrack: TrackInfo? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    /** Player's id of the song shown / about to be shown (YT Music: the video id). */
    private var currentMediaId: String? = null
    private var pendingMediaId: String? = null
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
        currentMediaId = pendingMediaId
        val art = pendingArt
        val current = _state.value.track
        if (track.title == current?.title && track.artist == current.artist && sameDuration(track, current)) return@Runnable

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
            detectedLanguage = null,
            translationStatus = null,
            switchingSource = false,
            switchMessage = null
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
            val pos = lastPositionMs + (elapsed * playbackSpeed).toLong()
            val duration = _state.value.track?.durationMs ?: 0L
            if (duration > 0) pos.coerceAtMost(duration) else pos
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
        if (controller != null && controller.sessionToken == activeController?.sessionToken) return
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            handler.removeCallbacks(trackChangeRunnable)
            trackChangePending = false
            artJob?.cancel()
            currentMediaId = null
            _state.value = LyricsState(offsetMs = lyricsOffsetMs)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        // An empty string must fall through too (YT Music sometimes sends "" first).
        fun field(key: String) = metadata.getString(key)?.takeIf { it.isNotBlank() }
        val rawTitle = field(MediaMetadata.METADATA_KEY_TITLE)
            ?: field(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = field(MediaMetadata.METADATA_KEY_ARTIST)
            ?: field(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: field(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
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
        val mediaId = field(MediaMetadata.METADATA_KEY_MEDIA_ID)

        // The artist (or duration) arrived after the title: same song, now
        // searchable properly. Replace the half-known track and look up again.
        val waiting = pendingTrack
        if (trackChangePending && waiting != null && fillsIn(waiting, newTrack)) {
            pendingTrack = newTrack
            if (art != null) pendingArt = art
            return
        }
        if (current != null && fillsIn(current, newTrack)) {
            fetchJob?.cancel()
            translationJob?.cancel()
            _state.value = _state.value.copy(
                track = newTrack,
                lines = emptyList(),
                currentIndex = -1,
                currentWordIndex = -1,
                status = LyricsStatus.LOADING,
                source = "",
                translatedLines = null,
                detectedLanguage = null,
                translationStatus = null
            )
            fetchLyrics(newTrack)
            return
        }

        // Same media id as the song on screen: the player only rewrote its title /
        // artist (Morphe "MediaSession lyrics" puts each lyric line there).
        if (prefs.getBoolean(PREF_TITLE_GUARD, true) && current != null && !trackChangePending &&
            mediaId != null && mediaId == currentMediaId
        ) {
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

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

        if (current != null && newTrack.title == current.title && newTrack.artist == current.artist &&
            sameDuration(newTrack, current)
        ) {
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        pendingMediaId = mediaId
        handler.removeCallbacks(trackChangeRunnable)
        trackChangePending = true
        handler.postDelayed(trackChangeRunnable, 600)
    }

    /**
     * Lyric-in-title players may also move the song name into the artist field,
     * so only the duration (to the millisecond) and, when both have one, the
     * album are compared. Two different songs practically never share both.
     */
    /** [new] completes [old]: same title, and it adds the missing artist or duration. */
    private fun fillsIn(old: TrackInfo, new: TrackInfo): Boolean {
        if (old.title != new.title) return false
        val addsArtist = old.artist.isBlank() && new.artist.isNotBlank()
        val addsDuration = old.durationMs <= 0 && new.durationMs > 0 && (old.artist == new.artist || addsArtist)
        return (addsArtist && sameDuration(old, new)) || addsDuration
    }

    /** Equal, or one side doesn't know it yet. Two songs that share a name differ here. */
    private fun sameDuration(a: TrackInfo, b: TrackInfo): Boolean =
        a.durationMs <= 0 || b.durationMs <= 0 || kotlin.math.abs(a.durationMs - b.durationMs) <= 2_000

    private fun isSameSong(a: TrackInfo, b: TrackInfo): Boolean {
        if (a.durationMs <= 0 || a.durationMs != b.durationMs) return false
        if (a.album.isNotBlank() && b.album.isNotBlank() && a.album != b.album) return false
        return true
    }

    /** Player position without the user's lyric offset. */
    fun rawPositionMs(): Long = getCurrentPositionMs() - lyricsOffsetMs

    /** The player session being followed (seek / skip must go to this one). */
    val controller: MediaController?
        get() = activeController

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

        // Buffering / skipping between songs is still "playing" to the listener.
        // Reporting it as paused made our own session flip PAUSED → PLAYING on
        // every buffer, which pulls Android Auto's media card over to this app.
        val isPlaying = pbState.state in PLAYING_STATES
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
                    val status = cached.status
                    val source = cached.source
                    val lines = finalizeLines(cached.lines)
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

                    // Prefetched entries were looked up with less information:
                    // show them, but look the song up again now it plays.
                    if (cached.checked && cached.ageMs < CACHE_REFRESH_MS) {
                        prefetchNextSong()
                        return@launch
                    }
                }

                val result = fetchFromProviders(track)
                val displayLines = result?.let { finalizeLines(it.lines) }
                if (result != null) {
                    lyricsCache.put(track.title, track.artist, result.lines, result.status, result.source)
                }

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track) return@withContext

                    if (result != null && displayLines != null) {
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

                    // Without an artist (and no duration here) nothing can be verified.
                    if (artist.isBlank()) return@launch
                    if (lyricsCache.get(title, artist) != null) return@launch

                    val nextTrack = TrackInfo(title, artist, "", 0)
                    val result = fetchFromProviders(nextTrack)
                    if (result != null) {
                        lyricsCache.put(title, artist, result.lines, result.status, result.source, checked = false)
                    }
                    return@launch
                }
            } catch (_: Exception) {
                // prefetch failures are non-fatal
            }
        }
    }

    /** Asks every enabled lyrics source, in the user's order. Raw (unconverted) lines. */
    private suspend fun fetchFromProviders(track: TrackInfo, exclude: Set<String> = emptySet()): LyricsResult? {
        val ctx = currentCoroutineContext()
        return LyricsProviders.fetch(prefs, track, exclude) { ctx.isActive }
    }

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
            detectedLanguage = null,
            translationStatus = null
        )
        fetchLyrics(track)
    }

    /**
     * "換來源重抓": looks for lyrics in the sources not used yet for this track
     * (the current one first of all). Found → shown and cached. Nothing else
     * found → current lyrics stay, and the next press starts over.
     */
    fun refetchFromOtherSource(onDone: ((String) -> Unit)? = null) {
        val track = _state.value.track ?: return
        if (triedForTrack != track) {
            triedSources.clear()
            triedForTrack = track
        }
        LyricsProviders.idForSource(_state.value.source)?.let { triedSources += it }
        val exclude = triedSources.toSet()

        switchJob?.cancel()
        // A still-running normal lookup must not overwrite the chosen source later.
        fetchJob?.cancel()
        _state.value = _state.value.copy(switchingSource = true, switchMessage = "換來源中…")
        switchJob = scope.launch(Dispatchers.IO) {
            val result = try {
                fetchFromProviders(track, exclude)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val displayLines = result?.let { finalizeLines(it.lines) }
            if (result != null) {
                lyricsCache.put(track.title, track.artist, result.lines, result.status, result.source)
            }

            withContext(Dispatchers.Main) {
                if (_state.value.track != track) return@withContext
                val message: String
                if (result != null && displayLines != null) {
                    val id = LyricsProviders.idForSource(result.source)
                    id?.let { triedSources += it }
                    message = "已改用 ${id?.let { LyricsProviders.displayName(it) } ?: result.source}"
                    translationJob?.cancel()
                    _state.value = _state.value.copy(
                        lines = displayLines,
                        currentIndex = -1,
                        currentWordIndex = -1,
                        status = result.status,
                        source = result.source,
                        translatedLines = null,
                        detectedLanguage = null,
                        translationStatus = null,
                        switchingSource = false,
                        switchMessage = message
                    )
                    if (result.status == LyricsStatus.FOUND) updateCurrentPosition()
                    translateIfNeeded(displayLines, track)
                } else {
                    triedSources.clear()
                    message = if (_state.value.lines.isEmpty()) "其他來源也都沒有找到"
                        else "其他來源都沒有找到，保留目前的歌詞"
                    _state.value = _state.value.copy(switchingSource = false, switchMessage = message)
                }
                onDone?.invoke(message)
            }
        }
    }

    fun clearCache() {
        lyricsCache.clear()
    }

    /** Translation switch or target language changed: apply to the current song now. */
    fun onTranslationSettingsChanged() {
        translationJob?.cancel()
        val s = _state.value
        _state.value = s.copy(translatedLines = null, detectedLanguage = null, translationStatus = null)
        val track = s.track ?: return
        if (s.lines.isNotEmpty() && (s.status == LyricsStatus.FOUND || s.status == LyricsStatus.PLAIN_ONLY)) {
            translateIfNeeded(s.lines, track)
        }
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean(PREF_TRANSLATION, true)) return
        val target = prefs.getString(PREF_TRANSLATION_TARGET, null) ?: LyricsTranslator.DEFAULT_TARGET
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            suspend fun show(update: (LyricsState) -> LyricsState) = withContext(Dispatchers.Main) {
                if (_state.value.track == track) _state.value = update(_state.value)
            }
            val outcome = try {
                LyricsTranslator.translateLines(lines, target) { msg ->
                    show { it.copy(translationStatus = msg) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TranslationOutcome.Failed("翻譯：發生錯誤，換首歌再試")
            }
            show {
                if (outcome is TranslationOutcome.Done) it.copy(
                    translatedLines = outcome.translatedLines,
                    detectedLanguage = outcome.detectedLanguage,
                    translationStatus = outcome.message
                ) else it.copy(translationStatus = outcome.message)
            }
        }
    }

    companion object {
        private const val CACHE_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        const val PREF_TRADITIONAL = "convert_traditional"
        private val PLAYING_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
        )
        const val PREF_TRANSLATION = "translation_enabled"
        const val PREF_TRANSLATION_TARGET = "translation_target"
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
