package com.twig.fs.network

import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Integration tests against a real server (MinIO / AWS S3 / R2, …). **Skipped entirely
 * when the environment variables aren't set**, so `./gradlew test` stays green on any
 * machine.
 *
 * ```
 * TWIG_S3_ENDPOINT=http://127.0.0.1:9000 TWIG_S3_KEY=<key> \
 * TWIG_S3_SECRET=<secret> TWIG_S3_BUCKET=<bucket> \
 *   ./gradlew :fs-network:test --tests "*S3LiveTest*"
 * ```
 *
 * Why this is worth having on its own: [S3FileSystemTest] asserts against **our own**
 * understanding of the wire format using mockwebserver — it cannot prove a signature is
 * accepted by a real server. SigV4 missing one signed header, or a canonical string off
 * by one byte, is just as green against the mock but turns into a wall of 403
 * SignatureDoesNotMatch on the real thing.
 *
 * Every write operation stays confined under [PREFIX] and is deleted once the run
 * finishes, leaving the rest of the bucket untouched.
 */
class S3LiveTest {

    private val endpoint = System.getenv("TWIG_S3_ENDPOINT")
    private val bucket = System.getenv("TWIG_S3_BUCKET").orEmpty()

    private lateinit var fs: S3FileSystem

    /** The sandbox directory for this run; the whole subtree is deleted afterward. */
    private val dir get() = XFile(S3FileSystem.SCHEME, "/$PREFIX", isDir = true)

    @Before
    fun setup() {
        assumeFalse("TWIG_S3_ENDPOINT not set — skipping live S3 test", endpoint.isNullOrEmpty())
        fs = S3FileSystem(
            S3Config(
                endpoint = endpoint!!,
                accessKey = System.getenv("TWIG_S3_KEY").orEmpty(),
                secretKey = System.getenv("TWIG_S3_SECRET").orEmpty(),
                region = System.getenv("TWIG_S3_REGION") ?: "us-east-1",
                bucket = bucket,
                pathStyle = System.getenv("TWIG_S3_VHOST").isNullOrEmpty(),
            ),
        )
        runCatching { fs.delete(dir) }
        fs.mkdir(fs.root(), PREFIX)
    }

    @After
    fun tearDown() {
        if (this::fs.isInitialized) runCatching { fs.delete(dir) }
    }

    // ---- read ----

    /** The most basic check: the signature is accepted by a real server. Failing here means SigV4 is broken. */
    @Test
    fun canListBucket() {
        val names = fs.list(fs.root()).map { it.name }
        assertTrue("bucket looks empty — seed files missing?", names.isNotEmpty())
        assertTrue(names.contains(PREFIX))
    }

    /** When the bucket is left empty, the root lists all buckets (the credentials need ListAllMyBuckets permission). */
    @Test
    fun canListAllBuckets() {
        val all = S3FileSystem(
            S3Config(
                endpoint = endpoint!!,
                accessKey = System.getenv("TWIG_S3_KEY").orEmpty(),
                secretKey = System.getenv("TWIG_S3_SECRET").orEmpty(),
                region = System.getenv("TWIG_S3_REGION") ?: "us-east-1",
                bucket = "",
                pathStyle = System.getenv("TWIG_S3_VHOST").isNullOrEmpty(),
            ),
        )
        val buckets = all.list(all.root()).map { it.name }
        assertTrue("expected $bucket among $buckets", buckets.contains(bucket))
        assertTrue(all.list(all.root()).all { it.isDir })
    }

    /**
     * Objects whose names contain spaces / plus signs / percent signs / Chinese characters
     * must all be **listable and readable**. Readability is the real test: with a name
     * decoding bug the listing can look fine and still 404 the moment you open the file.
     */
    @Test
    fun handlesAwkwardObjectNames() {
        val names = listOf("plain.txt", "with space.txt", "a+b.txt", "100% done #1.txt", "报告.txt")
        for ((i, n) in names.withIndex()) {
            fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/$n", isDir = false)).use {
                it.write("body-$i".toByteArray())
            }
        }
        assertEquals(names.sortedBy { it.lowercase() }, fs.list(dir).map { it.name }.sortedBy { it.lowercase() })

        for ((i, n) in names.withIndex()) {
            val got = fs.openInput(XFile(S3FileSystem.SCHEME, "/$PREFIX/$n", isDir = false))
                .use { String(it.readBytes()) }
            assertEquals("reading back '$n'", "body-$i", got)
        }
    }

    /** Positional reads via HTTP Range: player seeking and network video thumbnails both depend on this. */
    @Test
    fun randomAccessReadsExactBytes() {
        val data = ByteArray(300_000) { (it * 7 % 251).toByte() }
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/range.bin", isDir = false)
        fs.openOutput(f).use { it.write(data) }

        fs.openRandom(f.copy(size = data.size.toLong())).use { src ->
            val buf = ByteArray(1000)
            val n = src.readAt(123_456, buf, 0, 1000)
            assertTrue(n > 0)
            assertArrayEquals(data.copyOfRange(123_456, 123_456 + n), buf.copyOf(n))

            // Jumping backward: no pooled stream sits at that position, so a new Range request must be opened
            val back = ByteArray(16)
            val m = src.readAt(10, back, 0, 16)
            assertArrayEquals(data.copyOfRange(10, 10 + m), back.copyOf(m))
        }
    }

    // ---- write ----

    @Test
    fun smallUploadRoundTrips() {
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/small.txt", isDir = false)
        fs.openOutput(f).use { it.write("hello s3".toByteArray()) }
        assertTrue(fs.exists(f))
        assertEquals("hello s3", fs.openInput(f).use { String(it.readBytes()) })
        assertEquals(8L, fs.list(dir).first { it.name == "small.txt" }.size)
    }

    /** Beyond one part (8 MiB) it switches to multipart upload — only a real server actually validates ETags and part ordering. */
    @Test
    fun multipartUploadRoundTrips() {
        val data = ByteArray(9 * 1024 * 1024) { (it * 31 % 251).toByte() }
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/big.bin", isDir = false)
        fs.openOutput(f).use { it.write(data) }

        assertEquals(data.size.toLong(), fs.list(dir).first { it.name == "big.bin" }.size)
        val got = fs.openInput(f).use { it.readBytes() }
        // Compare content by digest: assertArrayEquals directly on a 9 MB array would flood the output on failure
        assertEquals(sha256(data), sha256(got))
    }

    @Test
    fun emptyFileRoundTrips() {
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/empty.txt", isDir = false)
        fs.openOutput(f).use { }
        assertTrue(fs.exists(f))
        assertEquals(0, fs.openInput(f).use { it.readBytes() }.size)
    }

    // ---- directories ----

    /** S3 has no directories; an empty directory is held up by a placeholder object — it must still be visible after creation. */
    @Test
    fun emptyDirectorySurvivesListing() {
        fs.mkdir(dir, "empty-dir")
        val d = fs.list(dir).firstOrNull { it.name == "empty-dir" }
        assertTrue("empty directory disappeared", d != null && d.isDir)
        // The placeholder itself must not show up as a 0-byte file
        assertTrue(fs.list(d!!).isEmpty())
    }

    @Test
    fun nestedDirectoriesFoldIntoTree() {
        fs.mkdir(dir, "a")
        val a = XFile(S3FileSystem.SCHEME, "/$PREFIX/a", isDir = true)
        fs.mkdir(a, "b")
        fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/a/b/leaf.txt", isDir = false))
            .use { it.write("leaf".toByteArray()) }

        assertEquals(listOf("b"), fs.list(a).map { it.name })
        val b = fs.list(a).first()
        assertTrue(b.isDir)
        assertEquals(listOf("leaf.txt"), fs.list(b).map { it.name })
    }

    @Test
    fun deleteRemovesWholeSubtree() {
        fs.mkdir(dir, "doomed")
        val d = XFile(S3FileSystem.SCHEME, "/$PREFIX/doomed", isDir = true)
        fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/doomed/x.txt", isDir = false))
            .use { it.write("x".toByteArray()) }
        fs.delete(d)
        assertFalse(fs.list(dir).any { it.name == "doomed" })
    }

    // ---- rename / move ----

    @Test
    fun renameMovesObjectServerSide() {
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/before.txt", isDir = false)
        fs.openOutput(f).use { it.write("keep me".toByteArray()) }

        val after = fs.rename(f, "after.txt")
        assertEquals("/$PREFIX/after.txt", after.path)
        assertEquals("keep me", fs.openInput(after).use { String(it.readBytes()) })
        assertFalse(fs.exists(f))
    }

    @Test
    fun renameDirectoryTakesItsContents() {
        fs.mkdir(dir, "old")
        fs.openOutput(XFile(S3FileSystem.SCHEME, "/$PREFIX/old/inside.txt", isDir = false))
            .use { it.write("inside".toByteArray()) }

        fs.rename(XFile(S3FileSystem.SCHEME, "/$PREFIX/old", isDir = true), "new")
        val names = fs.list(dir).map { it.name }
        assertTrue(names.contains("new"))
        assertFalse(names.contains("old"))
        assertEquals(
            "inside",
            fs.openInput(XFile(S3FileSystem.SCHEME, "/$PREFIX/new/inside.txt", isDir = false))
                .use { String(it.readBytes()) },
        )
    }

    @Test
    fun moveWithinIsServerSide() {
        fs.mkdir(dir, "dest")
        val f = XFile(S3FileSystem.SCHEME, "/$PREFIX/movable.txt", isDir = false)
        fs.openOutput(f).use { it.write("moved".toByteArray()) }

        val ok = fs.moveWithin(f, XFile(S3FileSystem.SCHEME, "/$PREFIX/dest", isDir = true), "movable.txt")
        assertTrue(ok)
        assertFalse(fs.exists(f))
        assertEquals(
            "moved",
            fs.openInput(XFile(S3FileSystem.SCHEME, "/$PREFIX/dest/movable.txt", isDir = false))
                .use { String(it.readBytes()) },
        )
    }

    /** Contract: must throw when a target of the same name already exists, never silently swallow the file. */
    @Test
    fun renameOntoExistingFails() {
        val a = XFile(S3FileSystem.SCHEME, "/$PREFIX/a.txt", isDir = false)
        val b = XFile(S3FileSystem.SCHEME, "/$PREFIX/b.txt", isDir = false)
        fs.openOutput(a).use { it.write("A".toByteArray()) }
        fs.openOutput(b).use { it.write("B".toByteArray()) }

        assertTrue(runCatching { fs.rename(a, "b.txt") }.isFailure)
        assertEquals("B", fs.openInput(b).use { String(it.readBytes()) }) // wasn't overwritten
        assertTrue(fs.exists(a))
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {
        "%02x".format(it)
    }

    companion object {
        private const val PREFIX = "_twig_livetest"
    }
}
