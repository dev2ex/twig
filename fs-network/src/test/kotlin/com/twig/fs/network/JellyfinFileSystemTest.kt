package com.twig.fs.network

import com.twig.core.FsException
import com.twig.core.MediaStream
import com.twig.core.PlayState
import com.twig.core.TextDecoding
import com.twig.core.XFile
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests: pin down **the bytes we actually send** and how we interpret the
 * response shape.
 *
 * This layer cannot prove "a real server will accept this" — that is `JellyfinLiveTest`'s
 * job (the S3 encoding trap could only be caught by a real server, see `CLAUDE.md`). This
 * layer covers the other half: whether the endpoint/params/body are right, and whether a
 * change in response shape/query params gets caught immediately — most failures of that
 * kind **do not throw**, they just show an empty directory or wrong content, and wire-level
 * assertions are the only thing that pins those down.
 */
class JellyfinFileSystemTest {

    private lateinit var server: MockWebServer
    private val seen = java.util.Collections.synchronizedList(ArrayList<RecordedRequest>())

    /** Response per route; matched by suffix of encodedPath, overridden per test as needed. */
    private val routes = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()

    @Before
    fun setup() {
        server = MockWebServer()
        routes["/Users/AuthenticateByName"] = {
            json("""{"AccessToken":"tok","User":{"Id":"u1","Name":"vale"}}""")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.requestUrl?.encodedPath.orEmpty()
                for ((k, v) in routes) if (path.endsWith(k)) return v(request)
                return MockResponse().setResponseCode(404).setBody("no route for $path")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fs(
        apiKey: String = "",
        labels: Map<String, String> = emptyMap(),
        onAuth: (String, String) -> Unit = { _, _ -> },
    ) = JellyfinFileSystem(
        JellyfinConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            user = "vale", password = "pw", apiKey = apiKey,
            deviceId = "dev-1", clientVersion = "9.9", labels = labels, onAuth = onAuth,
        ),
    )

    private fun dir(path: String) = XFile("jellyfin", path, isDir = true)

    private fun reqTo(suffix: String): RecordedRequest =
        seen.first { it.requestUrl?.encodedPath.orEmpty().endsWith(suffix) }

    private val movie = """
        {"Id":"m1","Name":"Blade Runner","Type":"Movie","IsFolder":false,"MediaType":"Video",
         "Path":"/data/movies/Blade Runner (1982).mkv","DateCreated":"2026-01-02T03:04:05.0000000Z",
         "MediaSources":[{"Size":1234567,"Container":"mkv"}],
         "ImageTags":{"Primary":"abc"},
         "UserData":{"PlaybackPositionTicks":600000000,"Played":false}}
    """.trimIndent()

    // ---- Authentication ----

    @Test
    fun `username password login sends token on every request`() {
        routes["/Users/u1/Items"] = { json("""{"Items":[]}""") }
        var savedToken = ""
        var savedUser = ""
        fs(onAuth = { t, u -> savedToken = t; savedUser = u }).list(dir("/folders/lib1"))

        val login = reqTo("/Users/AuthenticateByName")
        val body = JSONObject(login.body.readUtf8())
        assertEquals("vale", body.getString("Username"))
        assertEquals("pw", body.getString("Pw"))
        // The login request itself must not carry Token= (there isn't one yet), but
        // Client/Device/DeviceId/Version must all be present — Jellyfin answers 400
        // outright if any of them is missing
        val loginAuth = login.getHeader("Authorization").orEmpty()
        assertTrue(loginAuth, loginAuth.contains("""Client="Twig""""))
        assertTrue(loginAuth, loginAuth.contains("""DeviceId="dev-1""""))
        assertTrue(loginAuth, loginAuth.contains("""Version="9.9""""))
        assertTrue(loginAuth, !loginAuth.contains("Token="))

        val list = reqTo("/Users/u1/Items")
        assertTrue(list.getHeader("Authorization").orEmpty().contains("""Token="tok""""))
        assertEquals("tok", list.getHeader("X-Emby-Token"))
        // Emby recognizes the old header name, so send it too
        assertNotNull(list.getHeader("X-Emby-Authorization"))

        assertEquals("tok", savedToken)
        assertEquals("u1", savedUser)
    }

    @Test
    fun `api key mode skips login and locates the user via Users`() {
        routes["/Users"] = { json("""[{"Id":"a","Name":"other"},{"Id":"b","Name":"vale"}]""") }
        routes["/Users/b/Items"] = { json("""{"Items":[]}""") }
        fs(apiKey = "key-123").list(dir("/folders/lib1"))

        assertTrue(seen.none { it.requestUrl?.encodedPath.orEmpty().endsWith("AuthenticateByName") })
        // Picked by name is b, not the first one
        assertEquals("key-123", reqTo("/Users/b/Items").getHeader("X-Emby-Token"))
    }

    @Test
    fun `expired token (401) triggers automatic re-login and retry`() {
        var first = true
        routes["/Users/u1/Items"] = {
            if (first) { first = false; MockResponse().setResponseCode(401) } else json("""{"Items":[]}""")
        }
        fs().list(dir("/folders/lib1"))

        // Logs in twice (once at the start + once after 401), lists twice
        assertEquals(2, seen.count { it.requestUrl?.encodedPath.orEmpty().endsWith("AuthenticateByName") })
        assertEquals(2, seen.count { it.requestUrl?.encodedPath.orEmpty().endsWith("/Users/u1/Items") })
    }

    @Test
    fun `wrong password reports wrong password, not a bare HTTP 401`() {
        routes["/Users/AuthenticateByName"] = { MockResponse().setResponseCode(401) }
        val e = runCatching { fs().list(dir("/folders/lib1")) }.exceptionOrNull()
        assertTrue(e is FsException)
        assertTrue(e!!.message.orEmpty(), e.message.orEmpty().contains("wrong username or password"))
    }

    // ---- Virtual tree ----

    @Test
    fun `root entries reflect only the libraries that actually exist on the server`() {
        // ★ It used to be a hardcoded list of twelve items: with no music library, "Music"
        // and "Latest Music" would still show up, and opening them was empty.
        // Now only what actually exists is listed — this server has no playlist library,
        // so that row should not appear.
        routes["/Users/u1/Views"] = {
            json(
                """{"Items":[
                  {"Id":"lib1","Name":"电影","CollectionType":"movies"},
                  {"Id":"lib2","Name":"合集","CollectionType":"boxsets"}]}""",
            )
        }
        val rows = fs(labels = mapOf("resume" to "继续观看", "latest" to "最新")).list(dir("/"))
        // Media libraries sit directly at the root (named as the user set them on the
        // server), sandwiched between "Latest" and "Folders"
        assertEquals(
            listOf("resume", "collections", "latest", "lib/lib1", "folders"),
            rows.map { it.path.trimStart('/') },
        )
        assertEquals("继续观看", rows[0].name)
        assertEquals("最新", rows[2].name)
        assertEquals("电影", rows[3].name) // library name copied verbatim from the server
        // falls back to the built-in English name when the table has no entry
        assertEquals("Collections", rows[1].name)
        assertTrue(rows.all { it.isDir && !it.canWrite })
    }

    @Test
    fun `no libraries means no Latest or library rows`() {
        routes["/Users/u1/Views"] = { json("""{"Items":[]}""") }
        assertEquals(listOf("resume"), fs().list(dir("/")).map { it.path.trimStart('/') })
    }

    @Test
    fun `the first level under Latest and library entries is the library itself, named as the user set it`() {
        routes["/Users/u1/Views"] = {
            json(
                """{"Items":[
                  {"Id":"lib1","Name":"日剧","CollectionType":"tvshows"},
                  {"Id":"lib2","Name":"纪录片","CollectionType":"movies"},
                  {"Id":"lib3","Name":"Playlists","CollectionType":"playlists"},
                  {"Id":"lib4","Name":"Folders","CollectionType":"folders"}]}""",
            )
        }
        // Playlists (which already has its own root entry) and that nested "Folders"
        // view do not count as browsable libraries
        for (p in listOf("/latest", "/folders")) {
            val libs = fs().list(dir(p))
            assertEquals(p, listOf("日剧", "纪录片"), libs.map { it.name })
            assertEquals(p, listOf("$p/lib1", "$p/lib2"), libs.map { it.path })
            seen.clear()
        }
    }

    @Test
    fun `library items carry a real extension and byte size`() {
        routes["/Users/u1/Items"] = { json("""{"Items":[$movie],"TotalRecordCount":1}""") }
        val rows = fs().list(dir("/folders/lib1"))

        val url = reqTo("/Users/u1/Items").requestUrl!!
        assertEquals("lib1", url.queryParameter("ParentId"))
        // Path gives the extension, MediaSources gives the size (without it every row
        // would show 0 bytes), Width/Height give a photo's pixel dimensions
        // (those two fields are only returned when explicitly requested)
        assertEquals("Path,MediaSources,DateCreated,Width,Height", url.queryParameter("Fields"))

        val f = rows.single()
        // The extension comes from the server's Path; the player relies entirely on it
        // to identify the container
        assertEquals("Blade Runner.mkv", f.name)
        assertEquals(1234567L, f.size)
        assertEquals("/folders/lib1/m1", f.path)
        assertTrue(!f.isDir && !f.canWrite)
        assertTrue(f.lastModified > 0L)
    }

    @Test
    fun `directory is inferred from type when the server omits IsFolder`() {
        // ★ Specialized endpoints like `/Artists` may not return IsFolder at all; trusting
        // its absence turns artists and albums into "files" — they can't be opened, and
        // they get probed for byte size (this is exactly what the user reported)
        routes["/Users/u1/Views"] = {
            json("""{"Items":[{"Id":"lib1","Name":"音乐","CollectionType":"music"}]}""")
        }
        routes["/Artists"] = {
            json("""{"Items":[{"Id":"ar1","Name":"清风乐团","Type":"MusicArtist"}]}""")
        }
        val rows = fs().list(dir("/lib/lib1/@artists"))
        assertTrue("an artist should be an expandable directory", rows.single().isDir)
        // A directory should not be probed for size (that is for "files the server does
        // not give a size for")
        assertTrue(seen.none { it.requestUrl!!.encodedPath.endsWith("/Download") })
    }

    @Test
    fun `the music library opens into four fixed sections, not a pile of physical folders`() {
        // The server's music library root returns physical directories (in testing, Emby
        // returned Folder/<artist name>), but what the user wants is the same tabs the
        // official clients show
        routes["/Users/u1/Views"] = {
            json("""{"Items":[{"Id":"lib1","Name":"我的音乐","CollectionType":"music"}]}""")
        }
        val secs = fs(
            labels = mapOf("@albums" to "专辑", "@albumartists" to "专辑艺术家", "@artists" to "艺术家"),
        ).list(dir("/lib/lib1"))
        assertEquals(listOf("专辑", "专辑艺术家", "艺术家", "Folders"), secs.map { it.name })
        assertEquals(
            listOf("/lib/lib1/@albums", "/lib/lib1/@albumartists", "/lib/lib1/@artists", "/lib/lib1/@folder"),
            secs.map { it.path },
        )
    }

    @Test
    fun `each of the four music sections issues its own query`() {
        routes["/Users/u1/Views"] = {
            json("""{"Items":[{"Id":"lib1","Name":"音乐","CollectionType":"music"}]}""")
        }
        routes["/Users/u1/Items"] = { json("""{"Items":[]}""") }
        routes["/Artists/AlbumArtists"] = { json("""{"Items":[]}""") }
        routes["/Artists"] = { json("""{"Items":[]}""") }
        val f = fs()

        f.list(dir("/lib/lib1/@albums"))
        reqTo("/Users/u1/Items").requestUrl!!.let {
            assertEquals("MusicAlbum", it.queryParameter("IncludeItemTypes"))
            assertEquals("lib1", it.queryParameter("ParentId"))
        }
        seen.clear()
        f.list(dir("/lib/lib1/@albumartists"))
        assertEquals("lib1", reqTo("/Artists/AlbumArtists").requestUrl!!.queryParameter("ParentId"))
        seen.clear()
        f.list(dir("/lib/lib1/@artists"))
        assertTrue(seen.any { it.requestUrl!!.encodedPath.endsWith("/Artists") })
        seen.clear()
        // ★ "Folders" needs **every directory that directly contains music files** (same
        // idea as the albums case), not "the library root, clicked down level by level" —
        // the server's notion of "a directory containing music" is exactly MusicAlbum, so
        // it uses the same query as "Albums", but it **must be recursive**
        f.list(dir("/lib/lib1/@folder"))
        reqTo("/Users/u1/Items").requestUrl!!.let {
            assertEquals("lib1", it.queryParameter("ParentId"))
            assertEquals("MusicAlbum", it.queryParameter("IncludeItemTypes"))
            assertEquals("true", it.queryParameter("Recursive"))
        }
    }

    @Test
    fun `movie and show libraries fetch items recursively instead of exposing subfolders`() {
        // ★ It used to use the library root directly, which looked correct on a test
        // library with no subfolders; but once directories are split by year/region, the
        // library root just returns Folder/2020, Folder/Japanese Drama — **the folders
        // themselves** — and you had to click down level by level to see the actual movies
        // (this is exactly what the user reported).
        routes["/Users/u1/Views"] = {
            json(
                """{"Items":[
                  {"Id":"lib1","Name":"电影","CollectionType":"movies"},
                  {"Id":"lib2","Name":"剧集","CollectionType":"tvshows"}]}""",
            )
        }
        routes["/Users/u1/Items"] = { json("""{"Items":[]}""") }
        val f = fs()
        f.list(dir("/lib/lib1"))
        reqTo("/Users/u1/Items").requestUrl!!.let {
            assertEquals("Movie", it.queryParameter("IncludeItemTypes"))
            assertEquals("true", it.queryParameter("Recursive"))
            assertEquals("lib1", it.queryParameter("ParentId"))
        }
        seen.clear()
        f.list(dir("/lib/lib2"))
        reqTo("/Users/u1/Items").requestUrl!!.let {
            assertEquals("Series", it.queryParameter("IncludeItemTypes"))
            assertEquals("true", it.queryParameter("Recursive"))
        }
    }

    @Test
    fun `an unrecognized mixed library falls back to the library root`() {
        // that kind of library has no "natural" single level to begin with, so give the
        // server's structure as-is
        routes["/Users/u1/Views"] = { json("""{"Items":[{"Id":"lib9","Name":"杂物"}]}""") }
        routes["/Users/u1/Items"] = { json("""{"Items":[]}""") }
        fs().list(dir("/lib/lib9"))
        val q = reqTo("/Users/u1/Items").requestUrl!!
        assertEquals("lib9", q.queryParameter("ParentId"))
        assertNull(q.queryParameter("IncludeItemTypes"))
    }

    @Test
    fun `a photo library needs every viewable album directory, not just the top-level ones`() {
        routes["/Users/u1/Views"] = {
            json("""{"Items":[{"Id":"lib1","Name":"家庭相册","CollectionType":"homevideos"}]}""")
        }
        routes["/Users/u1/Items"] = {
            json("""{"Items":[{"Id":"al1","Name":"假期","Type":"PhotoAlbum","IsFolder":true}]}""")
        }
        assertEquals(listOf("假期"), fs().list(dir("/lib/lib1")).map { it.name })
        val q = reqTo("/Users/u1/Items").requestUrl!!
        assertEquals("PhotoAlbum", q.queryParameter("IncludeItemTypes"))
        assertEquals("true", q.queryParameter("Recursive")) // nested sub-albums need flattening too
    }

    @Test
    fun `a single-season series skips the season layer`() {
        // an extra click adds no information; a multi-season series is still grouped by season
        routes["/Users/u1/Items"] = { req ->
            when (req.requestUrl!!.queryParameter("ParentId")) {
                "series-1" -> json("""{"Items":[{"Id":"se-1","Name":"Season 1","Type":"Season","IsFolder":true}]}""")
                else -> json(
                    """{"Items":[{"Id":"e1","Name":"第一集","Type":"Episode","MediaType":"Video",
                       "ParentIndexNumber":1,"IndexNumber":1,"MediaSources":[{"Container":"mkv"}]}]}""",
                )
            }
        }
        val rows = fs().list(dir("/lib/lib1/series-1"))
        assertEquals(listOf("S01E01 - 第一集.mkv"), rows.map { it.name })
        assertTrue("should list episodes directly, not a season directory", rows.none { it.isDir })
    }

    @Test
    fun `a multi-season series still groups by season`() {
        routes["/Users/u1/Items"] = {
            json(
                """{"Items":[
                  {"Id":"se-1","Name":"第一季","Type":"Season","IsFolder":true},
                  {"Id":"se-2","Name":"第二季","Type":"Season","IsFolder":true}]}""",
            )
        }
        val rows = fs().list(dir("/lib/lib1/series-1"))
        assertEquals(listOf("第一季", "第二季"), rows.map { it.name })
        assertTrue(rows.all { it.isDir })
    }

    @Test
    fun `a library's Latest query needs ParentId and GroupItems=false`() {
        // ★ Two traps (settled by watching what each official web client actually sends):
        //  - Latest **is unscoped without ParentId**, returning any type at all — an
        //    earlier version attributed this to "Emby ignores IncludeItemTypes", which was
        //    an incomplete conclusion: adding ParentId cleans it up on both servers;
        //  - Only **GroupItems=false** gives individual tracks/episodes; the default
        //    returns containers like albums and series, so "Latest Music" ended up listing
        //    a pile of directories requiring another click (this is exactly what the user
        //    reported).
        routes["/Users/u1/Items/Latest"] = {
            json("""[{"Id":"a1","Name":"夜航","Type":"Audio","MediaSources":[{"Container":"mp3"}]}]""")
        }
        val rows = fs().list(dir("/latest/lib-music"))
        assertEquals(listOf("夜航.mp3"), rows.map { it.name })
        assertTrue("should give directly playable tracks, not albums requiring another click", rows.none { it.isDir })
        assertEquals("/latest/lib-music/a1", rows.single().path)

        val q = reqTo("/Items/Latest").requestUrl!!
        assertEquals("lib-music", q.queryParameter("ParentId"))
        assertEquals("false", q.queryParameter("GroupItems"))
        assertEquals("60", q.queryParameter("Limit"))
    }

    @Test
    fun `a bare array response still parses correctly`() {
        // We no longer call Items/Latest (which answers with a bare array), but the
        // parser still accepts both shapes: if a future server version returns one, it
        // won't silently turn into an empty directory
        routes["/Users/u1/Items/Resume"] = { json("""[$movie]""") }
        assertEquals(1, fs().list(dir("/resume")).size)
    }

    @Test
    fun `the same movie under two entry points gets different paths, so the tree keys never collide`() {
        routes["/Users/u1/Items/Resume"] = { json("""{"Items":[$movie]}""") }
        routes["/Users/u1/Items"] = { json("""{"Items":[$movie]}""") }
        val f = fs()
        val fromResume = f.list(dir("/resume")).single()
        val fromMovies = f.list(dir("/folders/lib1")).single()
        // The row key is f:<scheme>:<path>; if both were the same, DiffUtil would treat
        // them as the same row and expanding one would leave the other empty
        assertEquals("/resume/m1", fromResume.path)
        assertEquals("/folders/lib1/m1", fromMovies.path)
    }

    @Test
    fun `the series name comes first, followed by SxxEyy and the episode title`() {
        // In a flat list (resume/latest episodes), SxxExx alone gives no way to tell which
        // show it belongs to; it also makes a more useful file name when copied locally
        routes["/Users/u1/Items"] = {
            json(
                """{"Items":[
                  {"Id":"e2","Name":"Second","Type":"Episode","MediaType":"Video","SeriesName":"权游",
                   "ParentIndexNumber":1,"IndexNumber":2,"MediaSources":[{"Container":"mp4"}]},
                  {"Id":"e10","Name":"Tenth","Type":"Episode","MediaType":"Video","SeriesName":"权游",
                   "ParentIndexNumber":1,"IndexNumber":10,"MediaSources":[{"Container":"mkv"}]}
                ]}""",
            )
        }
        val rows = fs().list(dir("/folders/lib1/season-1"))
        assertEquals(listOf("权游 - S01E02 - Second.mp4", "权游 - S01E10 - Tenth.mkv"), rows.map { it.name })
        // Below the second level, children are always listed by ParentId, taken from the
        // last path segment
        assertEquals("season-1", reqTo("/Users/u1/Items").requestUrl!!.queryParameter("ParentId"))
    }

    @Test
    fun `text already present in the title is not appended again`() {
        // For episodes with no scraped metadata, Name is just the file name, which often
        // already includes both the series name and SxxExx (observed on real Emby). Without
        // dedup this becomes "Zephyr - S01E01 - Zephyr - S01E01.mkv"
        routes["/Users/u1/Items"] = {
            json(
                """{"Items":[{"Id":"e1","Name":"Zephyr - S01E01","Type":"Episode","MediaType":"Video",
                   "SeriesName":"Zephyr","ParentIndexNumber":1,"IndexNumber":1,
                   "MediaSources":[{"Container":"mkv"}]}]}""",
            )
        }
        assertEquals("Zephyr - S01E01.mkv", fs().list(dir("/folders/lib1/se")).single().name)
    }

    @Test
    fun `an episode entry without an episode number still carries the series name`() {
        routes["/Users/u1/Items"] = {
            json(
                """{"Items":[{"Id":"e1","Name":"特别篇","Type":"Episode","MediaType":"Video",
                   "SeriesName":"某剧","MediaSources":[{"Container":"mkv"}]}]}""",
            )
        }
        assertEquals("某剧 - 特别篇.mkv", fs().list(dir("/folders/lib1/se")).single().name)
    }

    @Test
    fun `same-name siblings get a distinguishing number appended`() {
        routes["/Users/u1/Items"] = {
            json(
                """{"Items":[
                  {"Id":"d1","Name":"Dune","Type":"Movie","MediaType":"Video","MediaSources":[{"Container":"mkv"}]},
                  {"Id":"d2","Name":"Dune","Type":"Movie","MediaType":"Video","MediaSources":[{"Container":"mkv"}]}
                ]}""",
            )
        }
        val rows = fs().list(dir("/folders/lib1"))
        assertEquals(listOf("Dune.mkv", "Dune (2).mkv"), rows.map { it.name })
    }

    @Test
    fun `a slash in the name does not break path semantics`() {
        routes["/Users/u1/Items"] = {
            json("""{"Items":[{"Id":"x","Name":"AC/DC Live","Type":"MusicAlbum","IsFolder":true}]}""")
        }
        assertEquals("AC_DC Live", fs().list(dir("/folders/lib1")).single().name)
    }

    @Test
    fun `the Folders entry lists the media libraries`() {
        routes["/Users/u1/Views"] = {
            json("""{"Items":[{"Id":"lib1","Name":"电影库","IsFolder":true,"CollectionType":"movies"}]}""")
        }
        val rows = fs().list(dir("/folders"))
        assertEquals("电影库", rows.single().name)
        assertTrue(rows.single().isDir)
        assertEquals("/folders/lib1", rows.single().path)
    }

    @Test
    fun `playlists and collections each get one root entry`() {
        routes["/Users/u1/Items"] = { json("""{"Items":[$movie]}""") }
        val f = fs()
        f.list(dir("/playlists"))
        assertEquals("Playlist", reqTo("/Users/u1/Items").requestUrl!!.queryParameter("IncludeItemTypes"))
        seen.clear()
        f.list(dir("/collections"))
        assertEquals("BoxSet", reqTo("/Users/u1/Items").requestUrl!!.queryParameter("IncludeItemTypes"))
    }

    @Test
    fun `playlist contents skip SortBy to preserve the curated order`() {
        // ★ The order inside a playlist is the user's own curated play order. In testing:
        // a list curated as 8, 6 came back as 6, 8 once SortBy=IsFolder,SortName was added
        // — that outright destroys the playlist.
        routes["/Users/u1/Items"] = { json("""{"Items":[$movie]}""") }
        val f = fs()
        f.list(dir("/playlists/pl1"))
        val q = reqTo("/Users/u1/Items").requestUrl!!
        assertEquals("pl1", q.queryParameter("ParentId"))
        assertNull("playlist contents should not carry SortBy", q.queryParameter("SortBy"))

        // Control: an ordinary directory still needs to be sorted (otherwise the order of
        // the whole tree would depend on the server's mood)
        seen.clear()
        f.list(dir("/folders/lib1"))
        assertEquals("IsFolder,SortName", reqTo("/Users/u1/Items").requestUrl!!.queryParameter("SortBy"))
    }

    @Test
    fun `the Folders view excludes playlists, collections, and the nested folders view`() {
        // Playlists/collections already each have a dedicated root entry, so showing them
        // again here means the same items appearing in two places; "folders" is the extra
        // UserView the server adds when "show media folders" is enabled (observed on real
        // Jellyfin 10.11: Type=UserView, CollectionType=folders), and opening it just shows
        // the same libraries again — since this layer already browses by library, wrapping
        // it in another identically-named "Folders" is purely an extra click
        routes["/Users/u1/Views"] = {
            json(
                """{"Items":[
                  {"Id":"lib1","Name":"电影库","IsFolder":true,"CollectionType":"movies"},
                  {"Id":"lib2","Name":"Playlists","IsFolder":true,"CollectionType":"playlists"},
                  {"Id":"lib3","Name":"Collections","IsFolder":true,"CollectionType":"boxsets"},
                  {"Id":"lib5","Name":"Folders","IsFolder":true,"CollectionType":"folders"},
                  {"Id":"lib4","Name":"随便什么","IsFolder":true}
                ]}"""
            )
        }
        assertEquals(listOf("电影库", "随便什么"), fs().list(dir("/folders")).map { it.name })
    }

    @Test
    fun `an item without a size gets probed once via Range`() {
        // ★ Photos are exactly this case: a Photo item has no MediaSources, and Size is
        // not even in the ItemFields enum (confirmed against the openapi spec — writing
        // Fields=Size gets silently ignored), the same on both servers — so every image in
        // an album showed 0 B. The denominator of the Content-Range from a Range:
        // bytes=0-0 request is the total length, so only 1 byte is transferred.
        // (HEAD does not work — both servers answered 405 in testing.)
        routes["/Users/u1/Items"] = {
            json(
                """{"Items":[{"Id":"p1","Name":"pic1","Type":"Photo","MediaType":"Photo",
                   "Path":"/media/Photos/pic1.jpg","Container":"jpg"}]}""",
            )
        }
        routes["/Items/p1/Download"] = { req ->
            assertEquals("bytes=0-0", req.getHeader("Range"))
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/27836").setBody("x")
        }
        val rows = fs().list(dir("/folders/lib1/album1"))
        assertEquals(27836L, rows.single().size)
        assertEquals("pic1.jpg", rows.single().name)
    }

    @Test
    fun `a failed probe does not break the directory listing`() {
        // when downloads are disabled or the network hiccups, the size should just stay
        // unknown — it must not take down the whole directory
        routes["/Users/u1/Items"] = {
            json("""{"Items":[{"Id":"p1","Name":"pic1","Type":"Photo","Container":"jpg"}]}""")
        }
        routes["/Items/p1/Download"] = { MockResponse().setResponseCode(403) }
        val rows = fs().list(dir("/folders/lib1/album1"))
        assertEquals(1, rows.size)
        assertEquals(0L, rows.single().size)
    }

    @Test
    fun `an item that already has a size is never probed`() {
        // a movie has MediaSources.Size — an extra round trip would be pure waste
        routes["/Users/u1/Items"] = { json("""{"Items":[$movie]}""") }
        fs().list(dir("/folders/lib1"))
        assertTrue(seen.none { it.requestUrl!!.encodedPath.endsWith("/Download") })
    }

    @Test
    fun `an unknown top-level directory errors out instead of silently returning empty`() {
        val e = runCatching { fs().list(dir("/nope")) }.exceptionOrNull()
        assertTrue(e is FsException)
    }

    // ---- Reading ----

    @Test
    fun `downloads go through Items Download with the Range header passed through`() {
        routes["/Users/u1/Items/m1"] = { json(movie) }
        routes["/Items/m1/Download"] = { json("hello-bytes") }
        val f = fs()
        val file = XFile("jellyfin", "/folders/lib1/m1", isDir = false, size = 11)
        assertEquals("hello-bytes", f.openInput(file).use { it.readBytes().decodeToString() })

        val src = f.openRandom(file)
        src.readAt(4, ByteArray(4), 0, 4)
        val ranged = seen.last { it.requestUrl?.encodedPath.orEmpty().endsWith("/Download") }
        assertEquals("bytes=4-", ranged.getHeader("Range"))
        src.close()
    }

    @Test
    fun `the playback path issues zero metadata requests`() {
        // ★ Regression test (2026-08-17, "4K stutters, seek stutters even more, the same
        // file plays smoothly over SMB/WebDAV"): openMedia used to call itemOf once per
        // opened stream, and that response carries MediaSources (full audio/subtitle
        // details, a few KB to tens of KB), while the item cache is only 30s. So every seek
        // meant metadata + Range as two serial round trips, where SMB (pread)/WebDAV
        // (a plain Range GET) only need one — at a high bitrate that is visible stutter.
        //
        // The criterion is deliberately **"playback works even when the item endpoint does
        // not exist at all"**: that route isn't registered, so touching it is an instant
        // 404. Asserting a request *count* is fragile as caching/pooling strategy changes;
        // asserting "never touched" is stable.
        val data = ByteArray(4096) { (it % 251).toByte() }
        routes["/Items/m1/Download"] = { req ->
            val from = req.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            MockResponse().setResponseCode(if (from > 0) 206 else 200)
                .setBody(okio.Buffer().write(data, from, data.size - from))
        }
        val f = fs()
        val file = XFile("jellyfin", "/folders/lib1/m1", isDir = false, size = data.size.toLong())
        val buf = ByteArray(16)
        f.openRandom(file).use { src ->
            // jump around a lot, simulating a user dragging the progress bar
            for (pos in longArrayOf(0, 3000, 100, 2048, 512, 4000)) {
                assertTrue("readAt($pos) returned no bytes", src.readAt(pos, buf, 0, buf.size) > 0)
            }
        }
        val meta = seen.map { it.requestUrl!!.encodedPath }
            .filter { !it.endsWith("/Download") && !it.endsWith("/AuthenticateByName") }
        assertEquals("the playback path issued extra metadata requests: $meta", emptyList<String>(), meta)
    }

    @Test
    fun `falls back to the stream endpoint when Download is forbidden`() {
        routes["/Users/u1/Items/m1"] = { json(movie) }
        routes["/Items/m1/Download"] = { MockResponse().setResponseCode(403) }
        routes["/Videos/m1/stream"] = { json("fallback") }
        val f = fs()
        val file = XFile("jellyfin", "/folders/lib1/m1", isDir = false, size = 8)
        assertEquals("fallback", f.openInput(file).use { it.readBytes().decodeToString() })
        assertEquals("true", reqTo("/Videos/m1/stream").requestUrl!!.queryParameter("static"))

        // remember it was forbidden: the second time should not hit the same 403 again
        // (this cost multiplies by file count during a batch copy)
        val before = seen.count { it.requestUrl?.encodedPath.orEmpty().endsWith("/Download") }
        f.openInput(file).close()
        assertEquals(before, seen.count { it.requestUrl?.encodedPath.orEmpty().endsWith("/Download") })
    }

    @Test
    fun `covers use the server's poster and let the server resize it`() {
        routes["/Users/u1/Items/m1"] = { json(movie) }
        routes["/Items/m1/Images/Primary"] = { json("jpeg-bytes") }
        val file = XFile("jellyfin", "/folders/lib1/m1", isDir = false)
        assertEquals("jpeg-bytes", fs().openCover(file, 256)!!.use { it.readBytes().decodeToString() })
        assertEquals("256", reqTo("/Images/Primary").requestUrl!!.queryParameter("maxHeight"))
    }

    /**
     * "Resume" uses a landscape still frame: `Thumb → Backdrop → Primary`, everywhere
     * else still uses a portrait poster. Field names follow what the two real servers'
     * actual Resume responses use (verified 2026-08-19, consistent on both).
     */
    @Test
    fun `resume movies prefer Thumb, then Backdrop, then the poster`() {
        fun movieWith(tags: String, backdrops: String) = """
            {"Id":"m1","Name":"M","Type":"Movie","MediaType":"Video",
             "ImageTags":$tags,"BackdropImageTags":$backdrops}
        """.trimIndent()
        fun coverType(body: String): String {
            seen.clear() // fs() is recreated each time, so the item cache is naturally independent
            routes["/Users/u1/Items/m1"] = { json(body) }
            for (t in listOf("Thumb", "Backdrop", "Primary")) {
                routes["/Items/m1/Images/$t"] = { json("bytes") }
            }
            fs().openCover(XFile("jellyfin", "/resume/m1", isDir = false), 256)?.close()
            return seen.mapNotNull { it.requestUrl?.encodedPath }
                .last { it.contains("/Images/") }.substringAfterLast('/')
        }
        assertEquals(
            "Thumb", coverType(movieWith("""{"Thumb":"t","Primary":"p"}""", """["b"]""")),
        )
        assertEquals("Backdrop", coverType(movieWith("""{"Primary":"p"}""", """["b"]""")))
        assertEquals("Primary", coverType(movieWith("""{"Primary":"p"}""", "[]")))
    }

    /**
     * ★ An episode always uses the **series-level** image, never its own
     * `ImageTags.Primary` — that is the episode's own screenshot, different for every
     * episode of the same show, so it does not identify the show in a flat list. So the
     * assertion checks both the image type **and** which item's image was requested
     * (s1, not e1).
     */
    @Test
    fun `resume episodes use the series image, not the episode's own screenshot`() {
        val ep = """
            {"Id":"e1","Name":"E1","Type":"Episode","MediaType":"Video",
             "ImageTags":{"Primary":"self"},"BackdropImageTags":[],
             "SeriesId":"s1","SeriesPrimaryImageTag":"sp",
             "ParentThumbItemId":"s1","ParentThumbImageTag":"pt",
             "ParentBackdropItemId":"s1","ParentBackdropImageTags":["pb"]}
        """.trimIndent()
        routes["/Users/u1/Items/e1"] = { json(ep) }
        for (t in listOf("Thumb", "Backdrop", "Primary")) {
            routes["/Items/s1/Images/$t"] = { json("bytes") }
            routes["/Items/e1/Images/$t"] = { json("WRONG") }
        }
        val got = fs().openCover(XFile("jellyfin", "/resume/e1", isDir = false), 256)
            ?.use { it.readBytes().decodeToString() }
        assertEquals("must fetch the series image, not the episode's own screenshot", "bytes", got)
        assertEquals(
            "s1's Thumb should take priority", "/Items/s1/Images/Thumb",
            seen.mapNotNull { it.requestUrl?.encodedPath }.last { it.contains("/Images/") },
        )
    }

    /** Elsewhere (movie library/shows) is unaffected and still uses a portrait poster. */
    @Test
    fun `library items still use a portrait poster`() {
        routes["/Users/u1/Items/m1"] = {
            json("""{"Id":"m1","Name":"M","Type":"Movie","MediaType":"Video",
                     "ImageTags":{"Thumb":"t","Primary":"p"},"BackdropImageTags":["b"]}""")
        }
        routes["/Items/m1/Images/Primary"] = { json("poster") }
        routes["/Items/m1/Images/Thumb"] = { json("WRONG") }
        val got = fs().openCover(XFile("jellyfin", "/lib/lib1/m1", isDir = false), 256)
            ?.use { it.readBytes().decodeToString() }
        assertEquals("poster", got)
    }

    @Test
    fun `an item with no poster never fetches an image`() {
        routes["/Users/u1/Items/m2"] = {
            json("""{"Id":"m2","Name":"No Art","Type":"Movie","MediaType":"Video"}""")
        }
        assertNull(fs().openCover(XFile("jellyfin", "/folders/lib1/m2", isDir = false), 256))
        assertTrue(seen.none { it.requestUrl?.encodedPath.orEmpty().contains("/Images/") })
    }

    // ---- Details ----

    @Test
    fun `details use metadata the API already returned, with zero extra requests`() {
        // The details card would otherwise default to MediaMetadataRetriever, which really
        // reads file bytes — on a remote source that is seconds to tens of seconds. But
        // this data already comes along with the listing via Fields=MediaSources.
        routes["/Users/u1/Items"] = {
            json(
                """{"Items":[{"Id":"m1","Name":"Blade Runner","Type":"Movie","MediaType":"Video",
                  "Path":"/data/movies/Blade Runner (1982).mkv","RunTimeTicks":72000000000,
                  "MediaSources":[{"Path":"/data/movies/Blade Runner (1982).mkv","Container":"mkv",
                    "Size":1234567,"Bitrate":8000000,"RunTimeTicks":72000000000,
                    "MediaStreams":[
                      {"Type":"Video","Codec":"h264","Width":1920,"Height":1080,
                       "AverageFrameRate":23.976,"BitRate":7000000,"DisplayTitle":"1080p H264"},
                      {"Type":"Audio","Codec":"dts","Channels":6,"Language":"eng","BitRate":1500000,
                       "DisplayTitle":"English DTS-HD MA 5.1","IsDefault":true},
                      {"Type":"Subtitle","Codec":"subrip","Language":"chi","DisplayTitle":"Chinese"},
                      {"Type":"Data","Codec":"bin"}
                    ]}]}]}"""
            )
        }
        val f = fs()
        val file = f.list(dir("/folders/lib1")).single()
        val before = seen.size
        val d = f.detailsOf(file)!!

        // already fetched during listing — asking again should not produce any request
        assertEquals("the details query issued an extra request", before, seen.size)
        // the real path on the server — our own path is a string of GUIDs, meaningless to a human
        assertEquals("/data/movies/Blade Runner (1982).mkv", d.realPath)
        assertEquals("mkv", d.container)
        assertEquals(7_200_000L, d.durationMs)
        assertEquals(8_000_000L, d.bitrate)

        val v = d.streams.single { it.kind == MediaStream.Kind.VIDEO }
        assertEquals(1920, v.width)
        assertEquals(1080, v.height)
        assertEquals("1080p H264", v.title)
        val a = d.streams.single { it.kind == MediaStream.Kind.AUDIO }
        assertEquals(6, a.channels)
        assertEquals("eng", a.language)
        assertTrue(a.isDefault)
        assertEquals(1, d.streams.count { it.kind == MediaStream.Kind.SUBTITLE })
        // tracks like Data/EmbeddedImage should not show up in details
        assertEquals(3, d.streams.size)
    }

    @Test
    fun `a photo's details include a real size and pixel dimensions`() {
        // to avoid firing dozens of requests, the list view leaves the size at 0 when it
        // cannot be probed (see the gate in withSizes); details only concern a single item,
        // so it is worth a dedicated request for it — both numbers must be provided
        routes["/Users/u1/Items/p1"] = {
            json(
                """{"Id":"p1","Name":"pic1","Type":"Photo","MediaType":"Photo","Container":"jpg",
                   "Path":"/media/Photos/pic1.jpg","Width":4032,"Height":3024}""",
            )
        }
        routes["/Items/p1/Download"] = {
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/27836").setBody("x")
        }
        // explicitly request Width/Height, otherwise the server omits them (observed:
        // photos only return these two fields when asked)
        val d = fs().detailsOf(XFile("jellyfin", "/folders/lib1/p1", isDir = false))!!
        assertEquals(27836L, d.size)
        assertEquals(4032, d.width)
        assertEquals(3024, d.height)
        assertEquals("jpg", d.container)
        assertTrue("a photo should have no audio/video streams", d.streams.isEmpty())
        assertTrue(reqTo("/Users/u1/Items/p1").requestUrl!!.queryParameter("Fields")!!.contains("Width"))
    }

    @Test
    fun `an item that already has a size is never probed again in details`() {
        routes["/Users/u1/Items"] = { json("""{"Items":[$movie]}""") }
        val f = fs()
        val file = f.list(dir("/folders/lib1")).single()
        f.detailsOf(file)
        assertTrue(seen.none { it.requestUrl!!.encodedPath.endsWith("/Download") })
    }

    @Test
    fun `music tags come straight from the source, without reading the file`() {
        // playlists used to rely on MediaMetadataRetriever reading each file one by one to
        // fill in tags, which is slow over the network; the server already provides these
        // at listing time (observed field names: Name/Album/Artists/AlbumArtist)
        routes["/Users/u1/Items/a1"] = {
            json(
                """{"Id":"a1","Name":"第1首 夜航","Type":"Audio","MediaType":"Audio",
                   "Album":"夏夜","AlbumArtist":"清风乐团","Artists":["夜航者","清风乐团"],
                   "Path":"/media/Music/01.mp3","Container":"mp3","RunTimeTicks":2000457140,
                   "MediaSources":[{"Size":4801849,"Container":"mp3","Bitrate":192000,
                     "MediaStreams":[{"Type":"Audio","Codec":"mp3","SampleRate":44100,
                       "Channels":1,"BitRate":192000}]}]}""",
            )
        }
        val d = fs().detailsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false))!!
        assertEquals("第1首 夜航", d.title)
        // artist prefers the first entry of Artists (the track artist) over the album artist
        assertEquals("夜航者", d.artist)
        assertEquals("夏夜", d.album)
        assertEquals(200_045L, d.durationMs)
        val audio = d.streams.single { it.kind == MediaStream.Kind.AUDIO }
        assertEquals(44100, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertEquals(192000L, audio.bitrate)
    }

    @Test
    fun `falls back to the album artist when Artists is absent`() {
        routes["/Users/u1/Items/a1"] = {
            json("""{"Id":"a1","Name":"曲","Type":"Audio","Album":"专","AlbumArtist":"某人"}""")
        }
        assertEquals("某人", fs().detailsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false))!!.artist)
    }

    // ---- Search ----

    @Test
    fun `search hits the server's endpoint, scoped by the current level`() {
        // the default BFS directory-recursion approach costs one HTTP round trip per level
        // of the virtual tree — a single search would pull down the whole library
        routes["/Users/u1/Views"] = {
            json("""{"Items":[{"Id":"lib1","Name":"音乐","CollectionType":"music"}]}""")
        }
        routes["/Users/u1/Items"] = {
            json("""{"Items":[{"Id":"a1","Name":"夜航","Type":"Audio","MediaSources":[{"Container":"mp3","Size":9}]}]}""")
        }
        val f = fs()

        // searching from the server root = the whole server, no ParentId
        val all = f.search(f.root(), "夜", 50)!!
        assertEquals(listOf("夜航.mp3"), all.map { it.name })
        reqTo("/Users/u1/Items").requestUrl!!.let {
            assertEquals("夜", it.queryParameter("SearchTerm"))
            assertEquals("true", it.queryParameter("Recursive"))
            assertNull(it.queryParameter("ParentId"))
        }
        // the result hangs off the searched directory, with the item id as the last
        // segment — opening/expanding it works the same as usual.
        // ★ the server root's path is "/", so concatenating it directly gives only one
        // segment, which list()/openCover would not recognize and would reject
        assertEquals("/search/a1", all.single().path)

        seen.clear()
        f.search(dir("/lib/lib1"), "夜", 50)
        assertEquals("lib1", reqTo("/Users/u1/Items").requestUrl!!.queryParameter("ParentId"))
    }

    /**
     * ★ A **directory** found by a root-level search must be openable. The user's
     * original report was `Unknown Jellyfin directory: /2121089` — the root's path is
     * "/", so concatenating a result directly gives `/<item id>`, a single segment, and
     * [JellyfinFileSystem.list] requires the first segment to be a known virtual
     * directory. A library search gives three segments (`/lib/<library>/<id>`), so this
     * bug **only shows up when searching from the root**.
     */
    @Test
    fun `a directory found by a root search can be opened`() {
        routes["/Users/u1/Items"] = { req ->
            // the search call answers with a series (a directory), expanding it answers
            // with its children
            if (req.requestUrl!!.queryParameter("SearchTerm") != null) {
                json("""{"Items":[{"Id":"s1","Name":"夜航西飞","Type":"Series","IsFolder":true}]}""")
            } else {
                json("""{"Items":[{"Id":"e1","Name":"第一集","Type":"Episode","MediaType":"Video"}]}""")
            }
        }
        val f = fs()
        val hit = f.search(f.root(), "夜", 50)!!.single()
        assertTrue("a series should be a directory", hit.isDir)
        // the name carrying an extension is existing behavior (the player relies on it for
        // the container); here we only care that it can be listed
        assertEquals(listOf("第一集.mp4"), f.list(hit).map { it.name })
    }

    /** The other half of the same bug: covers for root search results were also once rejected outright by `segs.size < 2`. */
    @Test
    fun `an item found by a root search can also fetch its cover`() {
        routes["/Users/u1/Items"] = {
            json("""{"Items":[{"Id":"m1","Name":"夜航","Type":"Movie","MediaType":"Video",
                               "ImageTags":{"Primary":"p"}}]}""")
        }
        routes["/Items/m1/Images/Primary"] = { json("poster") }
        val f = fs()
        val hit = f.search(f.root(), "夜", 50)!!.single()
        assertEquals("poster", f.openCover(hit, 256)?.use { it.readBytes().decodeToString() })
    }

    /**
     * At listing time we already record which image to fetch, so
     * [JellyfinFileSystem.openCover] **issues zero metadata requests**.
     *
     * This is not just saving one round trip: thumbnails are consumed slowly by a
     * 2-thread queue, while the item cache is only 30s — a single search returning a few
     * hundred results means by the time the queue gets to the tail, the cache has long
     * expired, so every cover would need an item lookup first and then the image, two
     * serial round trips × hundreds of items. This is exactly the "covers are slow, even
     * though there's an API for it" the user reported. The criterion is **the item
     * endpoint isn't even registered** (touching it 404s), not counting requests — the
     * latter shows no difference on a cache hit.
     */
    @Test
    fun `fetching a cover for an already-listed item does not query the item again`() {
        routes["/Users/u1/Views"] = {
            json("""{"Items":[{"Id":"lib1","Name":"电影","CollectionType":"movies"}]}""")
        }
        routes["/Users/u1/Items"] = {
            json("""{"Items":[{"Id":"m1","Name":"夜航","Type":"Movie","MediaType":"Video",
                               "ImageTags":{"Primary":"p"}}]}""")
        }
        routes["/Items/m1/Images/Primary"] = { json("poster") }
        val f = fs()
        val item = f.list(dir("/lib/lib1")).single()

        // ★★ the item cache must be cleared before asserting: that TtlCache has a 30s TTL,
        // and within the same test openCover would hit it and fire no request at all — if
        // this isn't cleared, **this test still passes even with coverRefs removed**
        // (this actually happened once). The real scenario is precisely "the thumbnail
        // queue took longer than 30s, so the cache already expired" — clearing it here
        // simulates exactly that. This is the same trap as the "shows no difference on a
        // cache hit" entry in the pitfall list.
        itemCacheOf(f).clear()

        // the item endpoint is not registered — a real lookup would 404 and get no image
        assertEquals("poster", f.openCover(item, 256)?.use { it.readBytes().decodeToString() })
    }

    @Test
    fun `entries with no well-defined scope are left for the caller to traverse`() {
        // aggregate entries like "Resume" have no corresponding ParentId; they only ever
        // have a few dozen items, so traversal is cheap
        val f = fs()
        assertNull(f.search(dir("/resume"), "夜", 50))
        assertNull(f.search(dir("/playlists"), "夜", 50))
        // an empty keyword also should not bother the server
        assertNull(f.search(f.root(), "   ", 50))
    }

    // ---- External subtitles ----

    private val withSubs = """
        {"Id":"m1","Name":"片","Type":"Movie","MediaType":"Video","MediaSources":[{"Id":"ms_m1",
          "MediaStreams":[
            {"Type":"Video","Codec":"h264","Index":0},
            {"Type":"Subtitle","Codec":"srt","Index":2,"IsExternal":true,"IsTextSubtitleStream":true,
             "DisplayTitle":"Chinese (SRT)","Language":"zh"},
            {"Type":"Subtitle","Codec":"pgssub","Index":3,"IsExternal":false,"IsTextSubtitleStream":false,
             "DisplayTitle":"English (PGS)"},
            {"Type":"Subtitle","Codec":"lrc","Index":4,"IsExternal":true,"IsTextSubtitleStream":true}
          ]}]}
    """.trimIndent()

    @Test
    fun `external subtitles are listed as directly readable items, excluding image subtitles and lyrics`() {
        // the server exposes external subtitles as "streams" rather than files in a
        // directory, so the player's "scan the same directory for .srt" approach finds
        // nothing at all. Here they get wrapped as ordinary XFiles, and the player's
        // read/parse/track-selection all follow the same path as before.
        routes["/Users/u1/Items/m1"] = { json(withSubs) }
        val subs = fs().subtitlesOf(XFile("jellyfin", "/folders/lib1/m1", isDir = false))
        // PGS (an image subtitle that can't produce text, and the player can already
        // decode it as an embedded track) and .lrc (that's lyrics) are both excluded
        assertEquals(1, subs.size)
        // the name must carry an extension: the caller relies on it to recognize "this is a subtitle"
        assertEquals("Chinese (SRT).srt", subs[0].name)
        assertTrue(!subs[0].isDir && !subs[0].canWrite)
    }

    @Test
    fun `a subtitle entry supports openInput like a normal file`() {
        routes["/Users/u1/Items/m1"] = { json(withSubs) }
        routes["/Videos/m1/ms_m1/Subtitles/2/Stream.srt"] = {
            MockResponse().setBody(
                listOf("1", "00:00:02,000 --> 00:00:05,000", "第一句", "").joinToString("\r\n"),
            )
        }
        val f = fs()
        val sub = f.subtitlesOf(XFile("jellyfin", "/folders/lib1/m1", isDir = false)).single()
        assertTrue(f.openInput(sub).use { String(it.readBytes()) }.contains("第一句"))
    }

    @Test
    fun `ASS is requested in its original format, and SRT does not waste a hit on subrip`() {
        // ASS's effect tags/multi-line dialogue only exist in the original format;
        // converting to SRT flattens them.
        // ★ Jellyfin reports the codec as "subrip", not "srt" — requesting Stream.subrip
        // literally is a round trip doomed to fail. Map it, then request.
        routes["/Users/u1/Items/m1"] = {
            json(
                """{"Id":"m1","Name":"片","Type":"Movie","MediaSources":[{"Id":"ms_m1",
                   "MediaStreams":[
                     {"Type":"Subtitle","Codec":"ass","Index":2,"IsExternal":true,
                      "IsTextSubtitleStream":true,"DisplayTitle":"特效"},
                     {"Type":"Subtitle","Codec":"subrip","Index":3,"IsExternal":true,
                      "IsTextSubtitleStream":true,"DisplayTitle":"简体"}]}]}""",
            )
        }
        routes["/Videos/m1/ms_m1/Subtitles/2/Stream.ass"] = {
            MockResponse().setBody(
                listOf(
                    "[Script Info]",
                    """Dialogue: 0,0:00:02.00,0:00:05.00,D,,0,0,0,,{\fad(300,300)}一行""",
                ).joinToString("\r\n"),
            )
        }
        routes["/Videos/m1/ms_m1/Subtitles/3/Stream.srt"] = { MockResponse().setBody("1") }
        val f = fs()
        val subs = f.subtitlesOf(XFile("jellyfin", "/folders/lib1/m1", isDir = false))
        assertEquals(listOf("特效.ass", "简体.srt"), subs.map { it.name })

        // ASS gets the original text, effect tags intact
        val ass = f.openInput(subs[0]).use { String(it.readBytes()) }
        assertTrue(ass, ass.contains("""\fad"""))
        // the subrip one is requested directly as srt, with no extra Stream.subrip request
        f.openInput(subs[1]).close()
        assertTrue(seen.none { it.requestUrl!!.encodedPath.endsWith("Stream.subrip") })
    }

    // ---- Lyrics ----

    @Test
    fun `Jellyfin's structured lyrics convert to LRC text`() {
        // Jellyfin 10.9+'s /Audio/{id}/Lyrics returns {Text, Start(tick)}; this is
        // uniformly converted to LRC text so the caller can reuse the existing LRC parser
        // instead of writing a separate one per server type
        routes["/Audio/a1/Lyrics"] = {
            json(
                """{"Metadata":{},"Lyrics":[
                  {"Text":"夜色落在船舷","Start":0},
                  {"Text":"风把灯火吹远","Start":125000000},
                  {"Text":"我们向着海心","Start":250000000}]}""",
            )
        }
        val lrc = fs().lyricsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false))!!
        // 125000000 ticks (100ns each) = 12.5 seconds
        assertEquals(
            listOf("[00:00.00]夜色落在船舷", "[00:12.50]风把灯火吹远", "[00:25.00]我们向着海心"),
            lrc.trim().lines(),
        )
    }

    @Test
    fun `falls back to the external subtitle stream when Emby has no lyrics endpoint`() {
        // ★ Emby has no /Audio/{id}/Lyrics at all (hitting that URL gets treated as a
        // transcode job and answered with 500); it associates .lrc as a subtitle stream
        // with Codec=lrc instead, so it has to go through the subtitle download URL
        routes["/Audio/a1/Lyrics"] = { MockResponse().setResponseCode(500) }
        routes["/Users/u1/Items/a1"] = {
            json(
                """{"Id":"a1","Name":"曲","Type":"Audio","MediaSources":[{"Id":"mediasource_a1",
                   "MediaStreams":[{"Type":"Audio","Codec":"mp3","Index":0},
                     {"Type":"Subtitle","Codec":"lrc","Index":1,"IsExternal":true}]}]}""",
            )
        }
        routes["/Videos/a1/mediasource_a1/Subtitles/1/Stream.lrc"] = {
            MockResponse().setBody("[00:03.00]原始歌词")
        }
        val lrc = fs().lyricsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false))!!
        assertTrue(lrc, lrc.contains("原始歌词"))
    }

    /**
     * ★ Lyric bytes must not be decoded via `body.string()`: that follows "whatever the
     * response header says, UTF-8 if it says nothing", and this endpoint can return the
     * original file's raw bytes — a user's `.lrc` is often GBK, which then decodes into a
     * page full of replacement characters, looking like "the server's lyrics are just
     * broken". The bytes are handed to [TextDecoding] (where `:app` registers the user's
     * encoding priority order); here we simulate that injection.
     */
    @Test
    fun `GBK lyrics decode with the injected decoder instead of being forced as UTF-8`() {
        routes["/Audio/a1/Lyrics"] = { MockResponse().setResponseCode(500) }
        routes["/Users/u1/Items/a1"] = {
            json(
                """{"Id":"a1","Name":"曲","Type":"Audio","MediaSources":[{"Id":"ms",
                   "MediaStreams":[{"Type":"Subtitle","Codec":"lrc","Index":1,"IsExternal":true}]}]}""",
            )
        }
        val gbk = java.nio.charset.Charset.forName("GBK")
        routes["/Videos/a1/ms/Subtitles/1/Stream.lrc"] = {
            // setBody(Buffer) is needed to give raw bytes; setBody(String) would encode as UTF-8
            MockResponse().setBody(okio.Buffer().write("[00:03.00]中文歌词".toByteArray(gbk)))
        }

        // without injection (the default lenient UTF-8), this is a page of replacement
        // characters — exactly the symptom this fixes
        assertTrue(fs().lyricsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false))!!.contains('�'))

        TextDecoding.decode = { b -> String(b, gbk) }
        try {
            val lrc = fs().lyricsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false))!!
            assertTrue(lrc, lrc.contains("中文歌词"))
        } finally {
            TextDecoding.decode = { b -> String(b, Charsets.UTF_8) }
        }
    }

    @Test
    fun `falls back to requesting srt and converting to LRC when the lrc output format yields nothing`() {
        // ★ Hit on real Emby 4.9.3 on 2026-08-18: that subtitle endpoint is "convert to
        // whatever format is written in the URL", and .lrc as an **output** format is not
        // supported by every version — 4.9.3 answers HTTP 200 + zero bytes (not a 404, and
        // no error either, the hardest kind to diagnose), while 4.9.5 can return the
        // original. .srt is supported by both versions.
        routes["/Audio/a1/Lyrics"] = { MockResponse().setResponseCode(500) }
        routes["/Users/u1/Items/a1"] = {
            json(
                """{"Id":"a1","Name":"曲","Type":"Audio","MediaSources":[{"Id":"ms_a1",
                   "MediaStreams":[{"Type":"Subtitle","Codec":"lrc","Index":2,"IsExternal":true}]}]}""",
            )
        }
        routes["/Videos/a1/ms_a1/Subtitles/2/Stream.lrc"] = { MockResponse().setBody("") }
        routes["/Videos/a1/ms_a1/Subtitles/2/Stream.srt"] = {
            // real SRT uses CRLF, testing that incidentally too
            MockResponse().setBody(
                listOf(
                    "1", "00:00:01,880 --> 00:00:03,490", "Cry", "",
                    "2", "00:01:05,200 --> 00:01:07,000", "第二行", "",
                ).joinToString("\r\n"),
            )
        }
        val lrc = fs().lyricsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false))!!
        // dropping the end time is harmless: lyrics only scroll by start time
        assertEquals(listOf("[00:01.88]Cry", "[01:05.20]第二行"), lrc.trim().lines())
    }

    @Test
    fun `returns null when there are no lyrics, without erroring`() {
        routes["/Audio/a1/Lyrics"] = { MockResponse().setResponseCode(404) }
        routes["/Users/u1/Items/a1"] = { json("""{"Id":"a1","Name":"曲","Type":"Audio"}""") }
        assertNull(fs().lyricsOf(XFile("jellyfin", "/folders/lib1/a1", isDir = false)))
    }

    @Test
    fun `a directory has no media details`() {
        assertNull(fs().detailsOf(dir("/folders/lib1")))
    }

    // ---- Progress sync ----

    @Test
    fun `resume position converts from ticks to milliseconds`() {
        routes["/Users/u1/Items/m1"] = { json(movie) }
        // 600000000 ticks (100ns each) = 60 seconds
        assertEquals(60_000L, fs().positionOf(XFile("jellyfin", "/folders/lib1/m1", isDir = false)))
    }

    @Test
    fun `a fully watched item gets no resume position`() {
        routes["/Users/u1/Items/m1"] = {
            json("""{"Id":"m1","UserData":{"PlaybackPositionTicks":900000000,"Played":true}}""")
        }
        // otherwise opening it would immediately jump to the very end
        assertEquals(0L, fs().positionOf(XFile("jellyfin", "/folders/lib1/m1", isDir = false)))
    }

    @Test
    fun `the three playback states hit three different endpoints`() {
        val hit = ArrayList<String>()
        routes["/Sessions/Playing"] = { hit += "start"; json("{}") }
        routes["/Sessions/Playing/Progress"] = { hit += "progress"; json("{}") }
        routes["/Sessions/Playing/Stopped"] = { hit += "stopped"; json("{}") }
        routes["/Users/u1/Items/m1"] = { json(movie) }
        val f = fs()
        val file = XFile("jellyfin", "/folders/lib1/m1", isDir = false)
        f.report(file, 0, 7_200_000, PlayState.START)
        f.report(file, 65_000, 7_200_000, PlayState.PROGRESS)
        f.report(file, 70_000, 7_200_000, PlayState.STOP)
        assertEquals(listOf("start", "progress", "stopped"), hit)

        val body = JSONObject(reqTo("/Sessions/Playing/Progress").body.readUtf8())
        assertEquals("m1", body.getString("ItemId"))
        assertEquals(650_000_000L, body.getLong("PositionTicks"))
    }

    @Test
    fun `reporting requires a non-empty PlaySessionId, shared by all three calls of one playback`() {
        // ★ Regression test (2026-08-17, real Emby 4.9.5): Emby uses this field directly
        // as a ConcurrentDictionary key, and null means ArgumentNullException → HTTP 400 —
        // all three endpoints fail outright and progress never syncs at all, showing up as
        // "Resume is empty". Jellyfin tolerates it being missing, so this only breaks on Emby.
        val bodies = ArrayList<JSONObject>()
        val grab: (RecordedRequest) -> MockResponse = { r -> bodies += JSONObject(r.body.readUtf8()); json("{}") }
        routes["/Sessions/Playing"] = grab
        routes["/Sessions/Playing/Progress"] = grab
        routes["/Sessions/Playing/Stopped"] = grab
        val f = fs()
        val file = XFile("jellyfin", "/folders/lib1/m1", isDir = false)
        f.report(file, 0, 1000, PlayState.START)
        f.report(file, 500, 1000, PlayState.PROGRESS)
        f.report(file, 900, 1000, PlayState.STOP)

        assertEquals(3, bodies.size)
        val ids = bodies.map { it.optString("PlaySessionId") }
        assertTrue("PlaySessionId must not be empty: $ids", ids.all { it.isNotEmpty() })
        assertEquals("all three calls of one playback must share a session id: $ids", 1, ids.toSet().size)

        // after STOP, playing again must use a new session id (the server-side one already ended)
        bodies.clear()
        f.report(file, 0, 1000, PlayState.START)
        assertTrue(bodies.single().getString("PlaySessionId") != ids[0])
    }

    @Test
    fun `a failed progress report throws instead of being silently swallowed`() {
        // if swallowed, "synced" and "not synced" look identical in the UI
        routes["/Sessions/Playing/Progress"] = { MockResponse().setResponseCode(500) }
        val e = runCatching {
            fs().report(XFile("jellyfin", "/folders/lib1/m1", isDir = false), 1, 2, PlayState.PROGRESS)
        }.exceptionOrNull()
        assertTrue(e is FsException)
    }

    // ---- Read-only ----

    @Test
    fun `the entire source is read-only`() {
        val f = fs()
        val file = XFile("jellyfin", "/folders/lib1/m1", isDir = false)
        assertTrue(!f.writable())
        assertTrue(runCatching { f.openOutput(file) }.exceptionOrNull() is FsException)
        assertTrue(runCatching { f.delete(file) }.exceptionOrNull() is FsException)
        assertTrue(runCatching { f.rename(file, "x") }.exceptionOrNull() is FsException)
        assertTrue(runCatching { f.mkdir(dir("/folders/lib1"), "x") }.exceptionOrNull() is FsException)
    }

    @Test
    fun `resolve genuinely asks the server instead of assuming isDir=true`() {
        // this class of bug (SftpFileSystem.resolve claiming "any path is a directory") has
        // caused a real production incident before
        routes["/Users/u1/Items/m1"] = { json(movie) }
        val f = fs().resolve("/folders/lib1/m1")
        assertTrue(!f.isDir)
        assertEquals("Blade Runner.mkv", f.name)
        assertEquals(1234567L, f.size)
    }

    /** The item's 30s cache; tests use this to simulate "after the TTL expires". */
    @Suppress("UNCHECKED_CAST")
    private fun itemCacheOf(f: JellyfinFileSystem): TtlCache<org.json.JSONObject> =
        JellyfinFileSystem::class.java.getDeclaredField("cache")
            .apply { isAccessible = true }.get(f) as TtlCache<org.json.JSONObject>
}
