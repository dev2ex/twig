package com.twig.core

/** Optional, task-owned reader that can reuse archive indexes and decoders across files. */
interface ReadBatchSource {
    fun openReadBatch(): ReadBatch
}

/** Used by one transfer at a time; close after all entry streams have been closed. */
interface ReadBatch : FileSystem, java.io.Closeable {
    /** Reorder distinct siblings when physical archive order avoids decoding a solid block again. */
    fun order(files: List<XFile>): List<XFile> = files

    // Resume reads use this batch's openInput instead of reopening the underlying archive.
    override fun openRandom(file: XFile): RandomSource = super.openRandom(file)
}
