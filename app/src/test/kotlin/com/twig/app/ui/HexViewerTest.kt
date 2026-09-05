package com.twig.app.ui

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Two pieces of the hex viewer's logic where "right or wrong" cannot be told by eye: the
 * layout arithmetic ([HexLayout]) and byte fetching/search ([HexSource]). Getting the
 * former wrong just looks on screen like "an odd extra bit of horizontal scroll / a gap
 * on the right for no reason"; getting the latter wrong means the bytes shown in a row are
 * simply incorrect — neither can be attributed by eye, so it has to be pinned down with
 * assertions.
 */
@RunWith(RobolectricTestRunner::class) // HexSource uses android.util.LruCache
class HexViewerTest {

    // ---- layout ----

    @Test
    fun `bytes per row are computed from the available width, rounded to a multiple of 2`() {
        assertEquals(16, HexLayout.bytesPerRow(widthFor(16), charW = 10f, offDigits = 8))
        // room for 19 fits 18 instead (rounds down to a multiple of 2, without falling all the way back to 16)
        assertEquals(18, HexLayout.bytesPerRow(widthFor(19), charW = 10f, offDigits = 8))
        assertEquals(20, HexLayout.bytesPerRow(widthFor(20), charW = 10f, offDigits = 8))
    }

    /** The available width that exactly fits [n] bytes when each character is 10px wide and the offset is 8 digits (with an extra 0.2-cell margin). */
    private fun widthFor(n: Int): Float {
        val perByte = 2f + 1f + (HexLayout.PAIR_GAP + 1f) / 2f
        return (8 + 1f + n * perByte + 0.2f) * 10f
    }

    @Test
    fun `a narrow screen still gets at least 4 bytes, never 0 or negative`() {
        assertEquals(4, HexLayout.bytesPerRow(usablePx = 10f, charW = 10f, offDigits = 8))
        assertEquals(4, HexLayout.bytesPerRow(usablePx = 0f, charW = 10f, offDigits = 8))
        // even when the font is so large that one character is wider than the whole row, it must not crash
        assertEquals(4, HexLayout.bytesPerRow(usablePx = 100f, charW = 200f, offDigits = 8))
    }

    @Test
    fun `the offset column has enough digits to represent the whole file`() {
        assertEquals(6, HexLayout.offsetDigits(0))
        assertEquals(6, HexLayout.offsetDigits(0xFFFFFF))
        assertEquals(8, HexLayout.offsetDigits(0x1000000))
        assertEquals(8, HexLayout.offsetDigits(0xFFFFFFFFL))
        assertEquals(10, HexLayout.offsetDigits(0x100000000L))
        // 64-bit values do not overflow into negative and cause an infinite loop
        assertTrue(HexLayout.offsetDigits(Long.MAX_VALUE) <= 16)
    }

    @Test
    fun `hex input ignores separators, an odd digit count drops the trailing half-byte`() {
        assertArrayEquals(byteArrayOf(0x4D, 0x5A), HexLayout.parseHex("4D5A"))
        assertArrayEquals(byteArrayOf(0x4D, 0x5A), HexLayout.parseHex("4d 5a"))
        assertArrayEquals(byteArrayOf(0x4D, 0x5A), HexLayout.parseHex("0x4D, 0x5A")) // 'x' is not a hex digit
        assertArrayEquals(byteArrayOf(0x4D), HexLayout.parseHex("4D5"))
        assertArrayEquals(ByteArray(0), HexLayout.parseHex("zz"))
    }

    // ---- fetching bytes ----

    @Test
    fun `a chunk that has not been read yet returns null first, and only gives bytes once loaded`() {
        val data = ByteArray(HexSource.CHUNK * 2) { (it % 251).toByte() }
        val src = open(data)
        assertEquals(data.size.toLong(), src.size)
        // before load(), peek must be null -- binding relies on this to lay out placeholders
        assertNull(src.peek(0, 16))
        runBlocking { src.load(0) }
        assertArrayEquals(data.copyOfRange(0, 16), src.peek(0, 16))
        // the second chunk is still unread
        assertNull(src.peek(HexSource.CHUNK.toLong(), 16))
    }

    @Test
    fun `a row spanning two chunks can only be assembled once both chunks are present`() {
        val data = ByteArray(HexSource.CHUNK * 2) { (it % 251).toByte() }
        val src = open(data)
        val off = HexSource.CHUNK - 8L // half in chunk 0, half in chunk 1
        runBlocking { src.load(0) }
        assertNull(src.peek(off, 16))
        runBlocking { src.load(1) }
        assertArrayEquals(data.copyOfRange(off.toInt(), off.toInt() + 16), src.peek(off, 16))
    }

    @Test
    fun `the last row is shorter at end of file, and never reads past the end`() {
        val data = ByteArray(HexSource.CHUNK + 5) { it.toByte() }
        val src = open(data)
        runBlocking { src.load(1) }
        val tail = src.peek(HexSource.CHUNK.toLong(), 16)
        assertNotNull(tail)
        assertEquals(5, tail!!.size)
    }

    @Test
    fun `a source with unknown length falls back to reading it whole, available in memory right away`() {
        val data = ByteArray(1000) { it.toByte() }
        val src = HexSource(ByteFs(data, reportSize = false), FILE).also { it.open(0L) }
        assertEquals(1000L, src.size)
        assertArrayEquals(data.copyOfRange(0, 16), src.peek(0, 16)) // no load needed
    }

    // ---- search ----

    @Test
    fun `text search is case-insensitive, hex search matches raw bytes`() {
        val data = "Hello WORLD hello".toByteArray()
        val src = open(data)
        assertEquals(listOf(0L, 12L), src.search("hello".toByteArray(), fold = true, limit = 10) { true })
        assertEquals(listOf(12L), src.search("hello".toByteArray(), fold = false, limit = 10) { true })
        // "WO" = 57 4F
        assertEquals(listOf(6L), src.search(HexLayout.parseHex("57 4F"), fold = false, limit = 10) { true })
    }

    @Test
    fun `a hit straddling a buffer boundary must not be missed, nor double-counted`() {
        // the scan buffer is 256KB; straddle the needle across the boundary: the first 3 bytes in the first round, the last 3 in the second
        val size = 512 * 1024
        val data = ByteArray(size) { 'x'.code.toByte() }
        val needle = "TWIGGY".toByteArray()
        val at = 256 * 1024 - 3
        needle.copyInto(data, at)
        val src = open(data)
        assertEquals(listOf(at.toLong()), src.search(needle, fold = false, limit = 10) { true })
    }

    @Test
    fun `hit count is capped, and cancelling aborts the scan`() {
        val data = ByteArray(1024) { 'a'.code.toByte() }
        val src = open(data)
        assertEquals(5, src.search("aa".toByteArray(), fold = false, limit = 5) { true }.size)
        assertTrue(src.search("aa".toByteArray(), fold = false, limit = 999) { false }.isEmpty())
    }

    private fun open(data: ByteArray): HexSource =
        HexSource(ByteFs(data), FILE).also { it.open(data.size.toLong()) }

    /** A byte-based file system prepared just for this test group: [FakeFileSystem] stores String content, which cannot carry binary data. */
    private class ByteFs(val data: ByteArray, val reportSize: Boolean = true) : FileSystem {
        override val scheme = "bytes"
        override val displayName = "bytes"
        override fun root() = XFile(scheme, "/", isDir = true)
        override fun resolve(path: String) =
            XFile(scheme, path, isDir = false, size = if (reportSize) data.size.toLong() else 0L)

        override fun list(dir: XFile): List<XFile> = emptyList()
        override fun openInput(file: XFile): InputStream = ByteArrayInputStream(data)
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("ro")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("ro")
        override fun delete(file: XFile) = throw FsException("ro")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("ro")
        override fun exists(file: XFile) = true
        override fun randomAccessEfficient() = true
        override fun openRandom(file: XFile) = object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= data.size) return -1
                val n = minOf(length.toLong(), data.size - position).toInt()
                System.arraycopy(data, position.toInt(), buffer, offset, n)
                return n
            }

            override fun length() = data.size.toLong()
            override fun close() = Unit
        }
    }

    private companion object {
        val FILE = XFile("bytes", "/f.bin", isDir = false)
    }
}
