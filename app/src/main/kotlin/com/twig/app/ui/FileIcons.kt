package com.twig.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.twig.app.Format
import com.twig.app.OpenFiles
import com.twig.app.R
import com.twig.core.XFile
import com.twig.fs.network.JellyfinFileSystem
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * File-list icons:
 * - Built-in openable types (video / audio / image / document / text) use coloured type icons;
 * - APKs use the in-package app icon (local files only, async resolution);
 * - For other types, if the system already has a default opener (one chosen as "always"), use that app's icon;
 * - Otherwise fall back to the generic file icon.
 *
 * App icons are cached by key (apk path / "ext:extension"); resolution runs on a single background thread,
 * and view.tag is checked before filling back in to prevent RecyclerView reuse misalignment.
 */
object FileIcons {

    private val cache = HashMap<String, Drawable?>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-icons").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Target edge for a decoded [bundleIcon]; the largest row icon is well under this. */
    private const val ICON_PX = 128

    fun baseIconRes(file: XFile): Int = when {
        // Entries in the "Apps" tree (.apk / .xapk for split APKs) uniformly use the apk icon as the base,
        // the real app icon is fetched live from PackageManager below by "pkg:" key (filled in async, doesn't go through the thumbnail pipeline)
        file.scheme == com.twig.app.AppsFileSystem.SCHEME -> R.drawable.ic_file_apk
        OpenFiles.isVideo(file) || file.extension == "wmv" -> R.drawable.ic_file_video
        OpenFiles.isAudio(file) -> R.drawable.ic_file_audio
        OpenFiles.isImage(file) -> R.drawable.ic_file_image
        file.extension == "pdf" -> R.drawable.ic_file_pdf
        OpenFiles.isDoc(file) || OpenFiles.isText(file) -> R.drawable.ic_file_doc
        OpenFiles.isArchive(file) -> R.drawable.ic_file_archive
        OpenFiles.isApk(file) -> R.drawable.ic_file_apk
        // Its own icon rather than the apk one: a bundle installs differently (see
        // ApkBundleInstall), and the real app icon below only shows for local files.
        OpenFiles.isApkBundle(file) -> R.drawable.ic_file_apk_bundle
        else -> R.drawable.ic_file
    }

    /**
     * Source-type icon (not file-type icon): the path bar, recents, and copy / compress target rows share this set,
     * so the same directory doesn't draw three different icons across three screens.
     *
     * Just match by scheme — each server's scheme is "type + hash" (see `PaneViewModel.schemeForConn`),
     * and stripping the hash via [Format.schemeLabel] gives the type; no need to look up the connection in
     * ConnectionStore (even a deleted connection is still recognizable).
     */
    fun sourceIconRes(scheme: String): Int = sourceIconOfType(Format.schemeLabel(scheme))

    /**
     * Which icon a given **directory** inside a media server (Jellyfin / Emby) should use; returns null for
     * non-media-server sources.
     *
     * All those directories are virtual (albums, artists, series, libraries…) — drawing them all as generic folders
     * doesn't show any difference, and once enlarged they also take up space. The type can only be inferred from
     * the path — there is no "what is this" field in `XFile`, and asking the server for one icon's sake isn't worth it.
     * For things that can't be inferred (series / albums all look like `/lib/<library>/<id>`) keep the folder icon.
     *
     * ★ Uses the **dedicated `ic_md_*`** set (same scheme as `ic_file_*`: `fillColor` is always white, all the
     * colour comes from `android:tint`, both themes share the same value), **not** the hard-coded black-and-white
     * icons from the player that callers then tint via `imageTintList` — the latter hit a major trap, see
     * the comments in the icon-binding section of [FileAdapter].
     */
    fun mediaDirIcon(file: XFile): Int? {
        if (!file.isDir) return null
        if (com.twig.app.Connections.ofScheme(file.scheme)?.isMediaServer() != true) return null
        val segs = file.path.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        return when {
            // Categories under the music library, and entries within those categories
            segs.any { it == JellyfinFileSystem.SEG_ALBUMS } -> R.drawable.ic_md_album
            segs.any { it == JellyfinFileSystem.SEG_ARTISTS } -> R.drawable.ic_md_artist
            segs.any { it == JellyfinFileSystem.SEG_ALBUM_ARTISTS } -> R.drawable.ic_md_artist
            segs[0] == JellyfinFileSystem.ID_PLAYLISTS -> R.drawable.ic_md_playlist
            segs[0] == JellyfinFileSystem.ID_COLLECTIONS -> R.drawable.ic_file_video
            segs[0] == JellyfinFileSystem.ID_RESUME -> R.drawable.ic_md_resume
            segs[0] == JellyfinFileSystem.ID_LATEST && segs.size == 1 -> R.drawable.ic_md_latest
            // ★ Media libraries themselves, as well as series / seasons / albums, all use the **generic folder icon**
            // (yellow, same as local directories). In the user's mind these are just "a directory", no need for a separate icon;
            // and entry icons all need to follow the theme's neutral colour; if they filled the screen it would be a sheet of grey.
            else -> null
        }
    }

    /** Same as [sourceIconRes], but given the connection type directly — recents only store the type, not the scheme. */
    fun sourceIconOfType(type: String): Int = when (type) {
        "smb" -> R.drawable.ic_lan
        // SFTP and FTP each get their own icon: they are the two most-used connection types,
        // and sharing "a monitor" / "a cloud" with everything else made the path bar and the
        // recents list say nothing about which kind of server a row came from.
        "sftp" -> R.drawable.ic_sftp
        "ftp" -> R.drawable.ic_ftp
        "webdav", "dav", "s3" -> R.drawable.ic_cloud
        // Jellyfin / Emby: the same self-drawn play-button shape, differentiated by **each one's official colours**.
        // A cloud icon doesn't read as a media library; sharing one icon between the two doesn't tell them apart.
        // **Deliberately not drawing the official logos** — both names and graphics are registered trademarks, and
        // third parties are explicitly required to use their own marks; non-free assets would also fail the
        // libre / F-Droid gate; colours are not protected (Jellyfin officially states the purple-blue gradient
        // is fair game on others' shapes), so recognition relies entirely on colour. The two are functionally
        // identical; manufacturing shape differences would only be fake information.
        "jellyfin" -> R.drawable.ic_media_jellyfin
        "emby" -> R.drawable.ic_media_emby
        "git" -> R.drawable.ic_git
        "restic" -> R.drawable.ic_restic
        com.twig.app.AppsFileSystem.SCHEME -> R.drawable.ic_file_apk
        // Archive schemes (a mounted archive's own root row), not extensions
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst" -> R.drawable.ic_file_archive
        else -> R.drawable.ic_storage // local / SAF / unknown type
    }

    fun bind(view: ImageView, file: XFile) {
        val base = baseIconRes(file)
        view.setImageResource(base)
        val key = when {
            // "Apps" entry: ask PackageManager for the icon live; no need to parse the apk
            file.scheme == com.twig.app.AppsFileSystem.SCHEME ->
                appPackageOf(file)?.let { "pkg:$it" } ?: run { view.tag = null; return }
            OpenFiles.isApk(file) && file.scheme == "file" -> file.path
            OpenFiles.isApkBundle(file) && file.scheme == "file" -> "bundle:${file.path}"
            base == R.drawable.ic_file && file.extension.isNotEmpty() -> "ext:${file.extension}"
            else -> { view.tag = null; return }
        }
        fill(view, key)
    }

    /**
     * Switch to an **installed app's** icon (async + cache, same path as the "Apps" entry).
     * The caller puts down the placeholder first — SAF document tree roots use the folder icon, not [baseIconRes].
     */
    fun bindApp(view: ImageView, pkg: String) = fill(view, "pkg:$pkg")

    private fun fill(view: ImageView, key: String) {
        view.tag = key
        synchronized(cache) {
            if (cache.containsKey(key)) {
                cache[key]?.let { view.setImageDrawable(it) }
                return
            }
        }
        val ctx = view.context.applicationContext
        executor.execute {
            val d = runCatching { load(ctx, key) }.getOrNull()
            synchronized(cache) { cache[key] = d }
            if (d != null) main.post { if (view.tag == key) view.setImageDrawable(d) }
        }
    }

    /** The system's default app may change (user picks "always"); on returning to the foreground clear the associated icon cache. */
    fun clearAppDefaults() {
        synchronized(cache) { cache.keys.removeAll { it.startsWith("ext:") } }
    }

    private fun appPackageOf(file: XFile): String? =
        (runCatching { com.twig.core.FsRegistry.of(file) }.getOrNull() as? com.twig.app.AppsFileSystem)
            ?.packageOf(file)

    private fun load(ctx: Context, key: String): Drawable? {
        val pm = ctx.packageManager
        if (key.startsWith("bundle:")) return bundleIcon(ctx, key.removePrefix("bundle:"))
        if (key.startsWith("pkg:")) { // icon of an installed app
            val ai = runCatching { pm.getApplicationInfo(key.removePrefix("pkg:"), 0) }.getOrNull()
            return ai?.loadIcon(pm)
        }
        if (!key.startsWith("ext:")) { // icon bundled with the apk
            val pi = pm.getPackageArchiveInfo(key, 0) ?: return null
            val ai = pi.applicationInfo ?: return null
            ai.sourceDir = key
            ai.publicSourceDir = key
            return ai.loadIcon(pm)
        }
        // System default app for this extension (only set after the user picks "always")
        val ext = key.removePrefix("ext:")
        val mime = OpenFiles.mimeOf("x.$ext")
        if (mime == "*/*") return null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://${ctx.packageName}.stream/probe/probe.$ext"), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val ai = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo ?: return null
        // When there's no default, the system returns the resolver itself, which doesn't count as an associated app
        if (ai.packageName == "android") return null
        return ai.loadIcon(pm)
    }

    /**
     * The app icon of a multi-apk bundle, read from the `icon.png` an XAPK carries at its
     * root (APKPure writes one, and so does [com.twig.app.AppsFileSystem] when packing).
     * `.apks` has no such thing and simply comes back without an icon.
     *
     * ★ Deliberately **not** "extract base.apk and ask PackageManager": that is the only
     * way to get the icon out of the apk itself, and it means writing a file that is
     * routinely 100 MB+ to cache to draw one 24dp row. The bundled png is a few KB and
     * sits right after the manifest. No icon.png, no icon — the row keeps
     * [R.drawable.ic_file_apk_bundle].
     */
    private fun bundleIcon(ctx: Context, path: String): Drawable? {
        val f = File(path)
        if (!f.isFile) return null
        return ZipFile(f).use { zip ->
            val entry = zip.getEntry("icon.png") ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                // Row icons are ~24dp; decoding a 512² launcher icon at full size and
                // holding it in the (never-evicted) drawable cache is pure waste.
                var s = 1
                while (bounds.outWidth / (s * 2) >= ICON_PX) s *= 2
                inSampleSize = s
            }
            val bmp = zip.getInputStream(entry).use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            BitmapDrawable(ctx.resources, bmp)
        }
    }
}
