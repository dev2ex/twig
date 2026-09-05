package com.twig.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL path encoding/decoding and share-path segmentation.
 *
 * The two easy pitfalls this focuses on: `+` is **not** a space (the path is not form-
 * encoded), and `..` must be rejected all the way through — the latter is the security
 * baseline for "exposing device files onto the LAN".
 *
 * Note: the literal Chinese strings below (`我的 文件.txt`, `我的 视频.mp4`) are
 * deliberately left untranslated — they are the fixture data exercising percent-decoding
 * of real non-ASCII (UTF-8) bytes.
 */
class HttpCodecTest {

    @Test
    fun `percent-decoding handles Chinese characters and spaces`() {
        assertEquals("/我的 文件.txt", HttpServer.decodePath("/%E6%88%91%E7%9A%84%20%E6%96%87%E4%BB%B6.txt"))
    }

    @Test
    fun `a plus sign in the path is a literal, not a space`() {
        // URLDecoder.decode would turn it into "a b.mp3", making the file unfindable
        assertEquals("/a+b.mp3", HttpServer.decodePath("/a+b.mp3"))
    }

    @Test
    fun `a malformed percent escape is preserved as is`() {
        assertEquals("/100%", HttpServer.decodePath("/100%"))
        assertEquals("/a%zz", HttpServer.decodePath("/a%zz"))
    }

    @Test
    fun `encoding then decoding is the identity`() {
        for (s in listOf("我的 视频.mp4", "a+b&c=d.txt", "100%.png", "one'two\"three", "tab\tsep")) {
            assertEquals(s, HttpServer.decodePath(HttpServer.encodeSegment(s)))
        }
    }

    @Test
    fun `encoding a whole path preserves slashes`() {
        assertEquals("/a%20b/c%2Bd", HttpServer.encodePath("/a b/c+d"))
    }

    @Test
    fun `path segmentation drops empty segments and dots`() {
        assertEquals(listOf("a", "b"), ShareRoot.segments("/a//./b/"))
        assertEquals(emptyList<String>(), ShareRoot.segments("/"))
    }

    @Test
    fun `path traversal is always rejected`() {
        assertNull(ShareRoot.segments("/../etc/passwd"))
        assertNull(ShareRoot.segments("/a/../../b"))
        assertNull(ShareRoot.segments("/a/.."))
    }

    /** `..` is only traversal when it is a **whole segment**; a file name containing two dots is legal. */
    @Test
    fun `a file name containing two dots is not treated as traversal`() {
        assertEquals(listOf("a..b.txt"), ShareRoot.segments("/a..b.txt"))
        assertEquals(listOf("...."), ShareRoot.segments("/...."))
    }

    @Test
    fun `a plus sign is a space only in query parsing`() {
        val q = HttpServer.parseQuery("op=upload&name=new+folder&empty=")
        assertEquals("upload", q["op"])
        assertEquals("new folder", q["name"])
        assertEquals("", q["empty"])
    }
}
