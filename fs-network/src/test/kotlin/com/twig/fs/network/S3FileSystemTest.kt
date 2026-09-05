package com.twig.fs.network

import com.twig.core.FsException
import com.twig.core.XFile
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for S3. This class of implementation bug (signing misses a header,
 * name encoding is inconsistent, multipart upload skips a request) shows up on a real
 * device only as "HTTP 403" or "the uploaded file is corrupt", almost impossible to
 * diagnose after the fact — here a single request/response pair pins it down.
 */
class S3FileSystemTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Default: locked to a single bucket, path-style (the common shape for self-hosted MinIO). */
    private fun fs(bucket: String = "buck", pathStyle: Boolean = true) = S3FileSystem(
        S3Config(
            endpoint = server.url("/").toString().trimEnd('/'),
            accessKey = "AK", secretKey = "SK", region = "us-east-1",
            bucket = bucket, pathStyle = pathStyle,
        ),
    )

    private fun okXml(body: String) = MockResponse().setBody(body.trimIndent())

    private fun listBody(
        contents: List<Triple<String, Long, String>> = emptyList(),
        prefixes: List<String> = emptyList(),
        truncated: String? = null,
    ) = buildString {
        append("<?xml version=\"1.0\"?><ListBucketResult>")
        prefixes.forEach { append("<CommonPrefixes><Prefix>$it</Prefix></CommonPrefixes>") }
        contents.forEach { (k, size, time) ->
            append("<Contents><Key>$k</Key><Size>$size</Size><LastModified>$time</LastModified></Contents>")
        }
        append("<IsTruncated>${truncated != null}</IsTruncated>")
        truncated?.let { append("<NextContinuationToken>$it</NextContinuationToken>") }
        append("</ListBucketResult>")
    }

    // ---- listing ----

    @Test
    fun listFoldsPrefixesIntoDirectories() {
        server.enqueue(
            okXml(
                listBody(
                    prefixes = listOf("docs/", "img/"),
                    contents = listOf(
                        Triple("a.txt", 12L, "2026-08-16T10:20:30.000Z"),
                        // Directory placeholder: must be filtered out, must not turn into a weird 0-byte file
                        Triple("docs/", 0L, "2026-08-16T10:20:30.000Z"),
                    ),
                ),
            ),
        )
        val items = fs().list(XFile("s3", "/", isDir = true))
        assertEquals(listOf("docs:true", "img:true", "a.txt:false"), items.map { "${it.name}:${it.isDir}" })
        assertEquals(12L, items.last().size)
        assertTrue(items.last().lastModified > 0)

        val req = server.takeRequest()
        assertEquals("/buck", req.requestUrl!!.encodedPath)
        assertEquals("2", req.requestUrl!!.queryParameter("list-type"))
        assertEquals("/", req.requestUrl!!.queryParameter("delimiter"))
    }

    @Test
    fun listOfSubdirectoryUsesPrefixAndStripsIt() {
        server.enqueue(okXml(listBody(contents = listOf(Triple("docs/notes.md", 3L, "")))))
        val items = fs().list(XFile("s3", "/docs", isDir = true))
        assertEquals(listOf("notes.md"), items.map { it.name })
        assertEquals("/docs/notes.md", items[0].path)
        assertEquals("docs/", server.takeRequest().requestUrl!!.queryParameter("prefix"))
    }

    /**
     * `bucket/prefix` roots the connection inside the bucket: the prefix goes out on the
     * wire, and the paths that come back are relative to it — if it leaked into
     * [XFile.path], every later request would send `pre/pre/…` and 404.
     */
    @Test
    fun bucketFieldMayCarryAPrefix() {
        server.enqueue(okXml(listBody(contents = listOf(Triple("pre/a.txt", 4L, "")), prefixes = listOf("pre/deep/"))))
        val rooted = fs(bucket = "buck/pre")
        val items = rooted.list(XFile("s3", "/", isDir = true))
        assertEquals(listOf("/deep", "/a.txt"), items.map { it.path })

        val req = server.takeRequest()
        assertEquals("pre/", req.requestUrl!!.queryParameter("prefix"))
        assertTrue(req.path!!.startsWith("/buck?")) // the bucket is only the first segment

        // A read below the root addresses the object by its real key
        server.enqueue(MockResponse().setBody("x"))
        rooted.openInput(XFile("s3", "/a.txt", isDir = false)).use { it.readBytes() }
        assertEquals("/buck/pre/a.txt", server.takeRequest().path)
    }

    /** When one page isn't enough, keep listing with the continuation-token instead of showing only the first page. */
    @Test
    fun listFollowsPagination() {
        server.enqueue(okXml(listBody(contents = listOf(Triple("a", 1L, "")), truncated = "TOK/EN+1")))
        server.enqueue(okXml(listBody(contents = listOf(Triple("b", 1L, "")))))
        val items = fs().list(XFile("s3", "/", isDir = true))
        assertEquals(listOf("a", "b"), items.map { it.name })

        server.takeRequest()
        // The token is unaffected by encoding-type (it's opaque base64) and must be passed back as-is:
        // form-decoding it as if it were an object name would turn '+' into a space and break pagination outright
        assertEquals("TOK/EN+1", server.takeRequest().requestUrl!!.queryParameter("continuation-token"))
    }

    @Test
    fun listBucketsWhenNoBucketConfigured() {
        server.enqueue(
            okXml(
                """
                <?xml version="1.0"?><ListAllMyBucketsResult><Buckets>
                  <Bucket><Name>beta</Name><CreationDate>2026-01-02T03:04:05.000Z</CreationDate></Bucket>
                  <Bucket><Name>alpha</Name><CreationDate>2026-01-02T03:04:05.000Z</CreationDate></Bucket>
                </Buckets></ListAllMyBucketsResult>
                """,
            ),
        )
        val items = fs(bucket = "").list(XFile("s3", "/", isDir = true))
        assertEquals(listOf("alpha", "beta"), items.map { it.name })
        assertTrue(items.all { it.isDir })
        assertEquals("/", server.takeRequest().requestUrl!!.encodedPath)
    }

    /** When the bucket is left empty, the first path segment is the bucket name. */
    @Test
    fun pathFirstSegmentIsBucketWhenUnscoped() {
        server.enqueue(okXml(listBody(contents = listOf(Triple("x/y.txt", 1L, "")))))
        val items = fs(bucket = "").list(XFile("s3", "/mybucket/x", isDir = true))
        assertEquals(listOf("/mybucket/x/y.txt"), items.map { it.path })
        val req = server.takeRequest()
        assertEquals("/mybucket", req.requestUrl!!.encodedPath)
        assertEquals("x/", req.requestUrl!!.queryParameter("prefix"))
    }

    // ---- name encoding ----

    /**
     * An object whose name contains Chinese characters / spaces / plus signs: the request path
     * must be RFC 3986 encoded, and **the signature must be computed over that exact encoding**
     * (a mismatch means SignatureDoesNotMatch).
     */
    @Test
    fun objectNamesAreRfc3986Encoded() {
        server.enqueue(MockResponse().setBody("hi"))
        fs().openInput(XFile("s3", "/报告 v1+2.txt", isDir = false)).use { it.readBytes() }

        val req = server.takeRequest()
        assertEquals("/buck/%E6%8A%A5%E5%91%8A%20v1%2B2.txt", req.requestUrl!!.encodedPath)
        // The path covered by the signature is exactly the one that was sent
        assertTrue(req.getHeader("Authorization")!!.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
    }

    /**
     * The key the server returns is **form-encoded** (★ captured from a real MinIO: a space
     * is `+`, a literal plus sign is `%2B`) — decoding in the wrong order turns `a+b.txt`
     * into `a b.txt`.
     */
    @Test
    fun encodedKeysFromServerAreDecoded() {
        server.enqueue(
            okXml(
                listBody(
                    contents = listOf(
                        Triple("my+notes.txt", 1L, ""),
                        Triple("a%2Bb.txt", 1L, ""),
                        Triple("100%25+done+%231.txt", 1L, ""),
                        Triple("%E6%8A%A5%E5%91%8A.txt", 1L, ""),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("100% done #1.txt", "a+b.txt", "my notes.txt", "报告.txt"),
            fs().list(XFile("s3", "/", isDir = true)).map { it.name },
        )
    }

    /** Directory names are form-encoded the same way. */
    @Test
    fun encodedCommonPrefixesAreDecoded() {
        server.enqueue(okXml(listBody(prefixes = listOf("my+docs/", "a%2Bb/"))))
        assertEquals(
            listOf("a+b", "my docs"),
            fs().list(XFile("s3", "/", isDir = true)).map { it.name },
        )
    }

    // ---- authentication ----

    @Test
    fun everyRequestIsSigned() {
        server.enqueue(MockResponse().setBody("x"))
        fs().openInput(XFile("s3", "/a.txt", isDir = false)).use { it.readBytes() }

        val req = server.takeRequest()
        val auth = req.getHeader("Authorization")!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AK/"))
        assertTrue(auth.contains("/us-east-1/s3/aws4_request"))
        assertEquals(Sigv4.EMPTY_SHA256, req.getHeader("x-amz-content-sha256"))
        assertTrue(req.getHeader("x-amz-date")!!.matches(Regex("\\d{8}T\\d{6}Z")))
    }

    /** Virtual-host style: the bucket name goes into the host, so it must not appear in the path. */
    @Test
    fun virtualHostStylePutsBucketInHost() {
        server.enqueue(okXml(listBody()))
        fs(pathStyle = false).list(XFile("s3", "/", isDir = true))
        val req = server.takeRequest()
        assertEquals("/", req.requestUrl!!.encodedPath)
        assertTrue(req.getHeader("Host")!!.startsWith("buck."))
    }

    // ---- read ----

    @Test
    fun randomAccessUsesRangeRequests() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("56789"))
        val src = fs().openRandom(XFile("s3", "/a.bin", isDir = false, size = 10))
        val buf = ByteArray(5)
        assertEquals(5, src.readAt(5, buf, 0, 5))
        assertEquals("56789", String(buf))
        src.close()
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
    }

    // ---- write ----

    /** An object smaller than one part goes through a single PUT — no extra initiate/complete round trips. */
    @Test
    fun smallUploadIsASinglePut() {
        server.enqueue(MockResponse())
        fs().openOutput(XFile("s3", "/a.txt", isDir = false)).use { it.write("hello".toByteArray()) }

        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/buck/a.txt", req.requestUrl!!.encodedPath)
        assertEquals("hello", req.body.readUtf8())
    }

    /**
     * Beyond one part it switches to multipart upload: initiate → one PUT per part → complete.
     * The part size is 8 MiB; writing 9 MiB here triggers it.
     */
    @Test
    fun largeUploadSwitchesToMultipart() {
        server.enqueue(okXml("<InitiateMultipartUploadResult><UploadId>UP1</UploadId></InitiateMultipartUploadResult>"))
        server.enqueue(MockResponse().setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setHeader("ETag", "\"e2\""))
        server.enqueue(okXml("<CompleteMultipartUploadResult><ETag>\"final\"</ETag></CompleteMultipartUploadResult>"))

        val data = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }
        fs().openOutput(XFile("s3", "/big.bin", isDir = false)).use { it.write(data) }

        assertEquals(4, server.requestCount)

        val init = server.takeRequest()
        assertEquals("POST", init.method)
        assertTrue(init.requestUrl!!.query!!.contains("uploads"))

        val p1 = server.takeRequest()
        assertEquals("PUT", p1.method)
        assertEquals("1", p1.requestUrl!!.queryParameter("partNumber"))
        assertEquals("UP1", p1.requestUrl!!.queryParameter("uploadId"))
        assertEquals(8L * 1024 * 1024, p1.bodySize)
        // Parts are not read a second time in full just to compute a hash for signing
        assertEquals(Sigv4.UNSIGNED, p1.getHeader("x-amz-content-sha256"))

        val p2 = server.takeRequest()
        assertEquals("2", p2.requestUrl!!.queryParameter("partNumber"))
        assertEquals(1L * 1024 * 1024, p2.bodySize) // the last part can be smaller than 5 MiB

        val done = server.takeRequest()
        assertEquals("POST", done.method)
        assertEquals("UP1", done.requestUrl!!.queryParameter("uploadId"))
        val xml = done.body.readUtf8()
        assertTrue(xml.contains("<PartNumber>1</PartNumber><ETag>\"e1\"</ETag>"))
        assertTrue(xml.contains("<PartNumber>2</PartNumber><ETag>\"e2\"</ETag>"))
    }

    /** A multipart upload that fails partway must be aborted, or the uploaded parts sit in the bucket forever, billed as storage. */
    @Test
    fun failedMultipartIsAborted() {
        server.enqueue(okXml("<InitiateMultipartUploadResult><UploadId>UP1</UploadId></InitiateMultipartUploadResult>"))
        server.enqueue(MockResponse().setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("<Error><Code>Boom</Code></Error>"))
        server.enqueue(MockResponse())

        val out = fs().openOutput(XFile("s3", "/big.bin", isDir = false))
        out.write(ByteArray(9 * 1024 * 1024))
        runCatching { out.close() }.let { assertTrue(it.isFailure) }

        repeat(3) { server.takeRequest() }
        val abort = server.takeRequest()
        assertEquals("DELETE", abort.method)
        assertEquals("UP1", abort.requestUrl!!.queryParameter("uploadId"))
    }

    /** complete replies 200 first and streams the result afterward — a failure is hidden in the body, not the status code. */
    @Test
    fun errorInsideSuccessfulCompleteIsDetected() {
        server.enqueue(okXml("<InitiateMultipartUploadResult><UploadId>UP1</UploadId></InitiateMultipartUploadResult>"))
        server.enqueue(MockResponse().setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setHeader("ETag", "\"e2\""))
        server.enqueue(okXml("<Error><Code>InternalError</Code><Message>try again</Message></Error>"))
        server.enqueue(MockResponse())

        val out = fs().openOutput(XFile("s3", "/big.bin", isDir = false))
        out.write(ByteArray(9 * 1024 * 1024))
        val e = runCatching { out.close() }.exceptionOrNull()
        assertTrue(e is FsException)
        assertTrue(e!!.message!!.contains("InternalError"))
        assertTrue(e.message!!.contains("try again"))
    }

    // ---- directories ----

    @Test
    fun mkdirWritesPlaceholderObject() {
        server.enqueue(MockResponse())
        val d = fs().mkdir(XFile("s3", "/docs", isDir = true), "sub")
        assertEquals("/docs/sub", d.path)
        assertTrue(d.isDir)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/buck/docs/sub/", req.requestUrl!!.encodedPath) // the trailing slash marks it as a directory
        assertEquals(0L, req.bodySize)
    }

    @Test
    fun deleteDirectoryRemovesEveryObjectUnderIt() {
        server.enqueue(
            okXml(listBody(contents = listOf(Triple("docs/", 0L, ""), Triple("docs/a.txt", 1L, "")))),
        )
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        fs().delete(XFile("s3", "/docs", isDir = true))

        val list = server.takeRequest()
        assertEquals("docs/", list.requestUrl!!.queryParameter("prefix"))
        assertEquals(null, list.requestUrl!!.queryParameter("delimiter")) // recursive: must not carry a delimiter
        assertEquals("/buck/docs/", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/buck/docs/a.txt", server.takeRequest().requestUrl!!.encodedPath)
    }

    /** When locked to a single bucket, the "root" is that bucket and must not be deletable. */
    @Test
    fun bucketRootCannotBeDeleted() {
        val e = runCatching { fs().delete(XFile("s3", "/", isDir = true)) }.exceptionOrNull()
        assertTrue(e is FsException)
        assertEquals(0, server.requestCount)
    }

    // ---- rename / move ----

    @Test
    fun renameCopiesServerSideThenDeletes() {
        server.enqueue(MockResponse().setResponseCode(404)) // exists(target) → does not exist
        server.enqueue(okXml("<CopyObjectResult><ETag>\"x\"</ETag></CopyObjectResult>"))
        server.enqueue(MockResponse())

        val out = fs().rename(XFile("s3", "/a.txt", isDir = false), "b.txt")
        assertEquals("/b.txt", out.path)

        server.takeRequest()
        val copy = server.takeRequest()
        assertEquals("PUT", copy.method)
        assertEquals("/buck/b.txt", copy.requestUrl!!.encodedPath)
        assertEquals("/buck/a.txt", copy.getHeader("x-amz-copy-source"))
        assertEquals(0L, copy.bodySize) // server-side transfer: the content never passes through the device

        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/buck/a.txt", del.requestUrl!!.encodedPath)
    }

    /** Contract: must throw when a target of the same name already exists, never silently overwrite. */
    @Test
    fun renameOntoExistingTargetFails() {
        server.enqueue(MockResponse()) // HEAD target → already exists
        val e = runCatching { fs().rename(XFile("s3", "/a.txt", isDir = false), "b.txt") }.exceptionOrNull()
        assertTrue(e is FsException)
        assertTrue(e!!.message!!.contains("exists"))
        assertEquals(1, server.requestCount)
    }

    /** Moving within the same endpoint goes through a server-side copy, not a download-then-upload. */
    @Test
    fun moveWithinIsServerSide() {
        server.enqueue(okXml("<CopyObjectResult/>"))
        server.enqueue(MockResponse())
        val moved = fs().moveWithin(
            XFile("s3", "/a.txt", isDir = false),
            XFile("s3", "/docs", isDir = true),
            "a.txt",
        )
        assertTrue(moved)
        assertEquals("/buck/a.txt", server.takeRequest().getHeader("x-amz-copy-source"))
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun renameDirectoryMovesEveryObject() {
        server.enqueue(MockResponse().setResponseCode(404)) // target does not exist (HEAD placeholder)
        server.enqueue(okXml(listBody())) // target does not exist (no objects under the prefix)
        server.enqueue(okXml(listBody(contents = listOf(Triple("old/a.txt", 1L, "")))))
        server.enqueue(okXml("<CopyObjectResult/>"))
        server.enqueue(MockResponse())

        fs().rename(XFile("s3", "/old", isDir = true), "new")

        server.takeRequest(); server.takeRequest(); server.takeRequest()
        val copy = server.takeRequest()
        assertEquals("/buck/new/a.txt", copy.requestUrl!!.encodedPath)
        assertEquals("/buck/old/a.txt", copy.getHeader("x-amz-copy-source"))
        assertEquals("DELETE", server.takeRequest().method)
    }

    // ---- errors ----

    /** The server's Code/Message is the only lead worth following up — never surface just "HTTP 403" to the user. */
    @Test
    fun serverErrorSurfacesCodeAndMessage() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                "<Error><Code>SignatureDoesNotMatch</Code>" +
                    "<Message>The request signature we calculated does not match</Message></Error>",
            ),
        )
        val e = runCatching { fs().list(XFile("s3", "/", isDir = true)) }.exceptionOrNull()
        assertTrue(e is FsException)
        assertTrue(e!!.message!!.contains("403"))
        assertTrue(e.message!!.contains("SignatureDoesNotMatch"))
        assertTrue(e.message!!.contains("does not match"))
    }

    @Test
    fun existsReportsMissingObject() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(fs().exists(XFile("s3", "/nope.txt", isDir = false)))
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun appendIsRejected() {
        val e = runCatching {
            fs().openOutput(XFile("s3", "/a.txt", isDir = false), append = true)
        }.exceptionOrNull()
        assertTrue(e is FsException)
    }

    private fun RecordedRequest.queryOf(name: String) = requestUrl!!.queryParameter(name)
}
