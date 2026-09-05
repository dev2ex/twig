package com.twig.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Text decoding priority and write-back. This class of bug has a peculiar shape: **no
 * error is raised, only the content changes** — subtitles/lyrics turn into mojibake, or
 * saving silently swaps out the whole file's encoding, so every assertion here pins down
 * "the decoded string / the bytes written back", not "whether an exception was thrown".
 *
 * Note: the literal Chinese strings below (`中文...`) are deliberately left untranslated
 * — they are the test fixture data exercising GBK/Big5/UTF-8 detection, and translating
 * them to ASCII would defeat the point of the test.
 */
class TextCodecTest {

    private fun cs(name: String) = Charset.forName(name)

    private val gbk = cs("GBK")
    private val big5 = cs("Big5")

    @Test
    fun `UTF-8 takes priority over any candidate`() {
        val d = TextCodec.decode("中文 abc".toByteArray(Charsets.UTF_8), charsets = listOf(gbk))
        assertEquals("中文 abc", d.text)
        assertEquals(Charsets.UTF_8, d.charset)
        assertNull(d.bom)
    }

    @Test
    fun `when it is not UTF-8, decode in candidate order`() {
        val d = TextCodec.decode("中文测试".toByteArray(gbk), charsets = listOf(gbk))
        assertEquals("中文测试", d.text)
        assertEquals(gbk, d.charset)
    }

    /**
     * Order is priority: for the same GBK bytes, Big5 also "decodes successfully" (just
     * into a different batch of characters), and whichever is listed first wins. This
     * pins down "we really do try in the order the user listed", not "GBK is hardcoded
     * internally".
     */
    @Test
    fun `the earlier-listed candidate is tried first`() {
        val bytes = "中文测试".toByteArray(gbk)
        assertEquals(gbk, TextCodec.decode(bytes, charsets = listOf(gbk, big5)).charset)

        val asBig5 = TextCodec.decode(bytes, charsets = listOf(big5, gbk))
        assertEquals(big5, asBig5.charset)
        assertNotEquals("中文测试", asBig5.text)
    }

    @Test
    fun `with no candidates checked, non-Unicode text has no recognizable encoding`() {
        val d = TextCodec.decode("中文测试".toByteArray(gbk), charsets = emptyList())
        assertNull("when it cannot be recognized, it must say so instead of pretending to succeed", d.charset)
        assertTrue("the lenient-decode result should contain the replacement character", d.text.contains('�'))
    }

    @Test
    fun `the BOM has the final say, and is restored on write-back`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val d = TextCodec.decode(bom + "hi".toByteArray(Charsets.UTF_8), charsets = listOf(gbk))
        assertEquals("hi", d.text) // the BOM must not leak into the body text
        assertEquals(Charsets.UTF_8, d.charset)
        assertArrayEquals(bom, d.bom)
        assertArrayEquals(bom + "hi".toByteArray(), TextCodec.encode(d.text, d.charset!!, d.bom))
    }

    @Test
    fun `a UTF-16 BOM is recognized too`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "中文".toByteArray(Charsets.UTF_16LE)
        val d = TextCodec.decode(bytes, charsets = listOf(gbk))
        assertEquals("中文", d.text)
        assertEquals(Charsets.UTF_16LE, d.charset)
    }

    /**
     * "It decoded" does not mean "it decoded correctly": decoding UTF-16 text with a
     * single-byte charset puts a NUL after every ASCII character — the result is wall-to-
     * wall control characters. This case must be judged unreasonable and fall through to
     * the next candidate, otherwise something like windows-1252 always wins first and
     * every candidate after it is dead weight.
     */
    @Test
    fun `a candidate that decodes into a pile of control characters does not count`() {
        val text = "hello world, this is plain ascii text"
        val d = TextCodec.decode(text.toByteArray(Charsets.UTF_16LE), charsets = listOf(cs("windows-1252")))
        assertNull(d.charset)
    }

    @Test
    fun `write-back uses the original encoding, not unconditionally UTF-8`() {
        val src = "中文测试".toByteArray(gbk)
        val d = TextCodec.decode(src, charsets = listOf(gbk))
        assertArrayEquals(src, TextCodec.encode(d.text, d.charset!!, d.bom))
    }

    /** An emoji inserted into GBK text: better to fail to encode than to silently substitute `?`. */
    @Test
    fun `a character the original encoding cannot represent fails to encode rather than being silently substituted`() {
        assertNull(TextCodec.encode("中文 🎉", gbk))
        assertEquals(listOf('\uD83C', '\uDF89'), TextCodec.unmappable("中文 🎉", gbk))
    }

    @Test
    fun `every candidate in the list is available at runtime`() {
        assertTrue(TextCodec.CANDIDATES.contains("GB18030"))
        // GBK is retired on purpose: GB18030 is a strict superset (identical bytes for every
        // GBK character), so offering both would be a choice with no right answer.
        assertFalse(TextCodec.CANDIDATES.contains("GBK"))
        TextCodec.CANDIDATES.forEach { assertTrue(it, Charset.isSupported(it)) }
    }
}
