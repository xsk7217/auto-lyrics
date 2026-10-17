package com.autolyrics

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.media.MediaListenerService
import com.autolyrics.media.MediaTracker
import com.autolyrics.ui.HaloView
import com.autolyrics.ui.Palette
import com.autolyrics.ui.ThemeStyler
import com.autolyrics.ui.Themes
import com.google.android.material.button.MaterialButton
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var rootLayout: View
    private lateinit var appBar: View
    private lateinit var palette: Palette
    private lateinit var halo: HaloView
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvTrack: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvLyrics: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var delayBar: LinearLayout
    private lateinit var tvDelay: TextView
    private lateinit var divider: View
    private lateinit var fontSettingsPanel: View
    private lateinit var tvFontSize: TextView
    private lateinit var switchAaKaraoke: SwitchCompat
    private lateinit var switchTranslation: SwitchCompat
    private lateinit var tvTranslationStatus: TextView
    private lateinit var tvAaDelay: TextView
    private lateinit var btnJumpToCurrent: Button
    private lateinit var prefs: SharedPreferences
    private var lastScrolledIndex = -1
    private var lyricsFontSizeSp = 16
    private var lyricsFontFamily = "sans-serif"
    private var aaOffsetMs = 0L
    private var userScrolling = false
    private var userTouching = false

    private var plainScrollAnimator: ValueAnimator? = null
    private var lastPlainTrackTitle: String? = null

    private lateinit var btnTapSync: Button
    private lateinit var tvSyncStatus: TextView
    private var tapSyncActive = false
    private var tapSyncTargetLineIndex = -1
    private var tapSyncOffsets = mutableListOf<Long>()

    private val fontButtons = mutableMapOf<String, Button>()
    private val scrollResetRunnable = Runnable {
        userScrolling = false
        btnJumpToCurrent.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeStyler.install(this, R.layout.activity_main)
        palette = Themes.current(this)

        mediaTracker = MediaTracker.getInstance(this)
        prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)

        rootLayout = findViewById(R.id.root_layout)
        appBar = findViewById(R.id.layout_app_bar)
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvAppSubtitle = findViewById(R.id.tv_app_subtitle)
        tvStatus = findViewById(R.id.tv_status)
        btnPermission = findViewById(R.id.btn_permission)
        ivAlbumArt = findViewById(R.id.iv_album_art)
        tvTrack = findViewById(R.id.tv_track)
        tvSource = findViewById(R.id.tv_source)
        tvTranslationStatus = findViewById(R.id.tv_translation_status)
        tvLyrics = findViewById(R.id.tv_lyrics)
        scrollView = findViewById(R.id.scroll_lyrics)
        delayBar = findViewById(R.id.layout_delay)
        tvDelay = findViewById(R.id.tv_delay)
        divider = findViewById(R.id.divider)

        ivAlbumArt.clipToOutline = true
        halo = findViewById(R.id.halo)
        findViewById<View>(R.id.lyrics_card).clipToOutline = true
        setupAppearanceControls()
        btnJumpToCurrent = findViewById(R.id.btn_jump_to_current)

        setupScrollDetection()

        btnPermission.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        findViewById<Button>(R.id.btn_delay_minus).setOnClickListener {
            mediaTracker.adjustOffset(-100)
        }
        findViewById<Button>(R.id.btn_delay_plus).setOnClickListener {
            mediaTracker.adjustOffset(100)
        }
        findViewById<Button>(R.id.btn_tb_delay_minus).setOnClickListener { mediaTracker.adjustOffset(-500) }
        findViewById<Button>(R.id.btn_tb_delay_plus).setOnClickListener { mediaTracker.adjustOffset(500) }
        findViewById<Button>(R.id.btn_delay_reset).setOnClickListener {
            mediaTracker.resetOffset()
        }

        fontSettingsPanel = findViewById(R.id.panel_scroll)
        if (prefs.getBoolean(KEY_PANEL_OPEN, false)) {
            fontSettingsPanel.visibility = View.VISIBLE
            prefs.edit().putBoolean(KEY_PANEL_OPEN, false).apply()
        }
        tvFontSize = findViewById(R.id.tv_font_size)

        lyricsFontSizeSp = prefs.getInt("lyrics_font_size", 16)
        lyricsFontFamily = prefs.getString("lyrics_font_family", "sans-serif") ?: "sans-serif"

        applyFontSettings()

        findViewById<ImageButton>(R.id.btn_switch_source).setOnClickListener {
            Toast.makeText(this, "換來源中…", Toast.LENGTH_SHORT).show()
            MediaTracker.getInstance(this).refetchFromOtherSource { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<ImageButton>(R.id.btn_settings_toggle).setOnClickListener {
            fontSettingsPanel.visibility = if (fontSettingsPanel.visibility == View.VISIBLE)
                View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btn_lyrics_search).setOnClickListener {
            startActivity(Intent(this, LyricsSearchActivity::class.java))
        }

        findViewById<Button>(R.id.btn_lyrics_sources).setOnClickListener {
            startActivity(Intent(this, LyricsSourcesActivity::class.java))
        }

        findViewById<Button>(R.id.btn_performance_mode).setOnClickListener {
            startActivity(Intent(this, PerformanceActivity::class.java))
        }

        findViewById<Button>(R.id.btn_font_size_minus).setOnClickListener {
            if (lyricsFontSizeSp > 12) {
                lyricsFontSizeSp -= 2
                saveFontSettings()
                applyFontSettings()
            }
        }
        findViewById<Button>(R.id.btn_font_size_plus).setOnClickListener {
            if (lyricsFontSizeSp < 28) {
                lyricsFontSizeSp += 2
                saveFontSettings()
                applyFontSettings()
            }
        }

        val btnSans = findViewById<Button>(R.id.btn_font_sans)
        val btnSerif = findViewById<Button>(R.id.btn_font_serif)
        val btnMono = findViewById<Button>(R.id.btn_font_mono)
        val btnCursive = findViewById<Button>(R.id.btn_font_cursive)

        fontButtons["sans-serif"] = btnSans
        fontButtons["serif"] = btnSerif
        fontButtons["monospace"] = btnMono
        fontButtons["cursive"] = btnCursive

        btnSans.setOnClickListener { selectFont("sans-serif") }
        btnSerif.setOnClickListener { selectFont("serif") }
        btnMono.setOnClickListener { selectFont("monospace") }
        btnCursive.setOnClickListener { selectFont("cursive") }

        updateFontButtonHighlights()

        switchAaKaraoke = findViewById(R.id.switch_aa_karaoke)
        tvAaDelay = findViewById(R.id.tv_aa_delay)

        switchAaKaraoke.isChecked = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        updateAaDelayDisplay()

        switchAaKaraoke.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("aa_karaoke_enabled", isChecked).apply()
        }

        switchTranslation = findViewById(R.id.switch_translation)
        switchTranslation.isChecked = prefs.getBoolean("translation_enabled", true)
        switchTranslation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MediaTracker.PREF_TRANSLATION, isChecked).apply()
            MediaTracker.getInstance(this).onTranslationSettingsChanged()
        }

        val btnTarget = findViewById<Button>(R.id.btn_translation_target)
        fun showTarget() {
            val tag = prefs.getString(MediaTracker.PREF_TRANSLATION_TARGET, null) ?: LyricsTranslator.DEFAULT_TARGET
            btnTarget.text = "翻譯成：${LyricsTranslator.targetName(tag)} ▾"
        }
        showTarget()
        btnTarget.setOnClickListener {
            val tags = LyricsTranslator.TARGETS.keys.toList()
            val current = prefs.getString(MediaTracker.PREF_TRANSLATION_TARGET, null) ?: LyricsTranslator.DEFAULT_TARGET
            AlertDialog.Builder(this)
                .setTitle("翻譯成哪種語言")
                .setSingleChoiceItems(
                    LyricsTranslator.TARGETS.values.toTypedArray(), tags.indexOf(current).coerceAtLeast(0)
                ) { dialog, which ->
                    prefs.edit().putString(MediaTracker.PREF_TRANSLATION_TARGET, tags[which]).apply()
                    showTarget()
                    MediaTracker.getInstance(this).onTranslationSettingsChanged()
                    dialog.dismiss()
                }
                .show()
        }

        findViewById<Button>(R.id.btn_aa_delay_minus).setOnClickListener {
            aaOffsetMs -= 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_plus).setOnClickListener {
            aaOffsetMs += 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_reset).setOnClickListener {
            aaOffsetMs = 0L
            prefs.edit().putLong("aa_offset_ms", 0L).apply()
            updateAaDelayDisplay()
        }

        btnTapSync = findViewById(R.id.btn_tap_sync)
        tvSyncStatus = findViewById(R.id.tv_sync_status)

        btnTapSync.setOnClickListener { onTapSyncPressed() }

        findViewById<Button>(R.id.btn_quick_minus_1s).setOnClickListener {
            mediaTracker.adjustOffset(-1000)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_minus_half).setOnClickListener {
            mediaTracker.adjustOffset(-500)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_plus_half).setOnClickListener {
            mediaTracker.adjustOffset(500)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_plus_1s).setOnClickListener {
            mediaTracker.adjustOffset(1000)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    updatePermissionUi()
                    updateDelayDisplay(state.offsetMs)
                    updateAlbumArt(state)
                    halo.setBreathing(state.isPlaying && ivAlbumArt.visibility == View.VISIBLE)

                    if (state.track != null) {
                        val artistText = if (state.track.artist.isNotBlank())
                            state.track.artist else "Unknown Artist"
                        tvTrack.text = "${state.track.title}\n$artistText"
                        tvTrack.visibility = View.VISIBLE
                    } else {
                        tvTrack.text = "還沒有播放中的歌曲"
                        tvTrack.visibility = View.VISIBLE
                    }

                    tvTranslationStatus.text = state.translationStatus.orEmpty()
                    tvTranslationStatus.visibility =
                        if (state.translationStatus.isNullOrBlank()) View.GONE else View.VISIBLE

                    if (state.source.isNotBlank()) {
                        tvSource.text = state.source
                        tvSource.visibility = View.VISIBLE
                    } else {
                        tvSource.visibility = View.GONE
                    }

                    if (state.status != LyricsStatus.PLAIN_ONLY) {
                        stopPlainScroll()
                    } else {
                        plainScrollAnimator?.let { anim ->
                            if (state.isPlaying && anim.isPaused) anim.resume()
                            else if (!state.isPlaying && anim.isRunning) anim.pause()
                        }
                    }

                    when (state.status) {
                        LyricsStatus.NO_MEDIA -> {
                            tvLyrics.text = "Play a song to see lyrics here.\n\nLyrics will also appear on Android Auto."
                        }
                        LyricsStatus.LOADING -> {
                            tvLyrics.text = "Loading lyrics…"
                        }
                        LyricsStatus.NOT_FOUND -> {
                            tvLyrics.text = "No lyrics found for this track."
                        }
                        LyricsStatus.ERROR -> {
                            tvLyrics.text = "Error loading lyrics.\nCheck your internet connection."
                        }
                        LyricsStatus.FOUND -> {
                            renderSyncedLyrics(state)
                        }
                        LyricsStatus.PLAIN_ONLY -> {
                            stopPlainScroll()
                            val hasTrans = state.translatedLines != null
                            val sb = SpannableStringBuilder()
                            sb.append("ℹ  Lyrics are not synced to playback\n\n")
                            sb.append("─────────────────────\n\n")
                            val dimColor = palette.dim
                            state.lines.forEachIndexed { i, line ->
                                sb.append("${line.text}\n")
                                if (hasTrans) {
                                    val tl = state.translatedLines?.getOrNull(i)
                                    if (!tl.isNullOrBlank()) {
                                        val tlStart = sb.length
                                        sb.append("$tl\n")
                                        sb.setSpan(
                                            RelativeSizeSpan(0.8f),
                                            tlStart, sb.length,
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                        sb.setSpan(
                                            ForegroundColorSpan(dimColor),
                                            tlStart, sb.length,
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    }
                                }
                                sb.append("\n")
                            }
                            tvLyrics.text = sb
                            startPlainScroll(state)
                        }
                    }
                }
            }
        }
    }

    private fun updateAlbumArt(state: LyricsState) {
        val art = state.albumArt
        if (art != null) {
            ivAlbumArt.setImageBitmap(art)
            ivAlbumArt.visibility = View.VISIBLE
            halo.visibility = View.VISIBLE
        } else {
            ivAlbumArt.setImageDrawable(null)
            ivAlbumArt.visibility = View.GONE
            halo.visibility = View.GONE
        }
    }

    private fun updateDelayDisplay(offsetMs: Long) {
        val sign = when {
            offsetMs > 0 -> "+"
            else -> ""
        }
        tvDelay.text = "Phone Sync: ${sign}${offsetMs}ms"
        findViewById<TextView>(R.id.tv_tb_delay).text = "%s%.1fs".format(sign, offsetMs / 1000.0)
    }

    private fun renderSyncedLyrics(state: LyricsState) {
        val ssb = SpannableStringBuilder()
        val hasKaraoke = state.lines.any { it.words.isNotEmpty() }
        val highlightColor = palette.accent
        val highlightBg = palette.chip
        val dimColor = palette.dim

        state.lines.forEachIndexed { i, line ->
            val isCurrentLine = i == state.currentIndex
            val lineStart = ssb.length

            if (isCurrentLine) {
                ssb.append("▶  ")
            } else {
                ssb.append("    ")
            }

            if (isCurrentLine && hasKaraoke && line.words.isNotEmpty()) {
                line.words.forEachIndexed { wi, word ->
                    val wordStart = ssb.length
                    ssb.append(word.text)
                    val wordEnd = ssb.length

                    if (wi == state.currentWordIndex) {
                        ssb.setSpan(
                            StyleSpan(Typeface.BOLD),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ssb.setSpan(
                            ForegroundColorSpan(palette.text),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ssb.setSpan(
                            BackgroundColorSpan(highlightBg),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }

                    if (wi < line.words.size - 1) ssb.append(" ")
                }
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                ssb.append(line.text)
                if (isCurrentLine) {
                    ssb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        lineStart, ssb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            ssb.setSpan(
                ForegroundColorSpan(if (isCurrentLine) palette.accent else dimColor),
                lineStart, ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val translatedLine = state.translatedLines?.getOrNull(i)
            if (!translatedLine.isNullOrBlank()) {
                ssb.append("\n")
                val tlStart = ssb.length
                ssb.append("    $translatedLine")
                ssb.setSpan(
                    RelativeSizeSpan(0.8f),
                    tlStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ssb.setSpan(
                    ForegroundColorSpan(dimColor),
                    tlStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            ssb.append("\n\n")
        }

        tvLyrics.text = ssb

        if (state.currentIndex != lastScrolledIndex && state.currentIndex >= 0) {
            lastScrolledIndex = state.currentIndex
            if (!userScrolling) {
                autoScrollToCurrentLine(state.currentIndex, state.lines.size)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    override fun onDestroy() {
        stopPlainScroll()
        super.onDestroy()
    }

    private fun updatePermissionUi() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            tvStatus.visibility = View.GONE
            btnPermission.visibility = View.GONE
        } else {
            tvStatus.text = "⚠  Notification access required"
            tvStatus.setBackgroundResource(R.drawable.bg_status_warn)
            tvStatus.visibility = View.VISIBLE
            btnPermission.visibility = View.VISIBLE
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val component = ComponentName(this, MediaListenerService::class.java)
        return flat?.contains(component.flattenToString()) == true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollDetection() {
        scrollView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    userTouching = true
                    handler.removeCallbacks(scrollResetRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    if (userScrolling) {
                        handler.removeCallbacks(scrollResetRunnable)
                        handler.postDelayed(scrollResetRunnable, 8000)
                    }
                }
            }
            false
        }

        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (userTouching && !userScrolling) {
                userScrolling = true
                btnJumpToCurrent.visibility = View.VISIBLE
            }
        }

        btnJumpToCurrent.setOnClickListener {
            userScrolling = false
            btnJumpToCurrent.visibility = View.GONE
            handler.removeCallbacks(scrollResetRunnable)
            val state = mediaTracker.state.value
            if (state.currentIndex >= 0) {
                lastScrolledIndex = state.currentIndex
                autoScrollToCurrentLine(state.currentIndex, state.lines.size)
            }
        }
    }

    private fun autoScrollToCurrentLine(currentIndex: Int, totalLines: Int) {
        if (totalLines == 0) return
        scrollView.post {
            val targetY = (currentIndex.toFloat() / totalLines * tvLyrics.height).toInt()
            val offset = scrollView.height / 3
            scrollView.smoothScrollTo(0, maxOf(0, targetY - offset))
        }
    }

    private fun updateAaDelayDisplay() {
        val sign = if (aaOffsetMs > 0) "+" else ""
        tvAaDelay.text = "${sign}${aaOffsetMs}ms"
    }

    private fun selectFont(family: String) {
        lyricsFontFamily = family
        saveFontSettings()
        applyFontSettings()
        updateFontButtonHighlights()
    }

    private fun saveFontSettings() {
        prefs.edit()
            .putInt("lyrics_font_size", lyricsFontSizeSp)
            .putString("lyrics_font_family", lyricsFontFamily)
            .apply()
    }

    private fun applyFontSettings() {
        tvLyrics.textSize = lyricsFontSizeSp.toFloat()
        tvLyrics.typeface = Typeface.create(lyricsFontFamily, Typeface.NORMAL)
        tvFontSize.text = "${lyricsFontSizeSp}sp"
    }

    private fun updateFontButtonHighlights() {
        fontButtons.forEach { (family, button) ->
            (button as? MaterialButton)?.let { ThemeStyler.styleChip(it, palette, family == lyricsFontFamily) }
        }
    }

    /** 外觀 (深色 / 淺色 / 跟隨系統) and 配色 chips in the settings panel, plus the header toggle. */
    private fun setupAppearanceControls() {
        val modeButtons = mapOf(
            Themes.MODE_DARK to findViewById<MaterialButton>(R.id.btn_mode_dark),
            Themes.MODE_LIGHT to findViewById<MaterialButton>(R.id.btn_mode_light),
            Themes.MODE_SYSTEM to findViewById<MaterialButton>(R.id.btn_mode_system)
        )
        val paletteButtons = mapOf(
            "indigo" to findViewById<MaterialButton>(R.id.btn_pal_indigo),
            "ocean" to findViewById<MaterialButton>(R.id.btn_pal_ocean),
            "midnight" to findViewById<MaterialButton>(R.id.btn_pal_midnight),
            "graphite" to findViewById<MaterialButton>(R.id.btn_pal_graphite)
        )
        val mode = Themes.mode(this)
        val paletteId = Themes.paletteId(this)
        modeButtons.forEach { (id, b) ->
            ThemeStyler.styleChip(b, palette, id == mode)
            b.setOnClickListener { changeMode(id) }
        }
        paletteButtons.forEach { (id, b) ->
            ThemeStyler.styleChip(b, palette, id == paletteId)
            b.setOnClickListener {
                if (id != Themes.paletteId(this)) {
                    Themes.setPalette(this, id)
                    reopenWithPanel()
                }
            }
        }
        findViewById<ImageButton>(R.id.btn_theme_cycle).setOnClickListener {
            val order = Themes.MODES.keys.toList()
            val next = order[(order.indexOf(Themes.mode(this)) + 1) % order.size]
            Toast.makeText(this, "外觀：${Themes.MODES[next]}", Toast.LENGTH_SHORT).show()
            changeMode(next)
        }
    }

    private fun changeMode(mode: String) {
        if (mode == Themes.mode(this)) return
        prefs.edit().putBoolean(KEY_PANEL_OPEN, fontSettingsPanel.visibility == View.VISIBLE).apply()
        val systemNight = (android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val willBeDark = when (mode) {
            Themes.MODE_DARK -> true
            Themes.MODE_LIGHT -> false
            else -> systemNight
        }
        Themes.setMode(this, mode)
        // AppCompat recreates the activity itself when dark/light really flips;
        // otherwise (e.g. 跟隨系統 resolving to the same) redraw for the chip state.
        if (willBeDark == palette.dark) recreate()
    }

    private fun reopenWithPanel() {
        prefs.edit().putBoolean(KEY_PANEL_OPEN, fontSettingsPanel.visibility == View.VISIBLE).apply()
        recreate()
    }

    private fun onTapSyncPressed() {
        val state = mediaTracker.state.value
        if (state.status != LyricsStatus.FOUND || !state.isPlaying) {
            showSyncStatus("Play a synced song first")
            return
        }

        if (!tapSyncActive) {
            tapSyncActive = true
            tapSyncOffsets.clear()
            val nextIdx = (state.currentIndex + 1).coerceAtMost(state.lines.size - 1)
            tapSyncTargetLineIndex = nextIdx
            setTapSyncActiveStyle(true)
            btnTapSync.text = "⏎  TAP when you hear it (1/3)"
            val lineText = state.lines.getOrNull(nextIdx)?.text?.take(60) ?: "…"
            showSyncStatus("Listen for:\n\"$lineText\"")
        } else {
            val targetLine = state.lines.getOrNull(tapSyncTargetLineIndex)
            if (targetLine != null && targetLine.timeMs > 0) {
                val rawPos = getCurrentRawPositionMs()
                val offset = rawPos - targetLine.timeMs
                tapSyncOffsets.add(offset)
            }

            if (tapSyncOffsets.size >= 3) {
                val avgOffset = tapSyncOffsets.average().toLong()
                mediaTracker.setOffset(avgOffset)
                showSyncStatus("Synced! Offset: ${formatOffset(avgOffset)}")
                tapSyncActive = false
                setTapSyncActiveStyle(false)
                btnTapSync.text = "Tap to Sync"
            } else {
                tapSyncTargetLineIndex = (tapSyncTargetLineIndex + 1).coerceAtMost(state.lines.size - 1)
                btnTapSync.text = "⏎  TAP when you hear it (${tapSyncOffsets.size + 1}/3)"
                val lineText = state.lines.getOrNull(tapSyncTargetLineIndex)?.text?.take(60) ?: "…"
                showSyncStatus("Listen for:\n\"$lineText\"")
            }
        }
    }

    private fun setTapSyncActiveStyle(active: Boolean) {
        (btnTapSync as? MaterialButton)?.let { ThemeStyler.styleChip(it, palette, active) }
    }

    private fun getCurrentRawPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() - mediaTracker.state.value.offsetMs
        } catch (_: Exception) { 0L }
    }

    private val hideSyncStatusRunnable = Runnable {
        if (!tapSyncActive) {
            tvSyncStatus.visibility = View.GONE
        }
    }

    private fun showSyncStatus(text: String) {
        tvSyncStatus.text = text
        tvSyncStatus.visibility = View.VISIBLE
        handler.removeCallbacks(hideSyncStatusRunnable)
        handler.postDelayed(hideSyncStatusRunnable, 8000)
    }

    private fun formatOffset(ms: Long): String {
        val sign = if (ms >= 0) "+" else ""
        return "${sign}${ms}ms"
    }

    private fun startPlainScroll(state: LyricsState) {
        val durationMs = state.track?.durationMs ?: 0
        if (durationMs <= 0 || state.lines.isEmpty()) {
            scrollView.scrollTo(0, 0)
            return
        }

        val trackTitle = state.track?.title
        val isNewTrack = trackTitle != lastPlainTrackTitle
        lastPlainTrackTitle = trackTitle

        tvLyrics.post {
            val maxScroll = tvLyrics.height - scrollView.height
            if (maxScroll <= 0) return@post

            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            val remainingMs = (durationMs - posMs).coerceAtLeast(0)
            val startFraction = posMs.toFloat() / durationMs
            val startScrollY = (startFraction * maxScroll).toInt().coerceIn(0, maxScroll)

            if (isNewTrack || !userScrolling) {
                scrollView.scrollTo(0, startScrollY)
            }

            plainScrollAnimator?.cancel()
            plainScrollAnimator = ValueAnimator.ofInt(startScrollY, maxScroll).apply {
                duration = remainingMs
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    if (!userScrolling) {
                        scrollView.scrollTo(0, anim.animatedValue as Int)
                    }
                }
                start()
                if (!state.isPlaying) pause()
            }
        }
    }

    private fun stopPlainScroll() {
        plainScrollAnimator?.cancel()
        plainScrollAnimator = null
        lastPlainTrackTitle = null
    }

    companion object {
        /** Keeps the settings panel open across a theme change (recreate). */
        private const val KEY_PANEL_OPEN = "ui_panel_open_once"
    }
}
