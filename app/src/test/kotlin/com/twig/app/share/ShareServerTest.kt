package com.twig.app.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * An end-to-end run over a real socket: bring up [HttpServer] + [ShareHandler], send real
 * HTTP/WebDAV messages with a plain TCP client, and assert on status codes and body.
 *
 * Why it is worth testing this way: bugs at the HTTP layer sit almost entirely on
 * **message boundaries** — a Range miscounted by one byte, a keep-alive body not drained
 * cleanly and bleeding into the next request, a write method the read-only mode forgot to
 * block. On the UI these show up as "the downloaded file is corrupt" or "it was deleted
 * even though it's read-only", extremely hard to root-cause after the fact, while sending
 * one message here pins it down immediately.
 *
 * Note: the literal Chinese strings used as file/directory names below
 * (`我的 文件+A.txt`, `我的 文件夹`, `子 文件.txt`) are deliberately left
 * untranslated — they are fixture data verifying that non-ASCII names round-trip
 * correctly through percent-encoded URLs and hrefs.
 */
@RunWith(RobolectricTestRunner::class)
class ShareServerTest {

    private lateinit var dir: File
    private var server: HttpServer? = null
    private var port = 0

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "twig-share-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "hello.txt").writeText("hello world")
        File(dir, "sub").mkdirs()
        File(dir, "sub/nested.bin").writeBytes(ByteArray(1000) { (it % 256).toByte() })
        FsRegistry.register(LocalFileSystem())
    }

    @After
    fun tearDown() {
        server?.stop()
        dir.deleteRecursively()
    }

    /** Starts the server; [readOnly] decides whether write methods pass or get 403. */
    private fun start(readOnly: Boolean = true, password: String = "") {
        port = ServerSocket(0).use { it.localPort } // borrow a free port number
        val cfg = ShareConfig(
            scope = ShareScope.Dir("file", dir.path, "share"),
            readOnly = readOnly,
            port = port,
            password = password,
        )
        val root = ShareRoot(app, cfg.scope)
        val auth = if (cfg.needsAuth) HttpServer.BasicAuth(cfg.authUser, cfg.password) else null
        server = HttpServer(port, auth, ShareHandler(app, cfg, root)).also { it.start() }
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /** Sends one request and receives one response (opens a new connection each time - simplest when not testing keep-alive). */
    private fun request(
        method: String,
        path: String,
        headers: List<String> = emptyList(),
        body: ByteArray? = null,
    ): Reply {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            val head = StringBuilder()
            head.append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
            headers.forEach { head.append(it).append("\r\n") }
            head.append("Content-Length: ${body?.size ?: 0}\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            body?.let { out.write(it) }
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val statusLine = HttpServer.readLine(input) ?: error("no status line")
            val status = statusLine.split(' ')[1].toInt()
            val hdrs = HashMap<String, String>()
            while (true) {
                val h = HttpServer.readLine(input) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) hdrs[h.substring(0, i).lowercase()] = h.substring(i + 1).trim()
            }
            val buf = ByteArrayOutputStream()
            val len = hdrs["content-length"]?.toIntOrNull()
            if (len != null) {
                val data = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(data, read, len - read)
                    if (n < 0) break
                    read += n
                }
                buf.write(data, 0, read)
            } else {
                input.copyTo(buf)
            }
            return Reply(status, hdrs, buf.toByteArray())
        }
    }

    // ---- Reads ----

    @Test
    fun `the directory listing page opens`() {
        start()
        val r = request("GET", "/")
        assertEquals(200, r.status)
        assertTrue(r.headers["content-type"]!!.startsWith("text/html"))
        assertTrue("hello.txt should be in the listing", r.text.contains("hello.txt"))
        assertTrue("the subdirectory should be in the listing", r.text.contains("sub"))
    }

    @Test
    fun `a downloaded file's content and length are both correct`() {
        start()
        val r = request("GET", "/hello.txt")
        assertEquals(200, r.status)
        assertEquals("hello world", r.text)
        assertEquals("11", r.headers["content-length"])
    }

    @Test
    fun `a file name must be percent-encoded to be fetched`() {
        File(dir, "我的 文件+A.txt").writeText("cn")
        start()
        assertEquals("cn", request("GET", "/" + HttpServer.encodeSegment("我的 文件+A.txt")).text)
    }

    @Test
    fun `a directory missing its trailing slash gets redirected`() {
        start()
        val r = request("GET", "/sub")
        assertEquals(301, r.status)
        assertEquals("/sub/", r.headers["location"])
    }

    @Test
    fun `path traversal cannot reach anything outside the shared directory`() {
        File(dir.parentFile, "outside.txt").writeText("secret")
        start()
        assertEquals(404, request("GET", "/../outside.txt").status)
        assertEquals(404, request("GET", "/sub/../../outside.txt").status)
    }

    @Test
    fun `a nonexistent path is 404`() {
        start()
        assertEquals(404, request("GET", "/nope.txt").status)
    }

    /**
     * Links to a directory containing Chinese characters/spaces must be clickable all the
     * way down.
     *
     * This is the root cause of "internal storage won't open" on a real device: the first
     * version took the **decoded** request path as the prefix and re-encoded the child
     * item's name, producing a half-baked URL like `/我的 文件夹/%E5%AD%90.txt`, which the
     * browser would 404 on the spot. This test does not hardcode the expected URL; instead
     * it **requests exactly the href the page provides** — any mismatch between the prefix
     * and suffix encoding is guaranteed to fail.
     */
    @Test
    fun `links for a Chinese-named directory can be clicked all the way through`() {
        File(dir, "我的 文件夹").mkdirs()
        File(dir, "我的 文件夹/子 文件.txt").writeText("nested")
        start()

        val home = request("GET", "/")
        val dirHref = hrefOf(home.text, "我的 文件夹")
        assertNotNull("the directory link should appear on the page", dirHref)

        val page = request("GET", dirHref!!)
        assertEquals(200, page.status)
        val fileHref = hrefOf(page.text, "子 文件.txt")
        assertNotNull("the file link should appear on the page", fileHref)

        val r = request("GET", fileHref!!)
        assertEquals(200, r.status)
        assertEquals("nested", r.text)
    }

    /** Digs the href for a given display name out of the HTML. */
    private fun hrefOf(html: String, displayName: String): String? {
        val esc = ShareHandler.xml(displayName)
        val i = html.indexOf(">$esc</a>")
        if (i < 0) return null
        val hrefEnd = html.lastIndexOf('"', i)
        val hrefStart = html.lastIndexOf('"', hrefEnd - 1)
        return html.substring(hrefStart + 1, hrefEnd)
    }

    // ---- Range ----

    @Test
    fun `Range fetches a middle segment`() {
        start()
        val r = request("GET", "/hello.txt", listOf("Range: bytes=6-10"))
        assertEquals(206, r.status)
        assertEquals("world", r.text)
        assertEquals("bytes 6-10/11", r.headers["content-range"])
    }

    @Test
    fun `an open-ended Range fetches to the end of the file`() {
        start()
        val r = request("GET", "/hello.txt", listOf("Range: bytes=6-"))
        assertEquals(206, r.status)
        assertEquals("world", r.text)
    }

    @Test
    fun `Range fetches the last N bytes`() {
        start()
        val r = request("GET", "/hello.txt", listOf("Range: bytes=-5"))
        assertEquals(206, r.status)
        assertEquals("world", r.text)
    }

    @Test
    fun `an out-of-range Range answers 416`() {
        start()
        assertEquals(416, request("GET", "/hello.txt", listOf("Range: bytes=999-")).status)
    }

    @Test
    fun `a large file reassembled from segments matches the original`() {
        start()
        val whole = ByteArrayOutputStream()
        var pos = 0
        while (pos < 1000) {
            val end = minOf(pos + 249, 999)
            val r = request("GET", "/sub/nested.bin", listOf("Range: bytes=$pos-$end"))
            assertEquals(206, r.status)
            whole.write(r.body)
            pos = end + 1
        }
        assertTrue(whole.toByteArray().contentEquals(File(dir, "sub/nested.bin").readBytes()))
    }

    // ---- Read-only ----

    @Test
    fun `read-only mode blocks every write method`() {
        start(readOnly = true)
        for (m in listOf("PUT", "DELETE", "MKCOL", "MOVE", "COPY", "POST", "PROPPATCH", "LOCK")) {
            assertEquals("$m should be rejected", 403, request(m, "/hello.txt").status)
        }
        assertTrue("the file must still be there in read-only mode", File(dir, "hello.txt").exists())
    }

    @Test
    fun `the read-only page does not render the upload area`() {
        start(readOnly = true)
        val r = request("GET", "/")
        assertFalse(r.text.contains("id=\"drop\""))
    }

    // ---- WebDAV ----

    @Test
    fun `OPTIONS declares DAV class 2`() {
        start()
        val r = request("OPTIONS", "/")
        assertEquals(200, r.status)
        assertTrue("Finder/Explorer will only write once they see class 2", r.headers["dav"]!!.contains("2"))
        assertTrue(r.headers["allow"]!!.contains("PROPFIND"))
    }

    @Test
    fun `PROPFIND with Depth 1 lists the children`() {
        start()
        val r = request("PROPFIND", "/", listOf("Depth: 1"))
        assertEquals(207, r.status)
        assertTrue(r.text.contains("<D:href>/hello.txt</D:href>"))
        assertTrue("a directory href must carry a trailing slash", r.text.contains("<D:href>/sub/</D:href>"))
        assertTrue(r.text.contains("<D:getcontentlength>11</D:getcontentlength>"))
        assertTrue(r.text.contains("<D:collection/>"))
    }

    @Test
    fun `PROPFIND with Depth 0 describes only itself`() {
        start()
        val r = request("PROPFIND", "/sub/", listOf("Depth: 0"))
        assertEquals(207, r.status)
        assertFalse("Depth 0 must not bring in children", r.text.contains("nested.bin"))
    }

    @Test
    fun `special characters are escaped in PROPFIND's XML`() {
        File(dir, "a&b<c>.txt").writeText("x")
        start()
        val r = request("PROPFIND", "/", listOf("Depth: 1"))
        assertTrue(r.text.contains("a&amp;b&lt;c&gt;.txt"))
    }

    @Test
    fun `PUT for create vs overwrite are 201 and 204 respectively`() {
        start(readOnly = false)
        assertEquals(201, request("PUT", "/new.txt", body = "one".toByteArray()).status)
        assertEquals("one", File(dir, "new.txt").readText())
        assertEquals(204, request("PUT", "/new.txt", body = "two".toByteArray()).status)
        assertEquals("two", File(dir, "new.txt").readText())
    }

    @Test
    fun `MKCOL creates a directory, creating it again answers 405`() {
        start(readOnly = false)
        assertEquals(201, request("MKCOL", "/fresh/").status)
        assertTrue(File(dir, "fresh").isDirectory)
        assertEquals(405, request("MKCOL", "/fresh/").status)
    }

    @Test
    fun `DELETE removes a file`() {
        start(readOnly = false)
        assertEquals(204, request("DELETE", "/hello.txt").status)
        assertFalse(File(dir, "hello.txt").exists())
    }

    @Test
    fun `DELETE cannot remove the share root itself`() {
        start(readOnly = false)
        assertEquals(403, request("DELETE", "/").status)
        assertTrue(dir.exists())
    }

    @Test
    fun `MOVE renames within the same directory`() {
        start(readOnly = false)
        val r = request("MOVE", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/renamed.txt"))
        assertTrue(r.status in listOf(201, 204))
        assertFalse(File(dir, "hello.txt").exists())
        assertEquals("hello world", File(dir, "renamed.txt").readText())
    }

    @Test
    fun `MOVE across directories`() {
        start(readOnly = false)
        val r = request("MOVE", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/sub/moved.txt"))
        assertTrue(r.status in listOf(201, 204))
        assertEquals("hello world", File(dir, "sub/moved.txt").readText())
    }

    @Test
    fun `MOVE with Overwrite F answers 412 when the target already exists`() {
        File(dir, "taken.txt").writeText("keep me")
        start(readOnly = false)
        val r = request(
            "MOVE", "/hello.txt",
            listOf("Destination: http://127.0.0.1:$port/taken.txt", "Overwrite: F"),
        )
        assertEquals(412, r.status)
        assertEquals("keep me", File(dir, "taken.txt").readText())
        assertTrue("a rejected MOVE must not touch the source file", File(dir, "hello.txt").exists())
    }

    @Test
    fun `COPY leaves the source file behind`() {
        start(readOnly = false)
        val r = request("COPY", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/copy.txt"))
        assertTrue(r.status in listOf(201, 204))
        assertTrue(File(dir, "hello.txt").exists())
        assertEquals("hello world", File(dir, "copy.txt").readText())
    }

    /** Copying a directory must copy its contents along with it, and the source directory stays intact. */
    @Test
    fun `COPY an entire directory`() {
        start(readOnly = false)
        val r = request("COPY", "/sub/", listOf("Destination: http://127.0.0.1:$port/sub2/"))
        assertTrue(r.status in listOf(201, 204))
        assertTrue(File(dir, "sub/nested.bin").exists())
        assertTrue(
            File(dir, "sub2/nested.bin").readBytes().contentEquals(File(dir, "sub/nested.bin").readBytes()),
        )
    }

    /** Source and destination are the same path: must not muddle a "copy" into deleting the source. */
    @Test
    fun `COPY to itself is rejected`() {
        start(readOnly = false)
        val r = request("COPY", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/hello.txt"))
        assertEquals(403, r.status)
        assertEquals("hello world", File(dir, "hello.txt").readText())
    }

    // ---- Browser upload ----

    @Test
    fun `a multipart upload lands on disk`() {
        start(readOnly = false)
        val b = "----twigtest"
        val payload = ByteArray(70_000) { (it % 97).toByte() }
        val body = ByteArrayOutputStream().apply {
            write("--$b\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"f\"; filename=\"up.bin\"\r\n\r\n".toByteArray())
            write(payload)
            write("\r\n--$b--\r\n".toByteArray())
        }.toByteArray()
        val r = request(
            "POST", "/?op=upload",
            listOf("Content-Type: multipart/form-data; boundary=$b"),
            body,
        )
        assertEquals(200, r.status)
        assertTrue(File(dir, "up.bin").readBytes().contentEquals(payload))
    }

    @Test
    fun `an uploaded file's name cannot carry a path`() {
        start(readOnly = false)
        val b = "----twigtest"
        val body = ByteArrayOutputStream().apply {
            write("--$b\r\n".toByteArray())
            write(
                ("Content-Disposition: form-data; name=\"f\"; " +
                    "filename=\"../../escaped.txt\"\r\n\r\n").toByteArray(),
            )
            write("pwned".toByteArray())
            write("\r\n--$b--\r\n".toByteArray())
        }.toByteArray()
        request("POST", "/?op=upload", listOf("Content-Type: multipart/form-data; boundary=$b"), body)
        assertFalse("must not be able to write outside the shared directory", File(dir.parentFile, "escaped.txt").exists())
        assertEquals("pwned", File(dir, "escaped.txt").readText())
    }

    // ---- Authentication ----

    @Test
    fun `once a password is set, Basic auth is required`() {
        start(password = "s3cret")
        val no = request("GET", "/hello.txt")
        assertEquals(401, no.status)
        assertTrue(no.headers["www-authenticate"]!!.contains("Basic"))

        val bad = basicAuthGet("twig", "wrong")
        assertEquals(401, bad.status)

        val ok = basicAuthGet("twig", "s3cret")
        assertEquals(200, ok.status)
        assertEquals("hello world", ok.text)
    }

    private fun basicAuthGet(user: String, pass: String): Reply {
        val token = android.util.Base64.encodeToString(
            "$user:$pass".toByteArray(), android.util.Base64.NO_WRAP,
        )
        return request("GET", "/hello.txt", listOf("Authorization: Basic $token"))
    }

    // ---- Connection reuse ----

    /**
     * Two requests sent back to back on the same connection. What this actually guards
     * against is "the previous response's body length was computed wrong" — which would
     * make the second response start parsing from the wrong offset, showing up as random
     * download corruption.
     */
    @Test
    fun `two requests sent back to back on keep-alive are both correct`() {
        start()
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            repeat(2) {
                out.write(
                    "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray(Charsets.ISO_8859_1),
                )
            }
            out.flush()
            val input = BufferedInputStream(sock.getInputStream())
            repeat(2) { i ->
                val status = HttpServer.readLine(input)!!.split(' ')[1].toInt()
                assertEquals("response #${i + 1}", 200, status)
                var len = -1
                while (true) {
                    val h = HttpServer.readLine(input) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", true)) {
                        len = h.substringAfter(':').trim().toInt()
                    }
                }
                assertEquals(11, len)
                val data = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(data, read, len - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals("hello world", String(data))
            }
        }
    }

    // ---- All-sources mode ----

    /** Starts an "all sources" server (differs from [start] only in scope). */
    private fun startAll() {
        port = ServerSocket(0).use { it.localPort }
        val cfg = ShareConfig(scope = ShareScope.AllSources, readOnly = true, port = port)
        server = HttpServer(port, null, ShareHandler(app, cfg, ShareRoot(app, cfg.scope)))
            .also { it.start() }
    }

    @Test
    fun `the all-sources home page lists each source`() {
        startAll()
        val r = request("GET", "/")
        assertEquals(200, r.status)
        assertTrue("internal storage should be present", r.text.contains("href=\"/storage/\""))
        assertTrue("the root directory should be present", r.text.contains("href=\"/root/\""))
    }

    @Test
    fun `all-sources mode can enter a specific source`() {
        startAll()
        val r = request("GET", "/storage/")
        assertEquals(200, r.status)
        // failing to list should not be a 500 either, but a page carrying a reason (on a
        // real device "won't open" is ruined precisely by giving no reason)
        assertTrue(r.headers["content-type"]!!.startsWith("text/html"))
    }

    @Test
    fun `all-sources PROPFIND lists each source`() {
        startAll()
        val r = request("PROPFIND", "/", listOf("Depth: 1"))
        assertEquals(207, r.status)
        assertTrue(r.text.contains("<D:href>/storage/</D:href>"))
        assertTrue(r.text.contains("<D:href>/root/</D:href>"))
    }

    @Test
    fun `an unknown source in all-sources mode is 404`() {
        startAll()
        assertEquals(404, request("GET", "/nosuchsource/").status)
    }

    @Test
    fun `HEAD returns only headers, no body`() {
        start()
        val r = request("HEAD", "/hello.txt")
        assertEquals(200, r.status)
        assertEquals("11", r.headers["content-length"])
        assertEquals(0, r.body.size)
    }
}
