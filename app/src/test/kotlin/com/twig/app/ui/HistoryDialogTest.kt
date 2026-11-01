package com.twig.app.ui

import android.app.Application
import android.view.View
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.twig.app.HistoryEntry
import com.twig.app.HistoryStore
import com.twig.app.PlaybackStore
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HistoryDialogTest {
    class Host : AppCompatActivity() {
        override fun onCreate(state: android.os.Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(state)
        }
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val location = HistoryEntry("dir", "/movies")
    private val video = XFile("file", "/movies/movie.mp4", false, size = 123456L)

    @Before fun setup() {
        HistoryStore.clear(app)
        PlaybackStore.clear(app)
        Prefs.setResumePlayback(app, true)
        HistoryStore.add(app, location)
        PlaybackStore.save(app, video, 30000L, 180000L)
    }

    @After fun cleanup() {
        HistoryStore.clear(app)
        PlaybackStore.clear(app)
    }

    @Test fun `continue watching shortcut opens videos immediately`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val dialog = HistoryDialog.show(host.get(), {}, { _, _ -> }, continueWatching = true)
        try {
            assertEquals(View.VISIBLE, dialog.findViewById<ListView>(R.id.videos_list)!!.visibility)
            assertEquals(View.GONE, dialog.findViewById<ListView>(R.id.locations_list)!!.visibility)
        } finally { dialog.dismiss(); host.pause().stop().destroy() }
    }

    @Test fun `many unfinished videos cannot push recent locations out of the default page`() {
        repeat(80) { PlaybackStore.save(app, video.copy(path = "/movies/$it.mp4"), 30000L, 180000L) }
        val host = Robolectric.buildActivity(Host::class.java).setup()
        var chosen: HistoryEntry? = null
        val dialog = HistoryDialog.show(host.get(), { chosen = it }, { _, _ -> fail("Wrong page") })
        try {
            val locations = dialog.findViewById<ListView>(R.id.locations_list)!!
            val videos = dialog.findViewById<ListView>(R.id.videos_list)!!
            assertEquals(1, locations.count)
            assertEquals(81, videos.count)
            assertEquals(View.VISIBLE, locations.visibility)
            assertEquals(View.GONE, videos.visibility)
            dialog.findViewById<View>(R.id.videos_tab)!!.performClick()
            assertEquals(View.GONE, locations.visibility)
            assertEquals(View.VISIBLE, videos.visibility)
            dialog.findViewById<View>(R.id.locations_tab)!!.performClick()
            locations.performItemClick(locations.adapter.getView(0, null, locations), 0, 0)
            assertEquals(location, chosen)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
            host.pause().stop().destroy()
        }
    }

    @Test fun `clearing each tab affects only that list and preserves video resume`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val dialog = HistoryDialog.show(host.get(), {}, { _, _ -> })
        try {
            val clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            clear.performClick()
            assertTrue(HistoryStore.all(app).isEmpty())
            assertEquals(1, PlaybackStore.recent(app).size)
            assertTrue(dialog.isShowing)
            assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.history_empty)!!.visibility)
            HistoryStore.add(app, location)
            dialog.findViewById<View>(R.id.videos_tab)!!.performClick()
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.history_empty)!!.visibility)
            clear.performClick()
            assertTrue(PlaybackStore.recent(app).isEmpty())
            assertEquals(listOf(location), HistoryStore.all(app))
            assertEquals(30000L, PlaybackStore.positionFor(app, video))
            assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.history_empty)!!.visibility)
        } finally {
            dialog.dismiss()
            host.pause().stop().destroy()
        }
    }

    @Test fun `video tab opens the selected video for resuming`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        var selectedPath: String? = null
        var fromStart = true
        val dialog = HistoryDialog.show(host.get(), { fail("Wrong page") }, { mark, start ->
            selectedPath = mark.path
            fromStart = start
        })
        try {
            dialog.findViewById<View>(R.id.videos_tab)!!.performClick()
            val videos = dialog.findViewById<ListView>(R.id.videos_list)!!
            videos.performItemClick(videos.adapter.getView(0, null, videos), 0, 0)
            assertEquals(video.path, selectedPath)
            assertFalse(fromStart)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
            host.pause().stop().destroy()
        }
    }

    @Test fun `long press can start from the beginning or locate the containing folder`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        try {
            for (choice in 0..1) {
                var selectedLocation: HistoryEntry? = null
                var selectedPath: String? = null
                var fromStart = false
                val dialog = HistoryDialog.show(host.get(), { selectedLocation = it }, { mark, start ->
                    selectedPath = mark.path
                    fromStart = start
                })
                try {
                    dialog.findViewById<View>(R.id.videos_tab)!!.performClick()
                    val videos = dialog.findViewById<ListView>(R.id.videos_list)!!
                    videos.onItemLongClickListener!!.onItemLongClick(videos,
                        videos.adapter.getView(0, null, videos), 0, 0)
                    val menu = ShadowDialog.getLatestDialog() as AlertDialog
                    menu.listView.performItemClick(menu.listView.adapter.getView(choice, null, menu.listView), choice, choice.toLong())
                    if (choice == 0) {
                        assertEquals(video.path, selectedPath)
                        assertTrue(fromStart)
                        assertNull(selectedLocation)
                    } else {
                        assertEquals(location, selectedLocation)
                        assertNull(selectedPath)
                    }
                    assertFalse(dialog.isShowing)
                } finally {
                    dialog.dismiss()
                }
            }
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun `removing a video keeps the video tab open and preserves progress and locations`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val dialog = HistoryDialog.show(host.get(), { fail("Must not navigate") }, { _, _ -> fail("Must not play") })
        try {
            dialog.findViewById<View>(R.id.videos_tab)!!.performClick()
            val videos = dialog.findViewById<ListView>(R.id.videos_list)!!
            videos.onItemLongClickListener!!.onItemLongClick(videos,
                videos.adapter.getView(0, null, videos), 0, 0)
            val menu = ShadowDialog.getLatestDialog() as AlertDialog
            menu.listView.performItemClick(menu.listView.adapter.getView(2, null, menu.listView), 2, 2)
            assertTrue(dialog.isShowing)
            assertTrue(dialog.findViewById<View>(R.id.videos_tab)!!.isSelected)
            assertEquals(0, videos.count)
            assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.history_empty)!!.visibility)
            assertEquals(30000L, PlaybackStore.positionFor(app, video))
            assertEquals(listOf(location), HistoryStore.all(app))
        } finally {
            dialog.dismiss()
            host.pause().stop().destroy()
        }
    }
}
