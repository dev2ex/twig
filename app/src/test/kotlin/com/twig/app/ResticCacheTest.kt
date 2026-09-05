package com.twig.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The cache key is a file name taken from a **remote** directory listing, so it decides
 * where a file gets written. restic names those files after the content hash, which is
 * hex — anything else is refused rather than sanitised, because there is no legitimate
 * cache entry that is not a hash, and quietly rewriting a hostile name into some other
 * path is how you end up with a subtle escape.
 */
class ResticCacheTest {

    private lateinit var dir: File
    private lateinit var cache: ResticCache

    private val hash = "3938adf5c05e7f1a04d93a1f577ba733ea1e4a3b9d4a983383a554de0fdf37e2"

    @Before
    fun setup() {
        dir = File.createTempFile("twigrestic", "").let { it.delete(); it.mkdirs(); it }
        cache = ResticCache(dir)
    }

    @Test
    fun `stores and returns bytes under a hash key`() {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        cache.write(hash, bytes)
        assertArrayEquals(bytes, cache.read(hash))
        assertTrue(File(dir, hash).isFile)
    }

    @Test
    fun `a missing key is a miss, not a failure`() {
        assertNull(cache.read(hash))
    }

    @Test
    fun `keys that are not hashes are refused, and write nothing anywhere`() {
        val hostile = listOf(
            "../escaped",
            "sub/nested",
            "..",
            "3938ADF5", // hex must be lower case — the hashes are
            "not-a-hash",
            "",
            "a".repeat(65), // longer than a sha-256
        )
        for (k in hostile) {
            cache.write(k, ByteArray(8) { 1 })
            assertNull("must not serve $k", cache.read(k))
        }
        // Nothing was created — neither inside the directory nor beside it
        assertEquals(emptyList<String>(), dir.list()!!.sorted())
        assertTrue(File(dir.parentFile, "escaped").let { !it.exists() })
    }

    @Test
    fun `a half-written entry never becomes a cache hit`() {
        // write() goes through a .part file and renames, so a crash mid-write leaves
        // the temporary behind rather than a short file under the real key
        cache.write(hash, ByteArray(4096) { 7 })
        assertEquals(4096, cache.read(hash)!!.size)
        assertTrue(dir.list()!!.none { it.endsWith(".part") })
    }
}
