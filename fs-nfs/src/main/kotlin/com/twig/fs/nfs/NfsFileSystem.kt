package com.twig.fs.nfs

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream

/** Minimal NFSv3/v4.0 evaluation backend, AUTH_SYS only. */
class NfsFileSystem(
    private val host: String,
    private val export: String,
    private val version: Int = 3,
    private val uid: Int = 65534,
    private val gid: Int = 65534,
    override val scheme: String = "nfs",
) : FileSystem {
    override val displayName = "NFS ($host:$export)"
    private var metadata: NativeNfsClient? = null
    private fun client() = NativeNfsClient(host, export, version, uid, gid)
    @Synchronized private fun <T> exec(block: (NativeNfsClient) -> T): T {
        val c = metadata ?: client().also { metadata = it }
        return block(c)
    }
    fun connect() { exec { } }
    @Synchronized fun disconnect() {
        val old = metadata
        metadata = null
        old?.close()
    }
    override fun root() = XFile(scheme, "/", isDir = true)
    override fun resolve(path: String) = XFile(scheme, path, isDir = true)
    private fun entry(path: String, mode: Long, size: Long, modified: Long) =
        XFile(scheme, path, isDir = mode.toInt() and 0xf000 == 0x4000,
            size = size, lastModified = modified)

    override fun list(dir: XFile): List<XFile> = exec { c ->
        c.list(dir.path).map { e -> entry(join(dir.path, e.name), e.mode, e.size, e.modified) }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
    }
    override fun stat(path: String): XFile? = exec { c ->
        c.stat(path)?.let { entry(path, it[0], it[1], it[2]) }
    }
    override fun exists(file: XFile) = exec { it.stat(file.path, follow = false) != null }
    private fun opened(file: XFile, write: Boolean): NativeNfsClient {
        val c = client()
        try { c.open(file.path, write); return c }
        catch (e: Throwable) { runCatching { c.close() }; throw e }
    }
    override fun openInput(file: XFile): InputStream {
        val source = openRandom(file)
        return object : InputStream() {
            private var position = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                source.readAt(position, b, off, len).also { if (it > 0) position += it }
            override fun skip(n: Long): Long = n.coerceAtLeast(0).coerceAtMost(
                (source.length() - position).coerceAtLeast(0)).also { position += it }
            override fun close() = source.close()
        }
    }
    override fun randomAccessEfficient() = true
    override fun openRandom(file: XFile): RandomSource {
        val c = opened(file, write = false)
        val size = try { c.stat(file.path)?.get(1) ?: file.size }
        catch (e: Throwable) { runCatching { c.close() }; throw e }
        return object : RandomSource {
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                require(position >= 0)
                bounds(buffer, offset, length)
                if (length == 0) return 0
                return c.read(position, buffer, offset, minOf(length, 256 * 1024))
            }
            override fun length() = size
            @Synchronized override fun close() = c.close()
        }
    }
    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        if (append) throw FsException("NFS evaluation does not support append")
        requireEntry(file)
        val c = opened(file, write = true)
        return object : OutputStream() {
            private var position = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
                bounds(b, off, len)
                var done = 0
                while (done < len) {
                    val n = minOf(len - done, 256 * 1024)
                    c.write(position, b, off + done, n)
                    position += n; done += n
                }
            }
            @Synchronized override fun close() = c.close()
        }
    }
    override fun mkdir(parent: XFile, name: String): XFile {
        val path = join(parent.path, name)
        exec { it.mutate(0, path) }
        return XFile(scheme, path, isDir = true)
    }
    override fun delete(file: XFile) {
        requireEntry(file)
        // lstat prevents recursive traversal through a symlink to a directory.
        val st = exec { it.stat(file.path, follow = false) } ?: throw FsException("NFS entry does not exist")
        val directory = st[0].toInt() and 0xf000 == 0x4000
        if (directory) list(file).forEach { delete(it) }
        exec { it.mutate(if (directory) 2 else 1, file.path) }
    }
    override fun rename(file: XFile, newName: String): XFile {
        requireEntry(file)
        val path = join(file.parentPath, newName)
        exec { c ->
            if (c.stat(path, follow = false) != null) throw FsException("NFS destination already exists")
            c.mutate(3, file.path, path)
        }
        return file.copy(path = path)
    }
    private fun requireEntry(file: XFile) {
        if (file.path.trim('/').isEmpty()) throw FsException("Cannot modify the NFS export root")
    }
    private fun join(parent: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name)
        return parent.trimEnd('/') + "/" + name
    }
    private fun bounds(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
    }
}
