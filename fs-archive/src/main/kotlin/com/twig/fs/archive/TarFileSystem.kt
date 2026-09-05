package com.twig.fs.archive

import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarFile
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Tar file system (read-only), on Commons Compress' [TarFile].
 *
 * Tar has no central directory: it is "512-byte header + data + padding" all the way
 * down, so listing means walking the whole file. But only the *headers* are read — the
 * data blocks are skipped, and nothing is ever decompressed (tar itself compresses
 * nothing). The walk goes through [openChannel], which is a FileChannel locally and a
 * [RandomSourceChannel] for remote hosts; that one carries a 256 KB block buffer, and a
 * header walk is exactly the sequential pattern it is good at — so a tar on SMB/WebDAV
 * lists without downloading the whole archive.
 *
 * ★ **Reading an entry must not re-walk that chain.** A tar's headers are spread across
 * the entire file, so one walk costs a pass over the whole archive's span — locally the
 * page cache hides it, but on a remote host every 512-byte header pulls a fresh 256 KB
 * block, and copying N files out of the archive would pay for N walks. Since tar stores
 * entry data contiguously and uncompressed, the walk that lists the archive also records
 * each entry's `(dataOffset, size)`, and reading is then a plain seek + bounded read with
 * no [TarFile] involved at all. This is the same trick zip plays on its STORED entries
 * (`ZipFileSystem.storedSlice`), except that for tar it applies to *every* entry.
 *
 * That is also what makes [fastRandom] true here: a nested archive inside a tar opens
 * without being materialised first, and video inside a tar can seek.
 *
 * Read-only for now. Appending to a tar is genuinely easy (truncate the two trailing
 * zero blocks and write on), but delete/replace still means rewriting the archive, which
 * is a separate piece of machinery from the one zip has.
 */
class TarFileSystem : ArchiveFileSystem() {

    override val scheme: String = SCHEME
    override val displayName: String = "Tar archive"
    override fun writable(): Boolean = false

    /** Where one entry's bytes live inside the archive. */
    private data class Slice(val offset: Long, val size: Long)

    /**
     * Archive stamp → (entry name → slice), filled by the listing walk.
     * The key is [stampOf] (path + size + mtime), so replacing the file with a different
     * archive of the same name invalidates it on its own. Bounded to a handful of
     * archives in LRU order: the table of a tar with tens of thousands of entries is not
     * huge, but there is no reason to keep every archive ever opened.
     */
    private val sliceCache = object : LinkedHashMap<String, Map<String, Slice>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Slice>>) =
            size > 4
    }

    private fun openTar(archivePath: String) = TarFile(openChannel(archivePath))

    override fun readEntries(archivePath: String): List<ArchiveEntry> {
        val slices = LinkedHashMap<String, Slice>()
        val list = openTar(archivePath).use { tar ->
            tar.entries.map { e ->
                val name = e.name.replace('\\', '/')
                // Sparse entries are stored in pieces with holes between them; only the
                // library knows how to reassemble one, so they are left off the fast path.
                if (!e.isDirectory && !e.isSparse && e.dataOffset >= 0) {
                    // Later wins, matching openEntry's "same name twice" rule below
                    slices[name] = Slice(e.dataOffset, e.size)
                }
                ArchiveEntry(
                    name = e.name,
                    isDir = e.isDirectory,
                    size = if (e.isDirectory) 0L else e.size,
                    time = timeOf(e),
                )
            }
        }
        synchronized(sliceCache) { sliceCache[stampOf(archivePath)] = slices }
        return list
    }

    /** The offset table, walking the archive once if it is not cached yet. */
    private fun slicesOf(archivePath: String): Map<String, Slice> {
        synchronized(sliceCache) { sliceCache[stampOf(archivePath)]?.let { return it } }
        runCatching { readEntries(archivePath) } // fills the cache as a side effect
        return synchronized(sliceCache) { sliceCache[stampOf(archivePath)] } ?: emptyMap()
    }

    override fun fastRandom(file: XFile): Boolean =
        slicesOf(archiveOf(file.path))[innerOf(file.path)] != null

    override fun openEntry(archivePath: String, inner: String): InputStream {
        val slice = slicesOf(archivePath)[inner]
        return if (slice != null) sliceStream(archivePath, slice) else viaTarFile(archivePath, inner)
    }

    override fun openRandom(file: XFile): RandomSource {
        val archive = archiveOf(file.path)
        val slice = slicesOf(archive)[innerOf(file.path)] ?: return super.openRandom(file)
        val ch = openChannel(archive)
        return object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= slice.size) return -1
                val want = minOf(length.toLong(), slice.size - position).toInt()
                val bb = ByteBuffer.wrap(buffer, offset, want)
                synchronized(ch) {
                    ch.position(slice.offset + position)
                    return ch.read(bb)
                }
            }

            override fun length(): Long = slice.size

            override fun close() {
                runCatching { ch.close() }
            }
        }
    }

    /** Plain bounded read of a contiguous region — no tar parsing on this path. */
    private fun sliceStream(archivePath: String, slice: Slice): InputStream {
        val ch = openChannel(archivePath)
        return try {
            ch.position(slice.offset)
            object : InputStream() {
                private var left = slice.size

                override fun read(): Int {
                    val b = ByteArray(1)
                    return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0L) return -1
                    if (len == 0) return 0
                    val want = minOf(len.toLong(), left).toInt()
                    val n = ch.read(ByteBuffer.wrap(b, off, want))
                    if (n <= 0) return -1
                    left -= n
                    return n
                }

                /** Seek rather than read-and-discard; the default would pull the bytes. */
                override fun skip(n: Long): Long {
                    if (n <= 0L || left <= 0L) return 0L
                    val k = minOf(n, left)
                    ch.position(ch.position() + k)
                    left -= k
                    return k
                }

                override fun available(): Int = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                override fun close() = ch.close()
            }
        } catch (t: Throwable) {
            runCatching { ch.close() }
            throw t
        }
    }

    /** Fallback for sparse entries (and anything the walk could not place). */
    private fun viaTarFile(archivePath: String, inner: String): InputStream {
        val tar = openTar(archivePath)
        try {
            // Same name twice: take the **last** one. Tar allows appending an entry that
            // shadows an earlier one, and later wins — which is also how the base class'
            // list() collapses them (LinkedHashMap overwrite), so the two agree.
            val e = tar.entries.lastOrNull { !it.isDirectory && it.name.replace('\\', '/') == inner }
                ?: throw FsException("No such file in tar archive: $inner")
            val input = tar.getInputStream(e)
            return object : FilterInputStream(input) {
                override fun close() {
                    try { super.close() } finally { tar.close() }
                }
            }
        } catch (t: Throwable) {
            tar.close()
            throw t
        }
    }

    /**
     * ★ Only [TarArchiveEntry.getLastModifiedDate] may be used here: the modern
     * `getLastModifiedTime()` returns a `java.nio.file.attribute.FileTime`, a type that
     * does not exist below API 26 while minSdk is 24. SevenZFileSystem reads its times
     * the same way for the same reason — keep the two consistent.
     */
    private fun timeOf(e: TarArchiveEntry): Long =
        runCatching { e.lastModifiedDate?.time ?: 0L }.getOrDefault(0L)

    companion object {
        const val SCHEME = "tar"
    }
}
