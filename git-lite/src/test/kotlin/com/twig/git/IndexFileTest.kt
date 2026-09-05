package com.twig.git

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robustness of index parsing. This is all raw-offset binary parsing, and `.git/index` can
 * perfectly well be read half-written (another process is writing it, or a network-repo
 * transfer got cut off); an out-of-bounds exception would bubble all the way up through
 * `GitRepo.status()` and crash the app -- so bad input must degrade to "parse as much as
 * possible" rather than throw.
 */
class IndexFileTest {

    @Test
    fun emptyAndNonIndexInputs() {
        assertTrue(IndexFile.read(null).isEmpty())
        assertTrue(IndexFile.read(ByteArray(0)).isEmpty())
        assertTrue(IndexFile.read("not an index".toByteArray()).isEmpty())
    }

    /** An unrecognized version number -> an empty list (not an exception). */
    @Test
    fun unsupportedVersion() {
        val b = header(version = 9, count = 3)
        assertTrue(IndexFile.read(b).isEmpty())
    }

    /**
     * Regression: the header claims 5 entries, but the actual bytes cut off partway through
     * the first entry. The old implementation would throw ArrayIndexOutOfBounds; it should now
     * quietly return whatever part it managed to parse.
     */
    @Test
    fun truncatedEntriesDoNotThrow() {
        val b = header(version = 2, count = 5) + ByteArray(30) // one entry is at least 62 bytes
        val result = IndexFile.read(b) // passes as long as it doesn't throw
        assertTrue(result.isEmpty())
    }

    /**
     * An absurdly inflated entry count (a corrupted header) must likewise not crash, and it must
     * **actually stop** -- claiming 2.1 billion entries with only 200 bytes of real data means
     * parsing has to give up once the data runs out instead of spinning forever.
     *
     * This only asserts "doesn't throw, returns something", not a specific count: feeding in
     * all-zero bytes first gets parsed as one valid-looking entry (empty file name, all-zero
     * sha), which is exactly the expected "parse as much as possible" behavior -- returning a
     * partial result is more useful to the user than discarding the whole index just because the
     * tail is corrupt (status would then report every file as deleted, which is far more
     * alarming).
     */
    @Test
    fun absurdCountDoesNotThrowAndTerminates() {
        val b = header(version = 2, count = Int.MAX_VALUE) + ByteArray(200)
        val result = IndexFile.read(b)
        assertTrue("parsing should stop once the data runs out", result.size < 10)
    }

    private fun header(version: Int, count: Int): ByteArray =
        "DIRC".toByteArray(Charsets.US_ASCII) + be32(version) + be32(count)

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
}
