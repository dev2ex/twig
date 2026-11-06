package com.twig.fs.nfs

import com.twig.core.FsException
import java.io.Closeable

internal data class NfsEntry(val name: String, val mode: Long, val size: Long, val modified: Long)

/** Each context is owned by the metadata lock or by one stream; never shared concurrently. */
internal class NativeNfsClient(host: String, export: String, version: Int, uid: Int, gid: Int,
    port: Int = NfsEndpoint.DEFAULT_PORT) : Closeable {
    private var handle = nConnect(host.utf8(), export.utf8(), version, uid, gid, port)
    private fun alive(): Long = handle.takeIf { it != 0L } ?: throw FsException("NFS connection is closed")
    fun list(path: String): Array<NfsEntry> = nList(alive(), path.utf8())
    fun stat(path: String, follow: Boolean = true): LongArray? = nStat(alive(), path.utf8(), follow)
    fun open(path: String, write: Boolean, append: Boolean = false) = nOpen(alive(), path.utf8(), write, append)
    fun size(): Long = nSize(alive())
    fun read(position: Long, bytes: ByteArray, offset: Int, length: Int): Int =
        nRead(alive(), position, bytes, offset, length)
    fun write(position: Long, bytes: ByteArray, offset: Int, length: Int) =
        nWrite(alive(), position, bytes, offset, length)
    fun mutate(operation: Int, path: String, target: String = "") =
        nMutate(alive(), operation, path.utf8(), target.utf8())
    override fun close() {
        val old = handle
        handle = 0
        if (old != 0L) nDisconnect(old)
    }

    private external fun nConnect(host: ByteArray, export: ByteArray, version: Int, uid: Int, gid: Int, port: Int): Long
    private external fun nDisconnect(handle: Long)
    private external fun nList(handle: Long, path: ByteArray): Array<NfsEntry>
    private external fun nStat(handle: Long, path: ByteArray, follow: Boolean): LongArray?
    private external fun nOpen(handle: Long, path: ByteArray, write: Boolean, append: Boolean)
    private external fun nSize(handle: Long): Long
    private external fun nRead(handle: Long, position: Long, bytes: ByteArray, offset: Int, length: Int): Int
    private external fun nWrite(handle: Long, position: Long, bytes: ByteArray, offset: Int, length: Int)
    private external fun nMutate(handle: Long, operation: Int, path: ByteArray, target: ByteArray)

    companion object {
        init { System.loadLibrary("twig_nfs") }
        private fun String.utf8() = toByteArray(Charsets.UTF_8)
    }
}
