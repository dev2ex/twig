package com.twig.app.ui

import android.app.Application
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.R
import com.twig.app.TwigApp
import com.twig.app.WebsiteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebsiteOrderTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        // AndroidX caches the Application across Robolectric test sandboxes.
        org.robolectric.util.ReflectionHelpers.setStaticField(
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        Dispatchers.setMain(dispatcher)
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        com.twig.app.Prefs.setRememberLocation(app, false)
        WebsiteStore.preferences(app).edit().clear().commit()
        val array = JSONArray()
        for (id in listOf("a", "b", "c")) array.put(JSONObject().apply {
            put("id", id); put("name", id); put("url", "https://$id.example")
        })
        WebsiteStore.importJson(app, array.toString())
        assertEquals(3, WebsiteStore.all(app).size)
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `reordering moves stored objects intact and keeps current edits additions and removals`() {
        val items = JSONArray(WebsiteStore.preferences(app).getString("list", null))
        val a = items.getJSONObject(0).apply {
            put("basicAuthPassword", "enc1:unchanged-ciphertext")
            put("headers", "enc1:unchanged-headers")
            put("name", "Renamed")
            put("unknownFutureOption", "preserve")
        }
        val c = items.getJSONObject(2)
        val d = JSONObject().apply { put("id", "d"); put("name", "d"); put("url", "https://d.example") }
        WebsiteStore.preferences(app).edit().putString("list", JSONArray().put(a).put(c).put(d).toString()).commit()
        WebsiteStore.reorder(app, listOf("c", "b", "a", "c"))
        val after = JSONArray(WebsiteStore.preferences(app).getString("list", null))
        assertEquals(listOf("c", "a", "d"), (0 until after.length()).map { after.getJSONObject(it).getString("id") })
        assertEquals(a.toString(), after.getJSONObject(1).toString())
        assertEquals(c.toString(), after.getJSONObject(0).toString())
        assertEquals(d.toString(), after.getJSONObject(2).toString())
    }

    @Test
    @Config(application = TwigApp::class)
    fun `website heading menu enters inline sorting drops save and outside taps exit`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val activity = controller.get()
            val pane = activity.supportFragmentManager.fragments.filterIsInstance<PaneFragment>().first()
            val list = pane.requireView().findViewById<RecyclerView>(R.id.list)
            val adapter = list.adapter as FileAdapter
            adapter.submitList(null)
            adapter.submitList(pane.viewModel.state.value.rows)
            val position = adapter.currentList.indexOfFirst { it.key == "g:websites" }
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            assertTrue(holder.itemView.performLongClick())
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val menu = dialog.listView
            val labels = (0 until menu.adapter.count).map { menu.adapter.getItem(it).toString() }
            assertTrue(labels.contains(activity.getString(R.string.website_defaults)))
            val sortIndex = labels.indexOf(activity.getString(R.string.action_sort_websites))
            assertTrue(sortIndex >= 0)
            menu.performItemClick(menu.adapter.getView(sortIndex, null, menu), sortIndex, sortIndex.toLong())
            assertTrue(pane.viewModel.sortingWebsites)
            val rows = pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.WebsiteNode>()
            assertEquals("Rows: ${pane.viewModel.state.value.rows.map { it.key }}; stored: ${WebsiteStore.all(app).map { it.id }}", 3, rows.size)
            assertTrue(rows.all { it.reordering })
            val keys = rows.map { it.key }
            assertFalse(pane.viewModel.moveSortedItem(keys[0], "g:fav", keys))
            assertTrue(pane.viewModel.moveSortedItem(keys[2], keys[0], keys))
            assertEquals(listOf("a", "b", "c"), WebsiteStore.all(app).map { it.id })
            pane.viewModel.saveItemOrder()
            assertEquals(listOf("c", "a", "b"), WebsiteStore.all(app).map { it.id })
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(0, 10, action, 0f, 0f, 0)
                activity.dispatchTouchEvent(event)
                event.recycle()
            }
            assertFalse(pane.viewModel.sortingItems)
            assertTrue(pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.WebsiteNode>().none { it.reordering })
        } finally { controller.stop().destroy() }
    }
}
