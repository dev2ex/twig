package com.twig.app

import android.content.Context
import com.twig.core.FsRegistry
import com.twig.core.XFile

/**
 * m3u/m3u8 playlist parser. Relative paths inside the list are resolved against the
 * "directory containing the m3u", and stay on the same source as the m3u (local / SMB /
 * … same scheme); absolute paths are resolved against that source's root. Cross-source
 * entries use the favourite/history location format (`smb://server/share/path`);
 * internal source URIs are also accepted. HTTP streams are skipped. Blocking I/O.
 */
object M3uPlaylist {

    /** Parse entries, including full source URIs written by [PlaylistExport].
     * Also lists each containing directory once to fill in size / lastModified — persisted
     * once at import, this keeps [Thumbs]' cache key (md5(name:size:mtime)) stable across
     * sessions, so we don't have to call MusicEngine.statByListing every time the playlist
     * opens (that's the root cause of "m3u8-imported tracks' covers / sizes are re-fetched
     * every time"). Grouped by directory so an album folder of dozens of songs is listed once. */
    fun parse(file: XFile, resolveLocation: (String) -> XFile? = { null }, prepareSource: (String) -> Boolean = { scheme ->
        FsRegistry.all().any { it.scheme == scheme }
    }): List<XFile> = runCatching {
        val fs = FsRegistry.of(file)
        val text = decode(fs.openInput(file).use { OpenFiles.readAllBytes(it, 256 * 1024) })
        val baseDir = file.parentPath
        val paths = ArrayList<XFile>()
        val sources = HashMap<String, Boolean>()
        for (raw in text.split('\n')) {
            val line = raw.trim().trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#")) continue // empty line / #EXTM3U / #EXTINF etc.
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) continue // network streams unsupported
            if (LOCATION_PATH.containsMatchIn(line)) {
                val entry = resolveLocation(line) ?: continue
                if (!sources.getOrPut(entry.scheme) { prepareSource(entry.scheme) }) continue
                paths.add(entry.copy(path = normalize(entry.path)))
                continue
            }
            val uri = SOURCE_URI.matchEntire(line)
            if (uri != null) {
                val scheme = uri.groupValues[1].let { if (it == "content") "saf" else it }
                if (!sources.getOrPut(scheme) { prepareSource(scheme) }) continue
                val path = if (scheme == "saf" || scheme == "share") line else uri.groupValues[2]
                // Twig source URIs have no authority: the path starts with '/'. Unknown URLs
                // must never be reinterpreted as relative filenames on the playlist's source.
                if (scheme != "saf" && scheme != "share" && !path.startsWith('/')) continue
                paths.add(XFile(scheme, if (path.startsWith('/')) normalize(path) else path, isDir = false))
                continue
            }
            // Malformed source addresses must not turn into local relative filenames.
            if (SOURCE_PATH.containsMatchIn(line)) continue
            val rel = line.replace('\\', '/') // tolerate Windows backslashes
            val abs = if (rel.startsWith("/")) rel else "${baseDir.trimEnd('/')}/$rel"
            paths.add(XFile(file.scheme, normalize(abs), isDir = false))
        }
        val dirCache = HashMap<Pair<String, String>, List<XFile>>()
        paths.map { entry ->
            // Document URIs cannot be split into parent/name; ask the provider directly.
            if (entry.path.startsWith("content://")) {
                return@map runCatching { FsRegistry.of(entry).stat(entry.path) }.getOrNull() ?: entry
            }
            val dir = entry.parentPath
            val listed = dirCache.getOrPut(entry.scheme to dir) {
                runCatching { FsRegistry.of(entry).list(XFile(entry.scheme, dir, isDir = true)) }.getOrDefault(emptyList())
            }
            listed.firstOrNull { !it.isDir && it.path == entry.path }
                ?: entry // missing/unreadable: playback retries on demand
        }
    }.getOrDefault(emptyList())

    /** Restore saved connections even when this pane has never expanded their server rows.
     * Blocking I/O: parse metadata on each entry's own source, then persist its connection label. */
    fun tracks(ctx: Context, file: XFile): List<PlaylistTrack> {
        val connections = ConnectionStore.all(ctx).associateBy { Connections.schemeOf(it) }
        val files = parse(file, resolveLocation = { line ->
            resolveLocation(line, connections.values.toList())
        }) { scheme ->
            val conn = connections[scheme]
            if (conn != null) {
                // Offline entries still retain their saved identity and can be retried later.
                runCatching { Connections.ensure(ctx, conn) }
                true
            } else scheme == "file" || scheme == "saf" || scheme == "share"
        }
        return files.mapNotNull { f ->
            val conn = connections[f.scheme]
            val kind = when {
                f.scheme == "file" -> "local"
                f.scheme == "saf" -> "saf"
                f.scheme == "share" -> "share"
                conn != null -> "conn"
                else -> return@mapNotNull PlaylistTrack.fromMounted(f)
            }
            PlaylistTrack(kind, f.path, connLabel = conn?.label().orEmpty(), size = f.size,
                lastModified = f.lastModified, displayName = f.displayName.orEmpty())
        }
    }

    private val SOURCE_URI = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$")
    private val LOCATION_PATH = Regex(
        "^(?:smb|sftp|ftp|webdav|s3|jellyfin|emby)://(?!/)",
    )

    private val SOURCE_PATH = Regex("^[A-Za-z][A-Za-z0-9+.-]*:/")

    /** Match the longest mounted root; use aliases only when no raw address matches.
     * An ambiguous address (e.g. two accounts on one server) must not pick an arbitrary user. */
    private fun resolveLocation(line: String, connections: List<SavedConnection>): XFile? {
        fun candidates(raw: Boolean): List<Pair<SavedConnection, String>> = connections.mapNotNull { conn ->
            val prefix = "${conn.type}://" + (if (raw) conn.rawShortLabel() else conn.shortLabel()).trimEnd('/')
            if (line.startsWith("$prefix/")) conn to prefix else null
        }
        val raw = candidates(true)
        val matches = raw.ifEmpty { candidates(false) }
        val longest = matches.maxOfOrNull { it.second.length } ?: return null
        val match = matches.filter { it.second.length == longest }.singleOrNull() ?: return null
        return XFile(Connections.schemeOf(match.first), line.removePrefix(match.second), isDir = false)
    }

    /** Normalise path: collapse `.` / `..`, strip duplicate slashes. */
    private fun normalize(path: String): String {
        val stack = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            else -> stack.add(p)
        }
        return "/" + stack.joinToString("/")
    }

    /** BOM → strict UTF-8 → the encodings the user has ordered in settings; see [TextCodec]. */
    private fun decode(bytes: ByteArray): String = TextCodec.decode(bytes).text
}
