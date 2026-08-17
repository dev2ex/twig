package com.twig.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL 路径编解码与共享路径拆分。
 *
 * 重点是两条容易踩的:`+` **不是**空格(路径不是 form 编码),以及 `..` 必须被
 * 一路拒到底——后者是"把设备文件摊到局域网上"这件事的安全底线。
 */
class HttpCodecTest {

    @Test
    fun `百分号解码中文与空格`() {
        assertEquals("/我的 文件.txt", HttpServer.decodePath("/%E6%88%91%E7%9A%84%20%E6%96%87%E4%BB%B6.txt"))
    }

    @Test
    fun `加号在路径里是字面量不是空格`() {
        // URLDecoder.decode 会把它变成 "a b.mp3",那样就找不到文件了
        assertEquals("/a+b.mp3", HttpServer.decodePath("/a+b.mp3"))
    }

    @Test
    fun `残缺的百分号转义原样保留`() {
        assertEquals("/100%", HttpServer.decodePath("/100%"))
        assertEquals("/a%zz", HttpServer.decodePath("/a%zz"))
    }

    @Test
    fun `编码后再解码是恒等的`() {
        for (s in listOf("我的 视频.mp4", "a+b&c=d.txt", "100%.png", "one'two\"three", "tab\tsep")) {
            assertEquals(s, HttpServer.decodePath(HttpServer.encodeSegment(s)))
        }
    }

    @Test
    fun `整路径编码保留斜杠`() {
        assertEquals("/a%20b/c%2Bd", HttpServer.encodePath("/a b/c+d"))
    }

    @Test
    fun `路径拆分丢掉空段与点`() {
        assertEquals(listOf("a", "b"), ShareRoot.segments("/a//./b/"))
        assertEquals(emptyList<String>(), ShareRoot.segments("/"))
    }

    @Test
    fun `路径穿越一律拒绝`() {
        assertNull(ShareRoot.segments("/../etc/passwd"))
        assertNull(ShareRoot.segments("/a/../../b"))
        assertNull(ShareRoot.segments("/a/.."))
    }

    /** `..` 只有作为**完整一段**才是穿越;文件名里带两个点是合法的。 */
    @Test
    fun `名字里含两点的文件不算穿越`() {
        assertEquals(listOf("a..b.txt"), ShareRoot.segments("/a..b.txt"))
        assertEquals(listOf("...."), ShareRoot.segments("/...."))
    }

    @Test
    fun `query 解析里加号才是空格`() {
        val q = HttpServer.parseQuery("op=upload&name=new+folder&empty=")
        assertEquals("upload", q["op"])
        assertEquals("new folder", q["name"])
        assertEquals("", q["empty"])
    }
}
