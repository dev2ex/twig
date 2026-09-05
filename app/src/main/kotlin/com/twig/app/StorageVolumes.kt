package com.twig.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * A mounted removable storage volume (SD card / USB drive).
 *
 * [initialUri] is the system-provided "locate this volume in the SAF picker"
 * location; if it cannot be obtained it is null — see the SAF fallback
 * mentioned in the [StorageVolumes] class note.
 */
data class RemovableVolume(val path: String, val label: String, val initialUri: Uri? = null)

/**
 * The "SD card / USB drive" rows at the top of the tree come from here:
 * **only the volumes the system hands to the app** (`getStorageVolumes()`,
 * with a fallback derived from the private directory) — no new permissions.
 *
 * The volume's path is not always `/storage/XXXX-XXXX` — the system can mount
 * it as "invisible to the app" (`mountFlags=0` in `dumpsys mount`), in which
 * case `getDirectory()` returns `/mnt/media_rw/<volume id>`. **It still shows
 * up in `getStorageVolumes()`**, so this layer does not need to worry about
 * it; but that directory is `root:external_storage 0750`, which even shell
 * (uid 2000) cannot read. So **opening it requires elevation** (the
 * root/Shizuku fallback in `LocalFileSystem.elevation` takes over
 * automatically), or long-pressing that row and choosing "Authorize this
 * volume with SAF".
 *
 * ★ **Tried and rejected: enumerating `/mnt/media_rw` from the privileged
 * side as a supplementary source** (added 2026-08-26 then removed). The
 * original assumption was that "invisible volumes are not in
 * `getStorageVolumes()`" — which is **wrong**: real-device logs show it has
 * always been there. And that extra source caused three concrete problems:
 * after unplugging, the row only updated on a full re-scan, leaving a
 * clickable-but-dead volume id on the tree; the name was a hex string; and
 * the directories under `/mnt/media_rw` are the same card as the visible
 * volume, which also had to be deduplicated by volume id. **Net benefit
 * zero** (no device has ever shown a "system did not give it but it's there"
 * volume), so the whole block was removed. Before re-adding it, prove with
 * logs that such a volume actually exists.
 *
 * ★ **The volume list must be cached, not rescanned inside
 * `PaneViewModel.rebuild()`** — `storageVolumes` is one binder IPC, while
 * rebuild is a hot path that runs on every tick / expansion / render. A
 * rescan only happens in [refresh] (back to foreground, plug/unplug
 * broadcasts, and a periodic poll every few seconds in the foreground).
 */
object StorageVolumes {

    private const val TAG = "twig-vol"

    @Volatile private var cache: List<RemovableVolume>? = null

    /** Cached volume list (the first call performs a scan). One binder IPC, callable from the main thread. */
    fun cached(ctx: Context): List<RemovableVolume> = cache ?: scan(ctx).also { cache = it }

    /** Rescan; **returns true only if the list changed**, so callers can decide whether to rebuild the tree. */
    fun refresh(ctx: Context): Boolean {
        // ★ Take "what we already have", not cached() — it would opportunistically
        // scan the first time, so the very first call always reports "no change"
        // and the first refresh after plugging in a card is silently dropped.
        val before = cache.orEmpty()
        val now = scan(ctx)
        cache = now
        // Log only on change: this line is what tells "card plugged in but nothing
        // showed up" apart from "we didn't scan"
        if (now != before) Log.i(TAG, "volumes -> ${now.map { it.path }}")
        return now != before
    }

    /** Is this path the root of a removable volume (treated like internal storage / root, to prevent accidental whole-volume deletes)? */
    fun isVolumeRoot(path: String): Boolean = cache.orEmpty().any { it.path == path }

    /** Look up a volume by root path; returns null if the path is not a volume root. */
    fun of(path: String): RemovableVolume? = cache.orEmpty().firstOrNull { it.path == path }

    /** For test reset only. */
    fun reset() {
        cache = null
    }

    /**
     * ★ Deduplicate by **volume id** (the last path segment) in a map, not by the
     * full path: the same card may come from both `getStorageVolumes()` and the
     * private-directory fallback, and deduping by path produces two rows at root.
     */
    internal fun scan(ctx: Context): List<RemovableVolume> {
        val out = LinkedHashMap<String, RemovableVolume>()
        runCatching {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                // ★ The test is isPrimary, not isRemovable: on some ROMs the primary
                // volume also reports isRemovable=true, and checking only that would
                // list internal storage twice at the root.
                if (v.isPrimary) continue
                val state = runCatching { v.state }.getOrNull()
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val path = pathOf(v) ?: continue
                // getDescription returns the system's human-readable name ("SD card" /
                // "USB drive"); ★ an empty string is not acceptable here — passing
                // an empty string to XFile.displayName makes name itself empty too.
                val label = v.getDescription(ctx)?.ifEmpty { null } ?: v.uuid ?: File(path).name
                out[File(path).name] = RemovableVolume(path, label, initialUri(v))
            }
        }
        // Fallback: when uuid is null and SDK < 30 the loop above cannot get a path,
        // so derive it from the app's private directory
        for (v in fromExternalFilesDirs(ctx)) out.putIfAbsent(File(v.path).name, v)
        return out.values.toList()
    }

    private fun pathOf(v: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { v.directory?.absolutePath }.getOrNull()?.let { return it }
        }
        // ★ Do not call getPath() via reflection: on Android 10 it is a restricted
        // non-SDK interface. A removable volume's uuid (XXXX-XXXX) is the name of
        // its directory under /storage, and getUuid() is public.
        val uuid = runCatching { v.uuid }.getOrNull() ?: return null
        val f = File("/storage", uuid)
        return if (f.exists()) f.absolutePath else null
    }

    private fun initialUri(v: StorageVolume): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            @Suppress("DEPRECATION")
            v.createOpenDocumentTreeIntent().getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        }.getOrNull()
    }

    /** `/storage/XXXX-XXXX/Android/data/<package>/files` → `/storage/XXXX-XXXX` (the primary volume row is dropped). */
    private fun fromExternalFilesDirs(ctx: Context): List<RemovableVolume> {
        val primary = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
        val dirs = runCatching { ctx.getExternalFilesDirs(null) }.getOrNull().orEmpty()
        return dirs.filterNotNull().mapNotNull { d -> volumeRootOf(d.absolutePath, primary) }
    }

    /** The pure string part is broken out separately so JVM tests without a real volume can pin it down. */
    internal fun volumeRootOf(privateDir: String, primary: String?): RemovableVolume? {
        val i = privateDir.indexOf(PRIVATE_MARK)
        if (i <= 0) return null
        val root = privateDir.substring(0, i)
        if (root == primary) return null
        // ★ The shape also has to match: on some ROMs the primary volume's private
        // directory is written as `/sdcard/Android/data/...`, which doesn't match
        // the string from getExternalStorageDirectory(), so a string-only check
        // would also list internal storage as a card. `/storage/emulated/0` has
        // an extra segment and so naturally does not match.
        if (!VOLUME_PATH.matches(root)) return null
        return RemovableVolume(root, File(root).name)
    }

    private const val PRIVATE_MARK = "/Android/data/"
    private val VOLUME_PATH = Regex("/storage/[^/]+")
}
