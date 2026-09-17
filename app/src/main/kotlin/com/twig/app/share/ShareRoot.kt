package com.twig.app.share

import android.content.Context
import android.os.Environment
import com.twig.app.Connections
import com.twig.app.ConnectionStore
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File

/**
 * URL path ↔ [XFile] mapping — the only place in the HTTP/WebDAV layer that knows about
 * "files".
 *
 * The two scopes ([ShareScope]) are flattened into one tree here:
 *  - single directory: `/a/b` is `a/b` under that directory;
 *  - all sources: `/` is a **virtual root** (no XFile behind it), its children are the
 *    sources from [sources], and `/<source segment>/a/b` walks down from there.
 *
 * ## Why "drill down by name, level by level" rather than "join the path" (★ rewritten 2026-08-10)
 *
 * The first version assumed "URL segments joined together = `XFile.path`". That holds for
 * local/SMB/FTP and not at all for sources whose **paths are opaque**:
 *  - "Apps" (`AppsFileSystem`) uses the package name as path (`/user/com.tencent.mm`) while
 *    [XFile.name] is `WeChat 8.0.x.apk` — only the latter can appear in a URL, and joining
 *    it back fails with "no such app";
 *  - a SAF path is an entire document URI; "parent path + name" does not exist there.
 *
 * Now: start from the source root, list each level and pick the child by [XFile.name]; what
 * comes back is its **real** XFile (opaque path carried as is). The cost is that a deep link
 * lists every level once, which [DirCache] absorbs — browsing goes level by level anyway, so
 * the ancestors are all cached.
 */
class ShareRoot(private val ctx: Context, private val scope: ShareScope) {

    /**
     * One entry under the "all sources" virtual root.
     *
     * [segment] is that URL segment and **must be stable** — WebDAV clients save it as a
     * mount point. Fixed literals (`storage`/`root`/`apps`) or the scheme (servers; derived
     * deterministically from the connection label) never shift because the user expanded
     * another server. [label] is display only.
     */
    class Source(val segment: String, val label: String, val scheme: String, val basePath: String)

    /**
     * The app's own private directories, canonicalised once. Never served, whatever the
     * scope — see [isPrivate].
     */
    private val privateDirs: List<String> = listOfNotNull(
        ctx.dataDir,
        ctx.applicationInfo.deviceProtectedDataDir?.let { File(it) },
    ).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.distinct()

    /**
     * Connect whatever needs connecting when the service starts: if the scope points into a
     * server and the process just cold-started (not in FsRegistry yet), reconnect using the
     * stored connection label. Blocking IO; call on a worker thread.
     *
     * Failures are not thrown — they surface on the first request as "cannot open + reason"
     * (see the error bar in [WebUi.renderDir]), which is easier to diagnose than a service
     * that refuses to start.
     */
    fun ensureReady() {
        val s = scope as? ShareScope.Dir ?: return
        if (s.connLabel.isEmpty()) return
        if (FsRegistry.all().any { it.scheme == s.scheme }) return
        runCatching {
            Connections.find(ctx, s.connLabel)?.let { Connections.ensure(ctx, it) }
        }
    }

    /** The virtual root of "all sources" mode: every other URL path maps to a real [XFile]. */
    fun isVirtualRoot(path: String): Boolean =
        scope is ShareScope.AllSources && segments(path)?.isEmpty() == true

    /**
     * Children of the virtual root; empty in single-directory mode.
     *
     * **Only sources that can actually be browsed.** `FsRegistry.all()` also holds things
     * that are not roots: zip/7z/rar are containers mounted on a host file (`root()` throws
     * "Archive must be mounted via rootOf(archive)"), SAF needs a tree picked first, and
     * `share` relays other apps' content:// URIs. The first version listed them all, so the
     * page grew `Archive`/`7z archive`/`RAR archive`/`Share` entries that always failed when
     * opened. The criterion is just: does [FileSystem.root] return normally.
     *
     * Local storage is split into "Internal storage" and "Root directory" — the root of
     * `LocalFileSystem` is `/`, which shows the user `acct`/`apex`/`vendor` and friends,
     * while nine times out of ten `/sdcard` is what they want. This matches the two
     * top-level nodes in the app's own tree.
     */
    fun sources(): List<Source> {
        if (scope !is ShareScope.AllSources) return emptyList()
        val out = ArrayList<Source>()
        val conns = ConnectionStore.all(ctx).associateBy { Connections.schemeOf(it) }
        for (fs in FsRegistry.all()) {
            if (fs.scheme == LOCAL) {
                val ext = runCatching { Environment.getExternalStorageDirectory().absolutePath }
                    .getOrNull().orEmpty()
                if (ext.isNotEmpty()) {
                    out += Source("storage", ctx.getString(R.string.group_internal_storage), LOCAL, ext)
                }
                out += Source("root", ctx.getString(R.string.group_root), LOCAL, "/")
                continue
            }
            // root() throws = this source is not an independently browsable tree; skip it
            val root = runCatching { fs.root() }.getOrNull() ?: continue
            out += Source(
                segment = fs.scheme,
                label = conns[fs.scheme]?.displayLabel() ?: fs.displayName,
                scheme = fs.scheme,
                basePath = root.path,
            )
        }
        return out
    }

    /**
     * Resolve a URL path to an [XFile]. Returns null when the path is illegal (contains
     * `..`), the source does not exist, some level cannot be found, or the result is one of
     * the app's private directories.
     *
     * Drills down level by level; see the class comment for why.
     */
    fun resolve(path: String): XFile? {
        val segs = segments(path) ?: return null
        val (fs, base, rest) = entry(segs) ?: return null
        var cur = runCatching { fs.resolve(base) }.getOrNull()?.copy(isDir = true) ?: return null
        for (name in rest) {
            if (!cur.isDir) return null
            val child = runCatching { cache.list(fs, cur) }.getOrNull()
                ?.firstOrNull { it.name == name } ?: return null
            cur = child
        }
        if (isPrivate(cur)) return null
        return cur
    }

    /** List a directory; [file] must be a directory from [resolve]. Exceptions propagate so the caller can show the reason. */
    fun list(file: XFile): List<XFile> {
        val all = cache.list(FsRegistry.of(file), file)
        // Only hide rows when a private directory sits directly in this one: anything that
        // gets there another way (a symlink) is still refused by [resolve], and canonicalising
        // every row of a large directory is not free.
        if (!holdsPrivate(file)) return all
        return all.filterNot { isPrivate(it) }
    }

    /** Whether the whole source can be written (read-only sources — restic/7z/RAR/apps/git view — are stopped here). */
    fun writable(file: XFile): Boolean =
        runCatching { FsRegistry.of(file).writable() }.getOrDefault(false)

    /** Drop the cache after a write changed a directory, so a browser refresh does not see the old listing. */
    fun invalidate(dirPath: String) = cache.drop(dirPath)

    /** Drop the whole cache (for changes that may touch several places, e.g. both ends of a MOVE). */
    fun invalidateAll() = cache.clear()

    // ---- internals ----

    /**
     * ★ Whether [f] is (inside) one of the app's own private directories. Those hold the
     * wrapped key material, imported SSH keys, the local shell's rc files — none of it is
     * the user's content, and writing there is how a visitor would plant code the app runs
     * later. Unreachable without root in practice, since `/data` cannot be listed, but the
     * share must not depend on that: the check is on the canonical path so `/data/data/…`
     * and `/data/user/0/…` are the same thing.
     */
    private fun isPrivate(f: XFile): Boolean {
        if (f.scheme != LOCAL || privateDirs.isEmpty()) return false
        val p = runCatching { File(f.path).canonicalPath }.getOrElse { return true }
        return privateDirs.any { p == it || p.startsWith("$it/") }
    }

    private fun holdsPrivate(dir: XFile): Boolean {
        if (dir.scheme != LOCAL || privateDirs.isEmpty()) return false
        val p = runCatching { File(dir.path).canonicalPath }.getOrElse { return true }
        return privateDirs.any { it.substringBeforeLast('/').ifEmpty { "/" } == p }
    }

    /** Split URL segments into (file system, starting path, segments still to walk). */
    private fun entry(segs: List<String>): Triple<FileSystem, String, List<String>>? {
        when (val sc = scope) {
            is ShareScope.AllSources -> {
                if (segs.isEmpty()) return null // the virtual root has no XFile; callers ask isVirtualRoot first
                val src = sources().firstOrNull { it.segment == segs[0] } ?: return null
                val fs = runCatching { FsRegistry.of(src.scheme) }.getOrNull() ?: return null
                return Triple(fs, src.basePath, segs.drop(1))
            }
            is ShareScope.Dir -> {
                val fs = runCatching { FsRegistry.of(sc.scheme) }.getOrNull() ?: return null
                return Triple(fs, sc.path, segs)
            }
        }
    }

    /**
     * A small LRU of directory listings.
     *
     * Level-by-level drilling lists the ancestors over and over (a deep link walks N levels;
     * a directory page with N files walks them N times), which on network sources multiplies
     * round trips. [MAX] entries with a [TTL_MS] expiry: within a browsing session the
     * ancestors all hit, yet another client does not see a stale listing right after an
     * upload (writes also [drop] explicitly).
     */
    private class DirCache {
        private class Entry(val files: List<XFile>, val at: Long)

        private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX
        }

        fun list(fs: FileSystem, dir: XFile): List<XFile> {
            val key = "${fs.scheme} ${dir.path}"
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(map) {
                map[key]?.let { if (now - it.at < TTL_MS) return it.files }
            }
            val fresh = fs.list(dir) // network IO stays outside the lock
            synchronized(map) { map[key] = Entry(fresh, now) }
            return fresh
        }

        fun drop(dirPath: String) = synchronized(map) {
            map.keys.removeAll { it.substringAfter(' ') == dirPath }
        }

        fun clear() = synchronized(map) { map.clear() }

        companion object {
            private const val MAX = 32
            private const val TTL_MS = 5000L
        }
    }

    private val cache = DirCache()

    companion object {
        private const val LOCAL = "file"

        /**
         * Split a URL path into segments. **`..` is always rejected** (returns null): this
         * service puts the device's files on the network, and path traversal would hand out
         * things outside the shared scope. `.` and empty segments are dropped.
         */
        fun segments(path: String): List<String>? {
            val out = ArrayList<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> return null
                    else -> out.add(seg)
                }
            }
            return out
        }
    }
}
