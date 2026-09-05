package com.twig.fs.archive

import com.twig.core.RandomSource
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * Adapts a [RandomSource] (SMB pread / WebDAV Range, position-based reads) into a
 * read-only [SeekableByteChannel], so commons-compress's ZipFile / SevenZFile can
 * parse archives straight off a remote source — only the central directory and
 * the data segments actually accessed are fetched, no full download needed.
 *
 * Internal single-block buffer (256KB): archive parsing is many small reads
 * plus positioning; naively forwarding each one produces huge amounts of round-trips.
 */
class RandomSourceChannel(
    private val src: RandomSource,
    private val size: Long,
) : SeekableByteChannel {

    private var pos = 0L
    private var open = true
    private val block = ByteArray(256 * 1024)
    private var blockStart = -1L
    private var blockLen = 0

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw java.nio.channels.ClosedChannelException()
        if (pos >= size) return -1
        if (blockStart < 0 || pos < blockStart || pos >= blockStart + blockLen) fill(pos)
        if (blockLen <= 0 || pos >= blockStart + blockLen) return -1
        val off = (pos - blockStart).toInt()
        val n = minOf(dst.remaining(), blockLen - off)
        dst.put(block, off, n)
        pos += n
        return n
    }

    private fun fill(position: Long) {
        val want = minOf(block.size.toLong(), size - position).toInt()
        var n = 0
        while (n < want) {
            val k = src.readAt(position + n, block, n, want - n)
            if (k <= 0) break
            n += k
        }
        blockStart = position
        blockLen = n
    }

    override fun position(): Long = pos

    override fun position(newPosition: Long): SeekableByteChannel = apply {
        require(newPosition >= 0)
        pos = newPosition
    }

    override fun size(): Long = size

    override fun isOpen(): Boolean = open

    override fun close() {
        if (!open) return
        open = false
        runCatching { src.close() }
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
}
