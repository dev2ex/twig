package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Playlist
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaylistImportUiTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() {
        // Robolectric replaces Application between tests; AndroidX caches the previous one.
        org.robolectric.util.ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        app.getSharedPreferences("twig_playlists", 0).edit().clear().commit()
        PlaylistStore.clearTemporary()
        Dispatchers.setMain(dispatcher)
    }
    @After fun cleanup() {
        MusicEngine.shutdown()
        FsRegistry.unregister("file")
        Dispatchers.resetMain()
        org.robolectric.util.ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
    }
    private fun open(id: String) = Robolectric.buildActivity(PlaylistActivity::class.java,
        Intent(app, PlaylistActivity::class.java).putExtra("playlist_id", id)).setup()
    private fun pick(activity: PlaylistActivity, title: Int) = run {
        clickMenu(activity, title)
        shadowOf(activity).nextStartedActivityForResult
    }
    private fun clickMenu(activity: PlaylistActivity, title: Int) {
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu as MenuBuilder
        val item = (0 until menu.size()).map(menu::getItem).single { it.title == activity.getString(title) }
        assertTrue(menu.performItemAction(item, 0))
    }

    @Test fun `drawer import opens the selected list and launches its picker only once`() {
        val shown = PlaylistStore.create(app, "Shown")
        val selected = PlaylistStore.create(app, "Selected")
        val controller = open(shown.id)
        try {
            for (directory in listOf(false, true)) {
                val activity = controller.get()
                val drawer = ReflectionHelpers.getField<PlaylistDrawer>(activity, "plDrawer")
                ReflectionHelpers.callInstanceMethod<Unit>(drawer, "rowMenu",
                    ReflectionHelpers.ClassParameter.from(Playlist::class.java, selected))
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val label = activity.getString(if (directory) R.string.music_import_directory else R.string.music_import_playlist)
                val index = (0 until dialog.listView.adapter.count).single { dialog.listView.adapter.getItem(it).toString() == label }
                dialog.listView.performItemClick(null, index, index.toLong())
                val intent = shadowOf(activity).nextStartedActivity
                shadowOf(activity).nextStartedActivityForResult // consume the shadow's matching startActivity record
                assertEquals(PlaylistActivity::class.java.name, intent.component!!.className)
                assertEquals(selected.id, intent.getStringExtra("playlist_id"))
                val destination = Robolectric.buildActivity(PlaylistActivity::class.java, intent).setup()
                try {
                    val request = shadowOf(destination.get()).nextStartedActivityForResult
                    assertEquals(PickerActivity::class.java.name, request.intent.component!!.className)
                    assertEquals(directory, request.intent.getBooleanExtra("pick_dir", false))
                    if (directory) assertFalse(request.intent.getBooleanExtra("require_writable_dir", true))
                    destination.recreate()
                    assertNull(shadowOf(destination.get()).nextStartedActivityForResult)
                    assertTrue(destination.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
                } finally { destination.pause().stop().destroy() }
            }
            assertEquals(shown, PlaylistStore.get(app, shown.id))
            assertEquals(selected, PlaylistStore.get(app, selected.id))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `drawer importing into the open list reuses its existing picker`() {
        val list = PlaylistStore.create(app, "Current")
        val controller = open(list.id)
        try {
            val activity = controller.get()
            val drawer = ReflectionHelpers.getField<PlaylistDrawer>(activity, "plDrawer")
            ReflectionHelpers.callInstanceMethod<Unit>(drawer, "rowMenu",
                ReflectionHelpers.ClassParameter.from(Playlist::class.java, list))
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val label = activity.getString(R.string.music_import_directory)
            val index = (0 until dialog.listView.adapter.count).single { dialog.listView.adapter.getItem(it).toString() == label }
            dialog.listView.performItemClick(null, index, index.toLong())
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals(PickerActivity::class.java.name, request.intent.component!!.className)
            assertTrue(request.intent.getBooleanExtra("pick_dir", false))
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `toolbar export retains its destination playlist through recreation`() {
        val list = PlaylistStore.create(app, "Export")
        val controller = open(list.id)
        try {
            val request = pick(controller.get(), R.string.music_export)
            assertTrue(request.intent.getBooleanExtra("pick_dir", false))
            val pending = ViewModelProvider(controller.get())[PlaylistDrawer.ExportState::class.java]
            assertEquals(list.id, pending.id)
            controller.recreate()
            assertEquals(list.id, ViewModelProvider(controller.get())[PlaylistDrawer.ExportState::class.java].id)
            assertTrue(controller.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
            assertNull(pending.id)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `saving Now Playing updates the open list identity and rename action across recreation`() {
        val now = PlaylistStore.setNow(app, "Now", emptyList())
        val controller = open(now.id)
        try {
            val activity = controller.get()
            clickMenu(activity, R.string.music_save)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fun input(view: android.view.View): android.widget.EditText? {
                if (view is android.widget.EditText) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
                return null
            }
            input(dialog.window!!.decorView)!!.setText("Saved mix")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val saved = PlaylistStore.all(app).single { it.name == "Saved mix" }
            assertNotEquals(Playlist.NOW, saved.id)
            assertNull(PlaylistStore.get(app, Playlist.NOW))
            assertEquals(saved.id, activity.intent.getStringExtra("playlist_id"))
            assertEquals("Saved mix", activity.findViewById<android.widget.TextView>(R.id.plTitle).text.toString())
            controller.recreate()
            assertFalse(controller.get().isFinishing)
            clickMenu(controller.get(), R.string.music_rename)
            (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            pick(controller.get(), R.string.music_export)
            assertEquals(saved.id, ViewModelProvider(controller.get())[PlaylistDrawer.ExportState::class.java].id)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `favorites can export and resume but cannot rename`() {
        val favorites = PlaylistStore.fav(app)
        val controller = open(favorites.id)
        try {
            val activity = controller.get()
            val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
            val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }
            assertTrue(activity.getString(R.string.music_export) in titles)
            assertTrue(activity.getString(R.string.music_resume) in titles)
            assertFalse(activity.getString(R.string.music_rename) in titles)
            assertFalse(activity.getString(R.string.music_save) in titles)
            clickMenu(activity, R.string.music_resume)
            assertFalse(MusicEngine.loadingQueue)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `both import actions use Twig path pickers without preparing external shares`() {
        val list = PlaylistStore.create(app, "Empty")
        val controller = open(list.id)
        try {
            val activity = controller.get()
            val file = pick(activity, R.string.music_import_playlist).intent
            assertEquals(PickerActivity::class.java.name, file.component!!.className)
            assertTrue(file.getBooleanExtra("return_path", false))
            assertFalse(file.getBooleanExtra("pick_dir", false))
            shadowOf(activity).receiveResult(file, Activity.RESULT_CANCELED, null)
            val dir = pick(activity, R.string.music_import_directory).intent
            assertTrue(dir.getBooleanExtra("return_path", false))
            assertTrue(dir.getBooleanExtra("pick_dir", false))
            assertFalse(dir.hasExtra("start_scheme"))
            shadowOf(activity).receiveResult(dir, Activity.RESULT_CANCELED, null)
            assertEquals(list, PlaylistStore.get(app, list.id))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `directory picker result after recreation appends once to the original list`() = runTest(dispatcher) {
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override fun list(dir: XFile) = listOf(XFile("file", "/music/song.mp3", false, size = 100))
        })
        val list = PlaylistStore.create(app, "Empty")
        val controller = open(list.id)
        try {
            val picker = pick(controller.get(), R.string.music_import_directory)
            val model = ViewModelProvider(controller.get())[PlaylistImportModel::class.java]
            assertSame("The ViewModel factory must use this test's Application", app, model.getApplication<Application>())
            model.ioDispatcher = dispatcher
            controller.recreate()
            val activity = controller.get()
            assertSame(model, ViewModelProvider(activity)[PlaylistImportModel::class.java])
            assertTrue(activity.activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent()
                .putExtra(PickerActivity.EXTRA_PICKED_SCHEME, "file")
                .putExtra(PickerActivity.EXTRA_PICKED_PATH, "/music")
                .putExtra(PickerActivity.EXTRA_PICKED_NAME, "Music")))
            assertTrue("Picker callback must start the retained scan: ${model.state.value}", model.state.value!!.busy)
            advanceUntilIdle()
            assertEquals("state=${model.state.value}; toast=${org.robolectric.shadows.ShadowToast.getTextOfLatestToast()}",
                listOf("/music/song.mp3"), PlaylistStore.get(app, list.id)!!.tracks.map { it.path })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `mounted temporary playlist does not offer durable import actions`() {
        val temporary = PlaylistStore.setNow(app, "Archive", listOf(PlaylistTrack("temporary", "/song.mp3", sessionScheme = "zip")))
        assertEquals(Playlist.TEMP, temporary.id)
        val controller = open(temporary.id)
        try {
            val activity = controller.get()
            val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
            val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }
            assertFalse(activity.getString(R.string.music_import_playlist) in titles)
            assertFalse(activity.getString(R.string.music_import_directory) in titles)
        } finally { controller.pause().stop().destroy() }
    }
}
