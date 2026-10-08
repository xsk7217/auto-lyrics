package com.autolyrics

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.autolyrics.lyrics.ChineseConverter
import com.autolyrics.lyrics.providers.LyricsProviders
import com.autolyrics.media.MediaTracker
import com.autolyrics.media.RecognizedSongs
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-only lyrics lookup: type a song, or pick one Pixel "Now Playing"
 * recognised. Shows the full lyrics text; nothing is played.
 */
class LyricsSearchActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etTitle: EditText
    private lateinit var etArtist: EditText
    private lateinit var layoutRecent: LinearLayout
    private lateinit var tvRecentHint: TextView
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultSource: TextView
    private lateinit var tvResult: TextView
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_search)
        prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)

        etTitle = findViewById(R.id.et_title)
        etArtist = findViewById(R.id.et_artist)
        layoutRecent = findViewById(R.id.layout_recent)
        tvRecentHint = findViewById(R.id.tv_recent_hint)
        tvResultTitle = findViewById(R.id.tv_result_title)
        tvResultSource = findViewById(R.id.tv_result_source)
        tvResult = findViewById(R.id.tv_result)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_search).setOnClickListener { search() }
        etArtist.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }

    override fun onResume() {
        super.onResume()
        RecognizedSongs.listener = { runOnUiThread { showRecent() } }
        showRecent()
    }

    override fun onPause() {
        RecognizedSongs.listener = null
        super.onPause()
    }

    private fun showRecent() {
        layoutRecent.removeAllViews()
        val songs = RecognizedSongs.list(this)
        tvRecentHint.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        for (song in songs) {
            val btn = Button(this).apply {
                text = if (song.artist.isNotBlank()) "${song.title} · ${song.artist}" else song.title
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setTextColor(0xFFCCCCDD.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A3E.toInt())
                setOnClickListener {
                    etTitle.setText(song.title)
                    etArtist.setText(song.artist)
                    search()
                }
            }
            layoutRecent.addView(btn)
        }
    }

    private fun search() {
        val title = etTitle.text.toString().trim()
        val artist = etArtist.text.toString().trim()
        if (title.isEmpty()) {
            etTitle.error = "請輸入歌名"
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etTitle.windowToken, 0)

        tvResultTitle.text = if (artist.isNotEmpty()) "$title · $artist" else title
        tvResultTitle.visibility = View.VISIBLE
        tvResultSource.visibility = View.GONE
        tvResult.text = "搜尋中…"

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    LyricsProviders.fetch(prefs, TrackInfo(title, artist, "", 0))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }
            if (result == null || result.lines.isEmpty()) {
                tvResult.text = "找不到歌詞。可以試試加上或修改歌手名稱，或到「歌詞來源與進階設定」開啟更多來源。"
                return@launch
            }
            val lines = if (prefs.getBoolean(MediaTracker.PREF_TRADITIONAL, true)) {
                ChineseConverter.linesToTraditional(result.lines)
            } else result.lines
            tvResultSource.text = result.source +
                if (result.status == LyricsStatus.PLAIN_ONLY) "" else "（只顯示文字，不含時間軸）"
            tvResultSource.visibility = View.VISIBLE
            tvResult.text = lines.joinToString("\n") { it.text }
            val scroll = findViewById<NestedScrollView>(R.id.scroll)
            scroll.post { scroll.smoothScrollTo(0, tvResultTitle.top) }
        }
    }
}
