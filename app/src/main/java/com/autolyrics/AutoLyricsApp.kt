package com.autolyrics

import android.app.Application
import android.content.Intent
import com.autolyrics.auto.LyricsBrowserService
import com.autolyrics.media.MediaTracker
import com.autolyrics.ui.Themes

class AutoLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaTracker.init(this)
        Themes.applyNightMode(this)
        try {
            startService(Intent(this, LyricsBrowserService::class.java))
        } catch (_: Exception) { }
    }
}
