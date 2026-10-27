package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.PlaybackMark
import com.twig.app.PlaybackStore
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryPlayerLoadingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val conn = SavedConnection("webdav", "https://history-loading.invalid")
    private val scheme = Connections.schemeOf(conn)
    private val video = XFile(scheme, "/videos/example.mp4", false, size = 123456, displayName = "Example.mp4")
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var result: XFile? = video
    private var statOnMain = false
    private val host = Robolectric.buildActivity(Activity::class.java)
    private var controller: ActivityController<MediaPlayerActivity>? = null
    private val player get() = controller!!.get()

    @Before fun setup() {
        host.setup()
        ConnectionStore.save(app, conn)
        PlaybackStore.clear(app)
        Prefs.setResumePlayback(app, true)
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override val scheme = this@HistoryPlayerLoadingTest.scheme
            override fun stat(path: String): XFile? {
                statOnMain = Looper.myLooper() == Looper.getMainLooper()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Metadata test timed out" }
                return result
            }
        })
        Connections.ensure(app, conn)
        PlaybackStore.save(app, video, 30_000, 180_000)
    }

    @After fun cleanup() {
        release.countDown()
        controller?.destroy()
        host.pause().stop().destroy()
        Connections.drop(scheme)
        ConnectionStore.remove(app, conn)
        PlaybackStore.clear(app)
    }

    private fun open(fromStart: Boolean = false, mark: PlaybackMark = PlaybackStore.recent(app).single()) {
        MediaPlayerActivity.startRecent(host.get(), mark, fromStart)
        val intent = shadowOf(host.get()).nextStartedActivity
        assertEquals(MediaPlayerActivity::class.java.name, intent.component!!.className)
        // Launching the activity must not connect or stat first.
        assertEquals(1L, entered.count)
        controller = Robolectric.buildActivity(MediaPlayerActivity::class.java, intent).create()
    }

    private fun awaitUi(condition: () -> Boolean) = runBlocking {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Player UI did not finish loading" }
            shadowOf(Looper.getMainLooper()).idle()
            delay(5)
        }
    }

    @Test fun `player shows title and spinner while server metadata is blocked then restores real progress`() {
        open()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(statOnMain)
        assertEquals("Example.mp4", player.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title)
        assertEquals(View.VISIBLE, player.findViewById<View>(R.id.loading).visibility)
        assertFalse(ReflectionHelpers.getField<Boolean>(player, "resumeReady"))
        assertFalse(player.findViewById<View>(R.id.btn_subtitle).isEnabled)
        assertFalse(player.findViewById<View>(R.id.btn_scale).isEnabled)
        assertNull(ReflectionHelpers.getField<Any?>(player, "player"))
        release.countDown()
        awaitUi { ReflectionHelpers.getField<Boolean>(player, "resumeReady") }
        assertTrue(player.findViewById<View>(R.id.btn_subtitle).isEnabled)
        assertTrue(player.findViewById<View>(R.id.btn_scale).isEnabled)
        assertEquals(video, ReflectionHelpers.getField<XFile>(player, "file"))
        assertEquals(30_000L, ReflectionHelpers.getField<Long>(player, "resumeFrom"))
    }

    @Test fun `from start still opens immediately and does not restore saved progress`() {
        open(fromStart = true)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        awaitUi { ReflectionHelpers.getField<Boolean>(player, "resumeReady") }
        assertEquals(0L, ReflectionHelpers.getField<Long>(player, "resumeFrom"))
    }

    @Test fun `leaving during metadata loading cannot start playback later`() {
        open()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        controller!!.destroy()
        val closed = player
        controller = null
        release.countDown()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(closed.isDestroyed)
        assertNull(ReflectionHelpers.getField<Any?>(closed, "player"))
        assertFalse(ReflectionHelpers.getField<Boolean>(closed, "resumeReady"))
    }

    @Test fun `missing file is reported inside the already opened player`() {
        result = null
        open()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        awaitUi { player.findViewById<View>(R.id.loading).visibility == View.GONE }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(app.getString(R.string.history_video_missing),
            dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
        assertNull(ReflectionHelpers.getField<Any?>(player, "player"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(player.isFinishing)
    }

    @Test fun `deleted connection opens player before reporting failure without stat`() {
        val mark = PlaybackStore.recent(app).single().copy(connLabel = "deleted-server")
        open(mark = mark)
        awaitUi { player.findViewById<View>(R.id.loading).visibility == View.GONE }
        assertEquals(1L, entered.count)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(app.getString(R.string.history_conn_missing),
            dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
    }
}
