package com.twig.app

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Season/episode parsing from file names — the only part of "continuous playback" that
 * can "guess wrong", so the cases are written against **real-world naming conventions**,
 * not a handful of clean names made up for the occasion.
 *
 * The one rule that matters most: **match order is priority**. An episode file name is
 * routinely followed by a long tail of quality/codec tags full of digits (`2160p`,
 * `DDP.5.1`, `H.265`); the moment `SxxExx` hits, matching must stop right there.
 */
class EpisodesTest {

    private fun ref(name: String) = Episodes.parse(name)

    /** A real sample from a user: S01E01 sits in the middle, followed by a long tag string full of digits. */
    @Test
    fun `scene naming - recognizes SxxExx and is not misled by the trailing quality digits`() {
        val r = ref(
            "A.Knight.of.the.Seven.Kingdoms.S01E01.The.Hedge.Knight." +
                "2160p.HBOMax.WEB-DL.DDP.5.1.Atmos.DV.HDR.H.265.mkv",
        )!!
        assertEquals(1, r.season)
        assertEquals("the episode number must be the 01 right after E, not 2160/5/1/265", 1, r.episode)
        assertEquals("a.knight.of.the.seven.kingdoms", r.prefix)
    }

    @Test
    fun `different episodes of the same show share the same prefix`() {
        val a = ref("A.Knight.of.the.Seven.Kingdoms.S01E01.The.Hedge.Knight.2160p.mkv")!!
        val b = ref("A.Knight.of.the.Seven.Kingdoms.S01E02.Another.Title.1080p.mkv")!!
        assertEquals(a.prefix, b.prefix)
        assertEquals(2, b.episode)
    }

    @Test
    fun `various ways of writing season and episode`() {
        // Note: literal filenames below deliberately keep Chinese-language fixtures
        // (a Chinese show prefix, and the "第NN集" episode notation) since the point of
        // this test is that non-ASCII prefixes and CJK-specific episode markers parse
        // correctly.
        assertEquals(1 to 20, ref("剑网 S01E20.mkv")!!.let { it.season to it.episode })
        assertEquals(2 to 1, ref("剑网 S02E01.mkv")!!.let { it.season to it.episode })
        assertEquals(1 to 2, ref("show.s1e2.mp4")!!.let { it.season to it.episode })
        assertEquals(1 to 5, ref("Show S01 E05.mkv")!!.let { it.season to it.episode })
    }

    @Test
    fun `episode-number-only notations`() {
        assertEquals(3, ref("剑网 E03.mkv")!!.episode)
        assertEquals(3, ref("剑网 EP03.mkv")!!.episode)
        assertEquals(3, ref("剑网 第03集.mkv")!!.episode)
        assertNull("season is null when there is no season number", ref("剑网 E03.mkv")!!.season)
    }

    @Test
    fun `a bare number requires the number to be the last segment`() {
        assertEquals(1, ref("剑网 01.mkv")!!.episode)
        assertEquals("剑网", ref("剑网 01.mkv")!!.prefix)
        // ★ Anything trailing after the number disqualifies it — otherwise 1080p/a year
        // would be mistaken for the episode number
        assertNull("1080p is not an episode number", ref("剑网 1080p 蓝光.mkv"))
    }

    @Test
    fun `returns null when no number can be recognized`() {
        assertNull(ref("肖申克的救赎.mkv"))
        assertNull(ref("vacation.mp4"))
    }

    // ---- Queue building ----

    private fun f(name: String) = XFile("file", "/tv/$name", isDir = false)

    @Test
    fun `the queue is sorted by season and episode, and continues across seasons`() {
        val files = listOf(
            f("Show.S02E01.mkv"), f("Show.S01E20.mkv"),
            f("Show.S01E02.mkv"), f("Show.S01E10.mkv"),
        )
        val q = Episodes.queueOf(files[1], files)
        assertEquals(
            // ★ 10 sorts after 2 (numerically, not lexically), and S02E01 sorts after S01E20
            listOf("Show.S01E02.mkv", "Show.S01E10.mkv", "Show.S01E20.mkv", "Show.S02E01.mkv"),
            q.map { it.name },
        )
    }

    @Test
    fun `another show never leaks into the queue`() {
        val files = listOf(f("Show.S01E01.mkv"), f("Show.S01E02.mkv"), f("Other.S01E01.mkv"))
        val q = Episodes.queueOf(files[0], files)
        assertEquals(listOf("Show.S01E01.mkv", "Show.S01E02.mkv"), q.map { it.name })
    }

    /**
     * ★ No queue unless at least two episodes can be assembled. Names like
     * `假期照片 01.mp4` ("vacation photo 01") are everywhere in real directories, and
     * treating them as continuous playback would only misfire — better to withhold the
     * button than to string a pile of unrelated videos into one show.
     */
    @Test
    fun `no queue when there is only one file`() {
        val only = listOf(f("Show.S01E01.mkv"), f("电影.mkv"))
        assertEquals(emptyList<XFile>(), Episodes.queueOf(only[0], only))
        assertEquals("a name with no recognizable number should have even less of a queue", emptyList<XFile>(), Episodes.queueOf(only[1], only))
    }
}
