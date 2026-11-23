package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.SequentialSource
import com.twig.core.XFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Compressed tar extraction with bounded buffers, without an intermediate tar file. */
object StreamingTar {
    data class Probe(val roots: List<String>, val complete: Boolean)

    fun supports(file: XFile): Boolean = !file.isDir &&
        (file.extension in SingleFileSystem.TAR_SHORTHAND ||
            file.extension in setOf("gz", "xz", "bz2", "zst") &&
            file.name.substringBeforeLast('.').endsWith(".tar", ignoreCase = true))

    /** Stop as soon as a second top-level entry proves that an enclosing folder is needed. */
    fun probe(file: XFile, cancelled: CopyEngine.Cancelled = CopyEngine.Cancelled { false }): Probe =
        open(file, cancelled).use { source ->
            val roots = LinkedHashSet<String>()
            var complete = false
            while (roots.size < 2) {
                val entry = source.next()
                if (entry == null) { complete = true; break }
                roots.add(entry.components.first())
            }
            Probe(roots.toList(), complete)
        }

    fun open(file: XFile, cancelled: CopyEngine.Cancelled = CopyEngine.Cancelled { false }): SequentialSource {
        require(supports(file)) { "Not a compressed tar: ${file.name}" }
        val codec = FsRegistry.of(Archives.schemeFor(file)!!) as SingleFileSystem
        val decoded = codec.openDecoded(file)
        return try { Reader(file, decoded, cancelled) } catch (t: Throwable) { decoded.close(); throw t }
    }

    private class Reader(host: XFile, decoded: InputStream, private val cancelled: CopyEngine.Cancelled) : SequentialSource {
        override val scheme = "tar-stream"
        override val displayName = "Tar stream"
        private val identity = "${host.scheme}:${host.path}!/"
        private val checked = object : FilterInputStream(decoded) {
            private val discard = ByteArray(64 * 1024)
            override fun read(): Int { checkCancelled(); return `in`.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                checkCancelled()
                return `in`.read(b, off, len)
            }
            override fun skip(n: Long): Long {
                var left = n.coerceAtLeast(0)
                while (left > 0) {
                    val count = read(discard, 0, minOf(left, discard.size.toLong()).toInt())
                    if (count < 0) break
                    left -= count
                }
                return n.coerceAtLeast(0) - left
            }
        }
        private val tar = TarArchiveInputStream(checked)
        private var current: XFile? = null
        private var opened = false
        private var ordinal = 0L
        private var ended = false

        private fun checkCancelled() {
            if (cancelled.isCancelled()) throw CancellationException("Tar extraction cancelled")
        }

        override fun next(): SequentialSource.Entry? {
            if (ended) return null
            while (true) {
                checkCancelled()
                val entry = tar.nextTarEntry
                if (entry == null) {
                    // Reading the tar EOF alone may not reach the compression trailer.
                    // Drain padding so gzip/xz/bzip2/zstd can validate their final checksums.
                    val buffer = ByteArray(64 * 1024)
                    while (checked.read(buffer) >= 0) { checkCancelled() }
                    ended = true
                    current = null
                    return null
                }
                val index = ordinal++
                val components = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (components.isEmpty()) continue
                if (!tar.canReadEntryData(entry)) throw FsException("Unsupported tar entry: ${entry.name}")
                if (!entry.isDirectory && !entry.isFile && !entry.isSymbolicLink)
                    throw FsException("Unsupported tar entry type: ${entry.name}")
                if (entry.isLink) throw FsException("Hard links cannot be extracted: ${entry.name}")
                val file = XFile(scheme, "$identity$index/${components.joinToString("/")}", entry.isDirectory,
                    if (entry.isDirectory) 0 else entry.realSize,
                    entry.lastModifiedDate?.time ?: 0, canWrite = false,
                    unixMode = entry.mode, unixUid = entry.longUserId, unixGid = entry.longGroupId,
                    isSymbolicLink = entry.isSymbolicLink, linkTarget = entry.linkName.takeIf { entry.isSymbolicLink })
                current = file
                opened = false
                return SequentialSource.Entry(file, components)
            }
        }

        override fun openInput(file: XFile): InputStream {
            check(file == current && !file.isDir && !opened) { "Tar entry is no longer current: ${file.name}" }
            opened = true
            return object : InputStream() {
                private var read = 0L
                override fun read(): Int {
                    val value = tar.read()
                    if (value >= 0) read++ else verifyEnd()
                    return value
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = tar.read(b, off, len)
                    if (count > 0) read += count else if (count < 0) verifyEnd()
                    return count
                }
                override fun skip(n: Long): Long = tar.skip(n).also { read += it }
                private fun verifyEnd() {
                    if (read != file.size) throw FsException("Truncated tar entry: ${file.name}")
                }
                // The archive owns the stream; closing one entry must not close the next.
                override fun close() = Unit
            }
        }

        override fun root() = XFile(scheme, identity, true, canWrite = false)
        override fun resolve(path: String): XFile = throw FsException("Sequential tar paths cannot be resolved")
        override fun list(dir: XFile): List<XFile> = emptyList() // Children arrive from next(), in physical order.
        override fun exists(file: XFile) = file == current
        override fun writable() = false
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("Tar stream is read-only")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("Tar stream is read-only")
        override fun delete(file: XFile): Unit = throw FsException("Tar stream is read-only")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("Tar stream is read-only")
        override fun close() = tar.close()
    }
}
