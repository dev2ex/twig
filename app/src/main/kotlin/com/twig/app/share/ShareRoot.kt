package com.twig.app.share

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.twig.app.Connections
import com.twig.app.ConnectionStore
import com.twig.app.R
import com.twig.app.RemovableVolume
import com.twig.app.SavedConnection
import com.twig.app.StorageVolumes
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.StorageSpace
import com.twig.core.StorageSpaceSource
import com.twig.core.XFile
import com.twig.fs.network.TwigDavSources
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
class ShareRoot(
    private val ctx: Context,
    private val scope: ShareScope,
    private val localSpace: (String) -> StorageSpace? = { path ->
        val stat = StatFs(path)
        val available = stat.availableBytes
        val total = stat.totalBytes
        if (total <= 0 || available < 0 || available > total) null
        else StorageSpace(available, total - available)
    },
    /**
     * The removable volumes (SD card / USB drive) offered as sources.
     *
     * Injectable for tests; production reads [StorageVolumes]' cached list, which the app
     * keeps up to date (the foreground poll in `MainActivity` re-scans it).
     */
    private val volumes: () -> List<RemovableVolume> = { StorageVolumes.cached(ctx) },
) {

    /**
     * One entry under the "all sources" virtual root.
     *
     * [segment] is that URL segment and **must be stable** — WebDAV clients save it as a
     * mount point, so it is the name the user sees there: `SMB-NAS`, `WebDAV-192.168.1.5`,
     * `External-SDCARD`. [alias] is an older segment that still resolves (the segment used
     * to be the hashed scheme for servers), so a mount point saved before the rename keeps
     * working. [label] is display only.
     */
    class Source(
        val segment: String,
        val label: String,
        val scheme: String,
        val basePath: String,
        val alias: String? = null,
    )

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
     * The removable volumes are re-scanned here as well: a card plugged in while the app
     * was in the background is not in the cache yet, and "all sources" is exactly the mode
     * where it has to show up.
     *
     * Failures are not thrown — they surface on the first request as "cannot open + reason"
     * (see the error bar in [WebUi.renderDir]), which is easier to diagnose than a service
     * that refuses to start.
     */
    fun ensureReady() {
        runCatching { StorageVolumes.refresh(ctx) }
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
     *
     * ★ **Removable volumes (SD card / USB drive) are sources too** (issue #64). The app's
     * own tree lists them at the top, but the share offered only internal storage and `/`,
     * so a visitor saw "internal storage only" on a device whose card and USB drive were
     * perfectly visible in the app. They are separate volumes with separate mount points
     * (`/storage/<uuid>`), never a subdirectory of internal storage, so nothing else
     * exposes them.
     */
    fun sources(): List<Source> = sources(legacy = false)

    // Reuse the previous collision algorithm for old addresses, including duplicate names.
    // Legacy names are resolved only; directory listings always advertise canonical names.
    private fun sources(legacy: Boolean): List<Source> {
        if (scope !is ShareScope.AllSources) return emptyList()
        val out = ArrayList<Source>()
        val used = HashSet<String>()
        val conns = ConnectionStore.all(ctx).associateBy { Connections.schemeOf(it) }

        // The non-local backends, collected first: a server whose name is taken by another
        // server needs a unique segment, and that must not depend on the order the registry
        // happens to hand them out in.
        val others = ArrayList<Triple<FileSystem, SavedConnection?, XFile>>()
        var hasLocal = false
        for (fs in FsRegistry.all()) {
            if (fs.scheme == LOCAL) {
                hasLocal = true
                continue
            }
            // root() throws = this source is not an independently browsable tree; skip it
            val root = runCatching { fs.root() }.getOrNull() ?: continue
            others += Triple(fs, conns[fs.scheme], root)
        }
        val bases = others.map { serverSegment(it.second, it.first.scheme, legacy) }
        // Include saved but disconnected servers so registry changes cannot rename a mount.
        val candidates = conns.mapValues { (scheme, conn) -> serverSegment(conn, scheme, legacy) }.toMutableMap()
        others.forEachIndexed { i, (fs, _, _) -> candidates[fs.scheme] = bases[i] }
        val shared = candidates.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val reserved = candidates.values.toSet() + candidates.keys

        if (hasLocal) {
            val ext = runCatching { Environment.getExternalStorageDirectory().absolutePath }
                .getOrNull().orEmpty()
            if (ext.isNotEmpty()) {
                out += Source(if (legacy) "storage" else "Internal-Storage", ctx.getString(R.string.group_internal_storage), LOCAL, ext)
                used += out.last().segment
            }
            out += Source(if (legacy) "root" else "Root", ctx.getString(R.string.group_root), LOCAL, "/")
            used += out.last().segment
            for (s in volumeSources(legacy)) {
                out += s
                used += s.segment
            }
        }
        others.sortedBy { it.first.scheme }.forEach { (fs, conn, root) ->
            val base = candidates.getValue(fs.scheme)
            var segment = base
            // Two servers of the same type with the same name: the hash of the connection
            // label (which is what the scheme is made of) tells them apart.
            if (segment in shared) segment = "$segment-${fs.scheme.removePrefix(conn?.type ?: "")}"
            val suffixBase = "$base-${fs.scheme}"
            var suffix = 2
            while (segment in used || (segment != base && segment in reserved) ||
                (segment != fs.scheme && segment in candidates.keys)) {
                segment = "$suffixBase-${suffix++}"
            }
            used += segment
            out += Source(
                segment = segment,
                label = conn?.displayLabel() ?: fs.displayName,
                scheme = fs.scheme,
                basePath = root.path,
                // The segment used to be the scheme itself; a mount point saved back then
                // still resolves.
                alias = fs.scheme,
            )
        }
        return out
    }

    /**
     * The URL segment of a server: its type and its name — `SMB-NAS`, `WebDAV-192.168.1.5`.
     *
     * The name is the connection's own name, or its address when it has none (the same
     * text the path bar shows). The type prefix keeps two servers apart even when they
     * share a name, and tells the user what kind of server it is at a glance.
     */
    private fun serverSegment(conn: SavedConnection?, scheme: String, legacy: Boolean): String {
        if (conn == null) return if (!legacy && scheme in setOf("apps", "trash")) TwigDavSources.prefix(scheme) else scheme
        val name = sanitizeSegment(conn.shortLabel())
        val type = if (legacy) conn.type else TwigDavSources.prefix(conn.type)
        return if (name.isEmpty()) type else "$type-$name"
    }

    /**
     * Removable volumes as sources.
     *
     * **The segment is `External-` plus the volume's own name** — the same text the app's
     * tree shows ("SDCARD", "USB drive") — so a WebDAV client that mounts the share shows
     * that name instead of an internal id. The name is sanitised (a segment may not
     * contain a path separator: the decoded path is split on "/", so an encoded "%2F" would
     * come back as one) and **remembered per volume** (see [ShareStore.volumeSegment]): the
     * system's description of a volume is *localized* ("USB drive" / "USB-накопитель"), so
     * deriving it fresh every time would move the address as soon as the system language
     * changed and a saved mount point would 404.
     *
     * **Only volumes the app can open itself.** A volume mounted "invisible to the app"
     * (its path is `/mnt/media_rw/<id>`, readable by root only) needs the privileged
     * fallback — and a LAN visitor never gets that (see `LocalFileSystem.withoutElevation`),
     * so listing it would produce an entry that always fails. Such a volume is not
     * offered at all rather than offered and broken.
     */
    private fun volumeSources(legacy: Boolean): List<Source> {
        val vs = runCatching { volumes() }.getOrDefault(emptyList())
        if (vs.isEmpty()) return emptyList()
        val open = vs.filter { File(it.path).isDirectory && idOf(it).isNotEmpty() }
        if (open.isEmpty()) return emptyList()
        val bases = open.associateWith { sanitizeSegment(it.label) }
        val shared = bases.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val duplicateLabels = open.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
        return open.sortedBy { idOf(it) }.map { v ->
            val id = idOf(v)
            val name = bases.getValue(v)
            // Two volumes with the same name would make the URL ambiguous: they get the
            // volume's id appended.
            val preferred = if (name in shared) "$name-$id" else name
            val segment = (if (legacy) "external-" else EXTERNAL_PREFIX) + ShareStore.volumeSegment(ctx, id, preferred)
            val label = if (v.label in duplicateLabels) "${v.label} ($id)" else v.label
            Source(segment = segment, label = label, scheme = LOCAL, basePath = v.path)
        }
    }

    /** The volume's identity: the name of its directory (`/storage/<uuid>` → `<uuid>`). */
    private fun idOf(v: RemovableVolume): String = v.path.trimEnd('/').substringAfterLast('/')

    /**
     * Make a display name usable as one URL path segment.
     *
     * Everything that is not a letter, a digit, a space or one of `-_.` becomes a dash:
     * a path separator would survive percent encoding and split the decoded path, and a
     * colon would be percent-encoded into something unreadable. "." and ".." are dropped —
     * [segments] rejects them outright.
     */
    private fun sanitizeSegment(name: String): String {
        val cleaned = name.trim()
            .map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.') it else '-' }
            .joinToString("")
            .trim()
        return if (cleaned == "." || cleaned == "..") "" else cleaned
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

    private data class SpaceSample(val space: StorageSpace?, val at: Long = System.nanoTime())
    private val spaces = object : LinkedHashMap<XFile, SpaceSample>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<XFile, SpaceSample>?) = size > 256
    }

    /** Ordinary app access only; the request boundary also disables local elevation. */
    fun storageSpace(file: XFile): StorageSpace? {
        if (!file.isDir || isPrivate(file)) return null
        // Ignore changing listing metadata when identifying the queried directory.
        val key = XFile(file.scheme, file.path, isDir = true)
        synchronized(spaces) {
            spaces[key]?.let { if (System.nanoTime() - it.at < 10_000_000_000L) return it.space }
        }
        val space = runCatching {
            if (file.scheme == LOCAL) localSpace(file.path)
            else (FsRegistry.of(file) as? StorageSpaceSource)?.storageSpace(file)
        }.getOrNull()
        synchronized(spaces) { spaces[key] = SpaceSample(space) }
        return space
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
                val src = sourceFor(segs[0]) ?: return null
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
     * The source a URL segment names.
     *
     * The readable segment is what the links use (`SMB-NAS`), but **the old segment still
     * resolves**: a server's segment used to be its scheme, and a WebDAV client that saved
     * that mount point must keep working.
     */
    private fun sourceFor(seg: String): Source? {
        val all = sources()
        return all.firstOrNull { it.segment == seg } ?: all.firstOrNull { it.alias == seg }
            ?: sources(legacy = true).firstOrNull { it.segment == seg }
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

        /** The prefix of a removable volume's segment (`External-SDCARD`). */
        private const val EXTERNAL_PREFIX = "External-"

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
