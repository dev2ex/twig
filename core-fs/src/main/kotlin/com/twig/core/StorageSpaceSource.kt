package com.twig.core

/** Capacity or quota applying to a directory; not the size of that directory's contents. */
data class StorageSpace(val available: Long, val used: Long) {
    init {
        require(available >= 0 && used >= 0 && available <= Long.MAX_VALUE - used)
    }
    val total: Long get() = available + used
}

/** Optional storage/quota information. Queries run on a worker thread, like filesystem IO. */
interface StorageSpaceSource {
    fun storageSpace(dir: XFile): StorageSpace?

    /** Listing-populated snapshot for rendering; must never perform IO. */
    fun knownStorageSpace(dir: XFile): StorageSpace? = null
}
