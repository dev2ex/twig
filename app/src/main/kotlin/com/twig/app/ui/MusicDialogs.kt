package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.twig.app.FileInfo
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.R
import com.twig.core.XFile

/** Shared dialogs for the music UI: track info / share / send to playlist (new playlist allowed).
 *  [showInfo] is source-independent (content comes entirely from [FileInfo]); the image viewer
 *  also reuses it, only the title differs. */
object MusicDialogs {

    fun showInfo(activity: Activity, file: XFile, titleRes: Int = R.string.music_info) {
        Thread {
            val details = runCatching { FileInfo.load(activity, file) }.getOrNull()
            val msg = details?.sections?.joinToString("\n\n") { s ->
                s.title + "\n" + s.rows.joinToString("\n") { "  ${it.first}: ${it.second}" }
            } ?: file.name
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                AlertDialog.Builder(activity)
                    .setTitle(titleRes)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Whether "go to containing folder" works for this source — when it does not, **the menu
     * entry is omitted**, instead of being shown and then failing.
     *
     * This action is fundamentally `revealPath(file.parentPath, focus = file)`, and `revealPath`
     * builds the ancestor chain by **splitting the path level by level on '/'**, treating each
     * level as a tree row to list. So the criterion is "is the path of this source a real path,
     * and is each level an actual tree row". Only local and ordinary file servers
     * (SMB/FTP/SFTP/WebDAV/S3) qualify.
     *
     * - **★ Document tree (SAF) does not qualify**: in a document URI, the '/' between a child
     *   and its parent document id is encoded as %2F, and [XFile.parentPath] slices out half a
     *   URI — not any row (same lesson as the `siblingsKey` one). And SAF **has no API for
     *   "get parent directory"**: the only way is to recursively list the whole tree from the
     *   authorized root looking for the parent, with unpredictable cost for deep directories.
     * - **★ Media servers (Jellyfin/Emby) also do not qualify**. Its path looks like an ancestor
     *   chain, but **the first segment may be a synthetic prefix with no actual row in the tree**:
     *   libraries are placed directly at the root (`libEntry(it, "/lib")` in `rootEntries`), so
     *   a track's path is `/lib/<libraryid>/<albumid>/<trackid>`, and the first level `/lib`
     *   sliced from this is no one — listing it returns
     *   `Unknown Jellyfin directory: /lib` (the else branch of `topLevelItems`). Fixing this would
     *   require the backend to declare its own "ancestor chain on the tree" (a new capability
     *   interface), and the media server tree is **the server's own organization**
     *   (Continue Watching / Libraries / Seasons), with the same item simultaneously attached
     *   to multiple virtual paths, so "jump back to its containing folder" has no unique answer —
     *   same judgment as excluding media servers from "go to path" (`supportsGoto`).
     */
    fun canLocate(file: XFile): Boolean = when {
        file.scheme == "file" -> true
        // Only works for saved connections (ofScheme is a process-wide reverse lookup; if it can
        // play, it has been ensured); media servers excluded for the reason above
        else -> com.twig.app.Connections.ofScheme(file.scheme)?.isMediaServer() == false
    }

    /**
     * Jumps to the file manager, expands the tree to locate the track's containing directory, and
     * scrolls to the track itself (for network sources, the server must have been connected this
     * session). Entry point is gated by [canLocate].
     */
    fun locateInFileManager(ctx: Context, file: XFile) {
        ctx.startActivity(
            com.twig.app.MainActivity.revealIntent(ctx, file.scheme, file.parentPath, file.path),
        )
    }

    fun share(ctx: Context, file: XFile) = com.twig.app.OpenFiles.share(ctx, file)

    /** Choose a target playlist (or create one) and add [tracks] to it. */
    fun sendToPlaylist(activity: Activity, tracks: List<PlaylistTrack>) {
        val lists = PlaylistStore.all(activity).filter { it.id != Playlist.NOW }
            .sortedBy { if (it.isFav) 0 else 1 }
        val names = lists.map { it.name } + activity.getString(R.string.music_new_playlist)
        AlertDialog.Builder(activity)
            .setTitle(R.string.music_send_to)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < lists.size) {
                    PlaylistStore.addTracks(activity, lists[which].id, tracks)
                } else {
                    promptNewList(activity) { name ->
                        PlaylistStore.create(activity, name, tracks)
                    }
                }
            }
            .show()
    }

    fun promptNewList(activity: Activity, onName: (String) -> Unit) {
        val input = EditText(activity).apply { hint = activity.getString(R.string.music_new_playlist_hint) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.music_new_playlist)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
