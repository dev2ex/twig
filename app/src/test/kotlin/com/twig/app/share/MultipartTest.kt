package com.twig.app.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * `multipart/form-data` 流式解析的单测。
 *
 * 这块值得单独测:边界扫描要跨缓冲区块拼接,而**正文里恰好出现边界前缀**、
 * 边界正好压在块边界上这两种情况在真机上极难复现——一旦错了表现为上传的文件
 * 尾部多/少几个字节,肉眼看不出来,视频却播不了。
 */
class MultipartTest {

    private val boundary = "----WebKitFormBoundaryABC123"

    private fun body(vararg parts: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((headers, data) in parts) {
            out.write("--$boundary\r\n".toByteArray())
            out.write(headers.toByteArray())
            out.write("\r\n\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    private fun parse(raw: ByteArray): List<Triple<String?, String?, ByteArray>> {
        val out = ArrayList<Triple<String?, String?, ByteArray>>()
        Multipart(ByteArrayInputStream(raw), boundary).forEachPart { name, filename, stream ->
            out.add(Triple(name, filename, stream.readBytes()))
        }
        return out
    }

    @Test
    fun `单个文件部分`() {
        val data = "hello world".toByteArray()
        val parts = parse(
            body("""Content-Disposition: form-data; name="f"; filename="a.txt"""" to data),
        )
        assertEquals(1, parts.size)
        assertEquals("f", parts[0].first)
        assertEquals("a.txt", parts[0].second)
        assertArrayEquals(data, parts[0].third)
    }

    @Test
    fun `多个部分与普通字段混排`() {
        val parts = parse(
            body(
                """Content-Disposition: form-data; name="name"""" to "新建目录".toByteArray(),
                """Content-Disposition: form-data; name="f"; filename="b.bin"""" to byteArrayOf(0, 1, 2, 3),
            ),
        )
        assertEquals(2, parts.size)
        assertEquals("name", parts[0].first)
        assertEquals(null, parts[0].second)
        assertEquals("新建目录", String(parts[0].third))
        assertEquals("b.bin", parts[1].second)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), parts[1].third)
    }

    /** 二进制正文里出现边界的**前缀**,不能被误判成真边界提前截断。 */
    @Test
    fun `正文含边界前缀不被截断`() {
        val tricky = ("\r\n--$boundary-not-really\r\nstill body").toByteArray()
        val parts = parse(
            body("""Content-Disposition: form-data; name="f"; filename="c.bin"""" to tricky),
        )
        assertEquals(1, parts.size)
        assertArrayEquals(tricky, parts[0].third)
    }

    /**
     * 大于内部缓冲区(64KB)的正文:边界必然落在某次填充的中间,
     * 跨块拼接错一个字节就会在这儿露馅。
     */
    @Test
    fun `跨缓冲区块的大正文`() {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val parts = parse(
            body("""Content-Disposition: form-data; name="f"; filename="big.bin"""" to big),
        )
        assertEquals(1, parts.size)
        assertArrayEquals(big, parts[0].third)
    }

    /** 处理方只读了一部分就走人时,下一个 part 仍要能对齐。 */
    @Test
    fun `部分未读完也不影响后续部分`() {
        val raw = body(
            """Content-Disposition: form-data; name="f"; filename="skip.bin"""" to ByteArray(100_000) { 7 },
            """Content-Disposition: form-data; name="tail"""" to "ok".toByteArray(),
        )
        val seen = ArrayList<String>()
        Multipart(ByteArrayInputStream(raw), boundary).forEachPart { name, _, stream ->
            if (name == "f") {
                stream.read(ByteArray(10)) // 故意只读一点点
            } else {
                seen.add(String(stream.readBytes()))
            }
        }
        assertEquals(listOf("ok"), seen)
    }

    /** 文件名带引号内的分号/中文,不能被参数分割逻辑切坏。 */
    @Test
    fun `文件名含特殊字符`() {
        val parts = parse(
            body(
                """Content-Disposition: form-data; name="f"; filename="我的 视频;第1集.mp4"""" to
                    "x".toByteArray(),
            ),
        )
        assertEquals("我的 视频;第1集.mp4", parts[0].second)
    }

    /** 没有任何 part 的空表单不该抛异常。 */
    @Test
    fun `空表单`() {
        val raw = "--$boundary--\r\n".toByteArray()
        assertTrue(parse(raw).isEmpty())
    }
}
