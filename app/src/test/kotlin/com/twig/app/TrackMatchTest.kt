package com.twig.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching a remembered audio track / subtitle onto the tracks another episode actually has.
 *
 * The cases are written around the two ways this feature can fail the user, which are not
 * symmetric: **selecting the wrong track is much worse than selecting nothing**. A missed
 * match costs one tap in a dialog the user already knows; a wrong match plays an episode in
 * a language they didn't ask for and looks like the player deciding on its own.
 */
class TrackMatchTest {

    private fun audio(lang: String? = null, label: String? = null, mime: String? = "audio/eac3", ch: Int = 6, index: Int = -1) =
        TrackDesc(lang = lang, label = label, mime = mime, channels = ch, index = index)

    /** The reason indices aren't stored: episode 3 has an extra commentary track up front. */
    @Test
    fun `picks by language even when the track order differs between episodes`() {
        val want = audio(lang = "ja")
        val ep3 = listOf(audio(lang = "en"), audio(lang = "ja"), audio(lang = "zh"))
        assertEquals(1, TrackMatch.pick(want, ep3))
    }

    @Test
    fun `iso 639-2 three letter codes match their two letter form`() {
        assertEquals(1, TrackMatch.pick(audio(lang = "jpn"), listOf(audio(lang = "en"), audio(lang = "ja"))))
        assertEquals(0, TrackMatch.pick(audio(lang = "zh"), listOf(audio(lang = "chi"), audio(lang = "eng"))))
    }

    /** ★ Simplified vs traditional is a distinction people care about: an exact hit must win. */
    @Test
    fun `exact script beats the same primary language with a different script`() {
        val want = TrackDesc(lang = "zh-Hans", mime = "text/vtt")
        val cands = listOf(TrackDesc(lang = "zh-Hant", mime = "text/vtt"), TrackDesc(lang = "zh-Hans", mime = "text/vtt"))
        assertEquals(1, TrackMatch.pick(want, cands))
        // …but a lone traditional track is still a better answer than another language
        assertEquals(0, TrackMatch.pick(want, listOf(TrackDesc(lang = "zh-Hant", mime = "text/vtt"), TrackDesc(lang = "en"))))
    }

    /** The episode simply has no Japanese audio — leave the player's own choice alone. */
    @Test
    fun `refuses to match when the remembered language is absent`() {
        assertEquals(-1, TrackMatch.pick(audio(lang = "ja"), listOf(audio(lang = "en"), audio(lang = "de"))))
    }

    /**
     * Codec and channel count agreeing is not evidence of anything: every track in a release
     * tends to be 5.1 E-AC-3. Without a language or label hit, a cross-episode memory must not
     * select anything.
     */
    @Test
    fun `codec and channels alone are never enough across episodes`() {
        val want = audio(lang = null, label = null, mime = "audio/eac3", ch = 6)
        val cands = listOf(audio(lang = null, label = null, mime = "audio/eac3", ch = 6))
        assertTrue(TrackMatch.score(want, cands[0]) < TrackMatch.MIN_SCORE)
        assertEquals(-1, TrackMatch.pick(want, cands))
    }

    /**
     * Same file, though (a per-file memory keeps its index — see `TrackPrefStore.memoFor`),
     * the layout cannot have moved, so the index is allowed to carry the match for tracks that
     * declare no language at all.
     */
    @Test
    fun `within one file the index can carry a match`() {
        val want = audio(lang = null, label = null, index = 1)
        val cands = listOf(audio(lang = null, label = null, index = 0), audio(lang = null, label = null, index = 1))
        assertEquals(1, TrackMatch.pick(want, cands))
    }

    @Test
    fun `labels match when the language is missing`() {
        val want = audio(lang = null, label = "Director's Commentary")
        val cands = listOf(audio(lang = null, label = "Main"), audio(lang = null, label = "Director's Commentary"))
        assertEquals(1, TrackMatch.pick(want, cands))
    }

    // ---- External subtitle files ----

    private val ep2 = "Show.S01E02.1080p.mkv"
    private val ep3 = "Show.S01E03.1080p.mkv"

    @Test
    fun `sidecar suffix is what survives across episodes`() {
        val suffix = TrackMatch.subSuffix(ep2, "Show.S01E02.1080p.chs.srt")
        assertEquals(".chs.srt", suffix)
        val subs = listOf("Show.S01E03.1080p.eng.srt", "Show.S01E03.1080p.chs.srt")
        assertEquals(1, TrackMatch.pickExternal(ep3, subs, suffix))
    }

    /**
     * A media server's subtitle stream isn't named after the video at all; the whole name
     * becomes the token, and it repeats across episodes, so matching still works.
     */
    @Test
    fun `a subtitle not named after the video matches by its whole name`() {
        val suffix = TrackMatch.subSuffix(ep2, "Chinese (Simplified).srt")
        assertEquals("chinese (simplified).srt", suffix)
        assertEquals(1, TrackMatch.pickExternal(ep3, listOf("English.srt", "Chinese (Simplified).srt"), suffix))
    }

    /** Last resort: the tail was renamed (a different subtitle format), the language token still lines up. */
    @Test
    fun `falls back to the language token when the tail differs`() {
        val suffix = TrackMatch.subSuffix(ep2, "Show.S01E02.1080p.chs.srt")
        assertEquals(1, TrackMatch.pickExternal(ep3, listOf("Show.S01E03.1080p.eng.ass", "Show.S01E03.1080p.chs.ass"), suffix))
    }

    @Test
    fun `no counterpart means no pick`() {
        val suffix = TrackMatch.subSuffix(ep2, "Show.S01E02.1080p.chs.srt")
        assertEquals(-1, TrackMatch.pickExternal(ep3, listOf("Show.S01E03.1080p.eng.srt"), suffix))
    }

    /**
     * A same-named sidecar carries no language marker (`.srt`), so there is nothing to match
     * on beyond the name itself — and the default "same name as the video" rule already covers
     * that case without any memory.
     */
    @Test
    fun `a bare extension has no language token to guess with`() {
        val suffix = TrackMatch.subSuffix(ep2, "Show.S01E02.1080p.srt")
        assertEquals(".srt", suffix)
        assertEquals(0, TrackMatch.pickExternal(ep3, listOf("Show.S01E03.1080p.srt"), suffix))
        assertEquals(-1, TrackMatch.pickExternal(ep3, listOf("Something.Else.chs.ass"), suffix))
    }
}
