package com.twig.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.MainActivity
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.MediaDetails
import com.twig.core.MediaInfoSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaylistMenuLoadingTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun setup() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        app.getSharedPreferences("twig_playlists", 0).edit().clear().commit()
        app.getSharedPreferences(ConnectionStore.FILE, 0).edit().clear().commit()
    }
    @After fun cleanup() {
        MusicEngine.shutdown()
        FsRegistry.unregister("info-test")
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
    }

    /** Keep the normal background queue busy, leaving the selected row unresolved. */
    private fun blockedList(block: (PlaylistActivity, PlaylistTrack) -> Unit) {
        val song = File.createTempFile("late-song", ".mp3", app.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        val track = PlaylistTrack("local", song.path, size = song.length(), title = "Song", durationMs = 1000, sampleRate = 44100)
        val list = PlaylistStore.create(app, "Mix", listOf(track))
        val controller = Robolectric.buildActivity(PlaylistActivity::class.java,
            Intent(app, PlaylistActivity::class.java).putExtra("playlist_id", list.id))
        val activity = controller.get()
        val io = ReflectionHelpers.getField<ExecutorService>(activity, "io")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        io.execute {
            started.countDown()
            try { release.await(15, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            controller.setup()
            assertTrue(ReflectionHelpers.getField<Map<String, XFile>>(activity, "files").isEmpty())
            block(activity, track)
            assertEquals("Actions must not release or await the background queue", 1L, release.count)
        } finally {
            io.shutdownNow()
            release.countDown()
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
            song.delete()
        }
    }
    private fun menu(activity: PlaylistActivity, track: PlaylistTrack): AlertDialog {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "trackMenu",
            ReflectionHelpers.ClassParameter.from(PlaylistTrack::class.java, track))
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun click(activity: PlaylistActivity, track: PlaylistTrack, label: Int) {
        val menu = menu(activity, track)
        val index = (0 until menu.listView.adapter.count).single {
            menu.listView.adapter.getItem(it).toString() == activity.getString(label)
        }
        menu.listView.performItemClick(null, index, index.toLong())
    }
    private fun awaitUi(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("UI action did not complete independently of the metadata queue")
    }

    @Test fun `track info opens immediately while background rows remain unresolved`() = blockedList { activity, track ->
        click(activity, track, R.string.music_info)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)!!
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.info_loading), message.text.toString())
        awaitUi { message.text.toString().contains(track.name) }
    }

    @Test fun `share works for an unresolved row without waiting for background metadata`() = blockedList { activity, track ->
        click(activity, track, R.string.music_share)
        awaitUi { shadowOf(activity).peekNextStartedActivity() != null }
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity.action)
    }

    @Test fun `locate is present and works before the selected row is resolved`() = blockedList { activity, track ->
        click(activity, track, R.string.music_locate)
        awaitUi { shadowOf(activity).peekNextStartedActivity() != null }
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun `deleted source reports failure instead of silently ignoring share`() = blockedList { activity, _ ->
        val track = PlaylistTrack("conn", "/song.mp3", connLabel = "deleted")
        click(activity, track, R.string.music_share)
        awaitUi { ShadowToast.getTextOfLatestToast() == activity.getString(R.string.music_export_source_missing, track.name) }
        assertNull(shadowOf(activity).peekNextStartedActivity())
    }

    @Test fun `saved source controls locate availability without establishing a connection`() {
        val smb = SavedConnection("smb", "unresolved-menu.test")
        ConnectionStore.save(app, smb)
        assertTrue(MusicDialogs.canLocate(app, PlaylistTrack("conn", "/song.mp3", smb.label())))
        for (type in listOf("jellyfin", "emby")) {
            val media = SavedConnection(type, "unresolved-$type.test")
            ConnectionStore.save(app, media)
            assertFalse(MusicDialogs.canLocate(app, PlaylistTrack("conn", "/lib/song", media.label())))
        }
        assertFalse(MusicDialogs.canLocate(app, PlaylistTrack("saf", "content://docs/42")))
        assertFalse(MusicDialogs.canLocate(app, PlaylistTrack("temporary", "/song.mp3", sessionScheme = "zip")))
    }

    @Test fun `dismissed info dialog stays closed when a slow read finishes`() = blockedList { activity, _ ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        FsRegistry.register(object : FileSystem by LocalFileSystem(), MediaInfoSource {
            override val scheme = "info-test"
            override fun detailsOf(file: XFile): MediaDetails {
                worker.set(Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return MediaDetails(title = "Slow song", durationMs = 1000)
            }
        })
        MusicDialogs.showInfo(activity, XFile("info-test", "/slow.mp3", false))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            dialog.dismiss()
        } finally { release.countDown() }
        worker.get().join(5000)
        assertFalse(worker.get().isAlive)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertSame(dialog, ShadowDialog.getLatestDialog())
    }
}
