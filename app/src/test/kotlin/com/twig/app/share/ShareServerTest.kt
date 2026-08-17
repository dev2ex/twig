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
 * 端到端跑一遍真 socket:起 [HttpServer] + [ShareHandler],用普通 TCP 客户端发
 * 真实的 HTTP/WebDAV 报文,断言状态码与正文。
 *
 * 为什么值得这么测:HTTP 这层的 bug 几乎全在**报文边界**上——Range 少算一个字节、
 * keep-alive 时正文没读干净串到下一个请求、只读模式漏挡某个方法。这些在 UI 上
 * 表现为"下载的文件损坏"或"明明只读却被删了",事后极难归因,而在这里一发报文就能钉死。
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

    /** 起服务;[readOnly] 决定写方法是放行还是 403。 */
    private fun start(readOnly: Boolean = true, password: String = "") {
        port = ServerSocket(0).use { it.localPort } // 借一个空闲端口号
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

    /** 发一个请求收一个响应(每次新建连接,不测 keep-alive 时最省心)。 */
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

    // ---- 读 ----

    @Test
    fun `目录列表页可以打开`() {
        start()
        val r = request("GET", "/")
        assertEquals(200, r.status)
        assertTrue(r.headers["content-type"]!!.startsWith("text/html"))
        assertTrue("列表里应有 hello.txt", r.text.contains("hello.txt"))
        assertTrue("列表里应有子目录", r.text.contains("sub"))
    }

    @Test
    fun `下载文件内容与长度都对`() {
        start()
        val r = request("GET", "/hello.txt")
        assertEquals(200, r.status)
        assertEquals("hello world", r.text)
        assertEquals("11", r.headers["content-length"])
    }

    @Test
    fun `文件名要百分号编码后才能取到`() {
        File(dir, "我的 文件+A.txt").writeText("cn")
        start()
        assertEquals("cn", request("GET", "/" + HttpServer.encodeSegment("我的 文件+A.txt")).text)
    }

    @Test
    fun `目录缺尾斜杠时重定向`() {
        start()
        val r = request("GET", "/sub")
        assertEquals(301, r.status)
        assertEquals("/sub/", r.headers["location"])
    }

    @Test
    fun `路径穿越拿不到共享目录外的东西`() {
        File(dir.parentFile, "outside.txt").writeText("secret")
        start()
        assertEquals(404, request("GET", "/../outside.txt").status)
        assertEquals(404, request("GET", "/sub/../../outside.txt").status)
    }

    @Test
    fun `不存在的路径是 404`() {
        start()
        assertEquals(404, request("GET", "/nope.txt").status)
    }

    /**
     * 中文/空格目录的链接必须能一路点下去。
     *
     * 这是真机上「内部存储打不开」的根:第一版拿**解码后**的请求路径当前缀、再把子项名字
     * 编一遍,拼出 `/我的 文件夹/%E5%AD%90.txt` 这种半生不熟的 URL,浏览器一发就 404。
     * 这里不写死期望的 URL,而是**照着页面里给的 href 去请求**——只要前后缀编码不一致
     * 就必然对不上。
     */
    @Test
    fun `中文目录的链接能一路点进去`() {
        File(dir, "我的 文件夹").mkdirs()
        File(dir, "我的 文件夹/子 文件.txt").writeText("nested")
        start()

        val home = request("GET", "/")
        val dirHref = hrefOf(home.text, "我的 文件夹")
        assertNotNull("目录链接应出现在页面里", dirHref)

        val page = request("GET", dirHref!!)
        assertEquals(200, page.status)
        val fileHref = hrefOf(page.text, "子 文件.txt")
        assertNotNull("文件链接应出现在页面里", fileHref)

        val r = request("GET", fileHref!!)
        assertEquals(200, r.status)
        assertEquals("nested", r.text)
    }

    /** 从 HTML 里挖出某个显示名对应的 href。 */
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
    fun `Range 取中间一段`() {
        start()
        val r = request("GET", "/hello.txt", listOf("Range: bytes=6-10"))
        assertEquals(206, r.status)
        assertEquals("world", r.text)
        assertEquals("bytes 6-10/11", r.headers["content-range"])
    }

    @Test
    fun `Range 开区间取到文件尾`() {
        start()
        val r = request("GET", "/hello.txt", listOf("Range: bytes=6-"))
        assertEquals(206, r.status)
        assertEquals("world", r.text)
    }

    @Test
    fun `Range 取末尾 N 字节`() {
        start()
        val r = request("GET", "/hello.txt", listOf("Range: bytes=-5"))
        assertEquals(206, r.status)
        assertEquals("world", r.text)
    }

    @Test
    fun `Range 越界答 416`() {
        start()
        assertEquals(416, request("GET", "/hello.txt", listOf("Range: bytes=999-")).status)
    }

    @Test
    fun `大文件分段拼回来与原文一致`() {
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

    // ---- 只读 ----

    @Test
    fun `只读模式挡住所有写方法`() {
        start(readOnly = true)
        for (m in listOf("PUT", "DELETE", "MKCOL", "MOVE", "COPY", "POST", "PROPPATCH", "LOCK")) {
            assertEquals("$m 应被拒绝", 403, request(m, "/hello.txt").status)
        }
        assertTrue("只读时文件必须还在", File(dir, "hello.txt").exists())
    }

    @Test
    fun `只读模式的页面不渲染上传区`() {
        start(readOnly = true)
        val r = request("GET", "/")
        assertFalse(r.text.contains("id=\"drop\""))
    }

    // ---- WebDAV ----

    @Test
    fun `OPTIONS 声明 DAV class 2`() {
        start()
        val r = request("OPTIONS", "/")
        assertEquals(200, r.status)
        assertTrue("Finder/资源管理器要看到 class 2 才肯写", r.headers["dav"]!!.contains("2"))
        assertTrue(r.headers["allow"]!!.contains("PROPFIND"))
    }

    @Test
    fun `PROPFIND Depth 1 列出子项`() {
        start()
        val r = request("PROPFIND", "/", listOf("Depth: 1"))
        assertEquals(207, r.status)
        assertTrue(r.text.contains("<D:href>/hello.txt</D:href>"))
        assertTrue("目录 href 必须带尾斜杠", r.text.contains("<D:href>/sub/</D:href>"))
        assertTrue(r.text.contains("<D:getcontentlength>11</D:getcontentlength>"))
        assertTrue(r.text.contains("<D:collection/>"))
    }

    @Test
    fun `PROPFIND Depth 0 只描述自己`() {
        start()
        val r = request("PROPFIND", "/sub/", listOf("Depth: 0"))
        assertEquals(207, r.status)
        assertFalse("Depth 0 不该带出子项", r.text.contains("nested.bin"))
    }

    @Test
    fun `PROPFIND 的 XML 里特殊字符被转义`() {
        File(dir, "a&b<c>.txt").writeText("x")
        start()
        val r = request("PROPFIND", "/", listOf("Depth: 1"))
        assertTrue(r.text.contains("a&amp;b&lt;c&gt;.txt"))
    }

    @Test
    fun `PUT 新建与覆盖分别是 201 和 204`() {
        start(readOnly = false)
        assertEquals(201, request("PUT", "/new.txt", body = "one".toByteArray()).status)
        assertEquals("one", File(dir, "new.txt").readText())
        assertEquals(204, request("PUT", "/new.txt", body = "two".toByteArray()).status)
        assertEquals("two", File(dir, "new.txt").readText())
    }

    @Test
    fun `MKCOL 建目录 重复建答 405`() {
        start(readOnly = false)
        assertEquals(201, request("MKCOL", "/fresh/").status)
        assertTrue(File(dir, "fresh").isDirectory)
        assertEquals(405, request("MKCOL", "/fresh/").status)
    }

    @Test
    fun `DELETE 删文件`() {
        start(readOnly = false)
        assertEquals(204, request("DELETE", "/hello.txt").status)
        assertFalse(File(dir, "hello.txt").exists())
    }

    @Test
    fun `DELETE 删不掉共享根本身`() {
        start(readOnly = false)
        assertEquals(403, request("DELETE", "/").status)
        assertTrue(dir.exists())
    }

    @Test
    fun `MOVE 同目录改名`() {
        start(readOnly = false)
        val r = request("MOVE", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/renamed.txt"))
        assertTrue(r.status in listOf(201, 204))
        assertFalse(File(dir, "hello.txt").exists())
        assertEquals("hello world", File(dir, "renamed.txt").readText())
    }

    @Test
    fun `MOVE 跨目录`() {
        start(readOnly = false)
        val r = request("MOVE", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/sub/moved.txt"))
        assertTrue(r.status in listOf(201, 204))
        assertEquals("hello world", File(dir, "sub/moved.txt").readText())
    }

    @Test
    fun `MOVE 带 Overwrite F 且目标已存在时答 412`() {
        File(dir, "taken.txt").writeText("keep me")
        start(readOnly = false)
        val r = request(
            "MOVE", "/hello.txt",
            listOf("Destination: http://127.0.0.1:$port/taken.txt", "Overwrite: F"),
        )
        assertEquals(412, r.status)
        assertEquals("keep me", File(dir, "taken.txt").readText())
        assertTrue("被拒的 MOVE 不该动源文件", File(dir, "hello.txt").exists())
    }

    @Test
    fun `COPY 留下源文件`() {
        start(readOnly = false)
        val r = request("COPY", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/copy.txt"))
        assertTrue(r.status in listOf(201, 204))
        assertTrue(File(dir, "hello.txt").exists())
        assertEquals("hello world", File(dir, "copy.txt").readText())
    }

    /** 目录的 COPY 要连内容一起复制,且源目录原样留着。 */
    @Test
    fun `COPY 整个目录`() {
        start(readOnly = false)
        val r = request("COPY", "/sub/", listOf("Destination: http://127.0.0.1:$port/sub2/"))
        assertTrue(r.status in listOf(201, 204))
        assertTrue(File(dir, "sub/nested.bin").exists())
        assertTrue(
            File(dir, "sub2/nested.bin").readBytes().contentEquals(File(dir, "sub/nested.bin").readBytes()),
        )
    }

    /** 源与目标是同一个路径:不能糊里糊涂地"复制"成把源删掉。 */
    @Test
    fun `COPY 到自身被拒绝`() {
        start(readOnly = false)
        val r = request("COPY", "/hello.txt", listOf("Destination: http://127.0.0.1:$port/hello.txt"))
        assertEquals(403, r.status)
        assertEquals("hello world", File(dir, "hello.txt").readText())
    }

    // ---- 浏览器上传 ----

    @Test
    fun `multipart 上传落盘`() {
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
    fun `上传的文件名不能带路径`() {
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
        assertFalse("不能写到共享目录之外", File(dir.parentFile, "escaped.txt").exists())
        assertEquals("pwned", File(dir, "escaped.txt").readText())
    }

    // ---- 认证 ----

    @Test
    fun `设了密码就必须带 Basic 认证`() {
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

    // ---- 连接复用 ----

    /**
     * 同一条连接上连发两个请求。这里真正要防的是"上一个响应的正文长度算错"——
     * 那样第二个响应会从错位的地方开始解析,表现为随机的下载损坏。
     */
    @Test
    fun `keep-alive 连发两个请求都正确`() {
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
                assertEquals("第 ${i + 1} 个响应", 200, status)
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

    // ---- 所有来源模式 ----

    /** 起一个「所有来源」的服务(与 [start] 的区别只在 scope)。 */
    private fun startAll() {
        port = ServerSocket(0).use { it.localPort }
        val cfg = ShareConfig(scope = ShareScope.AllSources, readOnly = true, port = port)
        server = HttpServer(port, null, ShareHandler(app, cfg, ShareRoot(app, cfg.scope)))
            .also { it.start() }
    }

    @Test
    fun `所有来源的首页列出各个来源`() {
        startAll()
        val r = request("GET", "/")
        assertEquals(200, r.status)
        assertTrue("应有内部存储", r.text.contains("href=\"/storage/\""))
        assertTrue("应有根目录", r.text.contains("href=\"/root/\""))
    }

    @Test
    fun `所有来源模式下能进到具体来源里`() {
        startAll()
        val r = request("GET", "/storage/")
        assertEquals(200, r.status)
        // 列不动也不该是 500,而是带着原因的页面(真机上"打不开"就毁在看不到原因)
        assertTrue(r.headers["content-type"]!!.startsWith("text/html"))
    }

    @Test
    fun `所有来源的 PROPFIND 列出各来源`() {
        startAll()
        val r = request("PROPFIND", "/", listOf("Depth: 1"))
        assertEquals(207, r.status)
        assertTrue(r.text.contains("<D:href>/storage/</D:href>"))
        assertTrue(r.text.contains("<D:href>/root/</D:href>"))
    }

    @Test
    fun `所有来源模式下未知来源是 404`() {
        startAll()
        assertEquals(404, request("GET", "/nosuchsource/").status)
    }

    @Test
    fun `HEAD 只给头不给正文`() {
        start()
        val r = request("HEAD", "/hello.txt")
        assertEquals(200, r.status)
        assertEquals("11", r.headers["content-length"])
        assertEquals(0, r.body.size)
    }
}
