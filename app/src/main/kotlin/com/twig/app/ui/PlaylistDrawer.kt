package com.twig.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.R
import com.twig.app.databinding.ItemPlaylistRowBinding

/**
 * Playlist side drawer (shared by the player page [MusicPlayerActivity] and the list page
 * [PlaylistActivity]): lists Now Playing / Favorites / user playlists + "New"; tapping opens the
 * corresponding list page, long-press to resume / rename / delete; [exitRow] is the bottom "Exit
 * player" row.
 */
@UnstableApi
class PlaylistDrawer(
    private val activity: AppCompatActivity,
    private val drawer: DrawerLayout,
    private val list: RecyclerView,
    exitRow: View,
) {
    private val adapter = Adapter()

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        exitRow.setOnClickListener { MusicUi.exit(activity) }
    }

    fun reload() = adapter.reload()

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private var rows: List<Playlist?> = emptyList() // null = "new playlist" row

        fun reload() {
            val now = PlaylistStore.get(activity, Playlist.NOW)
            val fav = PlaylistStore.fav(activity)
            val users = PlaylistStore.all(activity).filter { it.id != Playlist.NOW && it.id != Playlist.FAV }
            rows = (listOfNotNull(now, fav) + users) + listOf<Playlist?>(null)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemPlaylistRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val pl = rows[position]
            if (pl == null) {
                h.b.icon.setImageResource(R.drawable.ic_playlist_add)
                h.b.name.text = activity.getString(R.string.music_new_playlist)
                h.b.count.text = ""
                h.b.root.setOnClickListener {
                    MusicDialogs.promptNewList(activity) { name -> PlaylistStore.create(activity, name); reload() }
                }
                h.b.root.setOnLongClickListener { false }
                return
            }
            h.b.icon.setImageResource(
                when {
                    pl.isNow -> R.drawable.ic_music_note
                    pl.isFav -> R.drawable.ic_heart_filled
                    else -> R.drawable.ic_queue
                },
            )
            h.b.name.text = pl.name
            h.b.count.text = "${pl.tracks.size}"
            h.b.root.setOnClickListener { drawer.closeDrawers(); PlaylistActivity.start(activity, pl.id) }
            h.b.root.setOnLongClickListener { rowMenu(pl); true }
        }
    }

    private inner class VH(val b: ItemPlaylistRowBinding) : RecyclerView.ViewHolder(b.root)

    private fun rowMenu(pl: Playlist) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += activity.getString(R.string.music_resume) to { resume(pl) }
        // "Now Playing" is a temporary list; renaming it means "save" it as a normal list — the menu label changes accordingly.
        if (!pl.isFav) opts += activity.getString(if (pl.isNow) R.string.music_save else R.string.music_rename) to { rename(pl) }
        if (!pl.fixed) opts += activity.getString(R.string.music_delete) to { confirmDelete(pl) }
        AlertDialog.Builder(activity)
            .setTitle(pl.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .show()
    }

    private fun resume(pl: Playlist) {
        val fresh = PlaylistStore.get(activity, pl.id) ?: return
        if (fresh.tracks.isEmpty()) return
        MusicEngine.play(activity, fresh, fresh.lastIndex, fresh.lastPosMs, autoPlay = true)
        drawer.closeDrawers()
    }

    private fun rename(pl: Playlist) {
        val input = android.widget.EditText(activity).apply { setText(pl.name) }
        AlertDialog.Builder(activity)
            .setTitle(if (pl.isNow) R.string.music_save else R.string.music_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val promoted = PlaylistStore.rename(activity, pl.id, name)
                    // If the renamed list is the current playback queue (especially when NOW gets
                    // promoted to a new uuid and the original "now" is removed from the store),
                    // sync queueId to the new id, otherwise the player page's "current playlist"
                    // button would look up the list with the old id and fail to open it.
                    if (promoted != null) MusicEngine.onQueueRenamed(pl.id, promoted.id)
                    reload()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDelete(pl: Playlist) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.music_confirm_delete, pl.name))
            .setPositiveButton(android.R.string.ok) { _, _ -> PlaylistStore.delete(activity, pl.id); reload() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
