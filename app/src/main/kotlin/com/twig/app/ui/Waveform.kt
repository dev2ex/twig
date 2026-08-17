package com.twig.app.ui

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 波形计算:全量解码算 RMS,归一化成 [BUCKETS] 个 0..255 的桶。磁盘缓存
 * `cacheDir/waves/<md5(名:大小:mtime)>`(与缩略图同 key 规则);单线程后台。
 * 任何失败静默返回(UI 退回平条),不影响播放。
 *
 * ★ 前台(播放页当前曲)与预取(下一首)各占一个槽、**互不取消**:旧版共用一个全局 token,
 * 谁后发谁把前面正在算的顶掉——而 `MusicEngine` 开播 4s 就给下一首发预取,网络曲解码要
 * 几十秒,于是当前曲的波形每次都在半路被预取掐死(不回调也不缓存),表现为"正在听的这首
 * 永远没波形,退出再进来还是没有"。排队顺序由单线程 FIFO 保证:先来的当前曲先算完。
 */
object Waveform {

    const val BUCKETS = 240

    // 完整度门槛:解码被网络中断/提前 EOS 时只填得出前面一截桶,这种半截结果可以显示但
    // 绝不能写缓存——key 只含「名:大小:mtime」,缓存下来就永远是半截,不会自然失效。
    private const val COMPLETE_RATIO = 0.9

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-waveform").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var uiKey: String? = null   // 前台想要的那首
    @Volatile private var uiSeq = 0               // 前台请求序号(供预取让位)
    @Volatile private var preKey: String? = null  // 预取想要的那首

    /**
     * 请求波形;命中缓存立即回调,否则后台解码。同一槽内多次调用只有最后一次的结果会回。
     * [prefetch] = 下一首的预取:不会取消前台正在算的那首,自己则在前台换曲时让位。
     */
    fun request(ctx: Context, file: XFile, prefetch: Boolean = false, onReady: (ByteArray) -> Unit) {
        AudioCache.init(ctx) // 网络波形与播放共用 AudioCache,先保证目录就绪
        val key = keyOf(file)
        if (prefetch) preKey = key else { uiKey = key; uiSeq++ }
        executor.execute {
            // 预取任务:自己还是当前预取目标就继续;但前台在我开跑后又换了别的曲(uiSeq 变了)
            // 就让出这条单线程——除非前台要的正是我这首(切到下一首的常见情形),那接着算完。
            val seq0 = uiSeq
            val alive: () -> Boolean =
                if (prefetch) ({ key == preKey && (key == uiKey || uiSeq == seq0) })
                else ({ key == uiKey })
            if (!alive()) return@execute
            val cache = File(dir(ctx), key)
            val cached = runCatching { if (cache.isFile) cache.readBytes() else null }
                .getOrNull()?.takeIf { it.size == BUCKETS }
            val data = cached ?: runCatching { compute(file, alive) }.getOrNull()?.let { w ->
                if (w.complete) runCatching { cache.writeBytes(w.data) }
                else Log.w("twig", "waveform: incomplete, not cached: ${file.name}")
                w.data
            }
            if (data != null && alive()) main.post { if (alive()) onReady(data) }
        }
    }

    fun cancel() { uiKey = null; preKey = null }

    // 目录名带版本号:normalize() 对比度曲线改过,旧缓存是低对比度数据,换目录名让它自然失效
    private fun dir(ctx: Context) = File(ctx.cacheDir, "waves2").apply { mkdirs() }

    private fun keyOf(f: XFile): String =
        MessageDigest.getInstance("MD5").digest("${f.name}:${f.size}:${f.lastModified}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- 解码 ----

    /** [complete] = 桶基本填满(解码走到了声明时长的末尾);只有它为 true 才写缓存。 */
    private class Wave(val data: ByteArray, val complete: Boolean)

    private fun compute(file: XFile, alive: () -> Boolean): Wave? {
        val extractor = MediaExtractor()
        var netSource: RandomSource? = null
        try {
            if (file.scheme == "file") {
                extractor.setDataSource(file.path)
            } else {
                // 网络来源:走 AudioCache 的每曲共享磁盘缓存——波形解码顺序读整首的同时,把字节
                // 落进和播放器共用的那份缓存里,播放/seek 不必再下一遍(close 为空操作,连接归
                // AudioCache 统管)。总长未知时 AudioCache 内部退化为 BufferedRandomSource 直读。
                val src = AudioCache.source(file)
                netSource = src
                extractor.setDataSource(NetSource(src))
            }
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sumsq = DoubleArray(BUCKETS)
            val counts = LongArray(BUCKETS)
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            while (!outEos) {
                if (!alive()) { codec.stop(); codec.release(); return null }
                if (!inEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * BUCKETS).toInt()
                            .coerceIn(0, BUCKETS - 1)
                        out.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset)
                        val shorts = info.size / 2
                        var acc = 0.0
                        var i = 0
                        while (i < shorts) {
                            val s = out.short.toInt()
                            acc += (s.toDouble() * s)
                            i++
                        }
                        sumsq[bucket] += acc
                        counts[bucket] += shorts.toLong()
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                }
            }
            codec.stop(); codec.release()
            // 一个样本都没解出来 = 失败,不是"平波形":旧版这里让 normalize 吐一条平的 40 数组
            // 当成功结果写进缓存,失败就此被永久固化下来。
            val filled = counts.count { it > 0 }
            if (filled == 0) { Log.w("twig", "waveform: no samples decoded: ${file.name}"); return null }
            return Wave(normalize(sumsq, counts), filled >= BUCKETS * COMPLETE_RATIO)
        } catch (e: Exception) {
            Log.w("twig", "waveform: failed ${file.name}: $e")
            return null
        } finally {
            runCatching { extractor.release() }
            netSource?.let { s -> runCatching { s.close() } }
        }
    }

    private fun normalize(sumsq: DoubleArray, counts: LongArray): ByteArray {
        val rms = DoubleArray(BUCKETS) { if (counts[it] > 0) sqrt(sumsq[it] / counts[it]) else 0.0 }
        // 全 0 只可能是整首数字静音(有样本但幅度为 0),那是合法的完整结果,给条平的
        val max = rms.max().takeIf { it > 0 } ?: return ByteArray(BUCKETS) { 40 }
        return ByteArray(BUCKETS) {
            // 拉大对比度:线性比例(不再开根压缩动态范围)叠一个 >1 的指数曲线——
            // 安静段被进一步压矮、响的段仍接近满高,层次感更明显。映射到 8..255。
            val v = (rms[it] / max).pow(1.6)
            (8 + v * 247).toInt().coerceIn(0, 255).toByte()
        }
    }

    /** RandomSource → android.media.MediaDataSource(供网络来源全量解码)。 */
    private class NetSource(private val src: RandomSource) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            val n = src.readAt(position, buffer, offset, size)
            return if (n <= 0) -1 else n
        }
        override fun getSize(): Long = src.length()
        override fun close() { runCatching { src.close() } }
    }
}
