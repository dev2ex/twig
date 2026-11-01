package com.twig.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.twig.app.ConnectionStore
import com.twig.app.HistoryEntry
import com.twig.app.HistoryStore
import com.twig.app.PlaybackMark
import com.twig.app.PlaybackStore
import com.twig.app.R
import com.twig.app.databinding.DialogHistoryBinding
import com.twig.app.databinding.ItemHistoryBinding
import com.twig.app.formatLocationPath
import com.twig.core.XFile

/** Fixed tabs keep recent locations one tap away even with a long video history. */
internal object HistoryDialog {
    fun show(
        ctx: Context,
        openLocation: (HistoryEntry) -> Unit,
        openVideo: (PlaybackMark, Boolean) -> Unit,
        continueWatching: Boolean = false,
    ): AlertDialog {
        val inflater = LayoutInflater.from(ctx)
        val b = DialogHistoryBinding.inflate(inflater)
        b.historyContent.layoutParams.height = minOf(
            (320 * ctx.resources.displayMetrics.density).toInt(),
            ctx.resources.displayMetrics.heightPixels / 2,
        )
        val conns = ConnectionStore.all(ctx).associateBy { it.label() }
        var locations = HistoryStore.all(ctx)
        var videos = PlaybackStore.recent(ctx)
        var videoTab = false
        val locationAdapter = object : BaseAdapter() {
            override fun getCount() = locations.size
            override fun getItem(position: Int) = locations[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView?.let(ItemHistoryBinding::bind)
                    ?: ItemHistoryBinding.inflate(inflater, parent, false)
                val entry = locations[position]
                val conn = conns[entry.connLabel]
                val path = formatLocationPath(entry.connLabel, entry.path, conn)
                row.name.text = if (entry.kind == "git") ctx.getString(R.string.history_git_prefix, path) else path
                row.icon.setImageResource(when {
                    entry.kind == "git" -> R.drawable.ic_git
                    entry.connLabel.isEmpty() -> FileIcons.sourceIconOfType("file")
                    else -> FileIcons.sourceIconOfType(conn?.type ?: entry.connLabel.substringBefore("://"))
                })
                row.detail.visibility = View.GONE
                return row.root
            }
        }
        val videoAdapter = object : BaseAdapter() {
            override fun getCount() = videos.size
            override fun getItem(position: Int) = videos[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView?.let(ItemHistoryBinding::bind)
                    ?: ItemHistoryBinding.inflate(inflater, parent, false)
                val video = videos[position]
                row.name.text = video.name
                row.icon.setImageResource(R.drawable.ic_play)
                row.detail.visibility = View.VISIBLE
                row.detail.text = ctx.getString(R.string.history_video_progress,
                    time(video.posMs), time(video.durMs),
                    formatLocationPath(video.connLabel, video.path, conns[video.connLabel]))
                return row.root
            }
        }
        b.locationsList.adapter = locationAdapter
        b.videosList.adapter = videoAdapter

        fun selectTab(continueWatching: Boolean) {
            videoTab = continueWatching
            b.locationsList.visibility = if (!videoTab && locations.isNotEmpty()) View.VISIBLE else View.GONE
            b.videosList.visibility = if (videoTab && videos.isNotEmpty()) View.VISIBLE else View.GONE
            val empty = if (videoTab) videos.isEmpty() else locations.isEmpty()
            b.historyEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            for ((tab, selected) in listOf(b.locationsTab to !videoTab, b.videosTab to videoTab)) {
                tab.isSelected = selected
                tab.setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.primary else R.color.text_secondary))
                tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        fun reload() {
            locations = HistoryStore.all(ctx)
            videos = PlaybackStore.recent(ctx)
            locationAdapter.notifyDataSetChanged()
            videoAdapter.notifyDataSetChanged()
            selectTab(videoTab)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.history_title)
            .setView(b.root)
            .setNeutralButton(R.string.history_clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        b.locationsTab.setOnClickListener { selectTab(false) }
        b.videosTab.setOnClickListener { selectTab(true) }
        b.locationsList.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            openLocation(locations[pos])
        }
        b.videosList.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            openVideo(videos[pos], false)
        }
        b.locationsList.setOnItemLongClickListener { _, _, pos, _ ->
            HistoryStore.remove(ctx, locations[pos])
            reload()
            true
        }
        b.videosList.setOnItemLongClickListener { _, _, pos, _ ->
            val video = videos[pos]
            AlertDialog.Builder(ctx).setTitle(video.name)
                .setItems(arrayOf(ctx.getString(R.string.history_from_start),
                    ctx.getString(R.string.music_locate), ctx.getString(R.string.history_remove))) { _, which ->
                    when (which) {
                        0 -> { dialog.dismiss(); openVideo(video, true) }
                        1 -> {
                            dialog.dismiss()
                            openLocation(HistoryEntry("dir", XFile("file", video.path, false).parentPath, video.connLabel))
                        }
                        2 -> { PlaybackStore.hide(ctx, video.key); reload() }
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
            true
        }
        selectTab(continueWatching)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            // Clear only the visible tab; video positions remain available for resuming.
            if (videoTab) PlaybackStore.hideRecent(ctx) else HistoryStore.clear(ctx)
            reload()
        }
        return dialog
    }

    private fun time(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
