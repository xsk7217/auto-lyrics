package com.autolyrics.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            watchSessions(controllers)
            pickBestSession(controllers)
        }

    /**
     * Android only reports when the *list* of players changes. Pausing one
     * player and starting another leaves the list as it is, so every player is
     * watched here: a player that starts or stops playing re-picks the one
     * to follow (Spotify, YT Music, Apple Music… all alike).
     */
    private class Watch(val controller: MediaController, val callback: MediaController.Callback, var playing: Boolean)

    private val watches = mutableMapOf<android.media.session.MediaSession.Token, Watch>()

    private fun isPlaying(state: PlaybackState?) =
        state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING

    private fun watchSessions(controllers: List<MediaController>?) {
        val current = controllers.orEmpty().filter { it.packageName != packageName }
        val tokens = current.map { it.sessionToken }.toSet()
        // Stop watching players that are gone.
        watches.keys.filter { it !in tokens }.forEach { token ->
            watches.remove(token)?.let { w ->
                try { w.controller.unregisterCallback(w.callback) } catch (_: Exception) { }
            }
        }
        // Start watching new ones.
        for (controller in current) {
            if (controller.sessionToken in watches) continue
            lateinit var watch: Watch
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val nowPlaying = isPlaying(state)
                    // Only a start / stop matters; position updates are ignored.
                    if (nowPlaying != watch.playing) {
                        watch.playing = nowPlaying
                        updateSessions()
                    }
                }

                override fun onSessionDestroyed() = updateSessions()
            }
            watch = Watch(controller, callback, isPlaying(controller.playbackState))
            try {
                controller.registerCallback(callback)
                watches[controller.sessionToken] = watch
            } catch (_: Exception) { }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MediaListenerService::class.java)
            )
            updateSessions()
        } catch (_: SecurityException) { }
        try {
            activeNotifications?.forEach { RecognizedSongs.onNotification(this, it) }
        } catch (_: Exception) { }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
        watches.values.forEach { try { it.controller.unregisterCallback(it.callback) } catch (_: Exception) { } }
        watches.clear()
        // Without this, session tracking stays off until notification access is toggled.
        try {
            requestRebind(ComponentName(this, MediaListenerService::class.java))
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Pixel "Now Playing" results, plus a re-pick when a *media* notification
        // changes (another player starting). Other apps' notifications are ignored:
        // re-picking on all of them was wasted work and could jump players.
        sbn?.let { RecognizedSongs.onNotification(this, it) }
        if (sbn?.notification?.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true) {
            updateSessions()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun updateSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )
            watchSessions(controllers)
            pickBestSession(controllers)
        } catch (_: SecurityException) { }
    }

    private fun pickBestSession(controllers: List<MediaController>?) {
        val filtered = controllers?.filter { it.packageName != packageName }
        if (filtered.isNullOrEmpty()) {
            MediaTracker.getInstance(this).onMediaSessionChanged(null)
            return
        }

        val playing = filtered.firstOrNull { isPlaying(it.playbackState) }

        // Nothing playing: stay with the player already followed.
        val current = MediaTracker.getInstance(this).controller?.packageName
        val best = playing ?: filtered.firstOrNull { it.packageName == current } ?: filtered.first()
        MediaTracker.getInstance(this).onMediaSessionChanged(best)
    }
}
