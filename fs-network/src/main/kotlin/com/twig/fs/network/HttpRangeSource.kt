package com.twig.fs.network

import com.twig.core.FsException
import com.twig.core.RandomSource
import okhttp3.Response
import java.io.InputStream

/**
 * 基于 HTTP Range 的定位读,WebDAV 与 S3 共用(两边都是"GET + Range 头"这一套语义)。
 *
 * 维护一个小"流池",按读取位置匹配复用响应流:位置吻合的顺序读零成本,
 * 跳变才带 Range 重新 GET。
 *
 * 为什么是池而不是单条流:调用方(播放器主读 + 预读缓存线程)会并发地在不同位置
 * 推进,单条流会被来回打断、每次读都重开请求(播放卡顿);池让每个"读取序列"
 * 各占一条流互不干扰。网络 IO 在池锁外进行。
 *
 * @param openAt 从给定字节位置开一条响应(实现方负责带上 `Range: bytes=<pos>-` 与鉴权)。
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

    /** 取位置吻合的池中流,没有则新开 Range GET;起点越界返回 null。 */
    private fun obtain(position: Long): Stream? {
        synchronized(pool) {
            if (closed) throw FsException("Source is closed")
            val i = pool.indexOfFirst { it.pos == position }
            if (i >= 0) return pool.removeAt(i)
        }
        val r = openAt(position)
        if (r.code == 416) { r.close(); return null } // 起点超出文件末尾
        if (!r.isSuccessful) { r.close(); throw FsException("GET failed: HTTP ${r.code}") }
        val ins = r.body?.byteStream() ?: run { r.close(); throw FsException("GET returned no body") }
        // 服务器不支持 Range 时返回 200 全量,只能顺序丢弃到目标位置
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
        /** 并存的 Range 流上限(主读 + 预读 + 一次跳变余量)。 */
        private const val MAX_STREAMS = 3
    }
}
