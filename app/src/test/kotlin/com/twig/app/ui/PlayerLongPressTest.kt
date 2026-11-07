package com.twig.app.ui

import android.app.Application
import androidx.appcompat.app.AlertDialog
import org.robolectric.shadows.ShadowDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerLongPressTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ActivityController<MediaPlayerActivity>
    private val activity get() = controller.get()
    private var volume = 0.7f
    private var params = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    private var preparedParams: TrackSelectionParameters? = null
    private var handleAudioFocus = true
    private var readGate = CountDownLatch(0)
    private val readStarted = CountDownLatch(1)
    private val readFinished = CountDownLatch(1)
    private val sidecar = XFile("long-press-test", "/movie.en.srt", false, size = 40)
    private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
        when (method.name) {
            "prepare" -> { preparedParams = params; null }
            "setAudioAttributes" -> { handleAudioFocus = args!![1] as Boolean; null }
            "getVolume" -> volume
            "setVolume" -> { volume = args!![0] as Float; null }
            "getTrackSelectionParameters" -> params
            "setTrackSelectionParameters" -> { params = args!![0] as TrackSelectionParameters; null }
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer

    @Before fun setup() {
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = sidecar.scheme
            override fun list(dir: XFile) = emptyList<XFile>()
            override fun openInput(file: XFile): java.io.InputStream {
                readStarted.countDown()
                check(readGate.await(5, TimeUnit.SECONDS))
                return object : ByteArrayInputStream("1\n00:00:00,000 --> 00:00:05,000\nHello\n".toByteArray()) {
                    override fun close() { super.close(); readFinished.countDown() }
                }
            }
        })
        controller = Robolectric.buildActivity(MediaPlayerActivity::class.java,
            Intent(app, MediaPlayerActivity::class.java)
                .putExtra("scheme", sidecar.scheme).putExtra("path", "/movie.mp4").putExtra("name", "movie.mp4")
        ).create()
        ReflectionHelpers.setField(activity, "player", player)
        await { ReflectionHelpers.getField<Boolean>(activity, "extScanned") }
    }

    @After fun cleanup() {
        readGate.countDown()
        com.twig.app.Prefs.setStartVideosWithoutAudio(app, false)
        controller.destroy()
        FsRegistry.unregister(sidecar.scheme)
    }

    private fun press(id: Int) { assertTrue(activity.findViewById<View>(id).performLongClick()) }
    private fun choose(index: Int) = ReflectionHelpers.callInstanceMethod<Unit>(activity, "applySubChoice", from(Int::class.javaPrimitiveType!!, index))
    private fun choice() = ReflectionHelpers.getField<Int>(activity, "subChoice")
    private fun await(condition: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for subtitle work")
    }

    private fun audioTracks(): List<Tracks.Group> = listOf("en", "ja").mapIndexed { i, language ->
        Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType("audio/aac").setLanguage(language).build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(i == 1))
    }.also { ReflectionHelpers.setField(activity, "audioGroups", it) }

    private fun audioDialog(): AlertDialog {
        ReflectionHelpers.setField(activity, "prepared", true)
        activity.findViewById<View>(R.id.btn_audio_track).performClick()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test fun `videos start with audio disabled before preparation and manual unmute restores focus`() {
        assertFalse(com.twig.app.Prefs.startVideosWithoutAudio(app))
        com.twig.app.Prefs.setStartVideosWithoutAudio(app, true)
        val local = java.io.File.createTempFile("muted-video", ".mp4", app.cacheDir)
        try {
            ReflectionHelpers.setField(activity, "file", XFile("file", local.path, false))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadMemo")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadSource",
                from(ExoPlayer::class.java, player), from(android.view.SurfaceHolder::class.java, null),
                from(Boolean::class.javaPrimitiveType!!, false), from(Long::class.javaPrimitiveType!!, 0L))
            await { preparedParams != null }
            assertTrue(C.TRACK_TYPE_AUDIO in preparedParams!!.disabledTrackTypes)
            assertFalse(handleAudioFocus)
            audioTracks()
            press(R.id.btn_audio_track)
            assertFalse(C.TRACK_TYPE_AUDIO in params.disabledTrackTypes)
            assertTrue(handleAudioFocus)
            // Opening the next episode applies the startup policy again, without saving a user choice.
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadMemo")
            assertTrue(ReflectionHelpers.getField(activity, "audioDisabled"))
            assertFalse(ReflectionHelpers.getField(activity, "audioUserChosen"))
            ReflectionHelpers.setField(activity, "isVideo", false)
            ReflectionHelpers.setField(activity, "audioDisabled", false)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadMemo")
            assertFalse(ReflectionHelpers.getField(activity, "audioDisabled"))
        } finally {
            local.delete()
        }
    }

    @Test fun `note long press selects off and restores the same track with full icon opacity`() {
        val groups = audioTracks()
        press(R.id.btn_audio_track)
        assertTrue(C.TRACK_TYPE_AUDIO in params.disabledTrackTypes)
        assertEquals(0.7f, volume, 0f)
        assertEquals(1f, activity.findViewById<View>(R.id.btn_audio_track).alpha, 0f)
        audioDialog().let {
            assertEquals(activity.getString(R.string.player_subtitle_close), it.listView.adapter.getItem(0))
            assertEquals(0, it.listView.checkedItemPosition)
            it.dismiss()
        }
        press(R.id.btn_audio_track)
        assertFalse(C.TRACK_TYPE_AUDIO in params.disabledTrackTypes)
        assertTrue(params.overrides.containsKey(groups[1].mediaTrackGroup))
        assertEquals(0.7f, volume, 0f)
    }

    @Test fun `audio dialog off and track rows share long press state and offset indices`() {
        val groups = audioTracks()
        audioDialog().let {
            assertEquals(2, it.listView.checkedItemPosition)
            it.listView.performItemClick(null, 0, 0)
        }
        assertTrue(C.TRACK_TYPE_AUDIO in params.disabledTrackTypes)
        press(R.id.btn_audio_track)
        assertTrue(params.overrides.containsKey(groups[1].mediaTrackGroup))
        audioDialog().listView.performItemClick(null, 1, 1)
        assertFalse(C.TRACK_TYPE_AUDIO in params.disabledTrackTypes)
        assertTrue(params.overrides.containsKey(groups[0].mediaTrackGroup))
        // Track callbacks can arrive later; the explicit override is already the active choice.
        press(R.id.btn_audio_track)
        press(R.id.btn_audio_track)
        assertTrue(params.overrides.containsKey(groups[0].mediaTrackGroup))
    }

    @Test fun `subtitle long press restores same embedded track after sidecar discovery shifts indices`() {
        val group = Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType("application/x-subrip").build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        ReflectionHelpers.setField(activity, "textGroups", listOf(group))
        choose(1)
        press(R.id.btn_subtitle)
        assertEquals(0, choice())
        assertTrue(params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        ReflectionHelpers.setField(activity, "subFiles", listOf(sidecar))
        press(R.id.btn_subtitle)
        assertEquals(2, choice())
        assertFalse(params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertTrue(params.overrides.containsKey(group.mediaTrackGroup))
    }

    @Test fun `closing a loading external subtitle rejects its late result and can restore it`() {
        ReflectionHelpers.setField(activity, "subFiles", listOf(sidecar))
        readGate = CountDownLatch(1)
        choose(1)
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        press(R.id.btn_subtitle)
        readGate.countDown()
        assertTrue(readFinished.await(5, TimeUnit.SECONDS))
        // The invalidated load cannot turn subtitles back on, even after its UI callback runs.
        await { ReflectionHelpers.getField<List<*>>(activity, "cues").isEmpty() && choice() == 0 }
        press(R.id.btn_subtitle)
        await { ReflectionHelpers.getField<List<*>>(activity, "cues").isNotEmpty() }
        assertEquals(1, choice())
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "subEnabled"))
    }

    @Test fun `long pressing subtitles with no prior active selection leaves them off`() {
        choose(0)
        press(R.id.btn_subtitle)
        assertEquals(0, choice())
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "subEnabled"))
    }
}
