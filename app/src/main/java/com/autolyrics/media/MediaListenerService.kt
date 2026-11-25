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
            pickBestSession(controllers)
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
            pickBestSession(controllers)
        } catch (_: SecurityException) { }
    }

    private fun pickBestSession(controllers: List<MediaController>?) {
        val filtered = controllers?.filter { it.packageName != packageName }
        if (filtered.isNullOrEmpty()) {
            MediaTracker.getInstance(this).onMediaSessionChanged(null)
            return
        }

        val playing = filtered.firstOrNull { controller ->
            controller.playbackState?.state.let {
                it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
            }
        }

        // Nothing playing: stay with the player already followed.
        val current = MediaTracker.getInstance(this).controller?.packageName
        val best = playing ?: filtered.firstOrNull { it.packageName == current } ?: filtered.first()
        MediaTracker.getInstance(this).onMediaSessionChanged(best)
    }
}
