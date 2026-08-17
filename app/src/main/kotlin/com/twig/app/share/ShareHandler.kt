package com.twig.app.share

import android.content.Context
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.OutputStream

/**
 * 共享服务的请求分发:同一棵 [ShareRoot] 上同时长着两张脸——
 *  - **浏览器**走 GET/POST,拿到的是 [WebUi] 渲染的 HTML(目录列表 + 拖拽上传);
 *  - **WebDAV 客户端**走 PROPFIND/PUT/MKCOL/MOVE/COPY/DELETE/LOCK 那一套。
 *
 * 只读模式下所有会改动文件的方法统一 403,判定集中在 [requireWrite] 一处,
 * 不散落到各个 handler 里(漏一个就是"只读"没关严)。
 */
class ShareHandler(
    private val ctx: Context,
    private val cfg: ShareConfig,
    private val root: ShareRoot,
) : HttpHandler {

    private val ui = WebUi(ctx, cfg, root)

    override fun handle(req: HttpRequest, res: HttpResponder) {
        when (req.method) {
            "OPTIONS" -> options(res)
            "GET", "HEAD" -> get(req, res)
            "PROPFIND" -> propfind(req, res)
            "POST" -> requireWrite(res) { ui.post(req, res) }
            "PUT" -> requireWrite(res) { put(req, res) }
            "MKCOL" -> requireWrite(res) { mkcol(req, res) }
            "DELETE" -> requireWrite(res) { delete(req, res) }
            "MOVE" -> requireWrite(res) { moveOrCopy(req, res, move = true) }
            "COPY" -> requireWrite(res) { moveOrCopy(req, res, move = false) }
            "LOCK" -> requireWrite(res) { lock(res) }
            "UNLOCK" -> requireWrite(res) { res.send(204) }
            // 属性写不落地(没有可存元数据的地方),但必须答 207 而不是 501:
            // macOS Finder 复制文件时会紧跟一个 PROPPATCH 写 mtime,501 会让它判定整个
            // 复制失败并把刚上传好的文件删掉
            "PROPPATCH" -> requireWrite(res) { proppatch(req, res) }
            else -> res.send(405, extra = listOf("Allow: $ALLOW"))
        }
    }

    private inline fun requireWrite(res: HttpResponder, body: () -> Unit) {
        if (cfg.readOnly) res.sendText(403, "Read-only share") else body()
    }

    // ---- 基础 ----

    private fun options(res: HttpResponder) {
        res.send(
            200,
            extra = listOf(
                // DAV: 2 = 支持 LOCK。macOS Finder 与 Windows 资源管理器都只肯往
                // class 2 的服务器上写东西,哪怕锁本身是走过场的(见 [lock])。
                "DAV: 1, 2",
                "Allow: $ALLOW",
                "MS-Author-Via: DAV",
            ),
        )
    }

    /** 找不到就 404;顺带把"路径非法(含 ..)"也算进去。 */
    private fun target(req: HttpRequest, res: HttpResponder): XFile? {
        val f = root.resolve(req.path)
        if (f == null) res.sendText(404, "Not found")
        return f
    }

    // ---- 读 ----

    private fun get(req: HttpRequest, res: HttpResponder) {
        if (root.isVirtualRoot(req.path)) {
            ui.renderSources(res)
            return
        }
        val file = target(req, res) ?: return
        if (file.isDir) {
            // 目录 URL 不以 '/' 结尾时先重定向:否则页面里的相对链接会挂到上一级去
            if (!req.rawPath.endsWith("/")) {
                res.send(301, extra = listOf("Location: ${req.rawPath}/"))
                return
            }
            ui.renderDir(req, res, file)
            return
        }
        sendFile(req, res, file)
    }

    /** 单个文件下载,支持 Range(播放器拖进度、下载工具续传都要它)。 */
    private fun sendFile(req: HttpRequest, res: HttpResponder, file: XFile) {
        val fs = FsRegistry.of(file)
        val size = file.size
        val type = Mime.of(file.name)
        val extra = ArrayList<String>()
        if (file.lastModified > 0) extra += "Last-Modified: " + HttpResponder.httpDate(file.lastModified)
        // 文件名带非 ASCII 时用 RFC 5987 的 filename*,浏览器才不会把中文名存成乱码
        extra += "Content-Disposition: inline; filename*=UTF-8''" + HttpServer.encodeSegment(file.name)

        val range = parseRange(req.header("range"), size)
        if (range == null) {
            // size <= 0 = 该来源报不出长度(某些压缩包条目/虚拟文件):不发 Content-Length,
            // 写完关连接([HttpResponder.sendStream] 会自己把 keep-alive 关掉)
            res.sendStream(200, type, if (size > 0) size else -1L, extra) { out ->
                fs.openInput(file).use { copy(it, out, Long.MAX_VALUE) }
            }
            return
        }
        if (range.first < 0) {
            res.send(416, extra = listOf("Content-Range: bytes */$size"))
            return
        }
        val (start, endInclusive) = range
        val len = endInclusive - start + 1
        extra += "Content-Range: bytes $start-$endInclusive/$size"
        res.sendStream(206, type, len, extra) { out ->
            if (start == 0L) {
                fs.openInput(file).use { copy(it, out, len) }
            } else {
                // 定位读:SMB/WebDAV/SFTP/FTP 都实现了高效 openRandom,默认实现也能用
                // (重开跳过),总之比自己在这儿 skip 强
                fs.openRandom(file).use { src ->
                    val buf = ByteArray(BUF)
                    var pos = start
                    var left = len
                    while (left > 0) {
                        val n = src.readAt(pos, buf, 0, minOf(left, BUF.toLong()).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        pos += n; left -= n
                    }
                }
            }
        }
    }

    private fun copy(input: java.io.InputStream, out: OutputStream, limit: Long) {
        val buf = ByteArray(BUF)
        var left = limit
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, BUF.toLong()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /**
     * 解析 Range 头。返回 null = 没有 Range(整发);返回 first<0 = 区间不合法(416)。
     * 只支持单区间——多区间要 multipart/byteranges,实际没有客户端拿它下文件。
     */
    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        val h = header?.trim() ?: return null
        if (!h.startsWith("bytes=")) return null
        if (size <= 0) return null // 长度未知就没法算区间,当整发处理
        val spec = h.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return -1L to -1L
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        return if (startStr.isEmpty()) {
            // "bytes=-N" = 最后 N 字节
            val n = endStr.toLongOrNull() ?: return -1L to -1L
            if (n <= 0) -1L to -1L else maxOf(0L, size - n) to size - 1
        } else {
            val start = startStr.toLongOrNull() ?: return -1L to -1L
            if (start >= size) return -1L to -1L
            val end = endStr.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            if (end < start) -1L to -1L else start to end
        }
    }

    // ---- WebDAV ----

    private fun propfind(req: HttpRequest, res: HttpResponder) {
        val depth = req.depth(def = 1)
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">")

        val base = req.rawPath.trimEnd('/')
        if (root.isVirtualRoot(req.path)) {
            // 虚拟根本身没有 XFile,自己拼一条 collection,子项是各来源
            appendResponse(sb, hrefOf(base, dir = true), "/", true, 0, 0)
            if (depth > 0) {
                for (s in root.sources()) {
                    appendResponse(
                        sb, hrefOf("$base/${HttpServer.encodeSegment(s.segment)}", dir = true),
                        s.label, true, 0, 0,
                    )
                }
            }
        } else {
            val file = target(req, res) ?: return
            appendResponse(
                sb, hrefOf(base, dir = file.isDir), file.name,
                file.isDir, file.size, file.lastModified,
            )
            if (depth > 0 && file.isDir) {
                val children = runCatching { root.list(file) }.getOrElse { emptyList() }
                for (c in children) {
                    appendResponse(
                        sb, hrefOf("$base/${HttpServer.encodeSegment(c.name)}", dir = c.isDir), c.name,
                        c.isDir, c.size, c.lastModified,
                    )
                }
            }
        }
        sb.append("\n</D:multistatus>")
        res.send(207, "application/xml; charset=utf-8", sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun appendResponse(
        sb: StringBuilder,
        href: String,
        name: String,
        isDir: Boolean,
        size: Long,
        mtime: Long,
    ) {
        sb.append("\n <D:response>\n  <D:href>").append(xml(href)).append("</D:href>")
        sb.append("\n  <D:propstat>\n   <D:prop>")
        sb.append("\n    <D:displayname>").append(xml(name)).append("</D:displayname>")
        if (isDir) {
            sb.append("\n    <D:resourcetype><D:collection/></D:resourcetype>")
        } else {
            sb.append("\n    <D:resourcetype/>")
            sb.append("\n    <D:getcontentlength>").append(maxOf(0L, size)).append("</D:getcontentlength>")
            sb.append("\n    <D:getcontenttype>").append(xml(Mime.of(name))).append("</D:getcontenttype>")
        }
        if (mtime > 0) {
            sb.append("\n    <D:getlastmodified>").append(HttpResponder.httpDate(mtime))
                .append("</D:getlastmodified>")
        }
        sb.append("\n   </D:prop>\n   <D:status>HTTP/1.1 200 OK</D:status>\n  </D:propstat>\n </D:response>")
    }

    /**
     * 目录的 href 必须带尾斜杠,不少客户端靠它判断是不是集合。
     * [rawPath] 必须**已经是编码过的**(来自 [HttpRequest.rawPath] 或 encodeSegment)。
     */
    private fun hrefOf(rawPath: String, dir: Boolean): String {
        val p = "/" + rawPath.trim('/')
        return if (dir && !p.endsWith("/")) "$p/" else p
    }

    private fun put(req: HttpRequest, res: HttpResponder) {
        val segs = ShareRoot.segments(req.path)
        if (segs.isNullOrEmpty()) { res.sendText(403, "Cannot write here"); return }
        val parentPath = "/" + segs.dropLast(1).joinToString("/")
        val parent = root.resolve(parentPath)
        if (parent == null || !parent.isDir) { res.sendText(409, "Parent not found"); return }
        if (!root.writable(parent)) { res.sendText(403, "Source is read-only"); return }

        val fs = FsRegistry.of(parent)
        val existed = root.resolve(req.path) != null
        val dest = fs.createFile(parent, segs.last())
        fs.openOutput(dest, append = false).use { out ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = req.body.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        root.invalidate(parent.path)
        res.send(if (existed) 204 else 201)
    }

    private fun mkcol(req: HttpRequest, res: HttpResponder) {
        val segs = ShareRoot.segments(req.path)
        if (segs.isNullOrEmpty()) { res.sendText(403, "Cannot create here"); return }
        if (root.resolve(req.path) != null) { res.sendText(405, "Already exists"); return }
        val parentPath = "/" + segs.dropLast(1).joinToString("/")
        val parent = root.resolve(parentPath)
        if (parent == null || !parent.isDir) { res.sendText(409, "Parent not found"); return }
        if (!root.writable(parent)) { res.sendText(403, "Source is read-only"); return }
        FsRegistry.of(parent).mkdir(parent, segs.last())
        root.invalidate(parent.path)
        res.send(201)
    }

    private fun delete(req: HttpRequest, res: HttpResponder) {
        val file = target(req, res) ?: return
        if (ShareRoot.segments(req.path).isNullOrEmpty()) {
            // 共享根自己不能删——那是用户选的共享范围,不是共享内容
            res.sendText(403, "Cannot delete the share root")
            return
        }
        if (!root.writable(file)) { res.sendText(403, "Source is read-only"); return }
        FsRegistry.of(file).delete(file)
        root.invalidate(file.parentPath)
        res.send(204)
    }

    /**
     * MOVE / COPY。目标由 `Destination:` 头给(绝对 URL),必须落在同一个共享里。
     *
     * 同目录改名走 `rename`,跨目录先试同来源的 `moveWithin`,都不行才落到
     * [CopyEngine]——它本来就是跨来源流式搬运的那条路,"从 SMB 移到本地"这种组合零成本。
     */
    private fun moveOrCopy(req: HttpRequest, res: HttpResponder, move: Boolean) {
        val src = target(req, res) ?: return
        if (ShareRoot.segments(req.path).isNullOrEmpty()) {
            res.sendText(403, "Cannot move the share root")
            return
        }
        val destPath = destinationPath(req) ?: run { res.sendText(400, "Bad Destination"); return }
        val destSegs = ShareRoot.segments(destPath)
        if (destSegs.isNullOrEmpty()) { res.sendText(403, "Bad Destination"); return }

        val destParent = root.resolve("/" + destSegs.dropLast(1).joinToString("/"))
        if (destParent == null || !destParent.isDir) { res.sendText(409, "Destination parent not found"); return }
        if (!root.writable(destParent) || (move && !root.writable(src))) {
            res.sendText(403, "Source is read-only"); return
        }

        val newName = destSegs.last()
        val sameParent = src.scheme == destParent.scheme && src.parentPath == destParent.path

        // ★ 自身检查必须排在"覆盖已存在目标"**前面**:源与目标是同一个路径时,
        // 下面那句为覆盖做的 delete 删掉的正是源文件,等走到后面再判 403 已经晚了
        // (2026-08-10 `ShareServerTest.COPY 到自身被拒绝` 就是这么暴露出来的)。
        if (sameParent && newName == src.name) {
            res.sendText(403, "Source and destination are the same"); return
        }

        val existing = root.resolve(destPath)
        if (existing != null) {
            // WebDAV 默认 Overwrite: T;显式 F 时目标已存在必须答 412 而不是覆盖
            if (req.header("overwrite")?.trim()?.uppercase() == "F") {
                res.sendText(412, "Destination exists"); return
            }
            runCatching { FsRegistry.of(existing).delete(existing) }
        }

        when {
            move && sameParent -> FsRegistry.of(src).rename(src, newName)
            move && src.scheme == destParent.scheme &&
                FsRegistry.of(src).moveWithin(src, destParent, newName) -> Unit
            else -> transferAs(src, destParent, newName, move)
        }
        root.invalidateAll() // 两端目录都可能变,逐个失效不如整棵丢掉省心
        res.send(if (existing != null) 204 else 201)
    }

    /**
     * 把 [src] 搬到 `destParent/newName` —— 关键是**目标名可以和源名不同**。
     *
     * 不能直接用 `CopyEngine.transfer(listOf(src), destParent, move)`:那个接口的语义是
     * "复制进这个目录、保持原名",改名只能事后补一次 rename。而 WebDAV 的 COPY 完全允许
     * 目标就在源所在的目录里(`COPY /a.txt → /b.txt`),那时 transfer 会先把文件复制到
     * 它自己身上,再把**源**改成新名字——源文件当场消失。2026-08-10 的
     * `ShareServerTest.COPY 留下源文件` 就是踩在这儿。
     *
     * 所以文件直接流式写到确切的目标条目上;目录先建出目标目录、再把子项整体搬进去,
     * 跨来源的部分仍然交给 [CopyEngine](它才是处理递归与冲突的那一层)。
     */
    private fun transferAs(src: XFile, destParent: XFile, newName: String, move: Boolean) {
        val srcFs = FsRegistry.of(src)
        val destFs = FsRegistry.of(destParent)
        if (!src.isDir) {
            val dest = destFs.createFile(destParent, newName)
            srcFs.openInput(src).use { input ->
                destFs.openOutput(dest, false).use { out ->
                    CopyEngine.pipe(input, out, { false })
                }
            }
            if (move) srcFs.delete(src)
            return
        }
        val newDir = destFs.mkdir(destParent, newName)
        val children = srcFs.list(src)
        if (children.isNotEmpty()) {
            CopyEngine.transfer(
                children, newDir, move,
                resolver = { _, _ -> CopyEngine.Decision.OVERWRITE },
            )
        }
        if (move) srcFs.delete(src)
    }

    /** 从 Destination 头里取出本服务内部的路径;指向别的主机时返回 null。 */
    private fun destinationPath(req: HttpRequest): String? {
        val raw = req.header("destination")?.trim() ?: return null
        val path = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            runCatching { java.net.URI(raw).rawPath }.getOrNull() ?: return null
        } else {
            raw.substringBefore('?')
        }
        return HttpServer.decodePath(path)
    }

    /**
     * 假锁:记下 token 就答应,不做任何互斥。
     *
     * 真做锁要维护带超时的锁表、每个写方法校验 If 头,而这个服务的使用场景是
     * "一台手机 + 局域网里的自己"——冲突概率约等于零,代价却是一整套状态机。
     * 但 **DAV class 2 不能不声明**:macOS Finder / Windows 资源管理器发现不支持 LOCK
     * 就整个以只读方式挂载,写入功能等于白做。
     */
    private fun lock(res: HttpResponder) {
        val token = "opaquelocktoken:twig-" + java.util.UUID.randomUUID()
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:prop xmlns:D="DAV:">
              <D:lockdiscovery>
                <D:activelock>
                  <D:locktype><D:write/></D:locktype>
                  <D:lockscope><D:exclusive/></D:lockscope>
                  <D:depth>infinity</D:depth>
                  <D:owner>twig</D:owner>
                  <D:timeout>Second-3600</D:timeout>
                  <D:locktoken><D:href>$token</D:href></D:locktoken>
                </D:activelock>
              </D:lockdiscovery>
            </D:prop>
        """.trimIndent()
        res.send(
            200, "application/xml; charset=utf-8", body.toByteArray(Charsets.UTF_8),
            listOf("Lock-Token: <$token>"),
        )
    }

    /** 属性写不落地,但要按 207 逐条答"成功",否则客户端会判定整个操作失败。 */
    private fun proppatch(req: HttpRequest, res: HttpResponder) {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>${xml(hrefOf(req.rawPath, dir = false))}</D:href>
                <D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        res.send(207, "application/xml; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val BUF = 64 * 1024
        private const val ALLOW =
            "OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, MOVE, COPY, LOCK, UNLOCK"

        fun xml(s: String): String = buildString(s.length + 16) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

/** 扩展名 → MIME,直接问系统那份表,不自己维护。 */
object Mime {
    fun of(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return DEFAULT
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: DEFAULT
    }

    private const val DEFAULT = "application/octet-stream"
}
