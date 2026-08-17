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
 * 十六进制查看器的两块"看不出对错"的逻辑:版式算术([HexLayout])与字节取用/搜索
 * ([HexSource])。前者算错了屏幕上只是"莫名多出横向滚动/右边空一条",后者错了则是
 * 行里显示的字节压根不对——都无从肉眼归因,只能靠断言钉死。
 */
@RunWith(RobolectricTestRunner::class) // HexSource 用了 android.util.LruCache
class HexViewerTest {

    // ---- 版式 ----

    @Test
    fun `每行字节数按可用宽度算_并取2的整数倍`() {
        assertEquals(16, HexLayout.bytesPerRow(widthFor(16), charW = 10f, offDigits = 8))
        // 放得下 19 个就摆 18 个(向下取到 2 的整数倍,不再一路退到 16)
        assertEquals(18, HexLayout.bytesPerRow(widthFor(19), charW = 10f, offDigits = 8))
        assertEquals(20, HexLayout.bytesPerRow(widthFor(20), charW = 10f, offDigits = 8))
    }

    /** 单字符 10px、偏移 8 位时,刚好放得下 [n] 个字节的可用宽度(多给 0.2 格富余)。 */
    private fun widthFor(n: Int): Float {
        val perByte = 2f + 1f + (HexLayout.PAIR_GAP + 1f) / 2f
        return (8 + 1f + n * perByte + 0.2f) * 10f
    }

    @Test
    fun `窄屏也至少给4个字节_不返回0或负数`() {
        assertEquals(4, HexLayout.bytesPerRow(usablePx = 10f, charW = 10f, offDigits = 8))
        assertEquals(4, HexLayout.bytesPerRow(usablePx = 0f, charW = 10f, offDigits = 8))
        // 字号大到一个字符比整行还宽也不能崩
        assertEquals(4, HexLayout.bytesPerRow(usablePx = 100f, charW = 200f, offDigits = 8))
    }

    @Test
    fun `偏移列位数够表示整个文件`() {
        assertEquals(6, HexLayout.offsetDigits(0))
        assertEquals(6, HexLayout.offsetDigits(0xFFFFFF))
        assertEquals(8, HexLayout.offsetDigits(0x1000000))
        assertEquals(8, HexLayout.offsetDigits(0xFFFFFFFFL))
        assertEquals(10, HexLayout.offsetDigits(0x100000000L))
        // 64 位不会溢出成负数导致死循环
        assertTrue(HexLayout.offsetDigits(Long.MAX_VALUE) <= 16)
    }

    @Test
    fun `十六进制输入忽略分隔符_奇数位丢掉半个字节`() {
        assertArrayEquals(byteArrayOf(0x4D, 0x5A), HexLayout.parseHex("4D5A"))
        assertArrayEquals(byteArrayOf(0x4D, 0x5A), HexLayout.parseHex("4d 5a"))
        assertArrayEquals(byteArrayOf(0x4D, 0x5A), HexLayout.parseHex("0x4D, 0x5A")) // x 不是十六进制位
        assertArrayEquals(byteArrayOf(0x4D), HexLayout.parseHex("4D5"))
        assertArrayEquals(ByteArray(0), HexLayout.parseHex("zz"))
    }

    // ---- 取字节 ----

    @Test
    fun `没读到的块先返回null_读回来才给字节`() {
        val data = ByteArray(HexSource.CHUNK * 2) { (it % 251).toByte() }
        val src = open(data)
        assertEquals(data.size.toLong(), src.size)
        // 还没 load,peek 必须是 null——绑定时据此铺占位符
        assertNull(src.peek(0, 16))
        runBlocking { src.load(0) }
        assertArrayEquals(data.copyOfRange(0, 16), src.peek(0, 16))
        // 第二块仍未读
        assertNull(src.peek(HexSource.CHUNK.toLong(), 16))
    }

    @Test
    fun `跨块的一行要两块都在才拼得出来`() {
        val data = ByteArray(HexSource.CHUNK * 2) { (it % 251).toByte() }
        val src = open(data)
        val off = HexSource.CHUNK - 8L // 一半在第 0 块,一半在第 1 块
        runBlocking { src.load(0) }
        assertNull(src.peek(off, 16))
        runBlocking { src.load(1) }
        assertArrayEquals(data.copyOfRange(off.toInt(), off.toInt() + 16), src.peek(off, 16))
    }

    @Test
    fun `末行读到文件尾就短一截_不越界`() {
        val data = ByteArray(HexSource.CHUNK + 5) { it.toByte() }
        val src = open(data)
        runBlocking { src.load(1) }
        val tail = src.peek(HexSource.CHUNK.toLong(), 16)
        assertNotNull(tail)
        assertEquals(5, tail!!.size)
    }

    @Test
    fun `长度未知的来源退回整读_内存里直接可取`() {
        val data = ByteArray(1000) { it.toByte() }
        val src = HexSource(ByteFs(data, reportSize = false), FILE).also { it.open(0L) }
        assertEquals(1000L, src.size)
        assertArrayEquals(data.copyOfRange(0, 16), src.peek(0, 16)) // 无需 load
    }

    // ---- 搜索 ----

    @Test
    fun `文本搜索大小写不敏感_十六进制搜索按原字节`() {
        val data = "Hello WORLD hello".toByteArray()
        val src = open(data)
        assertEquals(listOf(0L, 12L), src.search("hello".toByteArray(), fold = true, limit = 10) { true })
        assertEquals(listOf(12L), src.search("hello".toByteArray(), fold = false, limit = 10) { true })
        // "WO" = 57 4F
        assertEquals(listOf(6L), src.search(HexLayout.parseHex("57 4F"), fold = false, limit = 10) { true })
    }

    @Test
    fun `跨缓冲区边界的命中不能漏_也不能重`() {
        // 扫描缓冲是 256KB,把 needle 骑在边界上:前 3 字节在第一轮、后 3 字节在第二轮
        val size = 512 * 1024
        val data = ByteArray(size) { 'x'.code.toByte() }
        val needle = "TWIGGY".toByteArray()
        val at = 256 * 1024 - 3
        needle.copyInto(data, at)
        val src = open(data)
        assertEquals(listOf(at.toLong()), src.search(needle, fold = false, limit = 10) { true })
    }

    @Test
    fun `命中数封顶_取消能中止扫描`() {
        val data = ByteArray(1024) { 'a'.code.toByte() }
        val src = open(data)
        assertEquals(5, src.search("aa".toByteArray(), fold = false, limit = 5) { true }.size)
        assertTrue(src.search("aa".toByteArray(), fold = false, limit = 999) { false }.isEmpty())
    }

    private fun open(data: ByteArray): HexSource =
        HexSource(ByteFs(data), FILE).also { it.open(data.size.toLong()) }

    /** 只为这组测试准备的字节文件系统:[FakeFileSystem] 存的是 String,喂不了二进制。 */
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
