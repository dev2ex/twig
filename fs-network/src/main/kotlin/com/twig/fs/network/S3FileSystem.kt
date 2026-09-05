package com.twig.fs.network

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Configuration for an S3 endpoint.
 *
 * When [bucket] is empty, the root lists every bucket owned by the account; when
 * it is filled in, the root is that bucket directly — many access credentials
 * are only authorized for a single bucket and lack `ListAllMyBuckets`, in which
 * case leaving it empty would open with an AccessDenied.
 *
 * [bucket] may also carry a key prefix (`photos-bucket/2026/raw`), which roots the
 * connection at that "directory": a bucket name and a start prefix are one path, so
 * they share one field.
 */
data class S3Config(
    /** For example, `https://s3.us-east-1.amazonaws.com` or `http://192.168.1.9:9000`. */
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "us-east-1",
    val bucket: String = "",
    /** true = `endpoint/bucket/key`; false = `bucket.endpoint/key` (the AWS canonical form). */
    val pathStyle: Boolean = true,
)

/**
 * S3 filesystem: OkHttp + hand-written SigV4 signing + hand-written XML parsing,
 * zero new dependencies.
 *
 * Why the AWS SDK is not used is explained in [Sigv4] — the s3 module alone pulls
 * in over ten MB of transitive dependencies, larger than the entire APK, while
 * we only need GET/PUT/DELETE REST calls. The same implementation also covers
 * AWS S3, MinIO, Cloudflare R2, Aliyun OSS, Tencent COS, Backblaze B2 and any
 * other S3-compatible service.
 *
 * **S3 has no real directories**; the directories here are faked from two pieces:
 *  - When listing, we pass `delimiter=/`; the server folds objects sharing a
 *    prefix into `CommonPrefixes` = subdirectories.
 *  - [mkdir] writes an empty object whose key ends with `/` as a placeholder,
 *    so empty directories can show up at all (relying on CommonPrefixes alone
 *    means directories with no objects simply do not exist).
 *  When listing, those placeholders are filtered out, so they do not surface as
 *    a 0-byte odd file.
 */
class S3FileSystem(
    private val config: S3Config,
    override val scheme: String = SCHEME,
) : FileSystem {

    /** The bucket name alone — the first segment of [S3Config.bucket], which may carry a prefix. */
    private val bucket = config.bucket.trim('/').substringBefore('/')

    /**
     * The key prefix the root sits at (the rest of [S3Config.bucket]); "" = the bucket
     * root. Named apart from [base], which is the endpoint URL.
     */
    private val keyBase = config.bucket.trim('/').substringAfter('/', "")

    override val displayName: String =
        "S3 (" + config.endpoint.substringAfter("://").trimEnd('/') +
            (if (bucket.isEmpty()) "" else "/$bucket") +
            (if (keyBase.isEmpty()) "" else "/$keyBase") + ")"

    /** Timeout values mirror [WebDavFileSystem]: short connect, generous read/write (large file transfers cannot be measured in 10-second units). */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = runCatching { config.endpoint.trimEnd('/').toHttpUrl() }
        .getOrElse { throw FsException("Bad S3 endpoint: ${config.endpoint}", it) }

    // ---- path ↔ (bucket, key) ----

    /** An empty [key] means the bucket root. */
    private data class Loc(val bucket: String, val key: String)

    /**
     * Splits [XFile.path] into bucket and key; returns null to mean the
     * "bucket list" layer (only present when [S3Config.bucket] is empty).
     * The prefix the connection is rooted at is prepended here, in the one place every
     * key passes through — everything that builds an [XFile] keeps the visible path.
     */
    private fun locOf(path: String): Loc? {
        val p = listOf(keyBase, path.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
        if (bucket.isNotEmpty()) return Loc(bucket, p)
        if (p.isEmpty()) return null
        val i = p.indexOf('/')
        return if (i < 0) Loc(p, "") else Loc(p.substring(0, i), p.substring(i + 1))
    }

    private fun loc(file: XFile): Loc =
        locOf(file.path) ?: throw FsException("Not inside a bucket: ${file.path}")

    /** The object prefix for a directory: empty for the bucket root, otherwise `key/`. */
    private fun prefixOf(l: Loc): String = if (l.key.isEmpty()) "" else l.key.trimEnd('/') + "/"

    private fun childPath(dirPath: String, name: String): String =
        if (dirPath.endsWith("/")) "$dirPath$name" else "$dirPath/$name"

    // ---- FileSystem ----

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    /** Same as FTP/WebDAV: no stat, plain path mapping (use [list] to determine type, see the resolve pitfall in CLAUDE.md). */
    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> {
        val l = locOf(dir.path) ?: return listBuckets()
        val prefix = prefixOf(l)
        val out = ArrayList<XFile>()
        var token: String? = null
        do {
            val q = ArrayList<Pair<String, String>>()
            q += "list-type" to "2"
            q += "delimiter" to "/"
            // Names may contain bytes that are illegal in XML (e.g. control chars), so let the server encode them before returning
            q += "encoding-type" to "url"
            if (prefix.isNotEmpty()) q += "prefix" to prefix
            token?.let { q += "continuation-token" to it }

            val doc = xml(call("GET", l.bucket, "", q), "ListObjectsV2")
            for (e in doc.byTag("CommonPrefixes")) {
                val p = decodeKey(e.text("Prefix") ?: continue).trimEnd('/')
                val name = p.substringAfterLast('/')
                if (name.isEmpty()) continue
                out += XFile(scheme, childPath(dir.path, name), isDir = true)
            }
            for (e in doc.byTag("Contents")) {
                val key = decodeKey(e.text("Key") ?: continue)
                // Directory placeholder (the empty object written by mkdir), plus the prefix entry for the bucket root itself
                if (key == prefix || key.endsWith("/")) continue
                val name = key.removePrefix(prefix)
                if (name.isEmpty() || '/' in name) continue
                out += XFile(
                    scheme = scheme,
                    path = childPath(dir.path, name),
                    isDir = false,
                    size = e.text("Size")?.toLongOrNull() ?: 0L,
                    lastModified = parseIso(e.text("LastModified")),
                )
            }
            token = doc.first("IsTruncated")?.textContent?.trim()?.toBoolean()
                ?.takeIf { it }
                ?.let { doc.first("NextContinuationToken")?.textContent?.trim() }
        } while (token != null)

        out.sortWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
        return out
    }

    private fun listBuckets(): List<XFile> {
        val doc = xml(call("GET", null, ""), "ListBuckets")
        return doc.byTag("Bucket").mapNotNull { e ->
            val name = e.text("Name") ?: return@mapNotNull null
            XFile(scheme, "/$name", isDir = true, lastModified = parseIso(e.text("CreationDate")))
        }.sortedBy { it.name.lowercase() }
    }

    override fun openInput(file: XFile): InputStream {
        val l = loc(file)
        val resp = call("GET", l.bucket, l.key)
        val body = resp.body ?: run { resp.close(); throw FsException("GET returned no body") }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try { super.close() } finally { resp.close() }
            }
        }
    }

    override fun randomAccessEfficient(): Boolean = true // HTTP Range positioned reads

    override fun openRandom(file: XFile): RandomSource {
        val l = loc(file)
        return HttpRangeSource(file.size) { position ->
            // S3 responds with 416 when the position is out of range; HttpRangeSource recognises this code, so we skip the checked call() here
            rawCall("GET", l.bucket, l.key, headers = mapOf("Range" to "bytes=$position-"))
        }
    }

    /**
     * PUT takes effect atomically: it is either the old object or the new one —
     * no in-between state to read. Multipart uploads work the same way (nothing
     * is visible until Complete), so an overwrite itself is atomic — text editors
     * therefore avoid the "write temp file → delete → rename" dance.
     */
    override fun atomicOverwrite(): Boolean = true

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        if (append) throw FsException("S3 objects cannot be appended to")
        val l = loc(file)
        if (l.key.isEmpty()) throw FsException("Cannot write to a bucket root")
        return S3Out(l.bucket, l.key)
    }

    override fun mkdir(parent: XFile, name: String): XFile {
        val l = locOf(childPath(parent.path, name))
            ?: throw FsException("Cannot create a directory above bucket level")
        if (l.key.isEmpty()) {
            createBucket(l.bucket)
        } else {
            // Empty-directory placeholder: S3 has no directories, so without writing this a freshly created empty directory disappears the next time we list it
            call("PUT", l.bucket, l.key.trimEnd('/') + "/", body = EMPTY_BODY, payloadSha = Sigv4.EMPTY_SHA256)
                .close()
        }
        return XFile(scheme, childPath(parent.path, name), isDir = true)
    }

    private fun createBucket(bucket: String) {
        // us-east-1 is the default region; sending it a LocationConstraint actually makes AWS reject the request (InvalidLocationConstraint)
        val bytes = if (config.region == "us-east-1") ByteArray(0) else (
            "<CreateBucketConfiguration><LocationConstraint>${config.region}</LocationConstraint>" +
                "</CreateBucketConfiguration>"
            ).toByteArray()
        call(
            "PUT", bucket, "",
            body = bytes.toRequestBody(if (bytes.isEmpty()) null else XML_TYPE),
            payloadSha = Sigv4.sha256Hex(bytes),
        ).close()
    }

    override fun delete(file: XFile) {
        val l = locOf(file.path) ?: throw FsException("Cannot delete the bucket list")
        // When the config locks onto a single bucket, the "root" *is* that bucket
        // — deleting it clears the entire connection, but the user only sees
        // the one row they tapped delete on. We do not allow that path.
        if (l.key.isEmpty() && bucket.isNotEmpty()) throw FsException("Cannot delete the bucket root")
        if (!file.isDir) {
            call("DELETE", l.bucket, l.key).close()
            return
        }
        // A directory = a batch of objects sharing a prefix; delete them one by one
        // (batch DELETE requires Content-MD5, and the various compatible
        // implementations have spotty support for it — not worth the risk)
        for (key in allKeys(l.bucket, prefixOf(l))) call("DELETE", l.bucket, key).close()
        if (l.key.isEmpty()) call("DELETE", l.bucket, "").close() // the bucket itself
    }

    /** Every object key under a prefix (no delimiter, i.e. recursive). */
    private fun allKeys(bucket: String, prefix: String): List<String> {
        val out = ArrayList<String>()
        var token: String? = null
        do {
            val q = ArrayList<Pair<String, String>>()
            q += "list-type" to "2"
            q += "encoding-type" to "url"
            if (prefix.isNotEmpty()) q += "prefix" to prefix
            token?.let { q += "continuation-token" to it }
            val doc = xml(call("GET", bucket, "", q), "ListObjectsV2")
            doc.byTag("Contents").forEach { e -> e.text("Key")?.let { out += decodeKey(it) } }
            token = doc.first("IsTruncated")?.textContent?.trim()?.toBoolean()
                ?.takeIf { it }
                ?.let { doc.first("NextContinuationToken")?.textContent?.trim() }
        } while (token != null)
        return out
    }

    override fun rename(file: XFile, newName: String): XFile {
        val to = XFile(scheme, childPath(file.parentPath, newName), file.isDir)
        if (exists(to)) throw FsException("Target already exists: $newName")
        transfer(file, to, move = true)
        return to
    }

    /**
     * Intra-endpoint moves go through server-side CopyObject + DELETE: no traffic
     * through the phone; even multi-tens-of-GB objects are one request
     * (otherwise [com.twig.core.CopyEngine] faithfully downloads and re-uploads).
     */
    override fun moveWithin(src: XFile, destDir: XFile, newName: String): Boolean {
        if (src.scheme != scheme || destDir.scheme != scheme) return false
        val dest = XFile(scheme, childPath(destDir.path, newName), src.isDir)
        if (locOf(dest.path)?.key.isNullOrEmpty()) return false // destination lands at bucket level, fall through to the copy engine
        transfer(src, dest, move = true)
        return true
    }

    /** Server-side move; for directories, each object is moved individually. */
    private fun transfer(src: XFile, dest: XFile, move: Boolean) {
        val from = loc(src)
        val to = loc(dest)
        if (from.key.isEmpty() || to.key.isEmpty()) throw FsException("Buckets cannot be renamed or moved")
        if (!src.isDir) {
            copyObject(from, to)
            if (move) call("DELETE", from.bucket, from.key).close()
            return
        }
        val srcPrefix = prefixOf(from)
        val dstPrefix = prefixOf(to)
        val keys = allKeys(from.bucket, srcPrefix)
        // Empty directories (placeholder only, or even completely empty) must still be created at the destination
        if (keys.isEmpty()) call("PUT", to.bucket, dstPrefix, body = EMPTY_BODY).close()
        for (key in keys) {
            copyObject(Loc(from.bucket, key), Loc(to.bucket, dstPrefix + key.removePrefix(srcPrefix)))
        }
        if (move) for (key in keys) call("DELETE", from.bucket, key).close()
    }

    private fun copyObject(from: Loc, to: Loc) {
        // The key in x-amz-copy-source must be encoded, but '/' must be kept as the path separator
        val source = "/${from.bucket}/${Sigv4.uriEncode(from.key, encodeSlash = false)}"
        call(
            "PUT", to.bucket, to.key,
            body = EMPTY_BODY,
            headers = mapOf("x-amz-copy-source" to source),
        ).use {
            // CopyObject first responds 200 then streams the result; failure information is in the body, not the status code
            val text = it.body?.string().orEmpty()
            if ("<Error" in text) throw FsException("COPY failed: ${errorText(text)}")
        }
    }

    override fun exists(file: XFile): Boolean {
        val l = locOf(file.path) ?: return true // bucket-list layer
        if (l.key.isEmpty()) return runCatching { call("HEAD", l.bucket, "").close() }.isSuccess
        if (!file.isDir) return runCatching { call("HEAD", l.bucket, l.key).close() }.isSuccess
        // Directory: has a placeholder, or has any object under the prefix — either counts as existing
        if (runCatching { call("HEAD", l.bucket, prefixOf(l)).close() }.isSuccess) return true
        return runCatching {
            val doc = xml(
                call(
                    "GET", l.bucket, "",
                    listOf("list-type" to "2", "prefix" to prefixOf(l), "max-keys" to "1"),
                ),
                "ListObjectsV2",
            )
            doc.byTag("Contents").isNotEmpty() || doc.byTag("CommonPrefixes").isNotEmpty()
        }.getOrDefault(false)
    }

    // ---- Uploads ----

    /**
     * Output stream for multipart upload: as soon as a part fills, send it —
     * **nothing is ever staged on disk**.
     *
     * Files smaller than one part take a single PUT (skipping the initiate/complete
     * round trips), so the vast majority of files still finish in one request.
     *
     * One part = 8 MiB × 10000 parts = max 80 GB per object; concurrent upload
     * memory usage stays pinned at one part. Note that the progress granularity
     * is **buffer writes**, not bytes actually on the wire — the progress bar
     * advances in part-sized steps.
     */
    private inner class S3Out(private val bucket: String, private val key: String) : OutputStream() {
        private val buf = ByteArray(PART_SIZE)
        private var pos = 0
        private var uploadId: String? = null
        private val etags = ArrayList<String>()
        private var closed = false

        override fun write(b: Int) {
            if (pos == PART_SIZE) flushPart()
            buf[pos++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remain = len
            while (remain > 0) {
                if (pos == PART_SIZE) flushPart()
                val n = minOf(remain, PART_SIZE - pos)
                System.arraycopy(b, o, buf, pos, n)
                pos += n; o += n; remain -= n
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                val id = uploadId
                if (id == null) {
                    // The whole object is less than one part
                    call("PUT", bucket, key, body = buf.toRequestBody(null, 0, pos)).close()
                } else {
                    if (pos > 0) flushPart() // The last part is allowed to be smaller than 5 MiB
                    complete(id)
                }
            } catch (t: Throwable) {
                uploadId?.let { id -> runCatching { call("DELETE", bucket, key, listOf("uploadId" to id)).close() } }
                throw t
            }
        }

        private fun flushPart() {
            val id = uploadId ?: initiate().also { uploadId = it }
            val part = etags.size + 1
            val resp = call(
                "PUT", bucket, key,
                query = listOf("partNumber" to part.toString(), "uploadId" to id),
                body = buf.toRequestBody(null, 0, pos),
                payloadSha = Sigv4.UNSIGNED,
            )
            val etag = resp.use { it.header("ETag") }
                ?: throw FsException("Upload part $part: server returned no ETag")
            etags += etag
            pos = 0
        }

        private fun initiate(): String {
            val doc = xml(
                call("POST", bucket, key, listOf("uploads" to ""), body = EMPTY_BODY),
                "CreateMultipartUpload",
            )
            return doc.first("UploadId")?.textContent?.trim()
                ?: throw FsException("CreateMultipartUpload returned no UploadId")
        }

        private fun complete(id: String) {
            val xml = buildString {
                append("<CompleteMultipartUpload>")
                etags.forEachIndexed { i, tag ->
                    append("<Part><PartNumber>").append(i + 1).append("</PartNumber>")
                    append("<ETag>").append(tag).append("</ETag></Part>")
                }
                append("</CompleteMultipartUpload>")
            }
            val bytes = xml.toByteArray()
            call(
                "POST", bucket, key,
                query = listOf("uploadId" to id),
                body = bytes.toRequestBody(XML_TYPE),
                payloadSha = Sigv4.sha256Hex(bytes),
            ).use {
                // Same as CopyObject: 200 may also be a failure; the real outcome lives in the response body
                val text = it.body?.string().orEmpty()
                if ("<Error" in text) throw FsException("CompleteMultipartUpload failed: ${errorText(text)}")
            }
        }
    }

    // ---- HTTP ----

    /** Sends a request and checks the status code; failures throw [FsException]. */
    private fun call(
        method: String,
        bucket: String?,
        key: String,
        query: List<Pair<String, String>> = emptyList(),
        body: RequestBody? = null,
        payloadSha: String = shaFor(body),
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val resp = rawCall(method, bucket, key, query, body, payloadSha, headers)
        if (!resp.isSuccessful) {
            val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
            resp.close()
            throw FsException(
                "$method ${bucket.orEmpty()}/$key failed: HTTP ${resp.code}" +
                    errorText(text).let { if (it.isEmpty()) "" else " — $it" },
            )
        }
        return resp
    }

    /** Same as [call] but does not check the status code (used when the caller needs to inspect codes like 416/404 itself). */
    private fun rawCall(
        method: String,
        bucket: String?,
        key: String,
        query: List<Pair<String, String>> = emptyList(),
        body: RequestBody? = null,
        payloadSha: String = shaFor(body),
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val url = urlOf(bucket, key, query)
        val amzDate = Sigv4.amzDate(System.currentTimeMillis())
        val hostHeader =
            if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        // Signing covers host + all x-amz-* + headers supplied by the caller; headers
        // OkHttp adds itself (Content-Length / User-Agent etc.) are not in SignedHeaders
        // and so do not participate in signing.
        val signed = LinkedHashMap<String, String>()
        signed["host"] = hostHeader
        signed["x-amz-content-sha256"] = payloadSha
        signed["x-amz-date"] = amzDate
        signed.putAll(headers)

        val auth = Sigv4.authorization(
            method = method,
            canonicalUri = url.encodedPath,
            query = query,
            headers = signed,
            payloadSha = payloadSha,
            amzDate = amzDate,
            region = config.region,
            accessKey = config.accessKey,
            secretKey = config.secretKey,
        )
        val rb = Request.Builder().url(url).method(method, body)
        signed.forEach { (k, v) -> if (k != "host") rb.header(k, v) }
        rb.header("Authorization", auth)
        return try {
            http.newCall(rb.build()).execute()
        } catch (e: Exception) {
            throw FsException("$method $url failed: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Payload hash for the request body. An empty body gets a real hash (cheap
     * and standard); anything with content uses `UNSIGNED-PAYLOAD` — uploaded
     * parts are 8 MiB, so reading the whole part again to compute SHA-256 for
     * signing is pure CPU waste, and S3 accepts this value.
     */
    private fun shaFor(body: RequestBody?): String =
        if (body == null || runCatching { body.contentLength() }.getOrDefault(-1L) == 0L) {
            Sigv4.EMPTY_SHA256
        } else {
            Sigv4.UNSIGNED
        }

    private fun urlOf(bucket: String?, key: String, query: List<Pair<String, String>>): HttpUrl {
        val b = HttpUrl.Builder().scheme(base.scheme).port(base.port)
        val path: String
        if (bucket == null) {
            b.host(base.host)
            path = "/"
        } else if (config.pathStyle) {
            b.host(base.host)
            // Bucket-level operations (list/create/delete a bucket) are `/bucket`,
            // no trailing slash — with a trailing slash some compatible implementations
            // interpret it as "an object with an empty key"
            path = if (key.isEmpty()) "/$bucket" else "/$bucket/" + Sigv4.uriEncode(key, encodeSlash = false)
        } else {
            b.host("$bucket.${base.host}")
            path = "/" + Sigv4.uriEncode(key, encodeSlash = false)
        }
        // encodedPath/encodedQuery: we encode everything ourselves and do not let
        // OkHttp rewrite it under its own rules — the signing computes its hash
        // over this exact string, and one differing character means SignatureDoesNotMatch.
        b.encodedPath(path)
        Sigv4.canonicalQuery(query).takeIf { it.isNotEmpty() }?.let { b.encodedQuery(it) }
        return b.build()
    }

    // ---- XML ----

    /** Parses the response body; [what] is only used to locate errors. */
    private fun xml(resp: Response, what: String): Element = resp.use {
        val bytes = try {
            it.body?.bytes() ?: throw FsException("$what returned no body")
        } catch (e: Exception) {
            throw FsException("$what response truncated: ${e::class.simpleName}: ${e.message}", e)
        }
        return try {
            // namespace-unaware: S3 uses the default namespace and the various
            // compatible implementations do not all agree on the URI; matching
            // by local tag name is simplest and most robust.
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(bytes.inputStream()).documentElement
        } catch (e: Exception) {
            throw FsException("$what returned malformed XML: ${e.message}", e)
        }
    }

    private fun Element.byTag(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.first(tag: String): Element? = byTag(tag).firstOrNull()

    private fun Element.text(tag: String): String? = first(tag)?.textContent?.trim()

    /** Pulls Code/Message out of S3's error XML (regex is sufficient: the body is small and fixed-shape). */
    private fun errorText(body: String): String {
        val code = CODE_RE.find(body)?.groupValues?.get(1).orEmpty()
        val msg = MSG_RE.find(body)?.groupValues?.get(1).orEmpty()
        return listOf(code, msg).filter { it.isNotEmpty() }.joinToString(": ")
    }

    /**
     * Decodes object names (Key / Prefix / Delimiter) from `encoding-type=url`
     * responses.
     *
     * ★ This is **form encoding** (`application/x-www-form-urlencoded`), not the
     * RFC 3986 used for request paths — ground truth from a real MinIO on
     * 2026-08-16:
     * ```
     * my notes.txt      → my+notes.txt          space is '+', not %20
     * a+b.txt           → a%2Bb.txt             literal plus is escaped, so '+' is unambiguous
     * 100% done #1.txt  → 100%25+done+%231.txt
     * ```
     * So we must **first** replace `+` with a space and **then** decode `%XX`:
     * doing it the other way around turns `%2B` into a space (treated as one),
     * and `a+b.txt` becomes `a b.txt`, which looks fine in the listing and
     * then 404s on click.
     *
     * Only object names go through this. `NextContinuationToken` is an opaque
     * value unaffected by `encoding-type` (the `=` inside is returned as-is);
     * decoding it would turn the `+` inside base64 into a space and pagination
     * would break on the spot — so for that one we pass through every byte.
     */
    private fun decodeKey(s: String): String = Sigv4.uriDecode(s.replace('+', ' '))

    private fun parseIso(s: String?): Long {
        if (s.isNullOrEmpty()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(s.take(19))?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        const val SCHEME = "s3"

        /** Part size; S3 requires that all parts except the last be at least 5 MiB. */
        private const val PART_SIZE = 8 * 1024 * 1024

        private val XML_TYPE = "application/xml".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val CODE_RE = Regex("<Code>(.*?)</Code>", RegexOption.DOT_MATCHES_ALL)
        private val MSG_RE = Regex("<Message>(.*?)</Message>", RegexOption.DOT_MATCHES_ALL)
    }
}
