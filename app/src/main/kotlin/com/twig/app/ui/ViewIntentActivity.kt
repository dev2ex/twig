package com.twig.app.ui

import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.twig.app.MainActivity
import com.twig.app.OpenFiles
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.ShareSourceFileSystem
import com.twig.app.SortSpec
import com.twig.app.TwigApp
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File

/**
 * "Open with Twig" entry point: catches ACTION_VIEW from other apps, wraps
 * file:// / content:// URIs into [XFile], then dispatches to the built-in viewers
 * (text/image/audio/video) or to the main screen (archives are mounted in place
 * and expanded).
 *
 * Displays no UI itself (transparent theme) and finishes immediately after dispatch.
 *
 * ★ The target viewer must start in **the task stack of this Activity** (no
 * FLAG_ACTIVITY_NEW_TASK): the temporary read grant on a content:// URI follows
 * the receiving task stack, so after this Activity finishes, as long as the same
 * stack still has our UI, the grant is still valid; once it lands in a new stack
 * the viewer may SecurityException on the first read.
 */
class ViewIntentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TwigApp.registerBaseFs(this) // on cold start FsRegistry is still empty (main screen never came up)
        val uri = intent?.data
        val file = uri?.let { runCatching { toXFile(it) }.getOrNull() }
        if (file == null) {
            Toast.makeText(this, R.string.err_unsupported_type, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        // If the master password is on, unlock first: this path can open viewers and
        // also mount archives onto the main screen tree; skipping it would mean the
        // master password only locks the front door
        SecurityUi.gate(this) {
            dispatch(file, intent.type)
            finish()
        }
    }

    /** file:// falls straight onto a local source; everything else (content://) is carried by [ShareSourceFileSystem]. */
    private fun toXFile(uri: Uri): XFile? = when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let { p ->
            val f = File(p)
            if (!f.isFile) null
            else XFile("file", f.absolutePath, isDir = false, size = f.length(), lastModified = f.lastModified())
        }
        "content" -> (FsRegistry.of(ShareSourceFileSystem.SCHEME) as ShareSourceFileSystem).wrap(uri)
        else -> null
    }

    /**
     * Dispatch: first by filename extension (same [OpenFiles] rules as tapping a file
     * in the pane); when the extension is unrecognised (content:// display names may
     * lack a suffix) fall back to the caller's MIME major type.
     */
    private fun dispatch(file: XFile, mime: String?) {
        when {
            // APK is also a zip, but the tree by default doesn't expand it as an archive
            // (see PaneViewModel.expandableArchive); mounting it would just yield a dead
            // row you can't open. Handing it to the system installer fits the use case better.
            OpenFiles.isApk(file) -> if (!OpenFiles.openWith(this, file)) HexViewerActivity.start(this, file)
            com.twig.fs.archive.Archives.isArchive(file) -> mountArchive(file)
            OpenFiles.canViewPdf(file) -> PdfViewerActivity.start(this, file)
            OpenFiles.isText(file) -> TextViewerActivity.start(this, file)
            OpenFiles.isImage(file) -> openImage(file)
            OpenFiles.isPlaylist(file) -> openM3u(file)
            OpenFiles.isAudio(file) -> openAudio(file)
            OpenFiles.isVideo(file) -> MediaPlayerActivity.start(this, file)
            // A content:// display name often has no suffix, so the extension rules above miss;
            // PDF is the one application/* type we open ourselves, and it is matched whole
            // rather than by major type (an "application" bucket would swallow everything).
            mime?.lowercase() == "application/pdf" -> PdfViewerActivity.start(this, file)
            else -> when (mime?.substringBefore('/')?.lowercase()) {
                "text" -> TextViewerActivity.start(this, file)
                "image" -> openImage(file)
                "audio" -> openAudio(file)
                "video" -> MediaPlayerActivity.start(this, file)
                else -> HexViewerActivity.start(this, file) // unrecognised types can at least show their bytes
            }
        }
    }

    /** Local images also bring along siblings in the same directory, sorted by the pane's order, so swiping left/right pages through them; non-local sources just show this one. */
    private fun openImage(file: XFile) {
        val siblings = localSiblings(file) { OpenFiles.isImage(it) }
        val index = siblings.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        // Coming in from another app, there's no file tree to sync selection with, so don't show the "Select" entry
        ImageViewerActivity.start(this, siblings.ifEmpty { listOf(file) }, index, allowSelect = false)
    }

    /**
     * Audio always goes into the music player (cover / lyrics / waveform / background
     * playback are all there): for local sources, siblings in the same directory are
     * put into "Now playing", enabling prev/next; content:// only has a lone track,
     * so the queue contains only that one.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openAudio(file: XFile) {
        val siblings = localSiblings(file) { OpenFiles.isAudio(it) }.ifEmpty { listOf(file) }
        val tracks = siblings.map { trackOf(it) }
        val startIndex = siblings.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        val listName = if (file.scheme == "file") {
            file.parentPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        } else file.name
        val now = PlaylistStore.setNow(this, listName, tracks)
        MusicEngine.play(this, now, startIndex, autoPlay = true)
        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    /**
     * Playlist track. content:// uses the "share" kind ([MusicEngine] maps it to
     * [ShareSourceFileSystem]); the name must travel with it — the URI's last segment
     * is the provider's internal id, from which neither the filename nor the
     * extension can be recovered.
     */
    private fun trackOf(f: XFile) = PlaylistTrack(
        kind = if (f.scheme == "file") "local" else "share",
        path = f.path, size = f.size, lastModified = f.lastModified,
        displayName = if (f.scheme == "file") "" else f.name,
    )

    /**
     * m3u/m3u8: only local playlists yield useful content (entries are mostly relative
     * paths and must be persistable into the playlist); other sources are treated as
     * text. Parsing is a small local file read, done synchronously — this relay
     * page is itself a "read and go" screen.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openM3u(file: XFile) {
        if (file.scheme != "file") { TextViewerActivity.start(this, file); return }
        val tracks = com.twig.app.M3uPlaylist.parse(file).filter { it.scheme == "file" }.map { trackOf(it) }
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.music_no_playable, Toast.LENGTH_SHORT).show()
            TextViewerActivity.start(this, file)
            return
        }
        val name = file.name.substringBeforeLast('.').ifEmpty { file.name }
        MusicEngine.play(this, PlaylistStore.setNow(this, name, tracks), 0, autoPlay = true)
        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    /** Archive: return to the main screen and mount it as a single row in the current pane, expanding in place (same path as tapping an archive in the tree). */
    private fun mountArchive(file: XFile) {
        startActivity(MainActivity.mountIntent(this, file))
    }

    /**
     * Same-kind files in the same directory (using the pane's current sort);
     * non-local sources return an empty list — content:// passed in from outside is
     * just an isolated entry, with no "same directory" concept.
     */
    private fun localSiblings(file: XFile, keep: (XFile) -> Boolean): List<XFile> {
        if (file.scheme != "file") return emptyList()
        val dir = XFile("file", file.parentPath, isDir = true)
        val kids = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        val showHidden = Prefs.showHidden(this)
        return SortSpec.load(this)
            .sort(kids.filter { !it.isDir && keep(it) && (showHidden || !it.name.startsWith(".")) })
    }
}
