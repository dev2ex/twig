package com.twig.fs.restic

/**
 * zstd decompression abstraction. restic v2 uses zstd compression; different platforms inject
 * different implementations (Android uses the native libzstd, JVM tests use pure Java),
 * avoiding a dependency on sun.misc.Unsafe on Android.
 */
fun interface Zstd {
    /**
     * Decompress a complete zstd frame.
     * @param expectedSize >= 0 means the original length is known (pack blob, from the
     *                     index's uncompressed_length); -1 means read the original length
     *                     from the frame header (for snapshot/index files, etc.).
     */
    fun decompress(input: ByteArray, expectedSize: Int): ByteArray
}
