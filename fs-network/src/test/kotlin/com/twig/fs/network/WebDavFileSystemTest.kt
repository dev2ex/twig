package com.twig.fs.network

import com.twig.core.StorageSpace
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

    private fun sourceListing(scope: String = "all-sources", status: Int = 200,
                              namespace: String = TwigDavSources.NAMESPACE,
                              markerOnChild: Boolean = false): String {
        fun marker() = "<t:scope xmlns:t=\"$namespace\">$scope</t:scope>"
        return """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href><d:propstat><d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
                ${if (!markerOnChild) marker() else ""}
              </d:prop><d:status>HTTP/1.1 $status Status</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/SMB-NAS-West/</d:href><d:propstat><d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
                ${if (markerOnChild) marker() else ""}
              </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
    }

    @Test fun sourceNamesRequireSuccessfulScopeOnEndpointAndRetainRealPaths() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(sourceListing()))
        val child = fs.list(fs.root()).single()
        assertEquals("/SMB-NAS-West", child.path)
        assertEquals("SMB-NAS-West", child.name)
        assertEquals(com.twig.core.SourcePresentation("smb", "NAS-West"), fs.sourcePresentation(child))
        assertEquals(null, fs.sourcePresentation(child.copy(path = "/photos/SMB-NAS-West")))
        assertEquals(null, fs.sourcePresentation(child.copy(isDir = false)))
        assertEquals(null, fs.sourcePresentation(child.copy(path = "/Unknown-name")))
        assertTrue(server.takeRequest().body.readUtf8().contains("urn:twig:share"))
        for (body in listOf(sourceListing(scope = "directory"), sourceListing(status = 404),
            sourceListing(namespace = "urn:other"), sourceListing(markerOnChild = true), quotaResponse("1", "2"))) {
            server.enqueue(MockResponse().setResponseCode(207).setBody(body))
            fs.list(fs.root())
            assertEquals(null, fs.sourcePresentation(child))
        }
    }

    @Test fun sourcePrefixesPreserveNamesAndOnlyRecognizeKnownCompletePrefixes() {
        val cases = mapOf("Internal-Storage" to "internal", "External-SD-card" to "external",
            "Root" to "root", "Apps" to "apps", "Trash" to "trash", "SMB-NAS" to "smb",
            "SFTP-host" to "sftp", "WebDAV-server" to "webdav", "S3-bucket" to "s3")
        cases.forEach { (name, type) -> assertEquals(type, TwigDavSources.parse(name)?.type) }
        assertEquals("SD-card", TwigDavSources.parse("External-SD-card")?.name)
        listOf("SMB-", "SMB", "SMBish-name", "Root-other", "unknown-NAS").forEach {
            assertEquals(null, TwigDavSources.parse(it))
        }
    }

    private fun quotaResponse(available: String, used: String, status: Int = 200): String = """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:quota-available-bytes>$available</d:quota-available-bytes>
              <d:quota-used-bytes>$used</d:quota-used-bytes></d:prop>
              <d:status>HTTP/1.1 $status Status</d:status></d:propstat>
          </d:response>
          <d:response><d:href>/dav/SD+card%2520/</d:href><d:propstat><d:prop>
            <d:resourcetype><d:collection/></d:resourcetype>
            <d:quota-available-bytes>0</d:quota-available-bytes>
            <d:quota-used-bytes>60</d:quota-used-bytes>
          </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun listingCachesRootAndChildQuotasWithoutExtraRequests() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(quotaResponse("40", "60")))
        val child = fs.list(fs.root()).single()
        assertEquals("/SD+card%20", child.path)
        assertEquals(StorageSpace(40, 60), fs.knownStorageSpace(fs.root()))
        assertEquals(StorageSpace(0, 60), fs.knownStorageSpace(child))
        assertEquals(100L, fs.storageSpace(fs.root())!!.total)
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("1", req.getHeader("Depth"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("quota-available-bytes"))
        assertTrue(body.contains("quota-used-bytes"))
    }

    @Test
    fun largeListingsRetainRootCapacityAndStatDoesNotEraseIt() {
        val extra = (1..300).joinToString("") { i -> """
            <d:response><d:href>/dav/dir$i/</d:href><d:propstat><d:prop>
              <d:resourcetype><d:collection/></d:resourcetype>
              <d:quota-available-bytes>1</d:quota-available-bytes>
              <d:quota-used-bytes>1</d:quota-used-bytes>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        """.trimIndent() }
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            quotaResponse("40", "60").replace("</d:multistatus>", "$extra</d:multistatus>")))
        assertEquals(301, fs.list(fs.root()).size)
        assertEquals(StorageSpace(40, 60), fs.knownStorageSpace(fs.root()))
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(207).setBody(quotaResponse("", "")))
        fs.stat("/dir1")
        assertEquals(StorageSpace(40, 60), fs.knownStorageSpace(fs.root()))
        assertTrue(!server.takeRequest().body.readUtf8().contains("quota-available-bytes"))
    }

    @Test
    fun quotasRejectFailedPropertiesInvalidValuesAndOverflow() {
        for ((available, used, status) in listOf(
            Triple("40", "60", 404), Triple("-1", "60", 200),
            Triple("unlimited", "60", 200), Triple(Long.MAX_VALUE.toString(), "1", 200),
        )) {
            server.enqueue(MockResponse().setResponseCode(207).setBody(quotaResponse(available, used, status)))
            fs.list(fs.root())
            assertEquals(null, fs.knownStorageSpace(fs.root()))
        }
        server.enqueue(MockResponse().setResponseCode(207).setBody(quotaResponse("40", "60")))
        fs.list(fs.root())
        assertEquals(StorageSpace(40, 60), fs.knownStorageSpace(fs.root()))
        server.enqueue(MockResponse().setResponseCode(207).setBody(quotaResponse("", "")))
        fs.list(fs.root())
        assertEquals(null, fs.knownStorageSpace(fs.root()))
    }

    @Test
    fun uncachedQuotaUsesDepthZeroAndUnsupportedQuotaStaysUnknown() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(quotaResponse("", "", 404)))
        assertEquals(null, fs.storageSpace(fs.root()))
        assertEquals(null, fs.storageSpace(fs.root()))
        assertEquals(1, server.requestCount)
        assertEquals("0", server.takeRequest().getHeader("Depth"))
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

    /** stat is a `Depth: 0` PROPFIND, and the entry it describes is the file itself (not filtered out as "self"). */
    @Test
    fun statUsesDepthZeroAndKeepsTheEntry() {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/dav/notes.txt</d:href>
                    <d:propstat><d:prop><d:resourcetype/>
                      <d:getcontentlength>17</d:getcontentlength>
                      <d:getlastmodified>Mon, 29 Jun 2026 10:00:00 GMT</d:getlastmodified>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent(),
            ),
        )
        val got = fs.stat("/notes.txt")!!
        assertEquals("/notes.txt", got.path)
        assertEquals(17L, got.size)
        assertTrue(got.lastModified > 0)

        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("0", req.getHeader("Depth"))
    }

    @Test
    fun statIsNullWhenTheServerSaysNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(null, fs.stat("/gone.txt"))
    }

    /**
     * XXE: a hostile server's DOCTYPE must not make the client read local files into the
     * listing (2026-09-17 review). Parsing may fail outright — that is fine; what must never
     * happen is the secret showing up as a file name.
     */
    @Test
    fun externalEntitiesAreNotResolved() {
        val secret = java.io.File.createTempFile("twig-xxe", ".txt").apply { writeText("TOPSECRET") }
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0"?>
                <!DOCTYPE d:multistatus [<!ENTITY leak SYSTEM "${secret.toURI()}">]>
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/dav/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
                  </d:response>
                  <d:response><d:href>/dav/x&leak;.txt</d:href>
                    <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent(),
            ),
        )
        val names = runCatching { fs.list(fs.root()).map { it.name } }.getOrDefault(emptyList())
        assertTrue("leaked: $names", names.none { "TOPSECRET" in it })
        secret.delete()
    }
}
