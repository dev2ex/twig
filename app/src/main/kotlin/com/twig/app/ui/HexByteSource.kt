package com.twig.app.ui

import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/** Bounded byte access shared by file and database hex panes; no filesystem registration required. */
interface HexByteSource : Closeable {
    val size: Long
    val truncated: Boolean get() = false
    fun peek(off: Long, len: Int): ByteArray?
    suspend fun load(ci: Int)

    companion object { const val CHUNK = 64 * 1024 }
}

/** A range reader with at most eight chunks resident, filled only for visible hex rows. */
internal class ChunkedHexSource(override val size: Long,
    private val read: suspend (Long, Int) -> ByteArray) : HexByteSource {
    private val cache = LruCache<Int, ByteArray>(8)
    private val mutex = Mutex()
    @Volatile private var closed = false

    init { require(size >= 0) }

    override fun peek(off: Long, len: Int): ByteArray? {
        if (closed) return null
        if (off < 0 || len <= 0 || off >= size) return ByteArray(0)
        val result = ByteArray(minOf(len.toLong(), size - off).toInt())
        var copied = 0
        while (copied < result.size) {
            val position = off + copied
            val chunk = cache.get((position / HexByteSource.CHUNK).toInt()) ?: return null
            val start = (position % HexByteSource.CHUNK).toInt()
            val count = minOf(result.size - copied, chunk.size - start)
            check(count > 0)
            chunk.copyInto(result, copied, start, start + count)
            copied += count
        }
        return result
    }

    override suspend fun load(ci: Int) {
        require(ci >= 0)
        val offset = ci.toLong() * HexByteSource.CHUNK
        if (offset >= size) return
        mutex.withLock {
            check(!closed)
            if (cache.get(ci) != null) return
            val count = minOf(HexByteSource.CHUNK.toLong(), size - offset).toInt()
            val bytes = read(offset, count)
            check(bytes.size == count) { "Incomplete hex range" }
            if (!closed) cache.put(ci, bytes)
        }
    }

    override fun close() { closed = true; cache.evictAll() }
}
