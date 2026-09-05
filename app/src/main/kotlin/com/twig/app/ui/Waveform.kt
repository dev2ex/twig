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
 * Waveform calculation: full decode to compute RMS, normalised into [BUCKETS]
 * buckets of 0..255. Disk cache `cacheDir/waves/<md5(name:size:mtime)>` (same
 * key rule as thumbnails); single-threaded in the background. Any failure
 * returns silently (UI falls back to a flat bar), playback is unaffected.
 *
 * ★ Foreground (current track on the player) and prefetch (next track) each
 * occupy their own slot and **do not cancel each other**: the old version
 * shared a global token, and whoever issued later would displace the one in
 * progress — `MusicEngine` issues a prefetch 4s into playback, network tracks
 * take dozens of seconds to decode, so the current track's waveform was always
 * killed mid-flight by the prefetch (no callback, no cache), appearing as
 * "the track I'm listening to never has a waveform, and still doesn't after
 * I exit and come back". Single-threaded FIFO guarantees order: an earlier
 * foreground request finishes first.
 */
object Waveform {

    const val BUCKETS = 240

    // Completeness threshold: when decoding is interrupted by network issues / early
    // EOS, only the first few buckets can be filled; such partial results can be
    // displayed but must never be cached — the key only contains "name:size:mtime",
    // so once cached it stays partial forever and won't expire naturally.
    private const val COMPLETE_RATIO = 0.9

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-waveform").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var uiKey: String? = null   // the track the foreground wants
    @Volatile private var uiSeq = 0               // foreground request sequence number (for prefetch yielding)
    @Volatile private var preKey: String? = null  // the track the prefetch wants

    /**
     * Request the waveform; cache hits fire the callback immediately, otherwise
     * decode in the background. Multiple calls within the same slot only deliver
     * the last one's result. [prefetch] = next-track prefetch: doesn't cancel
     * the track the foreground is currently computing, but yields itself when the
     * foreground switches to a different track.
     */
    fun request(ctx: Context, file: XFile, prefetch: Boolean = false, onReady: (ByteArray) -> Unit) {
        AudioCache.init(ctx) // network waveforms and playback share AudioCache, ensure the directory is ready first
        val key = keyOf(file)
        if (prefetch) preKey = key else { uiKey = key; uiSeq++ }
        executor.execute {
            // Prefetch task: continue if I'm still the current prefetch target; but
            // if the foreground switched to another track after I started (uiSeq
            // changed), yield this single thread — unless the foreground now wants
            // exactly this track (the common case when skipping to the next), then
            // finish the decode.
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

    // Directory name carries a version: normalize()'s contrast curve changed, old
    // cache has low-contrast data, renaming the directory lets it expire naturally
    private fun dir(ctx: Context) = File(ctx.cacheDir, "waves2").apply { mkdirs() }

    private fun keyOf(f: XFile): String =
        MessageDigest.getInstance("MD5").digest("${f.name}:${f.size}:${f.lastModified}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- Decoding ----

    /** [complete] = buckets essentially filled (decoding reached the declared duration's end); only when true is the result written to cache. */
    private class Wave(val data: ByteArray, val complete: Boolean)

    private fun compute(file: XFile, alive: () -> Boolean): Wave? {
        val extractor = MediaExtractor()
        var netSource: RandomSource? = null
        try {
            if (file.scheme == "file") {
                extractor.setDataSource(file.path)
            } else {
                // Network source: uses AudioCache's per-track shared disk cache — while the
                // waveform decode reads the entire track sequentially, the bytes are dropped
                // into the same cache shared with the player, so playback/seek don't have to
                // re-download (close is a no-op; connections are managed by AudioCache). When
                // the total length is unknown, AudioCache internally falls back to a direct
                // BufferedRandomSource read.
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
            // No samples decoded at all = failure, not a "flat waveform": the old version
            // let normalize emit a flat array of 40s here and treated it as a successful
            // result, writing it to cache — so failures were permanently baked in.
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
        // All-zeros can only be a digital-silence track (samples exist but amplitude is 0);
        // that's a legitimate complete result, give it a flat bar
        val max = rms.max().takeIf { it > 0 } ?: return ByteArray(BUCKETS) { 40 }
        return ByteArray(BUCKETS) {
            // Boost contrast: a linear ratio (no longer square-root compressing dynamic range)
            // combined with a >1 exponential curve — quiet sections are squashed further,
            // loud sections stay near full height, so the layering reads more clearly.
            // Mapped to 8..255.
            val v = (rms[it] / max).pow(1.6)
            (8 + v * 247).toInt().coerceIn(0, 255).toByte()
        }
    }

    /** RandomSource → android.media.MediaDataSource (for full decoding of network sources). */
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
