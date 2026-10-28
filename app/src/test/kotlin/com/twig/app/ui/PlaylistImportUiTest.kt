package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.appcompat.view.menu.MenuBuilder
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
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu as MenuBuilder
        val item = (0 until menu.size()).map(menu::getItem).single { it.title == activity.getString(title) }
        assertTrue(menu.performItemAction(item, 0))
        shadowOf(activity).nextStartedActivityForResult
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
