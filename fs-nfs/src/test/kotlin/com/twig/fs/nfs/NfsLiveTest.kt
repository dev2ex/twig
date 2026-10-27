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
        val port = System.getenv("TWIG_NFS_TEST_PORT")?.toInt() ?: 2049
        val fs = NfsFileSystem(host, System.getenv("TWIG_NFS_TEST_EXPORT") ?: "/export", version, port = port)
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
            // Append must preserve the prefix, including when the caller's size is stale.
            val appended = byteArrayOf(12, 34, 56)
            fs.appendPartial(listed.copy(size = 1)).use { it.write(appended) }
            assertArrayEquals(payload + appended, fs.openInput(listed).use { it.readBytes() })
            fs.openOutput(listed, false).use { it.write(payload) }
            val created = fs.createFile(dir, "append-new.bin")
            fs.openOutput(created, true).use { it.write(appended) }
            assertArrayEquals(appended, fs.openInput(created).use { it.readBytes() })
            fs.delete(created)
            val other = fs.createFile(dir, "existing.bin")
            fs.openOutput(other, false).use { it.write(byteArrayOf(7)) }
            try { fs.rename(listed, other.name); fail("Rename must not overwrite") }
            catch (_: FsException) { }
            val moved = fs.rename(listed, "renamed.bin")
            assertFalse(fs.exists(listed))
            assertTrue(fs.exists(moved))
            assertNull(fs.stat(listed.path))
            // A live handle still refers to its original file if its path is reused.
            NativeNfsClient(host, System.getenv("TWIG_NFS_TEST_EXPORT") ?: "/export", version, 65534, 65534, port).use { c ->
                c.open(moved.path, write = false)
                val held = fs.rename(moved, "held.bin")
                try {
                    fs.openOutput(moved, false).use { it.write(byteArrayOf(9)) }
                    assertEquals(1L, fs.stat(moved.path)!!.size)
                    assertEquals(payload.size.toLong(), c.size())
                    val bytes = ByteArray(16)
                    assertEquals(bytes.size, c.read(700_000, bytes, 0, bytes.size))
                    assertArrayEquals(payload.copyOfRange(700_000, 700_016), bytes)
                } finally {
                    fs.delete(moved)
                    fs.rename(held, moved.name)
                }
            }
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
            // Error text must preserve ordinary UTF-8, including supplementary characters.
            val unicodeDir = fs.mkdir(dir, "parent-中文-😀")
            try { fs.openInput(fs.createFile(unicodeDir, "missing")); fail("Missing file must throw") }
            catch (e: FsException) { assertTrue(e.message, e.message.orEmpty().contains(unicodeDir.name)) }
        } finally {
            fs.delete(dir)
            assertFalse(fs.exists(dir))
            fs.disconnect()
        }
    }
}
