package com.twig.fs.nfs

import com.twig.core.FsException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Run only against a disposable, writable export. Every test cleans its own directory. */
class NfsLiveTest {
    @Test fun nfs3RoundTrip() = roundTrip(3)
    @Test fun nfs4RoundTrip() = roundTrip(4)

    private fun roundTrip(version: Int) {
        val host = System.getenv("TWIG_NFS_TEST_HOST").orEmpty()
        assumeTrue(host.isNotEmpty())
        val fs = NfsFileSystem(host, System.getenv("TWIG_NFS_TEST_EXPORT") ?: "/export", version)
        fs.connect()
        val dir = fs.mkdir(fs.root(), "twig-${version}-${System.nanoTime()}")
        try {
            val payload = ByteArray(800_123) { (it * 31).toByte() }
            val file = fs.createFile(dir, "中文 😀 # % tab\t.bin")
            fs.openOutput(file, false).use { it.write(payload) }
            val listed = fs.list(dir).single()
            assertEquals(file.name, listed.name)
            assertFalse(listed.isDir)
            assertEquals(payload.size.toLong(), listed.size)
            assertArrayEquals(payload, fs.openInput(listed).use { it.readBytes() })
            fs.openRandom(listed).use { source ->
                assertEquals(payload.size.toLong(), source.length())
                val bytes = ByteArray(120)
                assertEquals(100, source.readAt(700_000, bytes, 10, 100))
                assertArrayEquals(payload.copyOfRange(700_000, 700_100), bytes.copyOfRange(10, 110))
                assertEquals(0, source.readAt(0, bytes, 0, 0))
                assertEquals(-1, source.readAt(payload.size.toLong(), bytes, 0, 1))
            }
            try { fs.openOutput(listed, append = true); fail("Append must not truncate") }
            catch (_: FsException) { }
            val other = fs.createFile(dir, "existing.bin")
            fs.openOutput(other, false).use { it.write(byteArrayOf(7)) }
            try { fs.rename(listed, other.name); fail("Rename must not overwrite") }
            catch (_: FsException) { }
            val moved = fs.rename(listed, "renamed.bin")
            assertFalse(fs.exists(listed))
            assertTrue(fs.exists(moved))
            assertNull(fs.stat(listed.path))
            // Independent stream contexts: copy within the same backend and list while open.
            val copy = fs.createFile(dir, "copy.bin")
            fs.openInput(moved).use { input ->
                fs.openOutput(copy, false).use { output ->
                    assertEquals(3, fs.list(dir).size)
                    input.copyTo(output)
                }
            }
            assertArrayEquals(payload, fs.openInput(copy).use { it.readBytes() })
            // Overwrite must truncate an existing larger file.
            fs.openOutput(copy, false).use { it.write(byteArrayOf(1, 2, 3)) }
            assertArrayEquals(byteArrayOf(1, 2, 3), fs.openInput(copy).use { it.readBytes() })
            val pool = Executors.newFixedThreadPool(3)
            try {
                val jobs = (0..2).map { pool.submit<Boolean> {
                    fs.openInput(moved).use { it.readBytes().contentEquals(payload) }
                } }
                jobs.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
            } finally { pool.shutdownNow() }
            try { fs.openInput(fs.createFile(dir, "missing")); fail("Missing file must throw") }
            catch (_: FsException) { }
            fs.mkdir(dir, "nested")
        } finally {
            fs.delete(dir)
            assertFalse(fs.exists(dir))
            fs.disconnect()
        }
    }
}
