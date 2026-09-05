package com.twig.app.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Unit tests for streaming `multipart/form-data` parsing.
 *
 * This deserves its own tests: boundary scanning has to stitch across buffer chunks, and
 * the two cases where **the body happens to contain a boundary prefix** or **the boundary
 * lands right on a chunk edge** are extremely hard to reproduce on a real device — get
 * either wrong and an uploaded file ends up a few bytes longer or shorter at the tail,
 * invisible to the eye but enough that the video will not play.
 *
 * Note: the literal Chinese/non-ASCII strings below (e.g. `新建目录`,
 * `我的 视频;第1集.mp4`) are deliberately left untranslated — they are fixture data
 * verifying that non-ASCII field values and filenames (including one containing a
 * semicolon inside quotes) survive multipart parsing intact.
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
    fun `a single file part`() {
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
    fun `multiple parts mixed with an ordinary field`() {
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

    /** A **prefix** of the boundary appearing inside binary body data must not be mistaken for the real boundary and truncate early. */
    @Test
    fun `body containing a boundary prefix is not truncated`() {
        val tricky = ("\r\n--$boundary-not-really\r\nstill body").toByteArray()
        val parts = parse(
            body("""Content-Disposition: form-data; name="f"; filename="c.bin"""" to tricky),
        )
        assertEquals(1, parts.size)
        assertArrayEquals(tricky, parts[0].third)
    }

    /**
     * A body larger than the internal buffer (64KB): the boundary is bound to land in the
     * middle of some fill, and getting the cross-chunk stitching wrong by even one byte
     * would surface right here.
     */
    @Test
    fun `a large body spanning buffer chunks`() {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val parts = parse(
            body("""Content-Disposition: form-data; name="f"; filename="big.bin"""" to big),
        )
        assertEquals(1, parts.size)
        assertArrayEquals(big, parts[0].third)
    }

    /** When the handler walks away after reading only part of a part, the next part must still line up correctly. */
    @Test
    fun `a partially-read part does not affect the parts that follow`() {
        val raw = body(
            """Content-Disposition: form-data; name="f"; filename="skip.bin"""" to ByteArray(100_000) { 7 },
            """Content-Disposition: form-data; name="tail"""" to "ok".toByteArray(),
        )
        val seen = ArrayList<String>()
        Multipart(ByteArrayInputStream(raw), boundary).forEachPart { name, _, stream ->
            if (name == "f") {
                stream.read(ByteArray(10)) // deliberately read only a little
            } else {
                seen.add(String(stream.readBytes()))
            }
        }
        assertEquals(listOf("ok"), seen)
    }

    /** A file name containing a semicolon/non-ASCII text inside quotes must not be mangled by the parameter-splitting logic. */
    @Test
    fun `a file name containing special characters`() {
        val parts = parse(
            body(
                """Content-Disposition: form-data; name="f"; filename="我的 视频;第1集.mp4"""" to
                    "x".toByteArray(),
            ),
        )
        assertEquals("我的 视频;第1集.mp4", parts[0].second)
    }

    /** An empty form with no parts at all must not throw. */
    @Test
    fun `an empty form`() {
        val raw = "--$boundary--\r\n".toByteArray()
        assertTrue(parse(raw).isEmpty())
    }
}
