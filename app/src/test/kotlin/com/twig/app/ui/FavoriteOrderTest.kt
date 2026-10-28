package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Favorite
import com.twig.app.FavoritesStore
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FavoriteOrderTest {
    private val dispatcher = StandardTestDispatcher()
    @After fun cleanup() {
        Dispatchers.resetMain()
        FsRegistry.unregister("file")
    }
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val favorites = listOf("a", "b", "c").map { Favorite(it, "local", "/$it") }

    @Before fun setup() {
        app.getSharedPreferences("twig_favorites", 0).edit().clear().commit()
        favorites.forEach { FavoritesStore.add(app, it) }
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        com.twig.app.Prefs.setRememberLocation(app, false)
        Dispatchers.setMain(dispatcher)
        FsRegistry.register(LocalFileSystem())
    }

    @Test fun `reorder preserves current edits excludes removals and appends new favorites`() {
        FavoritesStore.rename(app, favorites[0], "Renamed")
        FavoritesStore.remove(app, favorites[1])
        val added = Favorite("d", "local", "/d")
        FavoritesStore.add(app, added)
        FavoritesStore.reorder(app, listOf(favorites[2].id, favorites[1].id, favorites[0].id, favorites[2].id))
        assertEquals(listOf(favorites[2].id, favorites[0].id, added.id), FavoritesStore.all(app).map { it.id })
        assertEquals("Renamed", FavoritesStore.all(app)[1].customLabel)
    }

    @Test
    @Config(application = com.twig.app.TwigApp::class)
    fun `Favorites menu enters sorting and outside taps exit while scrolling keeps it active`() {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val controller = org.robolectric.Robolectric.buildActivity(com.twig.app.MainActivity::class.java).create().start()
        try {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val pane = controller.get().supportFragmentManager.fragments.filterIsInstance<PaneFragment>().first()
            val list = pane.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(com.twig.app.R.id.list)
            val adapter = list.adapter as FileAdapter
            fun openMenu(): androidx.appcompat.app.AlertDialog {
                adapter.submitList(null)
                adapter.submitList(pane.viewModel.state.value.rows)
                val position = adapter.currentList.indexOfFirst { it is PaneViewModel.GroupNode && it.id == "fav" }
                assertTrue(position >= 0)
                val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
                adapter.bindViewHolder(holder, position)
                assertTrue("The actual Favorites heading must dispatch long presses", holder.itemView.performLongClick())
                return org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            }
            fun choose(dialog: androidx.appcompat.app.AlertDialog, label: Int) {
                val menu = dialog.listView
                assertEquals(controller.get().getString(label), menu.adapter.getItem(0).toString())
                menu.performItemClick(menu.adapter.getView(0, null, menu), 0, 0)
            }
            choose(openMenu(), com.twig.app.R.string.action_sort_favorites)
            assertTrue(pane.viewModel.sortingFavorites)
            val activity = controller.get()
            adapter.submitList(null)
            adapter.submitList(pane.viewModel.state.value.rows)
            val decor = activity.window.decorView
            decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 1920)
            val favoritePosition = adapter.currentList.indexOfFirst { it is PaneViewModel.FavoriteNode }
            val handle = list.findViewHolderForAdapterPosition(favoritePosition)!!.itemView
                .findViewById<android.view.View>(com.twig.app.R.id.indicator)
            val handleBounds = android.graphics.Rect()
            assertTrue(handle.getGlobalVisibleRect(handleBounds))
            val handleX = handleBounds.exactCenterX()
            val handleY = handleBounds.exactCenterY()
            assertTrue(pane.isFavoriteDragHandleAt(handleX, handleY))
            val handleTouch = FavoriteSortTouch()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(0, 10, action, handleX, handleY, 0)
                handleTouch.dispatch(activity, event) { false }
                event.recycle()
            }
            assertTrue("A handle tap must keep sorting active", pane.viewModel.sortingFavorites)
            fun touch(action: Int, x: Float, y: Float) {
                val event = android.view.MotionEvent.obtain(0, 10, action, x, y, 0)
                activity.dispatchTouchEvent(event)
                event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, 0f, 0f)
            touch(android.view.MotionEvent.ACTION_MOVE, 0f, 200f)
            touch(android.view.MotionEvent.ACTION_UP, 0f, 200f)
            assertTrue("Scrolling must leave sorting active", pane.viewModel.sortingFavorites)
            // Fast scrolling may deliver UP without an intervening MOVE.
            touch(android.view.MotionEvent.ACTION_DOWN, 0f, 0f)
            touch(android.view.MotionEvent.ACTION_UP, 0f, 200f)
            assertTrue(pane.viewModel.sortingFavorites)
            val menuWhileSorting = openMenu()
            assertEquals(1, menuWhileSorting.listView.adapter.count)
            assertEquals(activity.getString(com.twig.app.R.string.action_sort_favorites),
                menuWhileSorting.listView.adapter.getItem(0).toString())
            menuWhileSorting.dismiss()
            touch(android.view.MotionEvent.ACTION_DOWN, 0f, 0f)
            touch(android.view.MotionEvent.ACTION_UP, 0f, 0f)
            assertFalse(pane.viewModel.sortingFavorites)
            pane.viewModel.setSortingFavorites(true)
            assertTrue(pane.handleBack())
            assertFalse(pane.viewModel.sortingFavorites)
        } finally { controller.stop().destroy() }
    }

    @Test fun `sorting row replaces chevron with handle and recycling restores normal clicks`() {
        val context = android.view.ContextThemeWrapper(app, com.twig.app.R.style.Theme_Twig)
        val parent = android.widget.FrameLayout(context)
        var clicked = 0
        var dragged = 0
        val adapter = FileAdapter(1, { clicked++ }, {}, {}, { _, _ -> }, {})
        adapter.onFavoriteDrag = { dragged++ }
        val node = PaneViewModel.FavoriteNode(favorites[0], null, 1, false, false, reordering = true)
        adapter.submitList(listOf(node))
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val indicator = holder.itemView.findViewById<android.widget.ImageView>(com.twig.app.R.id.indicator)
        assertEquals(com.twig.app.R.drawable.ic_drag_handle,
            org.robolectric.Shadows.shadowOf(indicator.drawable).createdFromResId)
        holder.itemView.performClick()
        assertEquals(0, clicked)
        fun touch() {
            val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 2f, 2f, 0)
            indicator.dispatchTouchEvent(event)
            event.recycle()
        }
        touch()
        assertEquals(1, dragged)
        adapter.submitList(null)
        adapter.submitList(listOf(node.copy(reordering = false)))
        adapter.bindViewHolder(holder, 0)
        assertEquals(com.twig.app.R.drawable.ic_chevron_right,
            org.robolectric.Shadows.shadowOf(indicator.drawable).createdFromResId)
        assertNull(indicator.contentDescription)
        touch()
        assertEquals(1, dragged)
        holder.itemView.performClick()
        assertEquals(1, clicked)
    }

    @Test fun `inline sorting hides children restores expansion and saves on drop`() = runTest(dispatcher) {
        val dir = java.io.File(app.cacheDir, "favorite-sort").apply { mkdirs() }
        val child = java.io.File(dir, "child.txt").apply { writeText("child") }
        val favorite = Favorite("folder", "local", dir.path)
        FavoritesStore.add(app, favorite)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap()
        advanceUntilIdle()
        val row = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().first { it.fav.id == favorite.id }
        vm.toggleFavorite(row, null) { ok, error -> assertTrue(error, ok) }
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.any { it is PaneViewModel.FileNode && it.file.path == child.path })
        val savedIds = FavoritesStore.all(app).map { it.id }
        vm.setSortingFavorites(true)
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().all { it.reordering && !it.expanded })
        assertFalse(vm.state.value.rows.any { it is PaneViewModel.FileNode && it.file.path == child.path })
        assertTrue(vm.moveFavorite(favorite.id, favorites[0].id, savedIds))
        assertEquals(savedIds, FavoritesStore.all(app).map { it.id })
        assertFalse("Ignore callbacks from an adapter still displaying the preceding order",
            vm.moveFavorite(favorite.id, favorites[1].id, savedIds))
        vm.saveFavoriteOrder()
        assertEquals(favorite.id, FavoritesStore.all(app).first().id)
        val sibling = PaneViewModel(app).apply { io = dispatcher; paneIndex = 1 }
        sibling.bootstrap()
        assertEquals(favorite.id, sibling.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().first().fav.id)
        vm.setSortingFavorites(false)
        assertFalse(vm.sortingFavorites)
        assertTrue(vm.state.value.rows.any { it is PaneViewModel.FileNode && it.file.path == child.path })
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().none { it.reordering })
        dir.deleteRecursively()
    }
}
