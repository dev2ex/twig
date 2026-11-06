package com.twig.fs.archive

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

class RemoteRarTest {
    @After
    fun tearDown() {
        FsRegistry.unregister("remote-rar-test")
    }

    /** No local file or sequential download is available; only short positional reads work. */
    private class Remote(private val bytes: ByteArray) : FileSystem by LocalFileSystem() {
        override val scheme = "remote-rar-test"
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val transferred = AtomicInteger()
        var forbidden: LongRange = LongRange.EMPTY
        @Volatile var failReads = false

        override fun openInput(file: XFile): java.io.InputStream =
            throw AssertionError("RAR must not download the archive through openInput")

        override fun openRandom(file: XFile): RandomSource {
            opened.incrementAndGet()
            return object : RandomSource {
                private var done = false
                override fun length() = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    check(!done)
                    if (failReads) throw IOException("Remote disconnected")
                    if (position >= bytes.size) return -1
                    val n = minOf(length, 997, bytes.size - position.toInt())
                    assertFalse("Unrelated entry data must be skipped", position in forbidden || position + n - 1 in forbidden)
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                    transferred.addAndGet(n)
                    return n
                }
                override fun close() {
                    if (!done) closed.incrementAndGet()
                    done = true
                }
            }
        }
    }

    private fun mount(bytes: ByteArray, sizeKnown: Boolean = true): Triple<RarFileSystem, Remote, XFile> {
        val remote = Remote(bytes)
        FsRegistry.register(remote)
        val fs = RarFileSystem()
        val host = XFile(remote.scheme, "/remote-only/box.rar", isDir = false,
            size = if (sizeKnown) bytes.size.toLong() else 0L)
        return Triple(fs, remote, fs.rootOf(host))
    }

    private fun decode(value: String) = Base64.getDecoder().decode(value)

    private fun assertContent(fs: RarFileSystem, root: XFile) {
        val src = fs.list(root).single()
        assertEquals("src", src.name)
        val children = fs.list(src)
        assertEquals(listOf("dir", "hello.txt"), children.map { it.name })
        assertEquals("hello encrypted world\n", fs.openInput(children[1]).use { it.reader().readText() })
        val nested = fs.list(children[0]).single()
        assertEquals("nested content here\n", fs.openInput(nested).use { it.reader().readText() })
    }

    @Test
    fun remoteRar4AndRar5ListAndOpenWithUnknownHostSize() {
        for (sample in listOf(RarFileSystemTest.PLAIN4, RarFileSystemTest.PLAIN5)) {
            val (fs, remote, root) = mount(decode(sample), sizeKnown = false)
            assertFalse(fs.needsPassword(root.path.substringBeforeLast("!/")))
            assertContent(fs, root)
            assertEquals(remote.opened.get(), remote.closed.get())
        }
    }

    @Test
    fun remoteContentAndHeaderEncryptionKeepPasswordChecksAndReadableContent() {
        for (sample in listOf(RarFileSystemTest.ENC4, RarFileSystemTest.ENC5, RarFileSystemTest.HEAD5)) {
            val (fs, remote, root) = mount(decode(sample))
            val path = root.path.substringBeforeLast("!/")
            assertTrue(fs.needsPassword(path))
            if (sample == RarFileSystemTest.HEAD5) {
                assertThrows(ArchivePasswordException::class.java) { fs.list(root) }
            } else {
                val file = fs.list(fs.list(root).single()).first { !it.isDir }
                assertThrows(ArchivePasswordException::class.java) { fs.openInput(file).close() }
            }
            assertFalse(fs.checkPassword(path, "wrong"))
            assertTrue(fs.checkPassword(path, "secret"))
            fs.setPassword(path, "secret")
            assertContent(fs, root)
            assertEquals(remote.opened.get(), remote.closed.get())
        }
    }

    @Test
    fun listingAndOpeningTailEntrySkipLargeUnrelatedPayload() {
        val bytes = largeStoredRar4()
        val (fs, remote, root) = mount(bytes)
        remote.forbidden = 1024L * 1024..7L * 1024 * 1024
        assertFalse(fs.needsPassword(root.path.substringBeforeLast("!/")))
        val dir = fs.list(fs.list(root).single()).first { it.isDir }
        val tail = fs.list(dir).single()
        assertEquals("nested content here\n", fs.openInput(tail).use { it.reader().readText() })
        assertTrue("Must fetch only header blocks and the requested entry", remote.transferred.get() < bytes.size / 2)
        assertEquals(remote.opened.get(), remote.closed.get())
    }

    @Test
    fun compressedSolidRar5CanOpenLaterEntryFirstAndKeepStreamsIndependent() {
        val bytes = javaClass.getResourceAsStream("/solid5.rar")!!.use { it.readBytes() }
        val (fs, remote, root) = mount(bytes)
        val files = fs.list(root)
        assertEquals(listOf("alpha.txt", "beta.txt"), files.map { it.name })
        fs.openInput(files[1]).use { second ->
            fs.openInput(files[0]).use { first ->
                assertEquals("second remote entry\n".repeat(12000), second.reader().readText())
                assertEquals("first remote entry\n".repeat(10000), first.reader().readText())
            }
        }
        // Closing a partially consumed stream must also release its remote handle.
        fs.openInput(files[1]).use { assertEquals('s'.code, it.read()) }
        assertEquals(remote.opened.get(), remote.closed.get())
    }

    @Test
    fun remoteReadFailureClosesTheChannel() {
        val (fs, remote, root) = mount(decode(RarFileSystemTest.PLAIN5))
        remote.failReads = true
        assertThrows(Exception::class.java) { fs.list(root) }
        assertTrue(remote.opened.get() > 0)
        assertEquals(remote.opened.get(), remote.closed.get())
    }

    @Test
    fun disconnectDuringExtractionThrowsInsteadOfReturningTruncatedContent() {
        val (fs, remote, root) = mount(largeStoredRar4())
        val file = fs.list(fs.list(root).single()).first { !it.isDir }
        fs.openInput(file).use { input ->
            assertEquals(0, input.read())
            remote.failReads = true
            assertThrows(IOException::class.java) { input.readBytes() }
        }
        assertEquals(remote.opened.get(), remote.closed.get())
    }

    /** Enlarge the real CLI-produced RAR4 fixture's first STORED entry, retaining its tail entries. */
    private fun largeStoredRar4(): ByteArray {
        val original = decode(RarFileSystemTest.PLAIN4)
        val start = 20 // seven-byte signature + thirteen-byte main header
        val originalBuffer = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = originalBuffer.getShort(start + 5).toInt() and 0xffff
        val oldSize = originalBuffer.getInt(start + 7)
        val payload = ByteArray(8 * 1024 * 1024)
        val header = original.copyOfRange(start, start + headerSize)
        val updated = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        updated.putInt(7, payload.size)
        updated.putInt(11, payload.size)
        updated.putInt(16, CRC32().apply { update(payload) }.value.toInt())
        updated.putShort(0, CRC32().apply { update(header, 2, header.size - 2) }.value.toShort())
        return original.copyOfRange(0, start) + header + payload +
            original.copyOfRange(start + headerSize + oldSize, original.size)
    }
}
