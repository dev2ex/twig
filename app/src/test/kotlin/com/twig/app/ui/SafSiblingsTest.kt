package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Files in a SAF tree must behave the same as everywhere else: images can be paged through,
 * audio can build a playback queue.
 *
 * ★ The root cause is that **a SAF `path` is a whole document URI**; the `/`s inside both a
 * child's and its parent's document ids are encoded as `%2F` -- whatever `XFile.parentPath`
 * slices out by splitting on `/` is not the parent directory at all. Using that to look up
 * "what else is in the same directory" in `children` finds nothing: an image can only be
 * viewed alone, and music cannot build a queue (falling back to the old single-track
 * player). This test pins that down using a real-shaped URI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafSiblingsTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private val dispatcher = StandardTestDispatcher()

    /** Real shape: a tree segment + a document segment, with %2F instead of / as the separator inside the document id. */
    private val dirUri =
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM"

    private fun child(name: String) = XFile(
        "saf", "$dirUri%2F$name", isDir = false, size = 10L, lastModified = 1L, displayName = name,
    )

    private val subUri = "$dirUri%2Fsub"

    private val kids = listOf(
        XFile("saf", subUri, isDir = true, displayName = "sub"),
        child("a.jpg"), child("b.jpg"), child("c.mp3"), child("d.mp3"),
    )

    private class SafLikeFs(private val dir: String, private val kids: List<XFile>) :
        FileSystem by FakeFileSystem("saf", emptyMap()) {
        override val scheme = "saf"
        override fun list(d: XFile): List<XFile> = if (d.path == dir) kids else emptyList()
        /** ★ The real SafFileSystem also returns null -- there is no general API for getting a document URI's parent directory. */
        override fun parentOf(file: XFile): XFile? = null
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        FsRegistry.register(SafLikeFs(dirUri, kids))
        vm = PaneViewModel(app).apply { io = dispatcher }
        vm.lockRoot = XFile("saf", dirUri, isDir = true, displayName = "DCIM")
        vm.lockLabel = "SAF"
        vm.refreshTree()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        FsRegistry.unregister("saf")
    }

    private fun kotlinx.coroutines.test.TestScope.expandRoot() {
        val root = vm.state.value.rows.first { it is PaneViewModel.FileNode }
        vm.toggle(root)
        advanceUntilIdle()
    }

    @Test
    fun `images in the same directory can all be paged, not just the one opened`() = runTest(dispatcher) {
        expandRoot()
        val (images, idx) = vm.imageSiblings(child("b.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg"), images.map { it.name })
        assertEquals(1, idx)
    }

    @Test
    fun `audio in the same directory can build a playback queue`() = runTest(dispatcher) {
        expandRoot()
        val (audios, idx) = vm.audioSiblings(child("d.mp3"))
        assertEquals(listOf("c.mp3", "d.mp3"), audios.map { it.name })
        assertEquals(1, idx)
    }

    /**
     * A SAF grant is persistent (`takePersistableUriPermission`), so its audio can still
     * enter "Now Playing" -- previously [PaneViewModel.trackFrom] only recognized local
     * files and connected servers, always returning null for SAF, which fell back to the
     * old single-track player, a completely separate path from everywhere else.
     */
    @Test
    fun `SAF audio can enter Now Playing, keeping both name and extension`() {
        val t = vm.trackFrom(child("d.mp3"))
        assertNotNull("a SAF track should not be blocked from the playlist", t)
        assertEquals("saf", t!!.kind)
        val back = MusicEngine.resolveFile(app, t)
        assertNotNull(back)
        assertEquals("d.mp3", back!!.name)
        assertEquals("mp3", back.extension) // media3 can't recognize the container without the extension
    }

    /**
     * ★ Expanding a subdirectory in SAF and then clicking "up" makes the green highlight
     * disappear entirely: `parentPath` slices `…/document/primary%3ADCIM%2Fsub` down to
     * `…/document`, a row that does not exist in the tree. "Up" has to ask the tree --
     * whoever contains a row is its parent.
     */
    @Test
    fun `expanding a subdirectory in SAF then clicking up returns the highlight to the parent directory`() = runTest(dispatcher) {
        expandRoot()
        val sub = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == subUri }
        vm.toggle(sub)
        advanceUntilIdle()
        assertEquals(subUri, vm.state.value.currentDir?.path)

        vm.up()
        advanceUntilIdle()

        assertEquals(dirUri, vm.state.value.currentDir?.path)
        assertEquals("f:saf:$dirUri", vm.state.value.currentKey)
    }

    /**
     * ★ Going up from SAF's **grant root** (the row under "Document Tree (SAF)") should
     * land the green highlight on the group header, not make it disappear. That row hangs
     * directly off the group, with nothing in the tree "containing" it, so the old logic
     * pieced together a nonexistent row via `parentPath` -- a server's root already falls
     * back to its group, and this aligns with that.
     */
    @Test
    fun `going up from a SAF grant root lands the highlight on the Document Tree row`() = runTest(dispatcher) {
        vm.lockRoot = null // this case needs the real tree (group + grant root)
        vm.lockLabel = null
        app.contentResolver.takePersistableUriPermission(
            android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM"),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        vm.expandGroup("saf")
        advanceUntilIdle()

        val root = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == dirUri }
        vm.toggle(root) // expand the grant root, the highlight lands on it
        advanceUntilIdle()
        assertEquals(dirUri, vm.state.value.currentDir?.path)

        vm.up()
        advanceUntilIdle()

        assertEquals("g:saf", vm.state.value.currentKey)
    }
}
