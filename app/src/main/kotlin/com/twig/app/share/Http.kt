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
 * One request. [body] is already bounded by Content-Length / chunked — reading
 * -1 means the end of this request's body and it will not bleed into the
 * next keep-alive request.
 */
class HttpRequest(
    val method: String,
    /** The decoded path (without the query), always starting with '/'. Used for **addressing**. */
    val path: String,
    /**
     * The as-received path, still percent-escaped. Used for **building links**
     * (HTML href, WebDAV D:href, 301 Location).
     *
     * Keeping both is not redundant: take the decoded [path], splice it into
     * an href, re-encode the child names, and you get a "decoded prefix +
     * encoded last segment" half-baked URL — the moment a directory name
     * contains Chinese or spaces, every link in the subtree breaks (the
     * 2026-08-10 incident). The rule is **addressing uses path, link
     * building uses rawPath**.
     */
    val rawPath: String,
    val query: Map<String, String>,
    /** Request headers; keys are lowercased. */
    val headers: Map<String, String>,
    val body: InputStream,
    /** The `Host:` the client sent; used to build absolute URLs for WebDAV Destination / href. */
    val host: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** WebDAV Depth, defaulting to [def]. "infinity" returns [Int.MAX_VALUE]. */
    fun depth(def: Int = Int.MAX_VALUE): Int = when (header("depth")?.lowercase()) {
        null -> def
        "0" -> 0
        "1" -> 1
        else -> Int.MAX_VALUE
    }
}

/**
 * Response writer. **One request can only produce one response** — repeated
 * calls are silently dropped, so that a branch in the handler that sends
 * and then keeps going cannot splice two responses onto the same connection
 * (which would offset every subsequent keep-alive request).
 */
class HttpResponder(private val out: OutputStream) {

    var responded = false
        private set

    /** Whether the connection can be reused after this response; a length-unknown streaming response forces the connection closed. */
    var keepAlive = true
        private set

    /** HEAD request: still computes Content-Length, but does not write the body. */
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
     * Streaming response. [length] < 0 means the length is unknown — the
     * connection must be closed after writing ([keepAlive] is set to false),
     * and chunked encoding is not used: HTTP/1.0 clients do not understand
     * it, and the small saving in connection reuse here is not worth being
     * "downloadable everywhere".
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
        // For large file downloads the client needs to show progress and resume
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

        /** RFC 1123 date, shared by WebDAV's getlastmodified and HTTP's Date/Last-Modified. */
        fun httpDate(ms: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(ms))
    }
}

/** Request handler; implemented by [ShareHandler] (the HTML UI + WebDAV are both dispatched inside). */
fun interface HttpHandler {
    fun handle(req: HttpRequest, res: HttpResponder)
}

/**
 * A minimal HTTP/1.1 server: one accept thread + one worker thread per
 * connection (cached thread pool).
 *
 * Only the "good enough" parts are implemented: request line / header
 * parsing, Content-Length and chunked bodies, Basic auth, keep-alive, and
 * 100-continue. No TLS (LAN, size-first), and no chunked **responses** (see
 * [HttpResponder.sendStream]).
 *
 * @param onActive Called with true while any request is being handled and
 *   false once all of them are done, so the upper layer can hold a WakeLock
 *   only as needed — the screen going off should not abort a half-finished
 *   transfer, but holding the lock while idle is just battery drain.
 */
class HttpServer(
    private val port: Int,
    private val auth: BasicAuth?,
    private val handler: HttpHandler,
    private val onActive: (Boolean) -> Unit = {},
) {

    /** Basic auth credentials; if `password` is empty the upper layer should not construct one at all. */
    class BasicAuth(val user: String, val password: String) {
        private val expected = "Basic " + android.util.Base64.encodeToString(
            "$user:$password".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )

        fun accepts(headerValue: String?): Boolean {
            val v = headerValue ?: return false
            // Constant-time comparison is not the point (LAN + short
            // password), but at least don't let the length difference give it
            // away at a glance
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
     * Bind the port and start the accept thread. **A failed bind throws
     * immediately** (port taken / disabled by the system), so the caller
     * can show the error to the user on the spot rather than starting a
     * service that nobody can connect to.
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
            // Connection-count cap: a malicious / runaway client must not be
            // able to blow up the thread pool
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

    /** The keep-alive loop on a single connection. */
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
                return // idle timeout: close it normally
            } catch (e: IOException) {
                return // the client went away
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
                return // client disconnected mid-write, this connection is gone
            } catch (e: Throwable) {
                runCatching { res.sendText(500, e.message ?: e::class.java.simpleName) }
            } finally {
                markActive(false)
            }

            // If the body is not fully read (e.g. only half was rejected)
            // it would bleed into the next request — the only option is to
            // close the connection
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

    /** Discard the remaining body so the connection can be reused; if it goes past the cap, it's not worth it — close. */
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

    /** Parse one request; returns null on end-of-stream, [MALFORMED] on format error. */
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

        // 100-continue: curl / some WebDAV clients ask before sending the body;
        // if we don't answer, they just sit there
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
        // Some clients (especially WebDAV's Destination round-trip) send an
        // absolute URI as the request target
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

        /** Sentinel for parse failures, so we don't need a whole new exception type. */
        private val MALFORMED = HttpRequest(
            "", "/", "/", emptyMap(), emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)), "",
        )

        /** Read one line (LF-terminated, swallowing the CR along the way); null on end-of-stream. */
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
                if (buf.size() > 8192) return null // header too long — treat as a broken stream
                buf.write(c)
            }
        }

        /**
         * Percent-decoding for paths. **`URLDecoder.decode` cannot be used** —
         * it is the form-encoding variant, which turns `+` into a space; the
         * plus signs that are common in music / movie file names would be eaten
         * and the file would become unfindable.
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
         * Percent-encoding for a path segment (used to produce href / Location / Destination).
         *
         * Only the RFC 3986 unreserved set is allowed through (`ALPHA DIGIT - . _ ~`);
         * everything else is encoded. The sub-delimiters (`+ , ; = & $ ...`) are
         * technically legal in a path segment, but clients vary in how strictly
         * they decode them (`+` in particular often gets form-decoded into a
         * space); encoding a few extra bytes is worth "works everywhere".
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

        /** Encode a whole path (one segment at a time, '/' preserved). */
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

        /** Query strings use form encoding, where `+` **is** a space. */
        private fun decodeForm(s: String): String = decodePath(s.replace('+', ' '))
    }
}

/** A body stream bounded by Content-Length: once the limit is reached it reports EOF, so it cannot bleed into the next keep-alive request. */
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

/** The `Transfer-Encoding: chunked` body stream (commonly used by WebDAV clients uploading large files). */
private class ChunkedInputStream(private val src: InputStream) : InputStream() {
    private var left = 0L
    private var done = false

    /** The CRLF after the current chunk is still owed (the separator between chunks). */
    private var pendingCrlf = false

    /** Make the next chunk readable; returns false = the whole body is over. */
    private fun nextChunk(): Boolean {
        if (done) return false
        if (left > 0) return true
        if (pendingCrlf) { HttpServer.readLine(src); pendingCrlf = false }
        val line = HttpServer.readLine(src) ?: run { done = true; return false }
        // Chunk size is hex, possibly followed by ";extension params"
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: run { done = true; return false }
        if (size == 0L) {
            // Trailer headers may follow the last chunk; read until the empty line
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
