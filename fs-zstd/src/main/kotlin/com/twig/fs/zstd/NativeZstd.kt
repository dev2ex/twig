package com.twig.fs.zstd

import com.twig.fs.restic.Zstd

/** Native libzstd decompression (reliable on Android, does not depend on sun.misc.Unsafe). */
class NativeZstd : Zstd {

    override fun decompress(input: ByteArray, expectedSize: Int): ByteArray =
        nativeDecompress(input, expectedSize) ?: throw RuntimeException("zstd decompression failed")

    private external fun nativeDecompress(input: ByteArray, expectedSize: Int): ByteArray?

    companion object {
        init { System.loadLibrary("twigzstd") }
    }
}
