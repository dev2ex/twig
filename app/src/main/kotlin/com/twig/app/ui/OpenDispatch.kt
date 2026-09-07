package com.twig.app.ui

import android.content.Context
import android.content.Intent
import com.twig.app.Connections
import com.twig.app.MainActivity
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.ShareSourceFileSystem
import com.twig.app.SortSpec
import com.twig.core.FsRegistry
import com.twig.core.XFile

/**
 * Auto-dispatch an arbitrary [XFile] to the matching built-in viewer/player — the same
 * per-type rules a tap in the pane uses ([PaneFragment.open]) and "Open with Twig" uses
 * ([ViewIntentActivity]). Pulled out so a third entry point (desktop file shortcuts,
 * [OpenShortcutActivity]) doesn't grow its own copy of the type table.
 *
 * Every function here only needs a [Context] (never an Activity-specific API), so any
 * headless relay Activity can call straight into it.
 */
object OpenDispatch {

    /**
     * @param mime Fallback used only when the extension is unrecognised (a content:// display
     *   name often lacks a suffix); ignored otherwise.
     */
    fun open(ctx: Context, file: XFile, mime: String? = null) {
        when {
            // APK is also a zip, but the tree by default doesn't expand it as an archive
            // (see PaneViewModel.expandableArchive); mounting it would just yield a dead
            // row you can't open. Handing it to the system installer fits the use case better.
            OpenFiles.isApk(file) -> if (!OpenFiles.openWith(ctx, file)) HexViewerActivity.start(ctx, file)
            com.twig.fs.archive.Archives.isArchive(file) -> mountArchive(ctx, file)
            OpenFiles.canViewPdf(file) -> PdfViewerActivity.start(ctx, file)
            OpenFiles.isText(file) -> TextViewerActivity.start(ctx, file)
            OpenFiles.isImage(file) -> openImage(ctx, file)
            OpenFiles.isPlaylist(file) -> openM3u(ctx, file)
            OpenFiles.isAudio(file) -> openAudio(ctx, file)
            OpenFiles.isVideo(file) -> MediaPlayerActivity.start(ctx, file)
            // A content:// display name often has no suffix, so the extension rules above miss;
            // PDF is the one application/* type we open ourselves, and it is matched whole
            // rather than by major type (an "application" bucket would swallow everything).
            mime?.lowercase() == "application/pdf" -> PdfViewerActivity.start(ctx, file)
            else -> when (mime?.substringBefore('/')?.lowercase()) {
                "text" -> TextViewerActivity.start(ctx, file)
                "image" -> openImage(ctx, file)
                "audio" -> openAudio(ctx, file)
                "video" -> MediaPlayerActivity.start(ctx, file)
                else -> HexViewerActivity.start(ctx, file) // unrecognised types can at least show their bytes
            }
        }
    }

    /** Local images also bring along siblings in the same directory, sorted by the pane's order, so swiping left/right pages through them; non-local sources just show this one. */
    private fun openImage(ctx: Context, file: XFile) {
        val siblings = localSiblings(ctx, file) { OpenFiles.isImage(it) }
        val index = siblings.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        // No file tree to sync selection with here, so don't show the "Select" entry.
        ImageViewerActivity.start(ctx, siblings.ifEmpty { listOf(file) }, index, allowSelect = false)
    }

    /**
     * Audio always goes into the music player (cover / lyrics / waveform / background
     * playback are all there): local sources bring along same-directory siblings into
     * "Now playing" for prev/next; other sources queue just this one track.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openAudio(ctx: Context, file: XFile) {
        val siblings = localSiblings(ctx, file) { OpenFiles.isAudio(it) }.ifEmpty { listOf(file) }
        val tracks = siblings.map { trackOf(it) }
        val startIndex = siblings.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        val listName = if (file.scheme == "file") {
            file.parentPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        } else file.name
        val now = PlaylistStore.setNow(ctx, listName, tracks)
        MusicEngine.play(ctx, now, startIndex, autoPlay = true)
        ctx.startActivity(Intent(ctx, MusicPlayerActivity::class.java))
    }

    /**
     * Track descriptor for the playlist store — "local" (plain path), "share" (content:// from
     * another app, carried by [ShareSourceFileSystem]), or "conn" (a saved server connection;
     * needs [Connections.ofScheme] for the label the playlist persists it under). By the time
     * this runs the source is already connected (dispatch happens after that), so the lookup
     * resolves.
     */
    private fun trackOf(f: XFile) = PlaylistTrack(
        kind = when (f.scheme) {
            "file" -> "local"
            ShareSourceFileSystem.SCHEME -> "share"
            else -> "conn"
        },
        path = f.path,
        connLabel = if (f.scheme != "file" && f.scheme != ShareSourceFileSystem.SCHEME) {
            Connections.ofScheme(f.scheme)?.label().orEmpty()
        } else {
            ""
        },
        size = f.size, lastModified = f.lastModified,
        displayName = if (f.scheme == "file") "" else f.name,
    )

    /**
     * m3u/m3u8: only local playlists yield useful content (entries are mostly relative
     * paths and must be persistable into the playlist); other sources are treated as
     * text. Parsing is a small local file read, done synchronously — callers only reach
     * this from a "read and go" relay, never from the main UI thread's hot path.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openM3u(ctx: Context, file: XFile) {
        if (file.scheme != "file") { TextViewerActivity.start(ctx, file); return }
        val tracks = com.twig.app.M3uPlaylist.parse(file).filter { it.scheme == "file" }.map { trackOf(it) }
        if (tracks.isEmpty()) {
            android.widget.Toast.makeText(ctx, com.twig.app.R.string.music_no_playable, android.widget.Toast.LENGTH_SHORT).show()
            TextViewerActivity.start(ctx, file)
            return
        }
        val name = file.name.substringBeforeLast('.').ifEmpty { file.name }
        MusicEngine.play(ctx, PlaylistStore.setNow(ctx, name, tracks), 0, autoPlay = true)
        ctx.startActivity(Intent(ctx, MusicPlayerActivity::class.java))
    }

    /** Archive: return to the main screen and mount it as a single row in the current pane, expanding in place (same path as tapping an archive in the tree). */
    private fun mountArchive(ctx: Context, file: XFile) {
        ctx.startActivity(MainActivity.mountIntent(ctx, file))
    }

    /**
     * Same-kind files in the same directory (using the pane's current sort);
     * non-local sources return an empty list — a file reached from outside the tree
     * (content:// share-in, a desktop shortcut) is just an isolated entry, with no
     * "same directory" concept worth paying a network listing for.
     */
    private fun localSiblings(ctx: Context, file: XFile, keep: (XFile) -> Boolean): List<XFile> {
        if (file.scheme != "file") return emptyList()
        val dir = XFile("file", file.parentPath, isDir = true)
        val kids = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        val showHidden = Prefs.showHidden(ctx)
        return SortSpec.load(ctx)
            .sort(kids.filter { !it.isDir && keep(it) && (showHidden || !it.name.startsWith(".")) })
    }
}
