package com.twig.fs.network

import com.twig.core.FsException
import com.twig.core.RandomSource
import okhttp3.Response
import java.io.InputStream

/**
 * Positioned reads based on HTTP Range, shared by WebDAV and S3 (both speak the
 * "GET + Range header" semantics).
 *
 * Maintains a small "stream pool" that matches the read position to reuse response
 * streams: sequential reads at a matching position cost nothing, only jumps trigger
 * a fresh Range GET.
 *
 * Why a pool instead of a single stream: callers (the player's main read plus the
 * prefetch/cache thread) advance concurrently at different positions. A single
 * stream would get interrupted back and forth, reopening the request on every read
 * (causing playback stutter); the pool gives each "read sequence" its own stream
 * so they do not interfere. Network I/O runs outside the pool's lock.
 *
 * @param openAt Opens a response from the given byte offset (the implementer is
 * responsible for attaching `Range: bytes=<pos>-` and auth headers).
 */
internal class HttpRangeSource(
    private val length: Long,
    private val openAt: (Long) -> Response,
) : RandomSource {

    private class Stream(val resp: Response, val body: InputStream, var pos: Long) {
        fun close() = runCatching { resp.close() }.let { }
    }

    private val pool = ArrayList<Stream>(MAX_STREAMS + 1)
    private var closed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val st = obtain(position) ?: return -1
        try {
            val n = st.body.read(buffer, offset, length)
            if (n > 0) { st.pos += n; recycle(st) } else st.close()
            return n
        } catch (e: Exception) {
            st.close()
            throw if (e is FsException) e else FsException("Read failed: ${e.message}", e)
        }
    }

    /** Picks a pooled stream whose position matches, or opens a fresh Range GET; returns null if the starting position is out of bounds. */
    private fun obtain(position: Long): Stream? {
        synchronized(pool) {
            if (closed) throw FsException("Source is closed")
            val i = pool.indexOfFirst { it.pos == position }
            if (i >= 0) return pool.removeAt(i)
        }
        val r = openAt(position)
        if (r.code == 416) { r.close(); return null } // start is past end of file
        if (!r.isSuccessful) { r.close(); throw FsException("GET failed: HTTP ${r.code}") }
        val ins = r.body?.byteStream() ?: run { r.close(); throw FsException("GET returned no body") }
        // When the server does not support Range, it returns 200 with the whole file; we must sequentially skip to the target position
        if (r.code == 200 && position > 0) {
            var skipped = 0L
            while (skipped < position) {
                val k = ins.skip(position - skipped)
                if (k <= 0) { if (ins.read() < 0) break else skipped++ } else skipped += k
            }
        }
        return Stream(r, ins, position)
    }

    private fun recycle(st: Stream) {
        var evict: Stream? = st
        synchronized(pool) {
            if (!closed) {
                pool.add(st)
                evict = if (pool.size > MAX_STREAMS) pool.removeAt(0) else null
            }
        }
        evict?.close()
    }

    override fun length(): Long = length

    override fun close() {
        val all: List<Stream>
        synchronized(pool) { closed = true; all = ArrayList(pool); pool.clear() }
        all.forEach { it.close() }
    }

    companion object {
        /** Upper bound on concurrent Range streams (main read + prefetch + one jump in flight). */
        private const val MAX_STREAMS = 3
    }
}
