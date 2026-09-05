package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sync plan ([CompareSync.kt]). What is covered here are three things that "go wrong
 * silently, with no error, just quietly moving the wrong thing": direction, whether the
 * extra items on the destination side get deleted, and whether the newer items on the
 * destination side get overwritten.
 *
 * Pure logic, no UI touched; both sides are faked with [FakeFileSystem], so no Robolectric
 * is needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompareSyncTest {

    private val io = UnconfinedTestDispatcher()

    /** Same name, same size, same time on both sides = same; different size = a diff. Time is only used on the "which one is newer" cases. */
    private val left = FakeFileSystem(
        "syncl",
        dirs = mapOf(
            "/" to listOf("same.txt", "diff.txt", "newer.txt", "onlyL.txt", "dirL", "both"),
            "/dirL" to listOf("a.txt", "b.txt"),
            "/both" to listOf("inner.txt"),
        ),
        files = mapOf(
            "/same.txt" to "xx",
            "/diff.txt" to "aaa",
            "/newer.txt" to "bbb",
            "/onlyL.txt" to "l",
            "/dirL/a.txt" to "a",
            "/dirL/b.txt" to "b",
            "/both/inner.txt" to "same",
        ),
        times = mapOf(
            "/same.txt" to 1_000L, "/diff.txt" to 1_000L, "/newer.txt" to 1_000L,
            "/both/inner.txt" to 1_000L,
        ),
    )

    private val right = FakeFileSystem(
        "syncr",
        dirs = mapOf(
            "/" to listOf("same.txt", "diff.txt", "newer.txt", "onlyR.txt", "dirR", "both"),
            "/dirR" to listOf("c.txt"),
            "/both" to listOf("inner.txt"),
        ),
        files = mapOf(
            "/same.txt" to "xx",
            "/diff.txt" to "aa",
            "/newer.txt" to "bb",
            "/onlyR.txt" to "r",
            "/dirR/c.txt" to "c",
            "/both/inner.txt" to "same",
        ),
        times = mapOf(
            "/same.txt" to 1_000L, "/diff.txt" to 1_000L,
            "/newer.txt" to 9_000_000L, // the right side's copy is newer here
            "/both/inner.txt" to 1_000L,
        ),
    )

    @Before
    fun setUp() {
        FsRegistry.register(left)
        FsRegistry.register(right)
    }

    @After
    fun tearDown() {
        FsRegistry.unregister(left.scheme)
        FsRegistry.unregister(right.scheme)
    }

    private fun rootOf(fs: FakeFileSystem) = XFile(fs.scheme, "/", isDir = true)

    private suspend fun planFrom(from: Int) =
        buildSyncPlan(rootOf(left), rootOf(right), CompareOptions(), from, io)

    // ---- per-item judging ----

    @Test
    fun `an item unique to the source is pushed over, one unique to the destination is marked for deletion`() {
        assertEquals(SyncAct.COPY, syncActionFor(PairState.LEFT_ONLY, isDir = false, from = 0))
        assertEquals(SyncAct.DELETE, syncActionFor(PairState.RIGHT_ONLY, isDir = false, from = 0))
        // reversed direction: same state, exactly opposite conclusion
        assertEquals(SyncAct.DELETE, syncActionFor(PairState.LEFT_ONLY, isDir = false, from = 1))
        assertEquals(SyncAct.COPY, syncActionFor(PairState.RIGHT_ONLY, isDir = false, from = 1))
    }

    @Test
    fun `directories always get descended into, identical files do nothing`() {
        // in the preorder event stream a directory is still SCANNING; DIFF is only backfilled once its subtree finishes scanning, and both must be descended into
        assertEquals(SyncAct.DESCEND, syncActionFor(PairState.SCANNING, isDir = true, from = 0))
        assertEquals(SyncAct.DESCEND, syncActionFor(PairState.DIFF, isDir = true, from = 0))
        assertEquals(SyncAct.COPY, syncActionFor(PairState.DIFF, isDir = false, from = 0))
        assertEquals(SyncAct.SKIP, syncActionFor(PairState.SAME, isDir = false, from = 0))
    }

    @Test
    fun `a time difference within tolerance does not count as the destination being newer`() {
        val o = CompareOptions()
        val src = XFile("x", "/a", isDir = false, lastModified = 1_000_000L)
        // 1.5 seconds: within the 2-second tolerance, on FAT and zip this is the same instant and should not be used to alarm the user
        assertFalse(targetIsNewer(src, src.copy(lastModified = 1_001_500L), o))
        assertTrue(targetIsNewer(src, src.copy(lastModified = 1_010_000L), o))
        assertFalse("an older destination obviously does not count", targetIsNewer(src, src.copy(lastModified = 990_000L), o))
    }

    // ---- plan ----

    @Test
    fun `left pushes to right - diffs and left-only items go into copies, right-only items go into deletes`() = runTest(io) {
        val plan = planFrom(0)
        assertEquals(
            listOf("diff.txt", "newer.txt", "onlyL.txt", "dirL"),
            plan.copies.map { it.key },
        )
        assertEquals(listOf("onlyR.txt", "dirR"), plan.deletes.map { it.key })
    }

    @Test
    fun `a directory unique to one side is taken as a whole, its children are not registered separately`() = runTest(io) {
        val plan = planFrom(0)
        assertTrue("dirL itself is present", plan.copies.any { it.key == "dirL" })
        assertTrue(
            "dirL's children must not be registered again (CopyEngine recurses on its own)",
            plan.copies.none { it.key.startsWith("dirL/") },
        )
        assertTrue("same goes for dirR's children", plan.deletes.none { it.key.startsWith("dirR/") })
    }

    @Test
    fun `files identical on both sides do not appear in the plan`() = runTest(io) {
        val plan = planFrom(0)
        val keys = (plan.copies + plan.deletes).map { it.key }
        assertFalse(keys.contains("same.txt"))
        assertFalse("identical ones inside a subdirectory do not appear either", keys.contains("both/inner.txt"))
    }

    @Test
    fun `syncing in reverse flips the conclusion exactly`() = runTest(io) {
        val plan = planFrom(1)
        assertEquals(
            listOf("diff.txt", "newer.txt", "onlyR.txt", "dirR"),
            plan.copies.map { it.key },
        )
        assertEquals(listOf("onlyL.txt", "dirL"), plan.deletes.map { it.key })
    }

    @Test
    fun `an item newer on the destination side gets flagged, and is not overwritten by default`() = runTest(io) {
        val plan = planFrom(0)
        assertEquals(listOf("newer.txt"), plan.newer.map { it.key })
        assertFalse(
            "with overwrite unchecked it should be excluded, so we do not clobber something the user changed on the other side",
            plan.copiesFor(overwriteNewer = false).any { it.key == "newer.txt" },
        )
        assertTrue(plan.copiesFor(overwriteNewer = true).any { it.key == "newer.txt" })
        // pushing in reverse, the left copy is older here — is the "destination is newer" item then the left one? No, the left side is the source
        assertTrue("right-to-left: the right side is newer, so the destination (left) is not newer", planFrom(1).newer.isEmpty())
    }

    @Test
    fun `incremental sync deletes nothing, only turning it off gives a mirror`() = runTest(io) {
        val plan = planFrom(0)
        assertTrue(plan.deletesFor(incremental = true).isEmpty())
        assertEquals(2, plan.deletesFor(incremental = false).size)
    }

    // ---- destination path ----

    @Test
    fun `a deeply nested item lands in the matching subdirectory on the other side, not dumped at the root`() {
        val dst = XFile("r", "/backup", isDir = true)
        assertEquals("/backup/a/b", compareDestDir(dst, "a/b/c.txt").path)
        assertEquals("/backup", compareDestDir(dst, "top.txt").path)
        assertEquals("an empty-path root must not produce an empty string either", "/", compareDestDir(XFile("r", "", isDir = true), "x").path)
    }
}
