package com.twig.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.webkit.MimeTypeMap
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File

/** File-opening helpers: open in external apps, materialize non-local sources, text detection. */
object OpenFiles {

    /**
     * Extensions that can be opened directly in the built-in text viewer.
     * Excludes "ts" because it collides with MPEG-TS video; in a file manager, TS
     * video is far more common than raw TypeScript source, so we yield to [VIDEO_EXT].
     */
    private val TEXT_EXT = setOf(
        "txt", "log", "md", "markdown", "json", "xml", "csv", "ini", "conf", "cfg", "properties",
        "html", "htm", "css", "js", "kt", "kts", "java", "c", "cpp", "h", "py",
        "sh", "gradle", "yml", "yaml", "toml", "rs", "go", "sql",
    )

    private val PREVIEW_EXT = setOf("md", "markdown", "html", "htm")

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "m4v", "mov", "avi", "ts", "m2ts", "flv")
    private val AUDIO_EXT = setOf("mp3", "aac", "m4a", "flac", "ogg", "opus", "wav", "wma", "mid")
    private val DOC_EXT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf", "epub", "mobi",
    )
    /** Multi-apk bundles; see [isApkBundle]. */
    private val BUNDLE_EXT = setOf("xapk", "apks", "apkm")

    private val ARCHIVE_EXT = setOf(
        "zip", "jar", "7z", "rar", "tar", "gz", "bz2", "xz", "zst",
        "tgz", "txz", "tbz", "tbz2",
    )

    fun isText(file: XFile): Boolean = !file.isDir && file.extension in TEXT_EXT

    /** markdown/html: openable via [com.twig.app.ui.TextViewerActivity] preview mode (WebView render). */
    fun isPreviewable(file: XFile): Boolean = !file.isDir && file.extension in PREVIEW_EXT

    fun isImage(file: XFile): Boolean = !file.isDir && file.extension in IMAGE_EXT

    fun isVideo(file: XFile): Boolean = !file.isDir && file.extension in VIDEO_EXT

    fun isAudio(file: XFile): Boolean = !file.isDir && file.extension in AUDIO_EXT

    fun isDoc(file: XFile): Boolean = !file.isDir && file.extension in DOC_EXT

    /**
     * Openable by the built-in [com.twig.app.ui.PdfViewerActivity].
     *
     * ★ Not simply "is it a .pdf": PdfRenderer has no streaming interface and needs a seekable
     * fd, so only `file`, SAF and `content://` entries qualify — the same wall
     * [com.twig.app.ui.Thumbs] hits generating PDF thumbnails. A PDF on SMB or inside a zip
     * still goes to an external app; routing it to our viewer would just show an error dialog
     * instead of opening.
     *
     * `share` is in the list because that is how another app's ACTION_VIEW arrives
     * ([com.twig.app.ui.ViewIntentActivity]) — most providers hand back a real fd, and
     * [com.twig.app.ui.PdfDoc.open] materialises the few that cannot. It never shows up in the
     * file tree, so allowing it here costs the pane nothing.
     */
    fun canViewPdf(file: XFile): Boolean =
        !file.isDir && file.extension == "pdf" &&
            (file.scheme == "file" || file.scheme == SafFileSystem.SCHEME ||
                file.scheme == ShareSourceFileSystem.SCHEME)

    fun isApk(file: XFile): Boolean = !file.isDir && file.extension == "apk"

    /**
     * The three ways one app's base.apk + split apks get shipped as a single zip:
     * `.xapk` (APKPure, and what [com.twig.app.XapkPack] writes when a split app is
     * copied out of the "Apps" tree), `.apks` (bundletool / SAI) and `.apkm`
     * (APKMirror). They differ in metadata and in where the apks sit inside the zip,
     * which is exactly what [com.twig.app.ui.ApkBundleInstall] does not care about:
     * it installs whatever `.apk` entries it finds. Handling them apart would only
     * mean three names for one code path.
     *
     * They cannot go to the system installer at all — an `ACTION_VIEW` intent takes
     * one apk, never a set.
     */
    fun isApkBundle(file: XFile): Boolean = !file.isDir && file.extension in BUNDLE_EXT

    /**
     * Types a tap installs rather than expands. They are all zips, so the tree would
     * happily mount them; it deliberately doesn't (see `PaneViewModel.expandableArchive`),
     * and "Open as archive" in the long-press menu is the way in for anyone who wants
     * the contents.
     */
    fun isInstallable(file: XFile): Boolean = isApk(file) || isApkBundle(file)

    /** m3u/m3u8 playlist files (opened with the music player). */
    fun isPlaylist(file: XFile): Boolean = !file.isDir && file.extension in setOf("m3u", "m3u8")

    fun isArchive(file: XFile): Boolean = !file.isDir && file.extension in ARCHIVE_EXT

    /**
     * Materializes a non-local source (zip/ftp/etc.) into the cache directory and
     * returns the local copy. Local files are returned as-is. Blocking IO; call from
     * a worker thread.
     */
    fun materialize(context: Context, file: XFile): File {
        if (file.scheme == "file") return File(file.path)
        val dir = CacheDirs.dir(context, CacheDirs.OPEN)
        // Prefix the filename with a hash of source + path. Using just file.name
        // would let same-named files in different directories (the ever-present
        // cover.jpg) overwrite each other, so opening A shows B's content.
        val key = Integer.toHexString("${file.scheme}:${file.path}:${file.size}:${file.lastModified}".hashCode())
        val out = File(dir, "${key}_${file.name}")
        // 1MB buffer (the 8KB default amplifies network round-trip latency into many
        // small reads — see CopyEngine).
        FsRegistry.of(file).openInput(file).use { input ->
            out.outputStream().use { input.copyTo(it, COPY_BUFFER_SIZE) }
        }
        CacheDirs.trim(dir, keep = out)
        return out
    }

    private const val COPY_BUFFER_SIZE = 1 shl 20

    /**
     * Reads a stream into a byte array with a large buffer. ★ Do NOT use
     * `InputStream.readBytes(bufferSize)` — that deprecated overload only uses the
     * argument as the ByteArrayOutputStream initial capacity; the actual read() is
     * still 8KB, useless for network round-trip latency (stepped on this on
     * 2026-07-28). Use `copyTo(out, bufferSize)` — bufferSize actually controls
     * read() size here.
     */
    fun readAllBytes(input: java.io.InputStream, bufferSize: Int = COPY_BUFFER_SIZE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        input.copyTo(out, bufferSize)
        return out.toByteArray()
    }

    /** Resolves MIME type from filename; returns *&#47;* if unknown. */
    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        // Some devices' MimeTypeMap has no APK mapping; spell it out so the
        // installer is actually launched.
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** Share to another app (also via [StreamProvider] for streamed authorization, no whole-file cache). */
    fun share(context: Context, file: XFile) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, StreamProvider.uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share)))
    }

    /**
     * Open with another app (via [StreamProvider] for streamed authorization —
     * any source works without caching the whole file, and external players can
     * seek).
     *
     * [forceChooser]=false: startActivity directly; the system shows its resolver
     * ("just once / always", or opens directly if a default app is set); true:
     * force the full chooser (ignore default, no "always").
     */
    fun openWith(context: Context, file: XFile, forceChooser: Boolean = false): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(StreamProvider.uriFor(context, file), mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(
                if (forceChooser) {
                    Intent.createChooser(intent, context.getString(R.string.open_with_external))
                } else intent,
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Candidate apps for viewing this file (excluding Twig itself) — used to let the user
     * pick one **up front** when pinning a "with this app" desktop shortcut. A shortcut has
     * no chance to show the system resolver's "just once / always" dialog on every tap the
     * way [openWith] does, so the choice has to be made once, at creation time, and baked in.
     */
    fun resolveViewers(context: Context, file: XFile): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(StreamProvider.uriFor(context, file), mimeOf(file.name))
        }
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != context.packageName }
    }

    /** Open with one specific, already-chosen app (see [resolveViewers]) — no resolver, no "always" prompt, straight to that component. */
    fun openWithComponent(context: Context, file: XFile, component: ComponentName): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(StreamProvider.uriFor(context, file), mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setComponent(component)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
