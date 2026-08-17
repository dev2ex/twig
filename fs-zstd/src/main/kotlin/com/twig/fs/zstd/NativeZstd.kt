package com.twig.fs.zstd

import com.twig.fs.restic.Zstd

/** 原生 libzstd 解压(Android 可靠,不依赖 sun.misc.Unsafe)。 */
class NativeZstd : Zstd {

    override fun decompress(input: ByteArray, expectedSize: Int): ByteArray =
        nativeDecompress(input, expectedSize) ?: throw RuntimeException("zstd decompression failed")

    private external fun nativeDecompress(input: ByteArray, expectedSize: Int): ByteArray?

    companion object {
        init { System.loadLibrary("twigzstd") }
    }
}
