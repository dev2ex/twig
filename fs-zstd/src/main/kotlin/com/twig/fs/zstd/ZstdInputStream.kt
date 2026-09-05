package com.twig.fs.zstd

import java.io.IOException
import java.io.InputStream

/**
 * Streaming zstd decompression. The whole-block interface in [NativeZstd] requires the
 * raw data to be loaded into memory all at once, which doesn't work for files inside
 * archives — a .tar.zst can decompress to several GB, while the archive layer streams
 * through an InputStream while copying.
 *
 * Frame boundaries are invisible to the caller: concatenated multi-frame files
 * (`cat a.zst b.zst`) are read frame by frame, matching the behavior of gzip/xz/bzip2.
 *
 * **Not thread-safe**: one instance corresponds to one read path (the DStream context
 * is stateful).
 *
 * ★ The reason the native methods live in the companion with `@JvmStatic` is so the
 * bytecode-level `static native` lands on **ZstdInputStream itself**, giving the symbol
 * `Java_com_twig_fs_zstd_ZstdInputStream_xxx`. Without `@JvmStatic` they move to
 * `ZstdInputStream$Companion` and the symbol name changes accordingly, surfacing as an
 * UnsatisfiedLinkError. (No extra R8 rule is needed: the `-keepclasseswithmembernames`
 * entry already covers every class with native methods.)
 *
 * JVM unit tests can't cover this path (no .so). To verify without installing on a
 * device: use the host's clang to compile the same C source under `src/main/cpp` into a
 * Linux libtwigzstd.so, point the classpath at `build/tmp/kotlin-classes/release`, load
 * it via `-Djava.library.path=`, and compare md5 against real .zst files — the loop
 * logic, the consumed/produced packing, and small-buffer edge cases all run in seconds.
 */
class ZstdInputStream(private val src: InputStream) : InputStream() {

    private var ctx: Long = nativeCreate()
    private val inBuf = ByteArray(IN_SIZE)
    private var inPos = 0
    private var inLen = 0
    private var srcEof = false
    private var finished = false
    private val single = ByteArray(1)

    init {
        if (ctx == 0L) throw IOException("cannot create zstd stream")
    }

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1
        val zds = ctx
        if (zds == 0L) throw IOException("zstd stream is closed")

        while (true) {
            if (inPos >= inLen && !srcEof) {
                val n = src.read(inBuf, 0, inBuf.size)
                if (n < 0) srcEof = true else if (n > 0) { inPos = 0; inLen = n }
            }
            // srcLen being 0 still requires a call: after input is exhausted, the decoder
            // may still hold unflushed output internally
            val r = nativeDecompress(zds, inBuf, inPos, inLen - inPos, b, off, len)
            if (r < 0) throw IOException("zstd decompression failed")
            inPos += (r ushr 32).toInt()
            val produced = (r and 0xFFFFFFFFL).toInt()
            if (produced > 0) return produced
            // produced nothing and consumed nothing, and there is no more input to feed — done
            if (inPos >= inLen && srcEof) {
                finished = true
                return -1
            }
        }
    }

    override fun close() {
        val zds = ctx
        ctx = 0L
        if (zds != 0L) nativeFree(zds)
        src.close()
    }

    /**
     * ★ The native context is not on the GC's books — missing close is a pure leak.
     * Callers all use `use {}`; this is just a safety net — finalize itself is a
     * deprecated mechanism, but API 24 has no Cleaner available.
     */
    @Suppress("removal", "DEPRECATION")
    protected fun finalize() {
        val zds = ctx
        ctx = 0L
        if (zds != 0L) nativeFree(zds)
    }

    companion object {
        init { System.loadLibrary("twigzstd") }

        /** On the order of ZSTD_DStreamInSize() (one max block plus frame header); feeding this size minimizes round-trips. */
        private const val IN_SIZE = 128 * 1024

        @JvmStatic private external fun nativeCreate(): Long

        @JvmStatic private external fun nativeFree(ctx: Long)

        /** Returns (consumed shl 32) or produced; a negative value means a decode error. */
        @JvmStatic private external fun nativeDecompress(
            ctx: Long,
            src: ByteArray,
            srcOff: Int,
            srcLen: Int,
            dst: ByteArray,
            dstOff: Int,
            dstLen: Int,
        ): Long
    }
}
