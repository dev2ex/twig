package com.twig.app

import com.twig.core.XFile

/**
 * Recognises "which season / episode of the same show" from a filename, used by the player
 * to chain episodes together.
 *
 * ★ **Match order = priority, strict first then loose** — this is the most important rule
 * in this file. Episode filenames are typically trailed by a string of quality / codec tags
 * stuffed with digits:
 *
 * ```
 * A.Knight.of.the.Seven.Kingdoms.S01E01.The.Hedge.Knight.2160p.HBOMax.WEB-DL.DDP.5.1.Atmos.DV.HDR.H.265.mkv
 *                                ^^^^^^ recognise this          ^^^^      ^^^     ^^^^^ must not be episode numbers
 * ```
 *
 * So as soon as `SxxExx` matches we **return immediately**, later digits are not even
 * looked at; only when none of the earlier patterns match do we fall through to the
 * "bare number" rule, the most error-prone of all.
 *
 * Grouping is via [EpisodeRef.prefix] — the segment **before** the number (above:
 * `a.knight.of.the.seven.kingdoms`), which is the same for every episode of a show.
 */
object Episodes {

    /**
     * @param prefix the part before the number, normalised (lowercase, leading/trailing
     *   separators stripped); the same across episodes of one show.
     * @param season season number; null when only the episode number is known (treated as
     *   the same season when sorting).
     * @param episode episode number.
     */
    data class EpisodeRef(val prefix: String, val season: Int?, val episode: Int)

    /** `S01E02` / `s1e2`; season and episode both present, most reliable. */
    private val SEASON_EP = Regex("""(?i)^(.*?)[sS](\d{1,2})[\s._-]{0,2}[eE](\d{1,3})(?![0-9])""")

    /** `E01` / `EP01` / CJK markers like `第01集`; episode number only. Must be preceded by
     *  a separator or string start, so it doesn't eat an 'e' inside a word. */
    private val EP_ONLY = Regex("""(?i)^(.*?)(?:^|[\s._\-\[(])(?:ep?|第)[\s._-]?(\d{1,3})(?![0-9])""")

    /**
     * Bare number, e.g. `Show 01.mkv`. **Most error-prone** (years, 1080p, 5.1 are all
     * numbers), so constrained the tightest: the digits must be the **last segment** of the
     * name, with nothing but separators after them.
     */
    private val BARE_NUM = Regex("""^(.*?)[\s._\-\[(]?(\d{1,3})[\s._\-\])]*$""")

    /**
     * Parse a **filename** (with or without extension); returns null when unrecognised.
     *
     * Unrecognised is common and normal (films, documentaries, casual videos); callers
     * use that to decide "no episode queue".
     */
    fun parse(name: String): EpisodeRef? {
        val stem = name.substringBeforeLast('.', name)
        SEASON_EP.find(stem)?.let { m ->
            return EpisodeRef(norm(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        EP_ONLY.find(stem)?.let { m ->
            // Empty prefix means the whole name is just "E01", with no show name to group by —
            // any such file could be matched into a queue, which is useless.
            val p = norm(m.groupValues[1])
            if (p.isNotEmpty()) return EpisodeRef(p, null, m.groupValues[2].toInt())
        }
        BARE_NUM.find(stem)?.let { m ->
            val p = norm(m.groupValues[1])
            if (p.isNotEmpty()) return EpisodeRef(p, null, m.groupValues[2].toInt())
        }
        return null
    }

    /** Normalise the prefix: lowercase + trim leading/trailing separators, so `Show.` and `Show ` collapse into the same group. */
    private fun norm(s: String) = s.lowercase().trim(' ', '.', '_', '-', '[', '(')

    /**
     * From [siblings], pick those that belong to the same show as [current] and sort them by (season, episode).
     *
     * The returned list **always includes [current] itself**; if fewer than two episodes
     * can be collected, return empty list — that means either no number is recognised, or
     * this "prefix" only has a single file; treating it as a queue would just cause
     * collateral damage (think `Holiday Photos 01.mp4` everywhere).
     */
    fun queueOf(current: XFile, siblings: List<XFile>): List<XFile> {
        val ref = parse(current.name) ?: return emptyList()
        val group = siblings.mapNotNull { f ->
            parse(f.name)?.takeIf { it.prefix == ref.prefix }?.let { f to it }
        }
        if (group.size < 2) return emptyList()
        return group
            .sortedWith(compareBy({ it.second.season ?: 0 }, { it.second.episode }, { it.first.name }))
            .map { it.first }
    }
}
