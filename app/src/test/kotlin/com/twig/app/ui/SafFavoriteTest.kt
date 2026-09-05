package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Favorite
import com.twig.app.SafFileSystem
import com.twig.app.favoriteDisplayName
import com.twig.app.favoriteFullPath
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Favoriting a folder inside a document tree (SAF).
 *
 * A SAF grant is persistent (`takePersistableUriPermission`), so the document URI is a
 * location that survives a restart — the same reason the music queue accepts `saf`
 * tracks. What it is *not* is a path: everything here exists because the last segment
 * of `XFile.path` is `primary%3ADCIM%2FPhotos`, not a name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafFavoriteTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private val dispatcher = StandardTestDispatcher()

    /** A real-shaped document URI: the '/'s inside both document ids are %2F. */
    private val uri =
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM" +
            "/document/primary%3ADCIM%2FPhotos"

    private fun safDir() = XFile(SafFileSystem.SCHEME, uri, isDir = true, displayName = "Photos")

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a folder in a document tree can be favorited`() {
        val fav = vm.favoriteFrom(safDir())
        assertNotNull("a SAF folder must offer a favorite", fav)
        assertEquals("saf", fav!!.kind)
        assertEquals(uri, fav.path)
    }

    /** A file inside the tree is still not a favorite — favorites point at folders. */
    @Test
    fun `a file in a document tree is not favoritable`() {
        assertNull(vm.favoriteFrom(safDir().copy(isDir = false)))
    }

    /** The row is named after the folder; neither line may show the encoded URI. */
    @Test
    fun `the row shows the folder name and a readable path`() {
        val fav = vm.favoriteFrom(safDir())!!
        assertEquals("Photos", favoriteDisplayName(fav, null))
        val path = favoriteFullPath(fav, null)
        assertEquals("saf:primary:DCIM/Photos", path)
        assertFalse("no percent-encoding on the row: $path", '%' in path)
    }

    /** Non-ASCII folder names are percent-encoded as UTF-8 bytes and must decode back. */
    @Test
    fun `a non-ascii folder name decodes back`() {
        val cn = "content://com.android.externalstorage.documents/tree/primary%3A" +
            "/document/primary%3A%E7%85%A7%E7%89%87"
        val fav = vm.favoriteFrom(XFile(SafFileSystem.SCHEME, cn, isDir = true, displayName = "照片"))!!
        assertEquals("saf:primary:照片", favoriteFullPath(fav, null))
    }

    /** The stored name has to survive the JSON round trip, or the row decays on restart. */
    @Test
    fun `the stored name survives a save and reload`() {
        val fav = vm.favoriteFrom(safDir())!!
        val back = Favorite.fromJson(JSONObject(fav.toJson().toString()))
        assertEquals(fav, back)
        assertEquals("Photos", favoriteDisplayName(back, null))
    }

    /** Favorites written before this field existed still read back (and name as before). */
    @Test
    fun `a favorite saved by an older version still reads back`() {
        val old = JSONObject("""{"label":"docs","kind":"local","path":"/sdcard/docs"}""")
        val fav = Favorite.fromJson(old)
        assertEquals("", fav.pathName)
        assertEquals("docs", favoriteDisplayName(fav, null))
    }

    /**
     * Expanding it must hand back a target that still knows its name: that XFile is what
     * the long-press menu, the info card and a copy's destination name all read.
     */
    @Test
    fun `expanding it yields a target that still knows its name`() = runTest(dispatcher) {
        FsRegistry.register(
            FakeFileSystem(
                SafFileSystem.SCHEME,
                dirs = mapOf(uri to listOf("a.txt")),
                files = mapOf("$uri/a.txt" to "x"),
            ),
        )
        vm.addFavorite(vm.favoriteFrom(safDir())!!)
        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single()

        vm.toggleFavorite(node, password = null) { _, _ -> }
        advanceUntilIdle()

        assertEquals("Photos", vm.favoriteTarget(node)?.name)
        // the tree also carries the storage roots, so look for the child under this row
        assertEquals(
            listOf("a.txt"),
            vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .filter { it.depth == 2 }.map { it.file.name },
        )
    }
}
