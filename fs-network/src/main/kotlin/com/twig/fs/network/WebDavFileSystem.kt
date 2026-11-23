package com.twig.fs.network

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.StorageSpace
import com.twig.core.StorageSpaceSource
import com.twig.core.XFile
import com.twig.core.SourcePresentation
import com.twig.core.SourcePresentationSource
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** Configuration for a WebDAV endpoint; [baseUrl] is shaped like https://host/dav/subdir. */
data class DavConfig(
    val baseUrl: String,
    val user: String = "",
    val password: String = "",
    val certificateTrust: CertificateTrust? = null,
)

/**
 * WebDAV filesystem: OkHttp + hand-written PROPFIND/MKCOL/MOVE (no SDK, to keep
 * the APK size in check).
 *
 * - Listing: PROPFIND Depth:1, parsing DAV: multistatus
 * - Reads: streaming GET; writes: stage to a temp file, PUT on stream close
 *   (RequestBody needs a known length)
 * - Directory deletion: recursive DELETE per RFC 4918; rename: MOVE + Destination
 */
class WebDavFileSystem(
    private val config: DavConfig,
    override val scheme: String = SCHEME,
) : FileSystem, StorageSpaceSource, SourcePresentationSource {

    @Volatile private var allSources = false

    override fun sourcePresentation(file: XFile): SourcePresentation? {
        if (!allSources || !file.isDir || file.scheme != scheme || file.parentPath != "/" || file.path == "/") return null
        return TwigDavSources.parse(file.path.trimEnd('/').substringAfterLast('/'))
    }

    override val displayName: String = "WebDAV (${config.baseUrl})"

    /**
     * OkHttp's default timeout is 10 seconds for connect/read/write — too tight
     * for file transfers:
     * - **read** is the gap between two reads; on a slow link where the server
     *   spends more than 10 seconds assembling the next packet, the connection drops;
     * - **write** works the same way, and [openOutput] PUTs the entire file at
     *   once, so weak links sending large files easily hit it;
     * - **callTimeout** defaults to 0 (unlimited), but we write it out explicitly
     *   so nobody quietly adds a value later.
     * Connect timeout stays short (failure to connect should fail fast); read
     * and write are relaxed.
     */
    private val http = ServerTls.configure(OkHttpClient.Builder(), config.certificateTrust)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    private data class SpaceSample(val value: StorageSpace?, val at: Long = System.nanoTime())
    private val spaces = object : LinkedHashMap<String, SpaceSample>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpaceSample>?) = size > 256
    }

    override fun knownStorageSpace(dir: XFile): StorageSpace? = synchronized(spaces) {
        spaces[dir.path.trimEnd('/')]?.value
    }

    override fun storageSpace(dir: XFile): StorageSpace? {
        synchronized(spaces) {
            spaces[dir.path.trimEnd('/')]?.let {
                if (System.nanoTime() - it.at < 10_000_000_000L) return it.value
            }
        }
        val url = urlOf(dir.path, dir = true)
        val req = request(url)
            .method("PROPFIND", SPACE_BODY.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0").build()
        http.newCall(req).execute().use {
            if (it.code != 207) return null
            val bytes = it.body?.bytes() ?: return null
            parseMultistatus(bytes.inputStream(), dir.path, URI(url).path, cacheSpace = true)
        }
        return knownStorageSpace(dir)
    }

    private fun readSpace(response: Element): StorageSpace? {
        val values = HashMap<String, Long>()
        val stats = response.getElementsByTagNameNS(DAV_NS, "propstat")
        for (i in 0 until stats.length) {
            val stat = stats.item(i) as Element
            if (textOf(stat, "status")?.split(' ')?.getOrNull(1) != "200") continue
            for (name in listOf("quota-available-bytes", "quota-used-bytes")) {
                textOf(stat, name)?.toLongOrNull()?.takeIf { it >= 0 }?.let { values[name] = it }
            }
        }
        val available = values["quota-available-bytes"] ?: return null
        val used = values["quota-used-bytes"] ?: return null
        return if (available <= Long.MAX_VALUE - used) StorageSpace(available, used) else null
    }

    override fun list(dir: XFile): List<XFile> {
        val url = urlOf(dir.path, dir = true)
        val req = request(url)
            .method("PROPFIND", LIST_BODY.toRequestBody("text/xml; charset=utf-8".toMediaType()))
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
            // ★ First read the whole body into memory before handing it to the XML parser;
            // do not feed the socket stream in directly. When parsing as we read,
            // a truncated-body exception surfaces from inside the parser, and the
            // UI is left with only a bare "unexpected end of stream" — you cannot
            // tell which request it was, nor whether the server refused or simply
            // closed mid-stream. On 2026-08-04 while debugging "WebDAV cannot
            // expand", the investigation stalled on this exact case (the real
            // cause was a broken mount under the server's root that mod_dav
            // walked into and aborted on, leaving the chunked stream unfinished).
            // The multistatus body is a directory listing with a manageable size;
            // the DOM that parseMultistatus builds afterwards is already much
            // larger than the raw bytes, so caching one extra copy costs nothing.
            val bytes = try {
                body.bytes()
            } catch (e: Exception) {
                throw FsException(
                    "PROPFIND response truncated @ $url (server closed before sending it all)\n" +
                        "${e::class.simpleName}: ${e.message}",
                    e,
                )
            }
            return parseMultistatus(bytes.inputStream(), dir.path, selfPath = URI(url).path, cacheSpace = true, readScope = true)
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

    override fun randomAccessEfficient(): Boolean = true // HTTP Range positioned reads

    /** HTTP Range positioned read; the stream-pool logic is identical to S3, see [HttpRangeSource]. */
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
            // Overwrite: F — make the server respond 412 when the target exists, instead
            // of overwriting it. The original value was T, which silently deleted
            // the file already there when renaming to an existing name in the same
            // directory (same class of bug as the local implementation).
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

    // ---- internals ----

    private fun request(url: String): Request.Builder {
        val rb = Request.Builder().url(url)
        if (config.user.isNotEmpty()) {
            rb.header("Authorization", Credentials.basic(config.user, config.password))
        }
        return rb
    }

    /** Maps an internal path to a full URL; directory URLs end with '/' (for strict servers). */
    private fun urlOf(path: String, dir: Boolean = false): String {
        val base = config.baseUrl.trimEnd('/')
        val enc = path.trim('/').split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val u = if (enc.isEmpty()) base else "$base/$enc"
        return if (dir) "$u/" else u
    }

    /** Parses multistatus; [requestUrlPath] is used to filter out the entry representing the directory itself. */
    /**
     * One `Depth: 0` PROPFIND — the same props the listing parses, for a single entry. The
     * response describes the entry itself, so nothing is filtered out of it.
     */
    override fun stat(path: String): XFile? = runCatching {
        val url = urlOf(path)
        val req = request(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code != 207) return null
            val bytes = resp.body?.bytes() ?: return null
            parseMultistatus(bytes.inputStream(), path.substringBeforeLast('/', "").ifEmpty { "/" }, selfPath = null)
                .firstOrNull()
                // The href names the entry itself; keep the path the caller asked about
                ?.copy(path = path)
        }
    }.getOrNull()

    /** [selfPath] is the request's own URL path, whose entry is dropped (a listing must not contain the directory itself); null keeps everything. */
    private fun parseMultistatus(input: InputStream, dirPath: String, selfPath: String?, cacheSpace: Boolean = false, readScope: Boolean = false): List<XFile> {
        val doc = SafeXml.parse(input, namespaceAware = true)
        val selfNorm = selfPath?.trimEnd('/')

        val out = ArrayList<XFile>()
        var ownSpace: SpaceSample? = null
        val responses = doc.getElementsByTagNameNS(DAV_NS, "response")
        // Only the endpoint's own successful property may enable source-name interpretation.
        if (readScope && selfPath != null && dirPath == "/") {
            allSources = (0 until responses.length).any { i ->
                val response = responses.item(i) as Element
                val href = textOf(response, "href") ?: return@any false
                val path = runCatching { URI(href).path }.getOrNull() ?: return@any false
                if (path.trimEnd('/') != selfNorm) return@any false
                val stats = response.getElementsByTagNameNS(DAV_NS, "propstat")
                (0 until stats.length).any { j ->
                    val stat = stats.item(j) as Element
                    val values = stat.getElementsByTagNameNS(TwigDavSources.NAMESPACE, TwigDavSources.PROPERTY)
                    textOf(stat, "status")?.split(' ')?.getOrNull(1) == "200" &&
                        values.length == 1 && values.item(0).textContent.trim() == TwigDavSources.ALL
                }
            }
        }
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as Element
            val hrefRaw = textOf(resp, "href") ?: continue
            // Some servers return full URLs; always take the path part and decode it
            val hrefPath = runCatching { URI(hrefRaw).path ?: hrefRaw }.getOrDefault(hrefRaw)
            val decoded = hrefPath.trimEnd('/')
            val own = selfNorm != null && decoded == selfNorm
            val entryPath = if (own) dirPath else join(dirPath, decoded.substringAfterLast('/'))
            if (cacheSpace) {
                val space = readSpace(resp)
                if (own) ownSpace = SpaceSample(space)
                else synchronized(spaces) {
                    if (space == null) spaces.remove(entryPath.trimEnd('/'))
                    else spaces[entryPath.trimEnd('/')] = SpaceSample(space)
                }
            }
            if (own) continue // Keep the self capacity, but omit the directory from its listing.

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
        // Keep the current mount's sample even when a large listing fills the bounded cache.
        ownSpace?.let { sample -> synchronized(spaces) { spaces[dirPath.trimEnd('/')] = sample } }
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
        private val SPACE_BODY = """
            <d:propfind xmlns:d="DAV:"><d:prop>
              <d:quota-available-bytes/><d:quota-used-bytes/>
            </d:prop></d:propfind>
        """.trimIndent()
        private val LIST_BODY = PROPFIND_BODY.replace("<d:resourcetype/>",
            "<d:resourcetype/><d:quota-available-bytes/><d:quota-used-bytes/>" +
                "<t:scope xmlns:t=\"${TwigDavSources.NAMESPACE}\"/>")
    }
}
