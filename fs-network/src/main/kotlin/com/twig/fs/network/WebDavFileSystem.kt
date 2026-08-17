package com.twig.fs.network

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** 一个 WebDAV 端点的配置;[baseUrl] 形如 https://host/dav/subdir。 */
data class DavConfig(
    val baseUrl: String,
    val user: String = "",
    val password: String = "",
)

/**
 * WebDAV 文件系统:OkHttp + 手写 PROPFIND/MKCOL/MOVE(不引入 SDK,守住体积)。
 *
 * - 列目录:PROPFIND Depth:1,解析 DAV: multistatus
 * - 读:GET 流式;写:先落临时文件,流关闭时 PUT(RequestBody 需已知长度)
 * - 目录删除:DELETE 按 RFC 4918 递归;重命名:MOVE + Destination
 */
class WebDavFileSystem(
    private val config: DavConfig,
    override val scheme: String = SCHEME,
) : FileSystem {

    override val displayName: String = "WebDAV (${config.baseUrl})"

    /**
     * OkHttp 的默认超时是 connect/read/write 各 10 秒,对文件传输太紧:
     * - **read** 是"两次读之间的间隔",慢速链路上服务端组包超过 10 秒就断;
     * - **write** 同理,而 [openOutput] 是把整个文件一次 PUT 上去,弱网传大文件很容易撞上;
     * - **callTimeout** 默认是 0(不限),但显式写出来免得以后有人顺手加上。
     * 连接超时保持较短(连不上就该快点报错),读写放宽。
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> {
        val url = urlOf(dir.path, dir = true)
        val req = request(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw FsException("PROPFIND failed @ $url: ${e::class.simpleName}: ${e.message}", e)
        }
        resp.use {
            if (it.code != 207) throw FsException("PROPFIND failed: HTTP ${it.code} @ $url")
            val body = it.body ?: throw FsException("PROPFIND returned no body @ $url")
            // ★ 先整读进内存再交给 XML parser,别把 socket 流直接喂进去。
            // 边读边解析时,响应体传到一半被截断的异常是从 parser 内部冒出来的,
            // 到了 UI 上只剩孤零零一句 `unexpected end of stream` —— 看不出是哪个
            // 请求、更分不清"服务器拒了"还是"回到一半断了"。2026-08-04 排查
            // 「WebDAV 展开不了」时就卡在这上面(真因是服务端根目录下有个坏掉的
            // mount,mod_dav 遍历到它就中断,chunked 流没发完)。
            // multistatus 是目录清单,体积可控;而且 parseMultistatus 随后要建的
            // DOM 本来就比原始字节大得多,多存这一份不算额外开销。
            val bytes = try {
                body.bytes()
            } catch (e: Exception) {
                throw FsException(
                    "PROPFIND response truncated @ $url (server closed before sending it all)\n" +
                        "${e::class.simpleName}: ${e.message}",
                    e,
                )
            }
            return parseMultistatus(bytes.inputStream(), dir.path, URI(url).path)
        }
    }

    override fun openInput(file: XFile): InputStream {
        val resp = http.newCall(request(urlOf(file.path)).get().build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw FsException("GET failed: HTTP ${resp.code}")
        }
        val body = resp.body ?: run { resp.close(); throw FsException("GET returned no body") }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try { super.close() } finally { resp.close() }
            }
        }
    }

    override fun randomAccessEfficient(): Boolean = true // HTTP Range 定位读

    /** HTTP Range 定位读;流池那套逻辑与 S3 完全一样,见 [HttpRangeSource]。 */
    override fun openRandom(file: XFile): RandomSource = HttpRangeSource(file.size) { position ->
        http.newCall(
            request(urlOf(file.path)).header("Range", "bytes=$position-").get().build(),
        ).execute()
    }

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        if (append) throw FsException("WebDAV does not support append")
        val tmp = File.createTempFile("twigdav", null)
        val url = urlOf(file.path)
        return object : FileOutputStream(tmp) {
            override fun close() {
                super.close()
                try {
                    http.newCall(request(url).put(tmp.asRequestBody(null)).build()).execute().use {
                        if (!it.isSuccessful) throw FsException("PUT failed: HTTP ${it.code}")
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    override fun mkdir(parent: XFile, name: String): XFile {
        val path = join(parent.path, name)
        http.newCall(request(urlOf(path, dir = true)).method("MKCOL", null).build()).execute().use {
            if (!it.isSuccessful) throw FsException("MKCOL failed: HTTP ${it.code}")
        }
        return XFile(scheme, path, isDir = true)
    }

    override fun delete(file: XFile) {
        http.newCall(request(urlOf(file.path, dir = file.isDir)).delete().build()).execute().use {
            if (!it.isSuccessful && it.code != 204) throw FsException("DELETE failed: HTTP ${it.code}")
        }
    }

    override fun rename(file: XFile, newName: String): XFile {
        val to = join(file.parentPath, newName)
        val req = request(urlOf(file.path, dir = file.isDir))
            .method("MOVE", null)
            .header("Destination", urlOf(to, dir = file.isDir))
            // Overwrite: F —— 目标已存在时让服务端答 412 而不是把它覆盖掉。
            // 原来是 T,重命名成同目录已有的名字会无声删掉那个文件(与本地实现同一类问题)。
            .header("Overwrite", "F")
            .build()
        http.newCall(req).execute().use {
            if (it.code == 412) throw FsException("Target already exists: $newName")
            if (!it.isSuccessful) throw FsException("MOVE failed: HTTP ${it.code}")
        }
        return file.copy(path = to)
    }

    override fun exists(file: XFile): Boolean = runCatching {
        val req = request(urlOf(file.path, dir = file.isDir))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .build()
        http.newCall(req).execute().use { it.code == 207 }
    }.getOrDefault(false)

    // ---- 内部 ----

    private fun request(url: String): Request.Builder {
        val rb = Request.Builder().url(url)
        if (config.user.isNotEmpty()) {
            rb.header("Authorization", Credentials.basic(config.user, config.password))
        }
        return rb
    }

    /** 把内部路径映射为完整 URL;目录 URL 以 '/' 结尾(兼容严格服务器)。 */
    private fun urlOf(path: String, dir: Boolean = false): String {
        val base = config.baseUrl.trimEnd('/')
        val enc = path.trim('/').split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val u = if (enc.isEmpty()) base else "$base/$enc"
        return if (dir) "$u/" else u
    }

    /** 解析 multistatus;[requestUrlPath] 用于剔除代表目录自身的条目。 */
    private fun parseMultistatus(input: InputStream, dirPath: String, requestUrlPath: String): List<XFile> {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = dbf.newDocumentBuilder().parse(input)
        val selfNorm = requestUrlPath.trimEnd('/')

        val out = ArrayList<XFile>()
        val responses = doc.getElementsByTagNameNS(DAV_NS, "response")
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as Element
            val hrefRaw = textOf(resp, "href") ?: continue
            // 有的服务器返回完整 URL,统一取 path 部分再解码
            val hrefPath = runCatching { URI(hrefRaw).path ?: hrefRaw }.getOrDefault(hrefRaw)
            val decoded = URLDecoder.decode(hrefPath, "UTF-8").trimEnd('/')
            if (decoded == selfNorm) continue // 跳过目录自身

            val name = decoded.substringAfterLast('/')
            if (name.isEmpty()) continue
            val isDir = resp.getElementsByTagNameNS(DAV_NS, "collection").length > 0
            val size = textOf(resp, "getcontentlength")?.toLongOrNull() ?: 0L
            val time = textOf(resp, "getlastmodified")?.let { parseHttpDate(it) } ?: 0L

            out.add(
                XFile(
                    scheme = scheme,
                    path = join(dirPath, name),
                    isDir = isDir,
                    size = if (isDir) 0L else size,
                    lastModified = time,
                ),
            )
        }
        out.sortWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
        return out
    }

    private fun textOf(el: Element, tag: String): String? {
        val nodes = el.getElementsByTagNameNS(DAV_NS, tag)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
    }

    private fun parseHttpDate(s: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0L
    }.getOrDefault(0L)

    private fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    companion object {
        const val SCHEME = "dav"
        private const val DAV_NS = "DAV:"
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
