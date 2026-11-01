package com.twig.app.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Playlist
import com.twig.app.PlaylistTrack
import com.twig.app.PlaylistStore
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistDrawerRebuildTest {
    class Host : AppCompatActivity() {
        override fun onCreate(state: android.os.Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(state)
        }
    }

    @Test fun `drawer can rebuild after resume while retaining its pending export`() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        fun drawer() = PlaylistDrawer(activity, DrawerLayout(activity), RecyclerView(activity), View(activity))
        drawer()
        val state = ViewModelProvider(activity)[PlaylistDrawer.ExportState::class.java]
        state.id = "pending-export"
        drawer()
        assertEquals("pending-export", state.id)
        controller.pause().stop().destroy()
    }

    @Test fun `temporary queue menu offers resume without save or export`() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        val drawer = PlaylistDrawer(activity, DrawerLayout(activity), RecyclerView(activity), View(activity))
        val playlist = Playlist(Playlist.TEMP, "Archive", listOf(PlaylistTrack("temporary", "/archive!/song.mp3")))
        PlaylistDrawer::class.java.getDeclaredMethod("rowMenu", Playlist::class.java).apply { isAccessible = true }
            .invoke(drawer, playlist)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val options = dialog.listView.adapter.let { adapter -> (0 until adapter.count).map { adapter.getItem(it).toString() } }
        assertEquals(listOf(activity.getString(R.string.music_resume)), options)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun `player drawer import actions target the long pressed playlist`() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        val drawer = PlaylistDrawer(activity, DrawerLayout(activity), RecyclerView(activity), View(activity))
        val playlist = PlaylistStore.create(activity, "Selected")
        try {
            for (directory in listOf(false, true)) {
                PlaylistDrawer::class.java.getDeclaredMethod("rowMenu", Playlist::class.java).apply { isAccessible = true }
                    .invoke(drawer, playlist)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val label = activity.getString(if (directory) R.string.music_import_directory else R.string.music_import_playlist)
                val index = (0 until dialog.listView.adapter.count).single {
                    dialog.listView.adapter.getItem(it).toString() == label
                }
                dialog.listView.performItemClick(null, index, index.toLong())
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(PlaylistActivity::class.java.name, intent.component!!.className)
                assertEquals(playlist.id, intent.getStringExtra("playlist_id"))
                assertEquals(directory, intent.getBooleanExtra("playlist_import_directory", !directory))
            }
        } finally { controller.pause().stop().destroy() }
    }
}
