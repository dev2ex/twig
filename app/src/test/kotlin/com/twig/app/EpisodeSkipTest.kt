package com.twig.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EpisodeSkipTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        MediaPrefStore.clear(ctx)
        Prefs.setRememberMediaPrefs(ctx, true)
    }

    @Test fun `opening stays absolute while ending follows episode runtime`() {
        val marks = EpisodeSkip(90_000, 120_000)
        assertEquals(90_000L, marks.headEnd(1_800_000))
        assertEquals(90_000L, marks.headEnd(2_400_000))
        assertEquals(1_680_000L, marks.tailStart(1_800_000))
        assertEquals(2_280_000L, marks.tailStart(2_400_000))
    }

    @Test fun `short episodes and overlapping marks never skip the entire video`() {
        val marks = EpisodeSkip(90_000, 120_000)
        for (duration in listOf(-1L, 0L, 90_000L, 120_000L, 200_000L, 210_000L)) {
            assertEquals(0L, marks.headEnd(duration))
            assertNull(marks.tailStart(duration))
        }
    }

    @Test fun `each mark works independently and absent ending does not complete playback`() {
        assertEquals(90_000L, EpisodeSkip(headMs = 90_000).headEnd(1_800_000))
        assertNull(EpisodeSkip(headMs = 90_000).tailStart(1_800_000))
        assertEquals(1_680_000L, EpisodeSkip(tailMs = 120_000).tailStart(1_800_000))
        assertEquals(0L, EpisodeSkip(tailMs = 120_000).headEnd(1_800_000))
    }

    @Test fun `marks persist without track preference memory and do not leak to other shows`() {
        Prefs.setRememberMediaPrefs(ctx, false)
        val marks = EpisodeSkip(90_000, 120_000)
        MediaPrefStore.putSkip(ctx, "show-a", marks)
        assertEquals(marks, MediaPrefStore.skipFor(ctx, "show-a"))
        assertEquals(EpisodeSkip(), MediaPrefStore.skipFor(ctx, "show-b"))
    }

    @Test fun `clearing one mark preserves the other and clearing both removes history`() {
        MediaPrefStore.putSkip(ctx, "show", EpisodeSkip(90_000, 120_000))
        MediaPrefStore.putSkip(ctx, "show", MediaPrefStore.skipFor(ctx, "show").copy(headMs = 0))
        assertEquals(EpisodeSkip(tailMs = 120_000), MediaPrefStore.skipFor(ctx, "show"))
        MediaPrefStore.putSkip(ctx, "show", EpisodeSkip())
        assertEquals(0, MediaPrefStore.count(ctx))
    }

    @Test fun `history stays bounded while the latest edited show survives`() {
        repeat(205) { MediaPrefStore.putSkip(ctx, "show-$it", EpisodeSkip(headMs = 90_000)) }
        assertEquals(200, MediaPrefStore.count(ctx))
        assertEquals(EpisodeSkip(headMs = 90_000), MediaPrefStore.skipFor(ctx, "show-204"))
    }
    @Test fun `skip and track choices share one record and clearing either preserves the other`() {
        val key = MediaPrefStore.seriesKey("file|example")
        val track = TrackDesc(lang = "ja")
        MediaPrefStore.putAudio(ctx, "file-record", key, track)
        MediaPrefStore.putSkip(ctx, key, EpisodeSkip(90_000, 120_000))
        assertEquals(track, MediaPrefStore.all(ctx).first { it.key == key }.memo.audio)
        assertEquals(EpisodeSkip(90_000, 120_000), MediaPrefStore.all(ctx).first { it.key == key }.memo.skip)
        MediaPrefStore.putSkip(ctx, key, EpisodeSkip())
        assertEquals(track, MediaPrefStore.all(ctx).first { it.key == key }.memo.audio)
        MediaPrefStore.putSkip(ctx, key, EpisodeSkip(headMs = 90_000))
        MediaPrefStore.putAudio(ctx, "file-record", key, null)
        assertEquals(EpisodeSkip(headMs = 90_000), MediaPrefStore.skipFor(ctx, key))
        MediaPrefStore.clear(ctx)
        assertEquals(EpisodeSkip(), MediaPrefStore.skipFor(ctx, key))
    }
}
