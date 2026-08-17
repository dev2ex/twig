package com.twig.fs.network

import com.twig.core.XFile
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavFileSystemTest {

    private lateinit var server: MockWebServer
    private lateinit var fs: WebDavFileSystem

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        fs = WebDavFileSystem(DavConfig(server.url("/dav").toString(), "u", "p"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listParsesMultistatus() {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/dav/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/dav/docs/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
                      <d:getlastmodified>Mon, 29 Jun 2026 10:00:00 GMT</d:getlastmodified>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/dav/%E6%8A%A5%E5%91%8A.txt</d:href>
                    <d:propstat><d:prop><d:resourcetype/>
                      <d:getcontentlength>5</d:getcontentlength>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent(),
            ),
        )

        val items = fs.list(fs.root())
        assertEquals(listOf("docs:true", "报告.txt:false"), items.map { "${it.name}:${it.isDir}" })
        assertEquals(5L, items.first { !it.isDir }.size)
        assertTrue(items.first { it.isDir }.lastModified > 0)

        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("1", req.getHeader("Depth"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun readsFileViaGet() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))

        val text = fs.openInput(XFile("dav", "/a.txt", false))
            .bufferedReader().use { it.readText() }
        assertEquals("hello", text)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/dav/a.txt", req.path)
    }

    @Test
    fun writesFileViaPut() {
        server.enqueue(MockResponse().setResponseCode(201))

        fs.openOutput(XFile("dav", "/b.txt", false)).use { it.write("data-123".toByteArray()) }

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/dav/b.txt", req.path)
        assertEquals("data-123", req.body.readUtf8())
    }

    @Test
    fun mkdirSendsMkcol() {
        server.enqueue(MockResponse().setResponseCode(201))

        fs.mkdir(fs.root(), "sub")

        val req = server.takeRequest()
        assertEquals("MKCOL", req.method)
        assertEquals("/dav/sub/", req.path)
    }

    @Test
    fun renameSendsMoveWithDestination() {
        server.enqueue(MockResponse().setResponseCode(201))

        fs.rename(XFile("dav", "/old.txt", false), "new.txt")

        val req = server.takeRequest()
        assertEquals("MOVE", req.method)
        assertEquals("/dav/old.txt", req.path)
        assertTrue(req.getHeader("Destination")!!.endsWith("/dav/new.txt"))
    }
}
