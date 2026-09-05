package com.twig.fs.restic

/**
 * A local cache for immutable repository objects.
 *
 * Everything under a restic repository is content-addressed — the file name *is* the
 * hash of the contents — so a cached object can never go stale and needs no invalidation
 * rule at all. All the implementation owes is a size cap.
 *
 * ★ **What gets cached is the raw ciphertext, never the decrypted bytes.** The
 * repository is encrypted; writing a decrypted index to local disk would spread the
 * backup's file names and directory structure in the clear across the device, which is
 * the exact thing that encryption is there to prevent. What the cache saves is the
 * network transfer — tens of megabytes of `index/` — while decryption and decompression
 * are local CPU and were never the bottleneck.
 */
interface ObjectCache {

    /** Cached bytes for [key], or null if absent (and on any read error — a cache miss). */
    fun read(key: String): ByteArray?

    /** Store [bytes] under [key]. Failures must be swallowed: a cache is never load-bearing. */
    fun write(key: String, bytes: ByteArray)

    companion object {
        /** No caching — the default, and what the unit tests run against. */
        val NONE: ObjectCache = object : ObjectCache {
            override fun read(key: String): ByteArray? = null
            override fun write(key: String, bytes: ByteArray) = Unit
        }
    }
}
