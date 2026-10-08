package com.autolyrics

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autolyrics.lyrics.ChineseConverter
import com.autolyrics.lyrics.providers.LyricsProviders
import com.autolyrics.media.MediaTracker
import java.util.Collections

/** Phone settings: pick lyrics sources (checkbox) and their order (drag). */
class LyricsSourcesActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val entries = mutableListOf<LyricsProviders.Entry>()
    private lateinit var adapter: SourceAdapter
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_sources)
        prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        entries.addAll(LyricsProviders.load(prefs))
        adapter = SourceAdapter()
        val rv = findViewById<RecyclerView>(R.id.rv_sources)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                Collections.swap(entries, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = true

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                save()
                adapter.notifyItemRangeChanged(0, entries.size) // refresh rank numbers
            }
        })
        touchHelper.attachToRecyclerView(rv)

        findViewById<Button>(R.id.btn_reset_sources).setOnClickListener {
            entries.clear()
            entries.addAll(LyricsProviders.defaults())
            adapter.notifyDataSetChanged()
            save()
        }
        findViewById<Button>(R.id.btn_refetch).setOnClickListener {
            MediaTracker.getInstance(this).refetchCurrent()
            Toast.makeText(this, "正在用新的來源設定重抓", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_clear_cache).setOnClickListener {
            MediaTracker.getInstance(this).clearCache()
            Toast.makeText(this, "已清除歌詞快取", Toast.LENGTH_SHORT).show()
        }

        bindSwitch(R.id.switch_lyrics_title, "aa_lyrics_as_title")
        bindSwitch(R.id.switch_compact_header, "aa_compact_header")
        bindSwitch(R.id.switch_traditional, MediaTracker.PREF_TRADITIONAL) {
            MediaTracker.getInstance(this).refetchCurrent()
        }
        bindSwitch(R.id.switch_title_guard, MediaTracker.PREF_TITLE_GUARD)

        findViewById<TextView>(R.id.tv_traditional_status).text =
            if (ChineseConverter.isAvailable) "此手機支援簡轉繁" else "此手機的系統不支援簡轉繁，開關無效"
    }

    private fun bindSwitch(id: Int, key: String, onChange: (() -> Unit)? = null) {
        val sw = findViewById<SwitchCompat>(id)
        sw.isChecked = prefs.getBoolean(key, true)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            onChange?.invoke()
        }
    }

    private fun save() = LyricsProviders.save(prefs, entries)

    private inner class SourceHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rank: TextView = view.findViewById(R.id.tv_rank)
        val checkBox: CheckBox = view.findViewById(R.id.cb_enabled)
        val name: TextView = view.findViewById(R.id.tv_name)
        val desc: TextView = view.findViewById(R.id.tv_desc)
        val handle: View = view.findViewById(R.id.drag_handle)
    }

    private inner class SourceAdapter : RecyclerView.Adapter<SourceHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceHolder =
            SourceHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_lyrics_source, parent, false))

        override fun getItemCount() = entries.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: SourceHolder, position: Int) {
            val entry = entries[position]
            holder.rank.text = "${position + 1}"
            holder.name.text = entry.provider.displayName
            holder.desc.text = entry.provider.description
            holder.itemView.alpha = if (entry.enabled) 1f else 0.5f

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = entry.enabled
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                entries[pos] = entries[pos].copy(enabled = checked)
                holder.itemView.alpha = if (checked) 1f else 0.5f
                save()
            }

            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
        }
    }
}
