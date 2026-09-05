package com.twig.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Virtual read-only view of installed apps: two directories under the root — "Installed"
 * (user apps) and "System", and each entry is one app.
 *
 * - Single-apk apps → just that apk file directly (read `sourceDir` real path, zero cost);
 * - Split apk apps → assembled on the fly into **XAPK** (APKPure format, zip container +
 *   `manifest.json`, see [XapkPack]). Copying just base.apk misses `split_config.*`, and
 *   reinstalling lacks resources/ABIs; XAPK bundles everything together, and SAI /
 *   MT Manager / APKPure installers all recognize it — tools that don't can rename the
 *   extension to .zip and still extract.
 *
 * Reads all go through [openInput], so copying to the other pane, viewing and packaging
 * reuse the existing pipeline with zero changes. Writes (create/rename/delete) are not
 * supported: uninstall is not file deletion, and the UI layer launches the system
 * uninstall screen (see `PaneFragment.performDelete`).
 *
 * Enumerating all device apps requires the `QUERY_ALL_PACKAGES` permission in the manifest
 * (Android 11+); without it, [PackageManager.getInstalledApplications] only returns this
 * app and those declared in `<queries>`.
 */
class AppsFileSystem(context: Context) : FileSystem {

    private val ctx = context.applicationContext
    private val pm = ctx.packageManager
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    override val scheme: String = SCHEME
    override val displayName: String = ctx.getString(R.string.apps_root)

    override fun root(): XFile = XFile(SCHEME, "/", isDir = true, canWrite = false)

    override fun resolve(path: String): XFile {
        val segs = segsOf(path)
        if (segs.isEmpty()) return root()
        val cat = segs[0]
        if (cat != USER && cat != SYSTEM) throw FsException(s(R.string.err_apps_unknown_dir, path))
        if (segs.size == 1) return dirOf(cat)
        val info = infoOf(segs[1]) ?: throw FsException(s(R.string.err_apps_missing, segs[1]))
        return toXFile(info, cat)
    }

    override fun list(dir: XFile): List<XFile> {
        val segs = segsOf(dir.path)
        if (segs.isEmpty()) return listOf(dirOf(USER), dirOf(SYSTEM))
        val cat = segs[0]
        val wantSystem = cat == SYSTEM
        // Fetch them all with one getInstalledPackages call, don't getPackageInfo per package —
        // for several hundred apps that would be hundreds of binder round-trips and the
        // expansion would freeze for several seconds
        return pm.getInstalledPackages(0)
            .filter { it.applicationInfo?.let { ai -> isSystem(ai) } == wantSystem }
            .mapNotNull { runCatching { toXFile(it, cat) }.getOrNull() }
    }

    override fun openInput(file: XFile): InputStream =
        packOf(file)?.open() ?: FileInputStream(baseApkOf(file))

    /**
     * Single-apk apps use real-file random reads (thumbnails / external players benefit);
     * XAPK is a stream assembled on the fly with no seekable backing file, so it falls
     * back to the default implementation (reopen + skip).
     */
    override fun openRandom(file: XFile): RandomSource {
        if (packOf(file) != null) return super.openRandom(file)
        val raf = RandomAccessFile(baseApkOf(file), "r")
        return object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                raf.seek(position)
                return raf.read(buffer, offset, length)
            }
            override fun length(): Long = raf.length()
            override fun close() { runCatching { raf.close() } }
        }
    }

    override fun randomAccessEfficient(): Boolean = true

    override fun writable(): Boolean = false

    override fun openOutput(file: XFile, append: Boolean): OutputStream = readOnly()

    override fun mkdir(parent: XFile, name: String): XFile = readOnly()

    override fun createFile(parent: XFile, name: String): XFile = readOnly()

    override fun rename(file: XFile, newName: String): XFile = readOnly()

    /** Uninstall is not file deletion: the UI layer detects this scheme and launches the system uninstall screen, so this is never reached. */
    override fun delete(file: XFile): Unit =
        throw FsException(ctx.getString(R.string.apps_use_uninstall))

    override fun exists(file: XFile): Boolean {
        val segs = segsOf(file.path)
        return when (segs.size) {
            0 -> true
            1 -> segs[0] == USER || segs[0] == SYSTEM
            else -> infoOf(segs[1]) != null
        }
    }

    // ---- Hooks for the UI layer ----

    /** The package name this entry corresponds to (path segment is the package name); returns null for non-app entries (root / category directory). */
    fun packageOf(file: XFile): String? = segsOf(file.path).takeIf { it.size >= 2 }?.get(1)

    /** The three fields shown separately per row (app label / version / package name). */
    data class AppMeta(val label: String, val version: String, val pkg: String)

    /**
     * App label and version — every row needs them, so we record them as a side effect when
     * listing entries ([toXFile]); here we look up the in-memory table first: when binding
     * rows we cannot go back to PackageManager (one binder round-trip per row drops frames).
     * The cache is refreshed each time the directory is listed (version updates with app
     * upgrades); only entries that have never been listed are fetched once.
     */
    fun metaOf(file: XFile): AppMeta? {
        val pkg = packageOf(file) ?: return null
        metaCache[pkg]?.let { return it }
        val info = infoOf(pkg) ?: return null
        val ai = info.applicationInfo ?: return null
        return refreshMeta(info, ai)
    }

    /** Compute on demand and refresh the cache. */
    private fun refreshMeta(info: PackageInfo, ai: ApplicationInfo): AppMeta {
        val label = runCatching { pm.getApplicationLabel(ai).toString() }
            .getOrDefault(info.packageName)
        return AppMeta(label, info.versionName.orEmpty(), info.packageName)
            .also { metaCache[info.packageName] = it }
    }

    /** Real base-apk path backing this entry (used when thumbnails need a real file); returns null when unavailable. */
    fun apkPathOf(file: XFile): String? =
        packageOf(file)?.let { infoOf(it)?.applicationInfo?.sourceDir }

    // ---- Internal ----

    /** Returns the packer when this entry should be packed as XAPK; returns null for single-apk apps. */
    private fun packOf(file: XFile): XapkPack? =
        packageOf(file)?.let { infoOf(it) }?.let { packFor(it) }

    /**
     * Split-app → XAPK packer; returns null for single-apk apps.
     * When total size overflows 32-bit (would need zip64 to fit, which real apps never reach),
     * also fall back to single-apk.
     */
    private fun packFor(info: PackageInfo): XapkPack? {
        val ai = info.applicationInfo ?: return null
        val splits = ai.splitSourceDirs?.filter { it.isNotEmpty() } ?: emptyList()
        if (splits.isEmpty()) return null
        val base = File(ai.sourceDir ?: return null)
        val files = ArrayList<Pair<String, File>>()
        files += "${info.packageName}.apk" to base
        // Platform type — may genuinely be null on old systems / abnormal packages, handle explicitly as nullable
        val names: Array<String>? = info.splitNames
        splits.forEachIndexed { i, path ->
            val f = File(path)
            // Prefer the split's own file name (e.g. split_config.arm64_v8a.apk, recognized by
            // installers); fall back to the split name when missing
            files += (f.name.ifEmpty { "split_${names?.getOrNull(i) ?: i}.apk" }) to f
        }
        val total = files.sumOf { it.second.length() }
        // ★ Render the icon **before** the manifest: the manifest has to declare it
        // (`"icon"`), and when the render fails there must be no dangling reference to a
        // file the bundle doesn't carry.
        val icon = iconPng(info, ai)
        val manifest = manifestJson(info, files, total, icon != null).toByteArray(Charsets.UTF_8)
        val entries = ArrayList<XapkPack.Entry>(files.size + 2)
        entries += XapkPack.Entry("manifest.json", manifest, null)
        icon?.let { entries += XapkPack.Entry(ICON_NAME, null, it) }
        files.forEach { (n, f) -> entries += XapkPack.Entry(n, null, f) }
        val pack = XapkPack(entries)
        return if (pack.totalSize() in 1..MAX_ZIP32) pack else null
    }

    /**
     * The app icon, rendered once into `cacheDir/xapk-icon` and packed as `icon.png` at
     * the bundle root — APKPure's own bundles carry one, and it is the only way to show
     * an app icon for a bundle sitting on disk (the alternative, extracting base.apk so
     * PackageManager can parse it, means writing 100 MB+ to draw one row; see
     * [com.twig.app.ui.FileIcons]).
     *
     * ★ Cached as a **file**, never as bytes in the [XapkPack.Entry]: a directory listing
     * builds one packer per app just to read [XapkPack.totalSize], and holding a few
     * hundred pngs in memory for that is pointless. The name carries the versionCode, so
     * an app upgrade produces a new icon instead of a stale one.
     *
     * ★ Written to a temp name and renamed: the entry size lands in the zip header at
     * listing time, and a half-written file would report a length the stream then fails
     * to match — the copy would silently come out truncated.
     */
    private fun iconPng(info: PackageInfo, ai: ApplicationInfo): File? {
        val dir = File(ctx.cacheDir, ICON_DIR).apply { mkdirs() }
        val out = File(dir, "${info.packageName}-${versionCodeOf(info)}.png")
        if (out.isFile && out.length() > 0) return out
        return runCatching {
            val icon = ai.loadIcon(pm) ?: return null
            val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, ICON_PX, ICON_PX)
            icon.draw(Canvas(bmp))
            val tmp = File(dir, "${out.name}.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (tmp.renameTo(out)) {
                out
            } else {
                tmp.delete()
                null
            }
        }.getOrNull()
    }

    /**
     * APKPure's XAPK manifest; field names follow its conventions, installers restore base + splits from this.
     *
     * ★ [hasIcon] writes the `icon` field. The png alone is not enough: installers that show an
     * app icon for a bundle (SAI, APKPure) read the manifest's `icon` field for the entry name
     * rather than probing for a well-known one, so without it the packed [ICON_NAME] is dead
     * weight there. Twig's own row icon reads the entry directly ([com.twig.app.ui.FileIcons]),
     * which is why this went unnoticed.
     */
    private fun manifestJson(
        info: PackageInfo,
        files: List<Pair<String, File>>,
        total: Long,
        hasIcon: Boolean,
    ): String {
        val ai = info.applicationInfo
        val label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?: info.packageName
        val splits = files.mapIndexed { i, (n, _) ->
            val id = if (i == 0) "base" else n.removeSuffix(".apk").removePrefix("split_")
            """{"file":${jsonStr(n)},"id":${jsonStr(id)}}"""
        }.joinToString(",")
        return buildString {
            append("{\"xapk_version\":2")
            append(",\"package_name\":").append(jsonStr(info.packageName))
            append(",\"name\":").append(jsonStr(label))
            append(",\"version_code\":").append(jsonStr(versionCodeOf(info).toString()))
            append(",\"version_name\":").append(jsonStr(info.versionName ?: ""))
            append(",\"min_sdk_version\":").append(jsonStr(minSdkOf(ai).toString()))
            append(",\"target_sdk_version\":").append(jsonStr((ai?.targetSdkVersion ?: 0).toString()))
            if (hasIcon) append(",\"icon\":").append(jsonStr(ICON_NAME))
            append(",\"total_size\":").append(total)
            append(",\"split_apks\":[").append(splits).append("]}")
        }
    }

    private fun minSdkOf(ai: ApplicationInfo?): Int =
        if (ai != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ai.minSdkVersion
        } else {
            0
        }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private fun baseApkOf(file: XFile): File {
        val src = apkPathOf(file) ?: throw FsException(s(R.string.err_apps_not_entry, file.path))
        return File(src)
    }

    private fun infoOf(pkg: String): PackageInfo? =
        runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()

    private fun isSystem(ai: ApplicationInfo): Boolean =
        (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun segsOf(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    private fun dirOf(cat: String): XFile = XFile(
        SCHEME, "/$cat", isDir = true, canWrite = false,
        displayName = ctx.getString(if (cat == SYSTEM) R.string.apps_system else R.string.apps_user),
    )

    /**
     * path uses the package name (unique and stable, easy for the UI to look up),
     * displayName is "App Label Version.apk/.xapk" — when copying to the other side,
     * [com.twig.core.CopyEngine] reads [XFile.name], so the file lands as that name.
     */
    private fun toXFile(info: PackageInfo, cat: String): XFile {
        val ai = info.applicationInfo ?: throw FsException(s(R.string.err_apps_no_info, info.packageName))
        val base = File(ai.sourceDir ?: throw FsException(s(R.string.err_apps_no_apk, info.packageName)))
        val pack = runCatching { packFor(info) }.getOrNull()
        // Also refresh the app label / version used by list rows (see metaOf); the file name
        // still uses the full name assembled below, which is what lands on disk after copy.
        val meta = refreshMeta(info, ai)
        val name = buildString {
            append(meta.label.replace('/', '_'))
            if (meta.version.isNotEmpty()) append(' ').append(meta.version.replace('/', '_'))
            append(if (pack != null) ".xapk" else ".apk")
        }
        return XFile(
            scheme = SCHEME,
            path = "/$cat/${info.packageName}",
            isDir = false,
            size = pack?.totalSize() ?: base.length(),
            lastModified = base.lastModified(),
            canWrite = false,
            displayName = name,
        )
    }

    private fun readOnly(): Nothing = throw FsException(ctx.getString(R.string.apps_read_only))

    companion object {
        /** package name → app label/version used by list rows; filled when listing entries, read from memory when binding rows. */
        private val metaCache = java.util.concurrent.ConcurrentHashMap<String, AppMeta>()

        const val SCHEME = "apps"

        /** Rendered app icons for packing (see iconPng); tiny and keyed by version, so it is not trimmed. */
        private const val ICON_DIR = "xapk-icon"

        /** Entry name of the packed app icon, at the bundle root; also what manifest.json's `icon` points at. */
        private const val ICON_NAME = "icon.png"
        private const val ICON_PX = 192
        private const val USER = "user"
        private const val SYSTEM = "system"
        private const val MAX_ZIP32 = 0xFFFFFFFFL - 1
    }
}
