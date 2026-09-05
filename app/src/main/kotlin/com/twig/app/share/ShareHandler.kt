package com.twig.app.share

import android.content.Context
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.OutputStream

/**
 * Request dispatch for the share service: a single [ShareRoot] simultaneously
 * wears two faces —
 *  - **browsers** talk GET/POST and receive the HTML rendered by [WebUi]
 *    (directory listing + drag-and-drop upload);
 *  - **WebDAV clients** talk the PROPFIND/PUT/MKCOL/MOVE/COPY/DELETE/LOCK set.
 *
 * In read-only mode every method that would change a file is uniformly 403;
 * the check is centralised in [requireWrite] and not scattered across each
 * handler (one miss and "read-only" is no longer watertight).
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
            // PROPPATCH does not write to disk (no place to store metadata),
            // but it must answer 207, not 501: when macOS Finder copies a file
            // it follows up with a PROPPATCH to set mtime; 501 makes it decide
            // the whole copy failed and delete the file it just uploaded.
            "PROPPATCH" -> requireWrite(res) { proppatch(req, res) }
            else -> res.send(405, extra = listOf("Allow: $ALLOW"))
        }
    }

    private inline fun requireWrite(res: HttpResponder, body: () -> Unit) {
        if (cfg.readOnly) res.sendText(403, "Read-only share") else body()
    }

    // ---- Basics ----

    private fun options(res: HttpResponder) {
        res.send(
            200,
            extra = listOf(
                // DAV: 2 = LOCK is supported. macOS Finder and Windows
                // Explorer will only write to a class-2 server, even though
                // the lock itself is purely token (see [lock]).
                "DAV: 1, 2",
                "Allow: $ALLOW",
                "MS-Author-Via: DAV",
            ),
        )
    }

    /** 404 if not found; this also covers "illegal path (contains ..)". */
    private fun target(req: HttpRequest, res: HttpResponder): XFile? {
        val f = root.resolve(req.path)
        if (f == null) res.sendText(404, "Not found")
        return f
    }

    // ---- Read ----

    private fun get(req: HttpRequest, res: HttpResponder) {
        if (root.isVirtualRoot(req.path)) {
            ui.renderSources(res)
            return
        }
        val file = target(req, res) ?: return
        if (file.isDir) {
            // When a directory URL does not end with '/', redirect first;
            // otherwise the page's relative links will point one level up
            if (!req.rawPath.endsWith("/")) {
                res.send(301, extra = listOf("Location: ${req.rawPath}/"))
                return
            }
            ui.renderDir(req, res, file)
            return
        }
        sendFile(req, res, file)
    }

    /** Single-file download, with Range support (player seek bars and downloader resume both need it). */
    private fun sendFile(req: HttpRequest, res: HttpResponder, file: XFile) {
        val fs = FsRegistry.of(file)
        val size = file.size
        val type = Mime.of(file.name)
        val extra = ArrayList<String>()
        if (file.lastModified > 0) extra += "Last-Modified: " + HttpResponder.httpDate(file.lastModified)
        // When the file name contains non-ASCII, use RFC 5987's filename* so
        // the browser does not save the Chinese name as mojibake
        extra += "Content-Disposition: inline; filename*=UTF-8''" + HttpServer.encodeSegment(file.name)

        val range = parseRange(req.header("range"), size)
        if (range == null) {
            // size <= 0 = this source cannot report a length (some archive
            // entries / virtual files): omit Content-Length and close the
            // connection when done (sendStream will turn keep-alive off itself)
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
                // Positional read: SMB / WebDAV / SFTP / FTP all implement an
                // efficient openRandom, and the default implementation is also
                // usable (reopen and skip) — either way it is better than
                // skipping here ourselves
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
     * Parse the Range header. null = no Range (send the whole file); first<0
     * = the range is invalid (416). Only single ranges are supported —
     * multi-range responses would need multipart/byteranges, and no actual
     * client uses that to download files.
     */
    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        val h = header?.trim() ?: return null
        if (!h.startsWith("bytes=")) return null
        if (size <= 0) return null // length unknown, so the range cannot be computed — treat as a full send
        val spec = h.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return -1L to -1L
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        return if (startStr.isEmpty()) {
            // "bytes=-N" = the last N bytes
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
            // The virtual root itself has no XFile; assemble a collection
            // entry by hand, with the sources as children
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
     * A directory's href must end with a trailing slash — plenty of clients
     * use it to tell a collection from a non-collection. [rawPath] must
     * **already be encoded** (from [HttpRequest.rawPath] or encodeSegment).
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
            // The share root itself cannot be deleted — that is the user's
            // chosen share scope, not the share's content
            res.sendText(403, "Cannot delete the share root")
            return
        }
        if (!root.writable(file)) { res.sendText(403, "Source is read-only"); return }
        FsRegistry.of(file).delete(file)
        root.invalidate(file.parentPath)
        res.send(204)
    }

    /**
     * MOVE / COPY. The destination comes from the `Destination:` header (an
     * absolute URL) and must land inside the same share.
     *
     * Same-directory renames go through `rename`; cross-directory operations
     * first try same-source `moveWithin`, and only fall through to
     * [CopyEngine] if that fails — and [CopyEngine] is the cross-source
     * streaming mover anyway, so "from SMB to local" comes at zero cost.
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

        // ★ The self-check must come **before** "overwrite the existing
        // destination": when the source and destination are the same path,
        // the delete below to clear the existing target would actually
        // delete the source file, and the 403 would arrive too late (this
        // is how the 2026-08-10 `ShareServerTest.COPY to self is rejected`
        // test came to be written).
        if (sameParent && newName == src.name) {
            res.sendText(403, "Source and destination are the same"); return
        }

        val existing = root.resolve(destPath)
        if (existing != null) {
            // WebDAV defaults to Overwrite: T; explicit F means the
            // destination already exists, so we must answer 412 rather than
            // overwrite
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
        root.invalidateAll() // either side's directory may have changed; dropping the whole cache is easier than invalidating per-entry
        res.send(if (existing != null) 204 else 201)
    }

    /**
     * Move [src] to `destParent/newName` — the key thing is that the
     * destination name can differ from the source name.
     *
     * `CopyEngine.transfer(listOf(src), destParent, move)` cannot be used
     * directly: its semantics is "copy into this directory, keep the
     * original name", and a rename would have to be a separate follow-up.
     * But WebDAV COPY completely allows the destination to sit next to the
     * source (`COPY /a.txt → /b.txt`), in which case transfer would first
     * copy the file onto itself and then rename the **source** to the new
     * name — the source file would vanish on the spot. The 2026-08-10
     * `ShareServerTest.COPY leaves the source` test stepped right on that.
     *
     * So for files, write straight to the exact destination entry; for
     * directories, create the destination directory first and then move its
     * children across — still going through [CopyEngine] for the
     // cross-source parts (it is the layer that handles recursion and
     // conflicts).
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

    /** Extract this service's internal path from the Destination header; returns null if it points at a different host. */
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
     * A fake lock: just note down the token and agree, no actual mutual exclusion.
     *
     * A real lock would require a timeout-aware lock table and every write
     * method checking the If header, while the use case for this service
     * is "one phone + the user themselves on the LAN" — the collision
     * probability is essentially zero, but the cost would be an entire
     * state machine. However, **DAV class 2 must be advertised**: when
     * macOS Finder / Windows Explorer find that LOCK is not supported, the
     * whole share mounts as read-only and the write feature is wasted.
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

    /** PROPPATCH does not persist, but it must answer 207 with each prop "succeeded" — otherwise the client treats the whole operation as failed. */
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

/** Extension → MIME, asking the system's own table rather than maintaining our own. */
object Mime {
    fun of(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return DEFAULT
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: DEFAULT
    }

    private const val DEFAULT = "application/octet-stream"
}
