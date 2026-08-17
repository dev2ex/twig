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
 * 一个 S3 端点的配置。
 *
 * [bucket] 留空时根目录列出账号下所有桶;填了则根目录直接是那个桶——
 * 很多访问凭证只被授权了单个桶、没有 `ListAllMyBuckets` 权限,
 * 那种情况下不填就会开局一个 AccessDenied。
 */
data class S3Config(
    /** 形如 `https://s3.us-east-1.amazonaws.com` 或 `http://192.168.1.9:9000`。 */
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "us-east-1",
    val bucket: String = "",
    /** true = `endpoint/bucket/key`;false = `bucket.endpoint/key`(AWS 正统写法)。 */
    val pathStyle: Boolean = true,
)

/**
 * S3 文件系统:OkHttp + 手写 SigV4 签名 + 手写 XML 解析,零新依赖。
 *
 * 不用 AWS SDK 的原因见 [Sigv4] —— 光 s3 模块连着依赖十几 MB,比整个 APK 还大,
 * 而实际用到的只是 GET/PUT/DELETE 几个 REST 调用。同一份实现通吃 AWS S3、MinIO、
 * Cloudflare R2、阿里云 OSS、腾讯云 COS、Backblaze B2 等一切 S3 兼容服务。
 *
 * **S3 没有目录**,这里的目录是两件东西凑出来的:
 *  - 列目录时带 `delimiter=/`,服务端把同前缀的对象折叠成 `CommonPrefixes` = 子目录;
 *  - [mkdir] 写一个以 `/` 结尾的空对象当占位符,好让空目录也能显示出来
 *    (纯靠 CommonPrefixes 的话,没有对象的目录根本不存在)。
 *  列目录时这些占位符会被滤掉,不会自己冒出来变成一个 0 字节的怪文件。
 */
class S3FileSystem(
    private val config: S3Config,
    override val scheme: String = SCHEME,
) : FileSystem {

    override val displayName: String =
        "S3 (" + config.endpoint.substringAfter("://").trimEnd('/') +
            (if (config.bucket.isEmpty()) "" else "/${config.bucket}") + ")"

    /** 超时取值与 [WebDavFileSystem] 同理:连接短、读写放宽(大文件传输不能按 10 秒算)。 */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = runCatching { config.endpoint.trimEnd('/').toHttpUrl() }
        .getOrElse { throw FsException("Bad S3 endpoint: ${config.endpoint}", it) }

    // ---- 路径 ↔ (桶, 键) ----

    /** [key] 为空表示桶根。 */
    private data class Loc(val bucket: String, val key: String)

    /**
     * 把 [XFile.path] 拆成桶与键;返回 null 表示"桶列表"这一层(仅 [S3Config.bucket] 为空时存在)。
     */
    private fun locOf(path: String): Loc? {
        val p = path.trim('/')
        if (config.bucket.isNotEmpty()) return Loc(config.bucket, p)
        if (p.isEmpty()) return null
        val i = p.indexOf('/')
        return if (i < 0) Loc(p, "") else Loc(p.substring(0, i), p.substring(i + 1))
    }

    private fun loc(file: XFile): Loc =
        locOf(file.path) ?: throw FsException("Not inside a bucket: ${file.path}")

    /** 目录的对象前缀:桶根为空串,否则 `key/`。 */
    private fun prefixOf(l: Loc): String = if (l.key.isEmpty()) "" else l.key.trimEnd('/') + "/"

    private fun childPath(dirPath: String, name: String): String =
        if (dirPath.endsWith("/")) "$dirPath$name" else "$dirPath/$name"

    // ---- FileSystem ----

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    /** 与 FTP/WebDAV 一致:不 stat,纯路径映射(判类型请走 [list],见 CLAUDE.md 的 resolve 坑)。 */
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
            // 名字里可能有 XML 里非法的字节(控制字符等),让服务端先编码再回
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
                // 目录占位符(mkdir 写的那个空对象),以及桶根自身的前缀条目
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

    override fun randomAccessEfficient(): Boolean = true // HTTP Range 定位读

    override fun openRandom(file: XFile): RandomSource {
        val l = loc(file)
        return HttpRangeSource(file.size) { position ->
            // 越界时 S3 答 416,HttpRangeSource 认这个码,所以这里不走 checked 的 call()
            rawCall("GET", l.bucket, l.key, headers = mapOf("Range" to "bytes=$position-"))
        }
    }

    /**
     * PUT 是整体生效的:要么还是旧对象,要么已经是新对象,不会读到写了一半的东西。
     * 分片上传中途失败也一样(没 Complete 就不可见),所以覆盖写自身是原子的——
     * 文本编辑器保存因此免掉"写临时文件 → 删 → 改名"那两趟。
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
            // 空目录占位符:S3 没有目录,不写这个的话新建的空目录下次列出来就没了
            call("PUT", l.bucket, l.key.trimEnd('/') + "/", body = EMPTY_BODY, payloadSha = Sigv4.EMPTY_SHA256)
                .close()
        }
        return XFile(scheme, childPath(parent.path, name), isDir = true)
    }

    private fun createBucket(bucket: String) {
        // us-east-1 是默认区域,给它带上 LocationConstraint 反而会被 AWS 拒(InvalidLocationConstraint)
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
        // 配置锁定了单个桶时,"根" 就是那个桶——删它等于把整个连接的内容清空,
        // 而用户看到的只是自己按了删除键的那一行。不给这条路。
        if (l.key.isEmpty() && config.bucket.isNotEmpty()) throw FsException("Cannot delete the bucket root")
        if (!file.isDir) {
            call("DELETE", l.bucket, l.key).close()
            return
        }
        // 目录 = 一批共享前缀的对象,逐个删(批量 DELETE 要算 Content-MD5,
        // 而各家兼容实现对它的支持参差不齐,不值得为它冒风险)
        for (key in allKeys(l.bucket, prefixOf(l))) call("DELETE", l.bucket, key).close()
        if (l.key.isEmpty()) call("DELETE", l.bucket, "").close() // 桶本身
    }

    /** 前缀下的全部对象键(不带 delimiter,即递归)。 */
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
     * 同一端点内的移动走服务端 CopyObject + DELETE:不经过手机,
     * 几十 GB 的对象也是一次请求的事(不覆盖的话 [com.twig.core.CopyEngine]
     * 会老老实实下载再上传一遍)。
     */
    override fun moveWithin(src: XFile, destDir: XFile, newName: String): Boolean {
        if (src.scheme != scheme || destDir.scheme != scheme) return false
        val dest = XFile(scheme, childPath(destDir.path, newName), src.isDir)
        if (locOf(dest.path)?.key.isNullOrEmpty()) return false // 目标落在桶层,交给拷贝引擎
        transfer(src, dest, move = true)
        return true
    }

    /** 服务端搬运;目录则逐个对象搬。 */
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
        // 空目录(只有占位符甚至什么都没有)也要在目标建出来
        if (keys.isEmpty()) call("PUT", to.bucket, dstPrefix, body = EMPTY_BODY).close()
        for (key in keys) {
            copyObject(Loc(from.bucket, key), Loc(to.bucket, dstPrefix + key.removePrefix(srcPrefix)))
        }
        if (move) for (key in keys) call("DELETE", from.bucket, key).close()
    }

    private fun copyObject(from: Loc, to: Loc) {
        // x-amz-copy-source 的键要编码,但 '/' 是路径分隔符必须留着
        val source = "/${from.bucket}/${Sigv4.uriEncode(from.key, encodeSlash = false)}"
        call(
            "PUT", to.bucket, to.key,
            body = EMPTY_BODY,
            headers = mapOf("x-amz-copy-source" to source),
        ).use {
            // CopyObject 会先回 200 再流式发结果,失败信息藏在响应体里而不是状态码上
            val text = it.body?.string().orEmpty()
            if ("<Error" in text) throw FsException("COPY failed: ${errorText(text)}")
        }
    }

    override fun exists(file: XFile): Boolean {
        val l = locOf(file.path) ?: return true // 桶列表层
        if (l.key.isEmpty()) return runCatching { call("HEAD", l.bucket, "").close() }.isSuccess
        if (!file.isDir) return runCatching { call("HEAD", l.bucket, l.key).close() }.isSuccess
        // 目录:有占位符、或前缀下有任何对象,都算存在
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

    // ---- 上传 ----

    /**
     * 分片上传的输出流:攒够一片就发一片,**全程不落盘**。
     *
     * 小于一片的文件走单次 PUT(省掉 initiate/complete 两趟往返),
     * 所以绝大多数文件仍是一个请求搞定。
     *
     * 一片 8 MiB × 上限 10000 片 = 单对象最大 80 GB,同时上传时的内存占用固定
     * 在一片的大小。注意进度回调的粒度是**写入缓冲**而非"发出去了",
     * 进度条会以片为单位一顿一顿地走。
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
                    // 整个对象不足一片
                    call("PUT", bucket, key, body = buf.toRequestBody(null, 0, pos)).close()
                } else {
                    if (pos > 0) flushPart() // 最后一片允许小于 5 MiB
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
                // 同 CopyObject:200 也可能是失败,真正的结果在响应体里
                val text = it.body?.string().orEmpty()
                if ("<Error" in text) throw FsException("CompleteMultipartUpload failed: ${errorText(text)}")
            }
        }
    }

    // ---- HTTP ----

    /** 发请求并检查状态码;失败抛 [FsException]。 */
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

    /** 同 [call] 但不检查状态码(调用方自己要看 416/404 这类码时用)。 */
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
        // 签名覆盖 host + 所有 x-amz-* + 调用方给的头;okhttp 自己补的
        // Content-Length / User-Agent 之类不在 SignedHeaders 里,不参与签名。
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
     * 请求体的 payload hash。空体算真 hash(便宜且最标准);有内容的一律
     * `UNSIGNED-PAYLOAD` —— 上传的分片有 8 MiB,为签名再整读一遍算 SHA-256
     * 纯属白烧 CPU,而 S3 本来就接受这个值。
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
            // 桶级操作(列对象/建桶/删桶)是 `/bucket`,不带尾斜杠——
            // 带了在部分兼容实现上会被当成"名为空串的对象"
            path = if (key.isEmpty()) "/$bucket" else "/$bucket/" + Sigv4.uriEncode(key, encodeSlash = false)
        } else {
            b.host("$bucket.${base.host}")
            path = "/" + Sigv4.uriEncode(key, encodeSlash = false)
        }
        // encodedPath/encodedQuery:自己编码到底,不让 okhttp 按它自己的规则改一遍——
        // 签名算的就是这份字符串,两边差一个字符就是 SignatureDoesNotMatch。
        b.encodedPath(path)
        Sigv4.canonicalQuery(query).takeIf { it.isNotEmpty() }?.let { b.encodedQuery(it) }
        return b.build()
    }

    // ---- XML ----

    /** 解析响应体;[what] 只用于出错时的定位信息。 */
    private fun xml(resp: Response, what: String): Element = resp.use {
        val bytes = try {
            it.body?.bytes() ?: throw FsException("$what returned no body")
        } catch (e: Exception) {
            throw FsException("$what response truncated: ${e::class.simpleName}: ${e.message}", e)
        }
        return try {
            // namespace-unaware:S3 用默认命名空间且各兼容实现的 URI 不完全一致,
            // 按本地名取标签最省事也最稳。
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

    /** 从 S3 的错误 XML 里抠出 Code/Message(正则足够:错误体很小且结构固定)。 */
    private fun errorText(body: String): String {
        val code = CODE_RE.find(body)?.groupValues?.get(1).orEmpty()
        val msg = MSG_RE.find(body)?.groupValues?.get(1).orEmpty()
        return listOf(code, msg).filter { it.isNotEmpty() }.joinToString(": ")
    }

    /**
     * 解码 `encoding-type=url` 响应里的对象名(Key / Prefix / Delimiter)。
     *
     * ★ 这里是 **form 编码**(`application/x-www-form-urlencoded`),不是请求路径用的
     * RFC 3986 —— 2026-08-16 拿真 MinIO 打出来的地面真相:
     * ```
     * my notes.txt      → my+notes.txt          空格是 '+',不是 %20
     * a+b.txt           → a%2Bb.txt             字面加号被转义,所以 '+' 无歧义
     * 100% done #1.txt  → 100%25+done+%231.txt
     * ```
     * 所以必须**先**把 `+` 换成空格、**再**解 `%XX`:反过来的话 `%2B` 解出来的那个
     * 加号会被当成空格,`a+b.txt` 就成了 `a b.txt`,列表看着没毛病、一点开就 404。
     *
     * 只有对象名走这套。`NextContinuationToken` 是不受 `encoding-type` 影响的
     * opaque 值(实测里面的 `=` 原样返回),拿它来解会把 base64 里的 `+` 变成空格、
     * 分页当场断掉——所以那边一个字都不解,原样带回给服务端。
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

        /** 分片大小;S3 规定除最后一片外不得小于 5 MiB。 */
        private const val PART_SIZE = 8 * 1024 * 1024

        private val XML_TYPE = "application/xml".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val CODE_RE = Regex("<Code>(.*?)</Code>", RegexOption.DOT_MATCHES_ALL)
        private val MSG_RE = Regex("<Message>(.*?)</Message>", RegexOption.DOT_MATCHES_ALL)
    }
}
