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
 * S3 的报文级测试。这类实现的 bug(签名少签一个头、名字编码不一致、分片上传少发
 * 一个请求)在真机上只表现成一句 "HTTP 403" 或"传上去的文件坏了",事后几乎无从
 * 归因,而在这里一发报文就能钉死。
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

    /** 默认:锁定单个桶、path-style(自建 MinIO 的常见形态)。 */
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

    // ---- 列目录 ----

    @Test
    fun listFoldsPrefixesIntoDirectories() {
        server.enqueue(
            okXml(
                listBody(
                    prefixes = listOf("docs/", "img/"),
                    contents = listOf(
                        Triple("a.txt", 12L, "2026-08-16T10:20:30.000Z"),
                        // 目录占位符:必须被滤掉,不能变成一个 0 字节的怪文件
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

    /** 一次列不完时要带着 continuation-token 接着列,而不是只显示第一页。 */
    @Test
    fun listFollowsPagination() {
        server.enqueue(okXml(listBody(contents = listOf(Triple("a", 1L, "")), truncated = "TOK/EN+1")))
        server.enqueue(okXml(listBody(contents = listOf(Triple("b", 1L, "")))))
        val items = fs().list(XFile("s3", "/", isDir = true))
        assertEquals(listOf("a", "b"), items.map { it.name })

        server.takeRequest()
        // token 不受 encoding-type 影响(是 opaque 的 base64),原样带回去即可:
        // 拿它当对象名去 form 解码会把里面的 '+' 变成空格,分页当场断掉
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

    /** 桶留空时,路径首段就是桶名。 */
    @Test
    fun pathFirstSegmentIsBucketWhenUnscoped() {
        server.enqueue(okXml(listBody(contents = listOf(Triple("x/y.txt", 1L, "")))))
        val items = fs(bucket = "").list(XFile("s3", "/mybucket/x", isDir = true))
        assertEquals(listOf("/mybucket/x/y.txt"), items.map { it.path })
        val req = server.takeRequest()
        assertEquals("/mybucket", req.requestUrl!!.encodedPath)
        assertEquals("x/", req.requestUrl!!.queryParameter("prefix"))
    }

    // ---- 名字编码 ----

    /**
     * 名字里带中文/空格/加号的对象:请求路径必须按 RFC 3986 编码,
     * 且**签名算的就是这份编码**(编码不一致 = SignatureDoesNotMatch)。
     */
    @Test
    fun objectNamesAreRfc3986Encoded() {
        server.enqueue(MockResponse().setBody("hi"))
        fs().openInput(XFile("s3", "/报告 v1+2.txt", isDir = false)).use { it.readBytes() }

        val req = server.takeRequest()
        assertEquals("/buck/%E6%8A%A5%E5%91%8A%20v1%2B2.txt", req.requestUrl!!.encodedPath)
        // 签名覆盖的路径就是发出去的那一份
        assertTrue(req.getHeader("Authorization")!!.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
    }

    /**
     * 服务端回的 key 是 **form 编码**的(★ 拿真 MinIO 打出来的:空格是 `+`、
     * 字面加号是 `%2B`),解码顺序反了 `a+b.txt` 就会变成 `a b.txt`。
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

    /** 目录名同样是 form 编码的。 */
    @Test
    fun encodedCommonPrefixesAreDecoded() {
        server.enqueue(okXml(listBody(prefixes = listOf("my+docs/", "a%2Bb/"))))
        assertEquals(
            listOf("a+b", "my docs"),
            fs().list(XFile("s3", "/", isDir = true)).map { it.name },
        )
    }

    // ---- 鉴权 ----

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

    /** virtual-host 风格:桶名进主机名,路径里就不该再出现它。 */
    @Test
    fun virtualHostStylePutsBucketInHost() {
        server.enqueue(okXml(listBody()))
        fs(pathStyle = false).list(XFile("s3", "/", isDir = true))
        val req = server.takeRequest()
        assertEquals("/", req.requestUrl!!.encodedPath)
        assertTrue(req.getHeader("Host")!!.startsWith("buck."))
    }

    // ---- 读 ----

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

    // ---- 写 ----

    /** 小于一片的对象走单次 PUT,不该多出 initiate/complete 两趟往返。 */
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
     * 超过一片就转分片上传:initiate → 每片一个 PUT → complete。
     * 分片大小是 8 MiB,这里写 9 MiB 触发它。
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
        // 分片不为签名再整读一遍算 hash
        assertEquals(Sigv4.UNSIGNED, p1.getHeader("x-amz-content-sha256"))

        val p2 = server.takeRequest()
        assertEquals("2", p2.requestUrl!!.queryParameter("partNumber"))
        assertEquals(1L * 1024 * 1024, p2.bodySize) // 最后一片可以小于 5 MiB

        val done = server.takeRequest()
        assertEquals("POST", done.method)
        assertEquals("UP1", done.requestUrl!!.queryParameter("uploadId"))
        val xml = done.body.readUtf8()
        assertTrue(xml.contains("<PartNumber>1</PartNumber><ETag>\"e1\"</ETag>"))
        assertTrue(xml.contains("<PartNumber>2</PartNumber><ETag>\"e2\"</ETag>"))
    }

    /** 分片上传中途失败要 abort,否则那些片永远躺在桶里按存储计费。 */
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

    /** complete 会先回 200 再流式发结果,失败信息藏在响应体里而不是状态码上。 */
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

    // ---- 目录 ----

    @Test
    fun mkdirWritesPlaceholderObject() {
        server.enqueue(MockResponse())
        val d = fs().mkdir(XFile("s3", "/docs", isDir = true), "sub")
        assertEquals("/docs/sub", d.path)
        assertTrue(d.isDir)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/buck/docs/sub/", req.requestUrl!!.encodedPath) // 尾斜杠是目录的标志
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
        assertEquals(null, list.requestUrl!!.queryParameter("delimiter")) // 递归:不能带 delimiter
        assertEquals("/buck/docs/", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/buck/docs/a.txt", server.takeRequest().requestUrl!!.encodedPath)
    }

    /** 锁定单桶时,"根" 就是那个桶,不能被删掉。 */
    @Test
    fun bucketRootCannotBeDeleted() {
        val e = runCatching { fs().delete(XFile("s3", "/", isDir = true)) }.exceptionOrNull()
        assertTrue(e is FsException)
        assertEquals(0, server.requestCount)
    }

    // ---- 改名 / 移动 ----

    @Test
    fun renameCopiesServerSideThenDeletes() {
        server.enqueue(MockResponse().setResponseCode(404)) // exists(target) → 不存在
        server.enqueue(okXml("<CopyObjectResult><ETag>\"x\"</ETag></CopyObjectResult>"))
        server.enqueue(MockResponse())

        val out = fs().rename(XFile("s3", "/a.txt", isDir = false), "b.txt")
        assertEquals("/b.txt", out.path)

        server.takeRequest()
        val copy = server.takeRequest()
        assertEquals("PUT", copy.method)
        assertEquals("/buck/b.txt", copy.requestUrl!!.encodedPath)
        assertEquals("/buck/a.txt", copy.getHeader("x-amz-copy-source"))
        assertEquals(0L, copy.bodySize) // 服务端搬运:内容不经过手机

        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/buck/a.txt", del.requestUrl!!.encodedPath)
    }

    /** 接口约定:同名目标已存在时必须抛,不得静默覆盖。 */
    @Test
    fun renameOntoExistingTargetFails() {
        server.enqueue(MockResponse()) // HEAD 目标 → 已存在
        val e = runCatching { fs().rename(XFile("s3", "/a.txt", isDir = false), "b.txt") }.exceptionOrNull()
        assertTrue(e is FsException)
        assertTrue(e!!.message!!.contains("exists"))
        assertEquals(1, server.requestCount)
    }

    /** 同一端点内移动走服务端 copy,不下载再上传。 */
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
        server.enqueue(MockResponse().setResponseCode(404)) // 目标不存在(HEAD 占位符)
        server.enqueue(okXml(listBody())) // 目标不存在(前缀下无对象)
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

    // ---- 出错 ----

    /** 服务端的 Code/Message 是唯一能往下查的线索,不能只丢一句 HTTP 403 给用户。 */
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
