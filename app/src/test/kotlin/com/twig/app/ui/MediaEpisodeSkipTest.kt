package com.twig.app.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.twig.app.EpisodeSkip
import com.twig.app.PlaybackStore
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityMediaPlayerBinding
import com.twig.core.XFile
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class MediaEpisodeSkipTest {
    private val activity = Robolectric.buildActivity(MediaPlayerActivity::class.java).get()
    private val files = listOf(
        XFile("file", "/shows/Example.S01E01.mp4", false, size = 1000),
        XFile("file", "/shows/Example.S01E02.mp4", false, size = 2000),
    )
    private var position = 0L
    private var playing = true
    private val seeks = mutableListOf<Long>()
    private val fakePlayer = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java)) { _, method, args ->
        when (method.name) {
            "getDuration" -> 1_800_000L
            "getCurrentPosition" -> position
            "isPlaying", "isCurrentMediaItemSeekable" -> playing
            "seekTo" -> { position = args!![0] as Long; seeks.add(position); null }
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer

    private fun setup(index: Int = 0) {
        ReflectionHelpers.setField(activity, "file", files[index])
        ReflectionHelpers.setField(activity, "queue", files)
        ReflectionHelpers.setField(activity, "queueIndex", index)
        ReflectionHelpers.setField(activity, "player", fakePlayer)
        ReflectionHelpers.setField(activity, "prepared", true)
        ReflectionHelpers.setField(activity, "isVideo", true)
        ReflectionHelpers.setField(activity, "skipMarks", EpisodeSkip(90_000, 120_000))
    }

    private fun apply(): Boolean = ReflectionHelpers.callInstanceMethod(activity, "applyEpisodeSkip")

    @Test fun `opening skips once and deliberate rewind remains watchable`() {
        setup()
        assertFalse(apply())
        assertEquals(listOf(90_000L), seeks)
        position = 10_000
        assertFalse(apply())
        assertEquals(listOf(90_000L), seeks)
    }

    @Test fun `resume after opening does not rewind and scrubbing defers automatic skip`() {
        setup()
        position = 300_000
        apply()
        assertTrue(seeks.isEmpty())
        setup()
        ReflectionHelpers.setField(activity, "headApplied", false)
        ReflectionHelpers.setField(activity, "scrubbing", true)
        position = 0
        apply()
        assertTrue(seeks.isEmpty())
        ReflectionHelpers.setField(activity, "scrubbing", false)
        apply()
        assertEquals(listOf(90_000L), seeks)
    }

    @Test fun `last episode ends at tail mark and removes stale resume even after a recent seek`() {
        setup(index = 1)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Prefs.setResumePlayback(ctx, true)
        PlaybackStore.clear(ctx)
        PlaybackStore.save(ctx, files[1], 600_000, 1_800_000)
        assertEquals(600_000L, PlaybackStore.positionFor(ctx, files[1]))
        position = 1_680_000
        assertTrue(apply())
        assertTrue(activity.isFinishing)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "saveProgress",
            ReflectionHelpers.ClassParameter.from(com.twig.core.PlayState::class.java, com.twig.core.PlayState.STOP))
        assertEquals(0L, PlaybackStore.positionFor(ctx, files[1]))
        assertTrue(seeks.isEmpty()) // Completing must not fetch credits over the network.
    }

    @Test fun `narrow controls keep every playback button visible and rejoin on wide screens`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val themed = ContextThemeWrapper(ctx, R.style.Theme_Twig)
        val binding = ActivityMediaPlayerBinding.inflate(LayoutInflater.from(themed))
        ReflectionHelpers.setField(activity, "b", binding)
        listOf(binding.btnPrev, binding.btnNext, binding.btnSkipHead, binding.btnSkipTail)
            .forEach { it.visibility = View.VISIBLE }
        fun layout(width: Int) {
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, width, 640)
        }
        layout(360)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "arrangePlaybackButtons")
        layout(360)
        assertSame(binding.controls, binding.playbackButtons.parent)
        assertEquals(240, binding.playbackButtons.width)
        assertTrue(binding.playbackButtons.left >= 0)
        assertTrue(binding.playbackButtons.right <= binding.controls.width)
        layout(800)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "arrangePlaybackButtons")
        layout(800)
        assertSame(binding.actionButtons, binding.playbackButtons.parent)
        assertTrue(binding.btnOrientation.right <= binding.actionButtons.width)
    }
    @Test fun `click toggles marks and reports them in the centered playback hint`() {
        setup()
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val binding = ActivityMediaPlayerBinding.inflate(LayoutInflater.from(ContextThemeWrapper(ctx, R.style.Theme_Twig)))
        ReflectionHelpers.setField(activity, "b", binding)
        fun clickHead() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "markEpisodeSkip",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        // First click clears the existing mark, without changing the ending.
        clickHead()
        assertEquals(com.twig.app.EpisodeSkip(tailMs = 120_000), binding.seek.marks)
        assertEquals(View.VISIBLE, binding.tvGesture.visibility)
        position = 120_000
        clickHead()
        assertEquals(com.twig.app.EpisodeSkip(120_000, 120_000), binding.seek.marks)
        assertTrue(binding.tvGesture.text.contains("02:00"))
        assertFalse(binding.tvGesture.text.contains("long press"))
        clickHead()
        assertEquals(com.twig.app.EpisodeSkip(tailMs = 120_000), binding.seek.marks)
    }
    private fun markEnding(): ActivityMediaPlayerBinding {
        setup(index = 1)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val binding = ActivityMediaPlayerBinding.inflate(LayoutInflater.from(ContextThemeWrapper(ctx, R.style.Theme_Twig)))
        ReflectionHelpers.setField(activity, "b", binding)
        ReflectionHelpers.setField(activity, "skipMarks", EpisodeSkip(headMs = 90_000))
        position = 1_680_000
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "markEpisodeSkip",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        return binding
    }

    @Test fun `setting ending keeps playback in this episode until a later crossing`() {
        val binding = markEnding()
        assertEquals(EpisodeSkip(90_000, 120_000), binding.seek.marks)
        assertFalse(apply())
        position += 30_000
        assertFalse(apply())
        playing = false
        assertFalse(apply())
        playing = true
        assertFalse(apply())
        assertFalse(activity.isFinishing)
        assertTrue(seeks.isEmpty())
        // Seeking back into the body arms the ending for the next crossing.
        position = 1_600_000
        assertFalse(apply())
        position = 1_680_000
        assertTrue(apply())
        assertTrue(activity.isFinishing)
    }

    @Test fun `saved ending becomes active when loading another playback`() {
        markEnding()
        assertFalse(apply())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadSkipMarks")
        assertTrue(apply())
        assertTrue(activity.isFinishing)
    }
}
