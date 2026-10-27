package com.twig.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistExport
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.databinding.ItemPlaylistRowBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playlist side drawer (shared by the player page [MusicPlayerActivity] and the list page
 * [PlaylistActivity]): lists Now Playing / Favorites / user playlists + "New"; tapping opens the
 * corresponding list page, long-press to resume / export / rename / delete; [exitRow] is the bottom "Exit
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
    class ExportState : ViewModel() { var id: String? = null }
    private val exportState = ViewModelProvider(activity)[ExportState::class.java].also {
        activity.savedStateRegistry.consumeRestoredStateForKey(EXPORT_STATE)?.let { state ->
            it.id = state.getString("id")
        }
    }
    private var exportId: String?
        get() = exportState.id
        set(value) { exportState.id = value }
    private val exportPicker = activity.activityResultRegistry.register(
        EXPORT_STATE, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val id = exportId
        exportId = null
        if (result.resultCode == android.app.Activity.RESULT_OK && id != null) {
            val data = result.data
            val scheme = data?.getStringExtra(PickerActivity.EXTRA_PICKED_SCHEME)
            val path = data?.getStringExtra(PickerActivity.EXTRA_PICKED_PATH)
            if (scheme != null && path != null) export(id, XFile(scheme, path, isDir = true))
        }
    }

    init {
        activity.savedStateRegistry.unregisterSavedStateProvider(EXPORT_STATE)
        activity.savedStateRegistry.registerSavedStateProvider(EXPORT_STATE) {
            android.os.Bundle().apply { putString("id", exportId) }
        }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        exitRow.setOnClickListener { MusicUi.exit(activity) }
    }

    fun reload() = adapter.reload()

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private var rows: List<Playlist?> = emptyList() // null = "new playlist" row

        fun reload() {
            val now = PlaylistStore.get(activity, Playlist.TEMP) ?: PlaylistStore.get(activity, Playlist.NOW)
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
            h.b.name.text = if (pl.temporary) activity.getString(R.string.music_temporary_playlist) else pl.name
            h.b.count.text = "${pl.tracks.size}"
            h.b.root.setOnClickListener { drawer.closeDrawers(); PlaylistActivity.start(activity, pl.id) }
            h.b.root.setOnLongClickListener { rowMenu(pl); true }
        }
    }

    private inner class VH(val b: ItemPlaylistRowBinding) : RecyclerView.ViewHolder(b.root)

    private fun rowMenu(pl: Playlist) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += activity.getString(R.string.music_resume) to { resume(pl) }
        if (!pl.temporary) opts += activity.getString(R.string.music_export) to {
            exportId = pl.id
            exportPicker.launch(PickerActivity.dirIntent(activity, activity.getString(R.string.music_export)))
        }
        // "Now Playing" is a temporary list; renaming it means "save" it as a normal list — the menu label changes accordingly.
        if (!pl.isFav && !pl.temporary) opts += activity.getString(if (pl.isNow) R.string.music_save else R.string.music_rename) to { rename(pl) }
        if (!pl.fixed) opts += activity.getString(R.string.music_delete) to { confirmDelete(pl) }
        AlertDialog.Builder(activity)
            .setTitle(pl.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .show()
    }

    private fun export(id: String, dir: XFile) {
        if (id == Playlist.TEMP) return
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pl = if (id == Playlist.FAV) PlaylistStore.fav(activity) else PlaylistStore.get(activity, id)
                    requireNotNull(pl) { activity.getString(R.string.music_export_missing) }
                    check(!pl.temporary)
                    // Export stored locations without reading audio or requiring its server to be online.
                    val tracks = pl.tracks.map { track ->
                        require('\r' !in track.path && '\n' !in track.path) {
                            activity.getString(R.string.music_export_invalid_path)
                        }
                        val scheme = when (track.kind) {
                            "local" -> "file"
                            "saf" -> "saf"
                            "share" -> "share"
                            "conn" -> {
                                val conn = Connections.find(activity, track.connLabel)
                                requireNotNull(conn) { activity.getString(R.string.music_export_source_missing, track.name) }
                                Connections.schemeOf(conn)
                            }
                            else -> error(activity.getString(R.string.music_export_source_missing, track.name))
                        }
                        track to XFile(scheme, track.path, isDir = false)
                    }
                    val text = PlaylistExport.render(dir, tracks) { Connections.find(activity, it.connLabel) }
                    val fs = FsRegistry.of(dir)
                    // A failed listing must abort, rather than risk overwriting an existing file.
                    val names = fs.list(dir).map { it.name.lowercase(java.util.Locale.ROOT) }.toHashSet()
                    val base = PlaylistExport.fileName(pl.name).removeSuffix(".m3u8")
                    var name = "$base.m3u8"
                    var n = 2
                    while (name.lowercase(java.util.Locale.ROOT) in names) name = "$base-${n++}.m3u8"
                    val target = fs.createFile(dir, name)
                    fs.openOutput(target).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    target.name
                }
            }
            val message = result.fold(
                onSuccess = { activity.getString(R.string.music_export_done, it) },
                onFailure = { activity.getString(R.string.music_export_failed, it.message.orEmpty()) },
            )
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
        }
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
            .create().showNameInput(input)
    }

    private fun confirmDelete(pl: Playlist) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.music_confirm_delete, pl.name))
            .setPositiveButton(android.R.string.ok) { _, _ -> PlaylistStore.delete(activity, pl.id); reload() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        private const val EXPORT_STATE = "playlist_export"
    }
}
