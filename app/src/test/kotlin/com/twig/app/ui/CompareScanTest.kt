package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Directory comparison judging and scanning ([CompareScan.kt]). What is covered here are
 * the points **most likely to false-positive across sources**: time precision/timezone,
 * case sensitivity, exclude-rule matching semantics, and "an excluded directory is really
 * not descended into". Pure logic, no UI touched, so no Robolectric is needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompareScanTest {

    private val registered = mutableListOf<String>()

    private fun fakeFs(
        scheme: String,
        dirs: Map<String, List<String>>,
        files: Map<String, String> = emptyMap(),
        times: Map<String, Long> = emptyMap(),
    ): FakeFileSystem {
        val fs = FakeFileSystem(scheme, dirs, files, times)
        FsRegistry.register(fs)
        registered += scheme
        return fs
    }

    @After
    fun tearDown() {
        registered.forEach { FsRegistry.unregister(it) }
    }

    // ---- time judging ----

    @Test
    fun `2-second tolerance covers FAT and zip coarse-grained timestamps`() {
        val o = CompareOptions()
        assertTrue(sameTime(1_000_000L, 1_001_999L, o))
        assertFalse(sameTime(1_000_000L, 1_003_000L, o))
    }

    @Test
    fun `a whole-hour offset is treated as a timezone mismatch, not a content change`() {
        val o = CompareOptions()
        val hour = 3_600_000L
        assertTrue("1 hour apart", sameTime(1_000_000L, 1_000_000L + hour, o))
        assertTrue("8 hours apart (UTC+8)", sameTime(1_000_000L, 1_000_000L - 8 * hour, o))
        assertTrue("a whole hour plus 1 more second of jitter still counts", sameTime(1_000_000L, 1_000_000L + hour + 1_000L, o))
        assertFalse("half an hour apart does not count", sameTime(1_000_000L, 1_000_000L + hour / 2, o))
    }

    @Test
    fun `with the hour-shift allowance off, one hour apart is a difference`() {
        val o = CompareOptions(allowHourShift = false)
        assertFalse(sameTime(1_000_000L, 1_000_000L + 3_600_000L, o))
    }

    // ---- exclude rules ----

    @Test
    fun `exclude rules match the full name, so rebuild is not mistaken for build`() {
        val pats = listOf("build")
        assertTrue(matchesExclude("build", "src/build", pats))
        assertFalse("substring matching would false-positive here; it must be a full-name match", matchesExclude("rebuild.log", "src/rebuild.log", pats))
    }

    @Test
    fun `a rule containing a slash matches the relative path, one without matches the file name`() {
        assertTrue(matchesExclude("a.o", "build/a.o", listOf("build/*")))
        assertFalse(matchesExclude("a.o", "src/a.o", listOf("build/*")))
        assertTrue(matchesExclude("a.tmp", "deep/nested/a.tmp", listOf("*.tmp")))
    }

    // ---- pairing ----

    @Test
    fun `case-insensitive pairing, and multiple same-named entries are not dropped`() {
        val l = listOf(file("a", "/README.md"), file("a", "/Readme.md"))
        val r = listOf(file("b", "/readme.md"))
        val pairs = pairEntries(l, r, ignoreCase = true)
        assertEquals(2, pairs.size)
        assertEquals("the first one gets paired", "/readme.md", pairs[0].second?.path)
        assertNull("the second one has nothing to pair with, becoming an orphan rather than being swallowed", pairs[1].second)
    }

    @Test
    fun `a directory and a file with the same name do not pair with each other`() {
        val pairs = pairEntries(
            listOf(XFile("a", "/x", isDir = true)),
            listOf(file("b", "/x")),
            ignoreCase = true,
        )
        assertEquals(2, pairs.size)
        assertTrue(pairs.all { it.first == null || it.second == null })
    }

    // ---- scanning ----

    @Test
    fun `the four states plus directory rollup`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("same.txt", "diff.txt", "only-l.txt", "sub"), "/sub" to listOf("k.txt")),
            files = mapOf("/same.txt" to "aaa", "/diff.txt" to "aaa", "/only-l.txt" to "x", "/sub/k.txt" to "aaa"),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("same.txt", "diff.txt", "only-r.txt", "sub"), "/sub" to listOf("k.txt")),
            files = mapOf("/same.txt" to "aaa", "/diff.txt" to "aaaaa", "/only-r.txt" to "y", "/sub/k.txt" to "aaa"),
        )

        val events = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList()

        val root = events.filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }
        fun stateOf(name: String) = root.rows.first { it.name == name }.state
        assertEquals(PairState.SAME, stateOf("same.txt"))
        assertEquals("a size difference alone is a diff", PairState.DIFF, stateOf("diff.txt"))
        assertEquals(PairState.LEFT_ONLY, stateOf("only-l.txt"))
        assertEquals(PairState.RIGHT_ONLY, stateOf("only-r.txt"))

        val done = events.filterIsInstance<CompareEvent.DirDone>().associate { it.dirKey to it.state }
        assertEquals("a subtree that is entirely the same rolls up to SAME", PairState.SAME, done["sub"])
        assertEquals("the root has a difference, so it rolls up to DIFF", PairState.DIFF, done[""])
    }

    @Test
    fun `an excluded directory is never descended into`() = runTest {
        val left = fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("keep", "build"), "/keep" to listOf(), "/build" to listOf("a.o")),
            files = mapOf("/build/a.o" to "x"),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("keep", "build"), "/keep" to listOf(), "/build" to listOf("a.o")),
            files = mapOf("/build/a.o" to "x"),
        )

        val events = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(excludes = listOf("build")),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList()

        assertFalse("an excluded directory must be pruned during the scan, not filtered out after scanning it fully", left.listed.contains("/build"))
        assertTrue(left.listed.contains("/keep"))
        val root = events.filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }
        assertTrue("excluded entries do not show up in the results", root.rows.none { it.name == "build" })
    }

    @Test
    fun `an excluded entry reports which rule blocked it`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("keep.txt", "a.tmp", "build"), "/build" to listOf()),
            files = mapOf("/keep.txt" to "x", "/a.tmp" to "y"),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("keep.txt", "a.tmp", "build"), "/build" to listOf()),
            files = mapOf("/keep.txt" to "x", "/a.tmp" to "y"),
        )
        val ex = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(excludes = listOf("*.tmp", "build")),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Excluded>()

        assertEquals(2, ex.size)
        // when a rule is removed, this is what pinpoints exactly "the entry it used to block" without rescanning the whole tree
        assertEquals("*.tmp", ex.first { it.name == "a.tmp" }.rule)
        assertEquals("build", ex.first { it.name == "build" }.rule)
        assertTrue("the containing directory must also be carried along", ex.all { it.dirKey == "" })
    }

    @Test
    fun `when one side is empty, the whole tree is judged as only-on-the-other-side`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("deep"), "/deep" to listOf("a.txt")),
            files = mapOf("/deep/a.txt" to "x"),
        )
        // the right side is not passed at all (this is exactly the call shape used when restoring a directory that is "only on one side")
        val rows = scanCompare(
            XFile("cl", "/", isDir = true),
            null,
            CompareOptions(),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Children>()

        assertEquals(PairState.LEFT_ONLY, rows.first { it.dirKey == "" }.rows.single().state)
        assertEquals(PairState.LEFT_ONLY, rows.first { it.dirKey == "deep" }.rows.single().state)
    }

    @Test
    fun `a directory unique to one side is still recursed into, and all its descendants get the same side's label`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("gone"), "/gone" to listOf("deep"), "/gone/deep" to listOf("a.txt")),
            files = mapOf("/gone/deep/a.txt" to "x"),
        )
        fakeFs("cr", dirs = mapOf("/" to listOf()))

        val events = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList()

        val deep = events.filterIsInstance<CompareEvent.Children>().first { it.dirKey == "gone/deep" }
        assertEquals(PairState.LEFT_ONLY, deep.rows.single().state)
        val done = events.filterIsInstance<CompareEvent.DirDone>().associate { it.dirKey to it.state }
        assertEquals("a directory that only exists on the left keeps its own state regardless of its subtree's content", PairState.LEFT_ONLY, done["gone"])
    }

    @Test
    fun `same size but different time - content comparison on judges SAME, off judges DIFF`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 1_000_000L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 9_000_000L),
        )
        val l = XFile("cl", "/", isDir = true)
        val r = XFile("cr", "/", isDir = true)

        suspend fun stateWith(o: CompareOptions) = scanCompare(l, r, o, UnconfinedTestDispatcher(testScheduler))
            .toList().filterIsInstance<CompareEvent.Children>()
            .first { it.dirKey == "" }.rows.single().state

        // the fake source's scheme is not "file", so it goes through the "network" ceiling.
        assertEquals(PairState.DIFF, stateWith(CompareOptions()))
        assertEquals(PairState.SAME, stateWith(CompareOptions(contentLimitNetwork = 1024)))
    }

    @Test
    fun `content differs but size and time are both the same - only content comparison catches it`() = runTest {
        fakeFs("cl", dirs = mapOf("/" to listOf("a.txt")), files = mapOf("/a.txt" to "hello"))
        fakeFs("cr", dirs = mapOf("/" to listOf("a.txt")), files = mapOf("/a.txt" to "world"))
        val l = XFile("cl", "/", isDir = true)
        val r = XFile("cr", "/", isDir = true)

        suspend fun stateWith(o: CompareOptions) = scanCompare(l, r, o, UnconfinedTestDispatcher(testScheduler))
            .toList().filterIsInstance<CompareEvent.Children>()
            .first { it.dirKey == "" }.rows.single().state

        assertEquals("looking only at size and time misses this", PairState.SAME, stateWith(CompareOptions()))
        // also need to turn off the "only read content when time differs" gate: this pair's times are also the same, so leaving it on would let it through unchecked
        assertEquals(
            PairState.DIFF,
            stateWith(CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = false)),
        )
    }

    @Test
    fun `content is only read when time differs - when size and time both match, it is not read`() = runTest {
        val left = fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 1_000_000L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "world"), // content differs, but size and time are both the same
            times = mapOf("/a.txt" to 1_000_000L),
        )
        val l = XFile("cl", "/", isDir = true)
        val r = XFile("cr", "/", isDir = true)

        suspend fun stateWith(o: CompareOptions) = scanCompare(l, r, o, UnconfinedTestDispatcher(testScheduler))
            .toList().filterIsInstance<CompareEvent.Children>()
            .first { it.dirKey == "" }.rows.single().state

        assertEquals(
            "with this gate on it should not read, so it is judged SAME",
            PairState.SAME,
            stateWith(CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = true)),
        )
        assertFalse("and the file really was never read", left.opened.contains("/a.txt"))

        assertEquals(
            "with the gate off it reads regardless, and different content is different",
            PairState.DIFF,
            stateWith(CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = false)),
        )
        assertTrue(left.opened.contains("/a.txt"))
    }

    @Test
    fun `when time differs it reads regardless, and identical content is still judged SAME`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 1_000_000L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 9_000_000L),
        )
        val state = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = true),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }.rows.single().state
        assertEquals(PairState.SAME, state)
    }

    @Test
    fun `a pair over the content-comparison size limit falls back to judging by time`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("big.bin")),
            files = mapOf("/big.bin" to "0123456789"),
            times = mapOf("/big.bin" to 0L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("big.bin")),
            files = mapOf("/big.bin" to "abcdefghij"),
            times = mapOf("/big.bin" to 0L),
        )
        val rows = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(contentLimitNetwork = 5), // file is 10 bytes, over the limit
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }.rows
        assertEquals("the content is actually different, but past the limit it is judged only by size and time", PairState.SAME, rows.single().state)
    }

    private fun file(scheme: String, path: String) = XFile(scheme, path, isDir = false, size = 1)
}
