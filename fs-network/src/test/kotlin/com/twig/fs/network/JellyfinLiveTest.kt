package com.twig.fs.network

import com.twig.core.PlayState
import com.twig.core.MediaStream
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * Integration tests against a real Jellyfin / Emby server. **Skipped entirely when the
 * environment variables aren't set**, so `./gradlew test` stays green on any machine.
 *
 * ```
 * TWIG_JF_URL=http://192.168.1.9:8096 TWIG_JF_USER=<username> TWIG_JF_PASS=<password> \
 *   ./gradlew :fs-network:test --tests "*JellyfinLiveTest*"
 * # API key mode: replace TWIG_JF_PASS with TWIG_JF_KEY
 * # Emby: add TWIG_JF_EMBY=1
 * ```
 *
 * Why this deserves its own file: [JellyfinFileSystemTest] asserts against mockwebserver
 * responses shaped by **our own** assumptions about the wire format, which cannot prove a
 * real server agrees. That's exactly how the S3 incident happened — the encoding direction
 * was guessed backwards, the mocks stayed green, and only a real MinIO run exposed it (see
 * `CLAUDE.md`). Several things here can only be answered by a real server: whether it
 * accepts our auth header format, whether `Items/Latest` actually returns a bare array,
 * whether `MediaSources` really has `Size`, whether progress reporting gets rejected with 400.
 *
 * **Entirely read-only** except for one write: reporting progress on a real item — so the
 * position is **restored to its original value** at the end, leaving the user's own
 * watch history untouched.
 */
class JellyfinLiveTest {

    private val url = System.getenv("TWIG_JF_URL")

    private lateinit var fs: JellyfinFileSystem

    @Before
    fun setup() {
        assumeFalse("TWIG_JF_URL not set — skipping live Jellyfin test", url.isNullOrEmpty())
        fs = JellyfinFileSystem(
            JellyfinConfig(
                baseUrl = url!!,
                user = System.getenv("TWIG_JF_USER").orEmpty(),
                password = System.getenv("TWIG_JF_PASS").orEmpty(),
                apiKey = System.getenv("TWIG_JF_KEY").orEmpty(),
                emby = !System.getenv("TWIG_JF_EMBY").isNullOrEmpty(),
                deviceId = "twig-live-test",
                clientVersion = "test",
            ),
        )
    }

    private fun dir(path: String) = XFile(JellyfinFileSystem.SCHEME, path, isDir = true)

    /**
     * Digs through the media libraries to find a batch of **directly playable** items, so
     * these test cases have something to work with regardless of library structure. A
     * shows library's top level is series, then seasons, so it needs to drill down a few
     * levels before reaching individual episodes.
     */
    private fun anyItems(vararg ignored: String): List<XFile> {
        fun dig(f: XFile, depth: Int): List<XFile> {
            if (depth > 3) return emptyList()
            val rows = runCatching { fs.list(f) }.getOrElse { return emptyList() }
            rows.filter { !it.isDir }.takeIf { it.isNotEmpty() }?.let { return it }
            for (d in rows.filter { it.isDir }) dig(d, depth + 1).takeIf { it.isNotEmpty() }?.let { return it }
            return emptyList()
        }
        for (lib in runCatching { fs.list(dir("/folders")) }.getOrElse { emptyList() }) {
            dig(lib, 1).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    @Test
    fun `authentication succeeds and every root entry can be opened`() {
        val roots = fs.list(fs.root())
        // which entries appear at the root depends on which libraries actually exist on
        // the server; only "Resume" is guaranteed to be there
        assertTrue("root is empty", roots.isNotEmpty())
        assertTrue("missing Resume", roots.any { it.path == "/resume" })
        // actually list every one of them: a wrong auth header or a misspelled endpoint
        // name will blow up right here, and "opens but empty" looks identical to "won't
        // open" in the UI, so we only assert that it doesn't throw
        for (r in roots) {
            runCatching { fs.list(r) }.onFailure {
                throw AssertionError("listing ${r.path} (${r.name}) failed: ${it.message}", it)
            }
        }
    }

    @Test
    fun `listed media items carry an extension and a real byte size`() {
        val rows = anyItems("/movies", "/shows", "/music", "/resume")
        assumeFalse("server has no media items at all, skipping", rows.isEmpty())
        val file = rows.firstOrNull { !it.isDir }
        assumeFalse("this server's top level is entirely folder items, skipping", file == null)
        // extension: the player relies entirely on it for the container (see the AVI entry in CLAUDE.md)
        assertTrue("no extension: ${file!!.name}", file.name.contains('.'))
        // size comes from MediaSources — without it every row in the file manager shows 0 bytes
        assertTrue("size is 0: ${file.name}", file.size > 0)
    }

    @Test
    fun `media bytes can be read, and Range-based random access works`() {
        val rows = anyItems("/movies", "/shows", "/music", "/resume")
        val file = rows.firstOrNull { !it.isDir && it.size > 4096 }
        assumeFalse("no readable media item, skipping", file == null)

        val head = ByteArray(16)
        fs.openInput(file!!).use { ins ->
            var n = 0
            while (n < head.size) {
                val k = ins.read(head, n, head.size - n)
                if (k <= 0) break
                n += k
            }
            assertEquals(head.size, n)
        }

        // random access read: player seeking and network video thumbnails both depend on this
        assertTrue(fs.randomAccessEfficient())
        fs.openRandom(file).use { src ->
            val at = ByteArray(16)
            val n = src.readAt(1024, at, 0, at.size)
            assertTrue("random access read returned $n", n > 0)
        }
    }

    @Test
    fun `a poster can be downloaded and is genuinely an image`() {
        val rows = anyItems("/movies", "/shows", "/music")
        assumeFalse("server has no items, skipping", rows.isEmpty())
        val bytes = rows.firstNotNullOfOrNull { item ->
            runCatching { fs.openCover(item, 256)?.use { it.readBytes() } }.getOrNull()
        }
        assumeFalse("none of these items has a primary image, skipping", bytes == null)
        assertTrue("image is too small (${bytes!!.size} bytes), probably an error page", bytes.size > 512)
        // JPEG (FFD8) or PNG (89504E47); an HTML error page would show up here
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val webp = bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        assertTrue("neither JPEG, PNG nor WebP: ${bytes.take(8)}", jpeg || png || webp)
    }

    @Test
    fun `progress reports are accepted by the server and read back correctly`() {
        val rows = anyItems("/movies", "/shows", "/resume")
        val file = rows.firstOrNull { !it.isDir }
        assumeFalse("no reportable item, skipping", file == null)

        val before = fs.positionOf(file!!)
        try {
            val mark = 123_000L
            fs.report(file, mark, 3_600_000L, PlayState.START)
            fs.report(file, mark, 3_600_000L, PlayState.PROGRESS)
            fs.report(file, mark, 3_600_000L, PlayState.STOP)
            // allow a few hundred ms of rounding error (tick conversion), but it must land near the same position
            val after = fs.positionOf(file)
            assertTrue("reported $mark but read back $after", kotlin.math.abs(after - mark) < 2_000)
        } finally {
            // don't leave a mark on the user's own watch history: restore the pre-test position
            runCatching { fs.report(file, before, 3_600_000L, PlayState.STOP) }
        }
    }

    /**
     * ★ The **full closed loop** for the 2026-08-17 incident, and the single most
     * important test in this file.
     *
     * Back then two independent bugs both made "Resume" permanently empty: reporting was
     * missing `PlaySessionId` (Emby answered 400, so progress never got written at all),
     * and the Resume query was missing `MediaTypes` (Emby answered 200 with an empty
     * list). Unit tests for each can only prove "the bytes we send match our own
     * expectations" — they cannot prove the whole chain actually works end to end. Only
     * "write it, then read it back" can.
     */
    @Test
    fun `after reporting progress, the item shows up in Resume`() {
        val existing = runCatching { fs.list(dir("/resume")) }.getOrElse { emptyList() }
        // an item already in the resume list necessarily satisfies the server's
        // duration/percentage threshold, so prefer it; only fall back to a random pick when
        // there are none (in which case the position value below might miss the threshold,
        // see the failure message)
        val file = existing.firstOrNull { !it.isDir }
            ?: anyItems("/movies", "/shows").firstOrNull { !it.isDir }
        assumeFalse("server has no playable item at all, skipping", file == null)
        val id = file!!.path.substringAfterLast('/')
        val before = fs.positionOf(file)
        try {
            val mark = 60_000L
            fs.report(file, mark, 3_600_000L, PlayState.START)
            fs.report(file, mark, 3_600_000L, PlayState.PROGRESS)
            val resume = fs.list(dir("/resume")).map { it.path.substringAfterLast('/') }
            assertTrue(
                "$id not found in Resume after reporting (Resume contains $resume). If this item is " +
                    "shorter than 5 minutes (Emby's MinResumeDurationSeconds defaults to 300s), or " +
                    "${mark / 1000}s falls outside the server's 5%-90% window, that's the server's " +
                    "resume threshold, not a query bug — try a longer video.",
                resume.contains(id),
            )
        } finally {
            // restore: if before is 0 this just clears the record, leaving no test trace for the user
            runCatching { fs.report(file, before, 3_600_000L, PlayState.STOP) }
        }
    }

    /**
     * "Latest" gives **directly playable items**, not albums/series requiring another click.
     *
     * ★ The server's default (`GroupItems=true`) returns containers, so "Latest Music"
     * ended up listing a pile of directories — the user reported this. Querying by library
     * (`ParentId`) incidentally fixed the other half: without it, `Latest` returns every
     * type regardless, which was once misdiagnosed as "Emby ignores IncludeItemTypes".
     */
    @Test
    fun `Latest contains directly playable items, not containers`() {
        val libs = runCatching { fs.list(dir("/latest")) }.getOrElse { emptyList() }
        assumeFalse("server has no media library, skipping", libs.isEmpty())
        var checked = 0
        for (lib in libs) {
            val rows = runCatching { fs.list(lib) }.getOrElse { emptyList() }
            if (rows.isEmpty()) continue
            checked++
            assertTrue(
                "Latest / ${lib.name} contains a directory: ${rows.filter { it.isDir }.map { it.name }}",
                rows.none { it.isDir },
            )
        }
        assumeFalse("Latest is empty for every library, skipping", checked == 0)
    }

    @Test
    fun `the first level under both Latest and library entries is the library, named as set on the server`() {
        val libs = runCatching { fs.list(dir("/latest")) }.getOrElse { emptyList() }
        assumeFalse("server has no media library, skipping", libs.isEmpty())
        assertTrue("every library should be expandable", libs.all { it.isDir })
        // same set of libraries, only the path prefix differs between the two entry points
        val viaFolders = runCatching { fs.list(dir("/folders")) }.getOrElse { emptyList() }
        assertEquals(libs.map { it.name }, viaFolders.map { it.name })
    }

    @Test
    fun `details come straight from the server without reading file bytes`() {
        val file = anyItems("/movies", "/shows").firstOrNull { !it.isDir }
        assumeFalse("server has no playable item, skipping", file == null)
        val d = fs.detailsOf(file!!)
        assertNotNull("could not get media details", d)
        // the real path on the server: the details card shows this, not our own GUID string
        assertTrue("realPath is empty", d!!.realPath.isNotEmpty())
        assertTrue("duration is 0", d.durationMs > 0)
        // field names (MediaStreams/Type/Codec/Width…) can only be verified against a real server
        assertTrue("no video stream was parsed out", d.streams.any { it.kind == MediaStream.Kind.VIDEO })
    }

    /**
     * In "Resume", **the most recently played item ranks first** — the UI layer preserves
     * this order as-is without applying the user's chosen sort (see
     * `PaneViewModelServerOrderTest`), so this precondition must hold or that work is
     * wasted.
     *
     * ★ Also pins down something learned the hard way while debugging: **sending only
     * PROGRESS without START never updates `LastPlayedDate`** — the item makes it into
     * Resume, but its ranking doesn't move. The player's path (`RemoteProgress`) sends a
     * START the first time it reports progress; this test does the same.
     */
    @Test
    fun `the most recently played item ranks first in Resume`() {
        val pool = (runCatching { fs.list(dir("/resume")) }.getOrElse { emptyList() } +
            anyItems("/movies", "/shows")).filter { !it.isDir }.distinctBy { it.path.substringAfterLast('/') }
        assumeFalse("need at least two playable items to compare order, skipping", pool.size < 2)
        val a = pool[0]
        val b = pool[1]
        val idA = a.path.substringAfterLast('/')
        val idB = b.path.substringAfterLast('/')
        val beforeA = fs.positionOf(a)
        val beforeB = fs.positionOf(b)
        fun play(f: XFile) {
            fs.report(f, 60_000L, 3_600_000L, PlayState.START)
            fs.report(f, 60_000L, 3_600_000L, PlayState.PROGRESS)
        }
        fun resumeIds() = fs.list(dir("/resume")).map { it.path.substringAfterLast('/') }
        try {
            play(a)
            Thread.sleep(1200) // the two playback timestamps need to be far enough apart
            play(b)
            val afterB = resumeIds()
            assumeFalse("neither item made it into the resume list (probably the server's duration threshold), skipping", !afterB.contains(idA) || !afterB.contains(idB))
            assertTrue("the just-played $idB should rank before $idA, actual $afterB", afterB.indexOf(idB) < afterB.indexOf(idA))

            Thread.sleep(1200)
            play(a) // play a again, it should return to the front
            val afterA = resumeIds()
            assertTrue("the just-played $idA should rank before $idB, actual $afterA", afterA.indexOf(idA) < afterA.indexOf(idB))
        } finally {
            // restore, leaving no test trace for the user
            runCatching { fs.report(a, beforeA, 3_600_000L, PlayState.STOP) }
            runCatching { fs.report(b, beforeB, 3_600_000L, PlayState.STOP) }
        }
    }

    /**
     * A playlist can be opened, and **its order is the one the server gives** (the user's
     * curated play order).
     *
     * The criterion only holds when "this particular playlist isn't already alphabetical"
     * — otherwise sorted-or-not looks identical and can't be distinguished, so that case
     * is skipped. Pinning down "the request itself carries no SortBy" is a job for the
     * wire-level tests.
     */
    @Test
    fun `a playlist can be opened and is not reordered`() {
        val lists = runCatching { fs.list(dir("/playlists")) }.getOrElse { emptyList() }
        assumeFalse("server has no playlists, skipping", lists.isEmpty())
        val items = lists.firstNotNullOfOrNull { pl ->
            runCatching { fs.list(pl) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        assumeFalse("all playlists are empty, skipping", items == null)
        val names = items!!.map { it.name }
        assumeFalse("this playlist happens to be alphabetical already, can't tell if it was resorted, skipping", names == names.sorted())
        // reaching here: the content is not alphabetical — meaning neither the query added
        // SortBy nor did the UI resort it
    }

    @Test
    fun `the entire source is read-only`() {
        assertTrue(!fs.writable())
    }

    /**
     * The "Resume" thumbnail is a **landscape still frame** (Thumb/Backdrop), not a
     * portrait poster.
     *
     * A mock can only prove "we sent the bytes we imagined"; what actually needs pinning
     * down is **whether the server recognizes those fields** — names like
     * `ParentThumbImageTag` / `ParentBackdropItemId` were reverse-engineered from both real
     * servers' actual Resume responses on 2026-08-19, and getting one character wrong
     * silently degrades to a portrait poster with no error at all. The criterion used here
     * is **the image's aspect ratio**: getting a landscape image confirms the still-frame
     * path was taken.
     */
    @Test
    fun `Resume returns a landscape still frame`() {
        val items = runCatching { fs.list(dir("/resume")) }.getOrElse { emptyList() }
        assumeFalse("resume list is empty — play something longer than 5 minutes first", items.isEmpty())
        var checked = 0
        for (it in items.take(5)) {
            val bytes = fs.openCover(it, 512)?.use { s -> s.readBytes() } ?: continue
            val (w, h) = jpegSize(bytes) ?: continue
            checked++
            assertTrue("${it.name}'s image is ${w}x$h — portrait means it degraded to a poster", w > h)
        }
        assumeFalse("no resume item has an image, cannot test", checked == 0)
    }

    /**
     * Episode queue: pick any episode, and `episodesOf` should return the whole series,
     * ordered across seasons, including the episode itself.
     *
     * `/Shows/{seriesId}/Episodes` is the foundation of this feature, and "does the server
     * recognize this route, and does it answer with `{Items:[…]}` or a bare array" can only
     * be answered by a real server — a mock only asserts our own assumptions (this is
     * exactly how the S3 encoding trap happened).
     */
    @Test
    fun `episodesOf returns the whole series' episode list`() {
        val ep = runCatching { fs.list(dir("/lib")) }.getOrElse { emptyList() }
            .asSequence()
            .flatMap { lib -> runCatching { fs.list(lib) }.getOrElse { emptyList() }.asSequence() }
            .flatMap { series -> runCatching { fs.list(series) }.getOrElse { emptyList() }.asSequence() }
            .firstOrNull { !it.isDir }
        assumeFalse("no single episode in the library, skipping", ep == null)
        val list = fs.episodesOf(ep!!)
        assertTrue("should recognize this as an episode and return a queue", list != null && list.size > 1)
        assertTrue("the queue should include itself", list!!.any { it.path == ep.path })
        // ordered: names carry SxxEyy, and if sorted, string order matches the given order
        val names = list.map { it.name }
        assertEquals("the order given by the server should already be sorted", names.sorted(), names)
    }

    /**
     * Reads width/height from a JPEG's SOF segment. ★ Deliberately not using
     * `javax.imageio`: the test JVM has **no JPEG reader**, so `ImageIO.read` just returns
     * null — which would silently `assume` the whole test away, looking like "skipped"
     * rather than "not actually tested" (this fooled us once while debugging).
     */
    private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val m = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            // SOF0..SOF15, excluding DHT(C4)/JPG(C8)/DAC(CC) — those are not frame headers
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return w to h
            }
            if (len <= 0) return null
            i += 2 + len
        }
        return null
    }
}
