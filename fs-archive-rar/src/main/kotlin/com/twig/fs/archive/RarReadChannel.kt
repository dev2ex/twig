package com.twig.fs.archive

import com.github.junrar.io.SeekableReadOnlyByteChannel
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Bridges junrar's seek/read API to the same buffered remote channel used by ZIP and 7z. */
internal class RarReadChannel(private val channel: SeekableByteChannel) : SeekableReadOnlyByteChannel {
    private val singleByte = ByteArray(1)
    @Volatile private var failure: Exception? = null

    // junrar can swallow IO failures while parsing headers or extracting through its pipe.
    // Preserve them so callers cannot mistake a disconnected source for an empty archive/file.
    fun rethrowFailure() {
        failure?.let { throw it }
    }

    override fun getPosition(): Long = channel.position()

    override fun setPosition(position: Long) {
        channel.position(position)
    }

    override fun read(): Int =
        if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xff

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = try {
        if (count == 0) 0 else channel.read(ByteBuffer.wrap(buffer, offset, count))
    } catch (e: Exception) {
        failure = if (e is IOException) e else IOException("Cannot read remote RAR", e)
        throw failure!!
    }

    override fun readFully(buffer: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = read(buffer, total, count - total)
            if (n < 0) throw EOFException("Unexpected end of RAR archive")
            total += n
        }
        return total
    }

    override fun close() = channel.close()
}
