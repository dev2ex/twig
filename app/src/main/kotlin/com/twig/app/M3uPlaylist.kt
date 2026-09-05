package com.twig.app

import com.twig.core.FsRegistry
import com.twig.core.XFile

/**
 * m3u/m3u8 playlist parser. Relative paths inside the list are resolved against the
 * "directory containing the m3u", and stay on the same source as the m3u (local / SMB /
 * … same scheme); absolute paths are resolved against that source's root; http(s) network
 * streams are unsupported and skipped. Blocking I/O, must run on a worker thread.
 */
object M3uPlaylist {

    /** Parse the entries into [XFile]s (same scheme as the m3u; existence isn't verified — the playback engine skips missing ones on demand).
     * Also lists each containing directory once to fill in size / lastModified — persisted
     * once at import, this keeps [Thumbs]' cache key (md5(name:size:mtime)) stable across
     * sessions, so we don't have to call MusicEngine.statByListing every time the playlist
     * opens (that's the root cause of "m3u8-imported tracks' covers / sizes are re-fetched
     * every time"). Grouped by directory so an album folder of dozens of songs is listed once. */
    fun parse(file: XFile): List<XFile> = runCatching {
        val fs = FsRegistry.of(file)
        val text = decode(fs.openInput(file).use { OpenFiles.readAllBytes(it, 256 * 1024) })
        val baseDir = file.parentPath
        val paths = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trim().trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#")) continue // empty line / #EXTM3U / #EXTINF etc.
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) continue // network streams unsupported
            val rel = line.replace('\\', '/') // tolerate Windows backslashes
            val abs = if (rel.startsWith("/")) rel else "${baseDir.trimEnd('/')}/$rel"
            paths.add(normalize(abs))
        }
        val dirCache = HashMap<String, List<XFile>>()
        paths.map { p ->
            val dir = p.substringBeforeLast('/', "").ifEmpty { "/" }
            val name = p.substringAfterLast('/')
            val listed = dirCache.getOrPut(dir) {
                runCatching { fs.list(XFile(file.scheme, dir, isDir = true)) }.getOrDefault(emptyList())
            }
            listed.firstOrNull { !it.isDir && it.name == name }
                ?: XFile(file.scheme, p, isDir = false) // not listed (path missing / directory unreadable): keep as-is, playback engine skips
        }
    }.getOrDefault(emptyList())

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
