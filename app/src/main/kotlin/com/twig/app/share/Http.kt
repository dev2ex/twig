package com.twig.app.share

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次请求。[body] 已按 Content-Length / chunked 限长,读到 -1 就是这次请求的正文结束,
 * 不会串到下一个 keep-alive 请求上去。
 */
class HttpRequest(
    val method: String,
    /** 解码后的路径(不含 query),永远以 '/' 开头。用于**寻址**。 */
    val path: String,
    /**
     * 原样的、仍带百分号转义的路径。用于**生成链接**(HTML 的 href、WebDAV 的
     * D:href、301 的 Location)。
     *
     * 两个都留着不是冗余:拿解码后的 [path] 去拼 href,再把子项名字编码一遍,就成了
     * "解码的前缀 + 编码的末段"这种半生不熟的 URL——目录名一带中文或空格,整棵子树
     * 的链接就全断了(2026-08-10 的现场)。规矩是**寻址用 path,拼链接用 rawPath**。
     */
    val rawPath: String,
    val query: Map<String, String>,
    /** 请求头,key 一律小写。 */
    val headers: Map<String, String>,
    val body: InputStream,
    /** 客户端看到的 `Host:`,拼 WebDAV 的 Destination / href 绝对 URL 时要用。 */
    val host: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** WebDAV 的 Depth,缺省按 [def]。"infinity" 返回 [Int.MAX_VALUE]。 */
    fun depth(def: Int = Int.MAX_VALUE): Int = when (header("depth")?.lowercase()) {
        null -> def
        "0" -> 0
        "1" -> 1
        else -> Int.MAX_VALUE
    }
}

/**
 * 响应写出器。**一次请求只能发一个响应**——重复调用会被忽略,免得处理逻辑里
 * 某条分支发完又往下走,把两份响应串到同一条连接上(那会让后续 keep-alive 请求全部错位)。
 */
class HttpResponder(private val out: OutputStream) {

    var responded = false
        private set

    /** 这次响应之后连接还能不能复用;发了长度未知的流式响应就只能关掉。 */
    var keepAlive = true
        private set

    /** HEAD 请求:照常算 Content-Length,但不写正文。 */
    var headOnly = false

    fun send(
        code: Int,
        contentType: String? = null,
        body: ByteArray = EMPTY,
        extra: List<String> = emptyList(),
    ) {
        if (responded) return
        responded = true
        writeHead(code, contentType, body.size.toLong(), extra)
        if (!headOnly && body.isNotEmpty()) out.write(body)
        out.flush()
    }

    fun sendText(code: Int, text: String, contentType: String = "text/plain; charset=utf-8") =
        send(code, contentType, text.toByteArray(Charsets.UTF_8))

    /**
     * 流式响应。[length] < 0 表示长度未知——那就只能写完关连接([keepAlive] 置 false),
     * 不上 chunked:HTTP/1.0 客户端不认它,而这里省下的那点复用收益远不如"到处都能下"。
     */
    fun sendStream(
        code: Int,
        contentType: String?,
        length: Long,
        extra: List<String> = emptyList(),
        writer: (OutputStream) -> Unit,
    ) {
        if (responded) return
        responded = true
        if (length < 0) keepAlive = false
        writeHead(code, contentType, length, extra)
        if (!headOnly) writer(out)
        out.flush()
    }

    private fun writeHead(code: Int, contentType: String?, length: Long, extra: List<String>) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n")
        sb.append("Server: Twig\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        // 下载大文件时客户端要能显示进度、也要能断点续传
        sb.append("Accept-Ranges: bytes\r\n")
        for (h in extra) sb.append(h).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun reason(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            206 -> "Partial Content"
            207 -> "Multi-Status"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            412 -> "Precondition Failed"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            507 -> "Insufficient Storage"
            else -> "Status"
        }

        /** RFC 1123 日期,WebDAV 的 getlastmodified 与 HTTP 的 Date/Last-Modified 共用。 */
        fun httpDate(ms: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(ms))
    }
}

/** 请求处理器;由 [ShareHandler] 实现(HTML 界面 + WebDAV 两套都在里面分发)。 */
fun interface HttpHandler {
    fun handle(req: HttpRequest, res: HttpResponder)
}

/**
 * 极小 HTTP/1.1 服务器:一条 accept 线程 + 每连接一个工作线程(缓存线程池)。
 *
 * 只实现"够用"的那部分:请求行/头解析、Content-Length 与 chunked 正文、Basic 认证、
 * keep-alive、100-continue。没有 TLS(局域网内、体积优先),没有 chunked **响应**
 * (见 [HttpResponder.sendStream])。
 *
 * @param onActive 有请求正在处理时回调 true、全部处理完回调 false,供上层按需持有
 *   WakeLock——传输期间锁屏不该把传到一半的文件掐掉,而空闲时死攥着锁只是耗电。
 */
class HttpServer(
    private val port: Int,
    private val auth: BasicAuth?,
    private val handler: HttpHandler,
    private val onActive: (Boolean) -> Unit = {},
) {

    /** Basic 认证凭据;`password` 为空时上层根本不该构造它。 */
    class BasicAuth(val user: String, val password: String) {
        private val expected = "Basic " + android.util.Base64.encodeToString(
            "$user:$password".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )

        fun accepts(headerValue: String?): Boolean {
            val v = headerValue ?: return false
            // 常量时间比较不是重点(局域网 + 短口令),但至少别被长度差异一眼看穿
            return v.trim() == expected
        }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    private val inFlight = AtomicInteger(0)
    private val connections = AtomicInteger(0)

    private val pool = Executors.newCachedThreadPool(
        object : ThreadFactory {
            private val n = AtomicInteger(0)
            override fun newThread(r: Runnable) = Thread(r, "twig-http-${n.incrementAndGet()}")
                .apply { isDaemon = true }
        },
    )

    /**
     * 绑定端口并起 accept 线程。**绑定失败直接抛**(端口被占/被系统禁用),
     * 让调用方当场把错误摆给用户,而不是起一个连不上的服务。
     */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port), 64)
        server = s
        running = true
        Thread({ acceptLoop(s) }, "twig-http-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val sock = try {
                s.accept()
            } catch (e: IOException) {
                if (running) continue else break
            }
            // 连接数兜底:恶意/失控的客户端不该把线程池撑爆
            if (connections.get() >= MAX_CONNECTIONS) {
                runCatching { sock.close() }
                continue
            }
            connections.incrementAndGet()
            runCatching {
                pool.execute {
                    try {
                        serve(sock)
                    } finally {
                        connections.decrementAndGet()
                        runCatching { sock.close() }
                    }
                }
            }.onFailure { connections.decrementAndGet(); runCatching { sock.close() } }
        }
    }

    /** 一条连接上的 keep-alive 循环。 */
    private fun serve(sock: Socket) {
        sock.soTimeout = IDLE_TIMEOUT_MS
        sock.tcpNoDelay = true
        val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
        val output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
        while (running && !sock.isClosed) {
            val res = HttpResponder(output)
            val req = try {
                readRequest(input, sock)
            } catch (e: SocketTimeoutException) {
                return // 空闲超时:正常关掉
            } catch (e: IOException) {
                return // 客户端走了
            } ?: return
            if (req === MALFORMED) {
                runCatching { res.sendText(400, "Bad Request") }
                return
            }

            markActive(true)
            try {
                res.headOnly = req.method == "HEAD"
                if (auth != null && !auth.accepts(req.header("authorization"))) {
                    res.send(
                        401, "text/plain; charset=utf-8", "Unauthorized".toByteArray(),
                        listOf("WWW-Authenticate: Basic realm=\"Twig\", charset=\"UTF-8\""),
                    )
                } else {
                    handler.handle(req, res)
                }
                if (!res.responded) res.sendText(500, "No response")
            } catch (e: IOException) {
                return // 写到一半客户端断了,这条连接没救了
            } catch (e: Throwable) {
                runCatching { res.sendText(500, e.message ?: e::class.java.simpleName) }
            } finally {
                markActive(false)
            }

            // 正文没读完(比如只读了一半就拒了)会串到下一个请求上,只能关连接
            val drained = runCatching { drain(req.body) }.getOrDefault(false)
            val wantsClose = req.header("connection")?.lowercase()?.contains("close") == true
            if (!res.keepAlive || wantsClose || !drained) return
        }
    }

    private fun markActive(on: Boolean) {
        val n = if (on) inFlight.incrementAndGet() else inFlight.decrementAndGet()
        if (on && n == 1) onActive(true)
        if (!on && n == 0) onActive(false)
    }

    /** 把剩余正文丢掉,好复用连接;超过上限就不值得了,直接关。 */
    private fun drain(body: InputStream): Boolean {
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = body.read(buf)
            if (n < 0) return true
            total += n
            if (total > MAX_DRAIN) return false
        }
    }

    /** 解析一个请求;流末尾返回 null,格式错误返回 [MALFORMED]。 */
    private fun readRequest(input: BufferedInputStream, sock: Socket): HttpRequest? {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) return MALFORMED
        val parts = line.split(' ')
        if (parts.size < 2) return MALFORMED
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return MALFORMED
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i <= 0) continue
            headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }

        // 100-continue:curl/部分 WebDAV 客户端会先问再发正文,不答它就一直等着
        if (headers["expect"]?.lowercase()?.contains("100-continue") == true) {
            sock.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            sock.getOutputStream().flush()
        }

        val body: InputStream = when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true ->
                ChunkedInputStream(input)
            else -> LimitedInputStream(input, headers["content-length"]?.trim()?.toLongOrNull() ?: 0L)
        }

        val qIdx = target.indexOf('?')
        val rawPath = if (qIdx < 0) target else target.substring(0, qIdx)
        val query = if (qIdx < 0) emptyMap() else parseQuery(target.substring(qIdx + 1))
        // 有的客户端(尤其 WebDAV 的 Destination 回环)发绝对 URI 形式的请求目标
        val pathOnly = if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
            runCatching { java.net.URI(rawPath).rawPath }.getOrNull() ?: rawPath
        } else {
            rawPath
        }

        return HttpRequest(
            method = method,
            path = decodePath(pathOnly).ifEmpty { "/" },
            rawPath = pathOnly.ifEmpty { "/" },
            query = query,
            headers = headers,
            body = body,
            host = headers["host"] ?: "${sock.localAddress?.hostAddress ?: "localhost"}:$port",
        )
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 30_000
        private const val MAX_CONNECTIONS = 48
        private const val MAX_DRAIN = 1L shl 20

        /** 解析失败的哨兵,免得再造一个异常类型。 */
        private val MALFORMED = HttpRequest(
            "", "/", "/", emptyMap(), emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)), "",
        )

        /** 读一行(以 LF 结尾,顺带吃掉 CR);流末尾返回 null。 */
        fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val c = input.read()
                if (c < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (c == '\n'.code) {
                    var s = buf.toString("ISO-8859-1")
                    if (s.endsWith("\r")) s = s.dropLast(1)
                    return s
                }
                if (buf.size() > 8192) return null // 头太长,当断流处理
                buf.write(c)
            }
        }

        /**
         * 路径的百分号解码。**不能用 `URLDecoder.decode`**——它是 form 编码那套,
         * 会把 `+` 当空格;文件名里的加号(常见于音乐/影片命名)会被吃掉,变成找不到文件。
         */
        fun decodePath(s: String): String {
            if ('%' !in s) return s
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3; continue }
                }
                out.write(c.code)
                i++
            }
            return out.toString("UTF-8")
        }

        /**
         * 路径段的百分号编码(生成 href / Location / Destination 用)。
         *
         * 只放过 RFC 3986 的 unreserved(`ALPHA DIGIT - . _ ~`),别的一律编码。
         * 子分隔符(`+ , ; = & $ ...`)在路径段里其实合法,但各家客户端对它们的解码
         * 宽严不一(`+` 尤其容易被按 form 编码当成空格),多编几个字节换"到处都对"。
         */
        fun encodeSegment(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xFF
                val c = v.toChar()
                val unreserved = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "-._~"
                if (unreserved) sb.append(c) else sb.append('%').append("%02X".format(v))
            }
            return sb.toString()
        }

        /** 整条路径编码(逐段编,'/' 保留)。 */
        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { encodeSegment(it) }

        fun parseQuery(q: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (kv in q.split('&')) {
                if (kv.isEmpty()) continue
                val i = kv.indexOf('=')
                val k = if (i < 0) kv else kv.substring(0, i)
                val v = if (i < 0) "" else kv.substring(i + 1)
                out[decodeForm(k)] = decodeForm(v)
            }
            return out
        }

        /** query 用的是 form 编码,这里 `+` **就是**空格。 */
        private fun decodeForm(s: String): String = decodePath(s.replace('+', ' '))
    }
}

/** 按 Content-Length 限长的正文流:读满就报末尾,不会串进下一个 keep-alive 请求。 */
private class LimitedInputStream(private val src: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val c = src.read()
        if (c >= 0) left--
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = src.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun available(): Int = minOf(src.available().toLong(), left).toInt()
}

/** `Transfer-Encoding: chunked` 的正文流(WebDAV 客户端上传大文件时常用)。 */
private class ChunkedInputStream(private val src: InputStream) : InputStream() {
    private var left = 0L
    private var done = false

    /** 当前块读完后还欠一个 CRLF 没吃掉(块与块之间的分隔符)。 */
    private var pendingCrlf = false

    /** 让下一块可读;返回 false = 整个正文结束。 */
    private fun nextChunk(): Boolean {
        if (done) return false
        if (left > 0) return true
        if (pendingCrlf) { HttpServer.readLine(src); pendingCrlf = false }
        val line = HttpServer.readLine(src) ?: run { done = true; return false }
        // 块大小是十六进制,后面可能跟 ";扩展参数"
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: run { done = true; return false }
        if (size == 0L) {
            // 末块之后可能还有 trailer 头,一路读到空行为止
            while (true) {
                val t = HttpServer.readLine(src) ?: break
                if (t.isEmpty()) break
            }
            done = true
            return false
        }
        left = size
        return true
    }

    private fun consumed(n: Int) {
        left -= n
        if (left == 0L) pendingCrlf = true
    }

    override fun read(): Int {
        if (!nextChunk()) return -1
        val c = src.read()
        if (c >= 0) consumed(1)
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!nextChunk()) return -1
        val n = src.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) consumed(n)
        return n
    }
}
