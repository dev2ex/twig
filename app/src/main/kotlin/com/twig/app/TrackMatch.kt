package com.twig.app

/**
 * Matches a remembered track ([TrackDesc]) against the tracks an episode actually has.
 *
 * ★ **Refusing to match is a valid answer, and the important one.** Scores are additive and
 * a candidate below [MIN_SCORE] is never selected: when this episode simply has no Japanese
 * audio, jumping to some arbitrary track is worse than leaving the player's own default
 * alone. That threshold is set so that codec / channel / flag agreement **alone** can never
 * carry a match — across files, either the language or the label has to agree.
 */
object TrackMatch {

    /** Same language including region/script, e.g. both `zh-Hans`. */
    private const val LANG_EXACT = 100

    /** Same primary language, different region/script (`zh-Hans` vs `zh-Hant`). Still far better
     *  than another language — but simplified/traditional is a distinction people care about,
     *  so it must not score the same as an exact hit. */
    private const val LANG_PRIMARY = 70
    private const val LABEL_EXACT = 60
    private const val LABEL_PART = 30

    /** Only reachable for a per-file memory, where the track layout cannot have moved. */
    private const val INDEX_SAME = 25
    private const val MIME_SAME = 20
    private const val CHANNELS_SAME = 10
    private const val FLAG_SAME = 5

    /** Below this, report "no counterpart in this episode". */
    const val MIN_SCORE = 50

    /**
     * ISO 639-2 → 639-1 for the languages that actually turn up in media files. media3
     * normalises most of this itself, but external subtitle files and media-server streams
     * arrive with whatever the source wrote.
     */
    private val ISO3 = mapOf(
        "chi" to "zh", "zho" to "zh", "jpn" to "ja", "eng" to "en", "kor" to "ko",
        "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "spa" to "es",
        "ita" to "it", "rus" to "ru", "por" to "pt", "tha" to "th", "vie" to "vi",
        "ara" to "ar", "hin" to "hi", "dut" to "nl", "nld" to "nl", "swe" to "sv",
        "pol" to "pl", "tur" to "tr", "ind" to "id", "may" to "ms", "msa" to "ms",
    )

    /** Lowercase, `_`→`-`, drop the "unknown" placeholders, map three-letter codes onto two. */
    fun normLang(s: String?): String? {
        val v = s?.trim()?.lowercase()?.replace('_', '-') ?: return null
        if (v.isEmpty() || v == "und" || v == "unknown" || v == "mul" || v == "zxx") return null
        val primary = v.substringBefore('-')
        val mapped = ISO3[primary] ?: primary
        val rest = v.substringAfter('-', "")
        return if (rest.isEmpty()) mapped else "$mapped-$rest"
    }

    private fun normLabel(s: String?): String? =
        s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * How well [cand] answers the remembered [want].
     *
     * ★ Index agreement counts only when [want] still carries an index, which happens for a
     * per-file memory alone: [TrackPrefStore.memoFor] strips it from a series-level record,
     * where the track layout is not the one the index was taken from.
     */
    fun score(want: TrackDesc, cand: TrackDesc): Int {
        var s = 0
        val wl = normLang(want.lang)
        val cl = normLang(cand.lang)
        if (wl != null && cl != null) {
            s += when {
                wl == cl -> LANG_EXACT
                wl.substringBefore('-') == cl.substringBefore('-') -> LANG_PRIMARY
                else -> 0
            }
        }
        val wlab = normLabel(want.label)
        val clab = normLabel(cand.label)
        if (wlab != null && clab != null) {
            s += when {
                wlab == clab -> LABEL_EXACT
                wlab.length >= 2 && clab.length >= 2 && (wlab.contains(clab) || clab.contains(wlab)) -> LABEL_PART
                else -> 0
            }
        }
        if (want.mime != null && want.mime == cand.mime) s += MIME_SAME
        if (want.channels > 0 && want.channels == cand.channels) s += CHANNELS_SAME
        if (want.forced == cand.forced) s += FLAG_SAME
        if (want.isDefault == cand.isDefault) s += FLAG_SAME
        if (want.index >= 0 && want.index == cand.index) s += INDEX_SAME
        return s
    }

    /** Index of the best candidate, or -1 when none clears [MIN_SCORE]. Ties go to the earlier track. */
    fun pick(want: TrackDesc, cands: List<TrackDesc>): Int {
        var best = -1
        var bestScore = 0
        cands.forEachIndexed { i, c ->
            val s = score(want, c)
            if (s > bestScore) { bestScore = s; best = i }
        }
        return if (bestScore >= MIN_SCORE) best else -1
    }

    // ---- External subtitle files ----

    /**
     * The part of a subtitle file's name that stays the same across a series.
     *
     * `Show.S01E02.1080p.mkv` + `Show.S01E02.1080p.chs.srt` → `.chs.srt`, which applied to
     * episode 3's stem names that episode's subtitle file. When the subtitle name doesn't
     * start with the video's stem — a media server's subtitle stream (`Chinese (Simplified).srt`),
     * or a differently named sidecar — the whole name is the token instead; those names
     * repeat across episodes, so matching on them still works.
     */
    fun subSuffix(videoName: String, subName: String): String {
        val stem = videoName.substringBeforeLast('.', videoName)
        val sub = subName.lowercase()
        return if (stem.isNotEmpty() && sub.startsWith(stem.lowercase())) {
            sub.substring(stem.length)
        } else {
            sub
        }
    }

    /**
     * Find the subtitle in [subNames] that corresponds to a remembered [suffix], for the
     * episode named [videoName]. Three attempts, narrowing to widening:
     * exact `stem + suffix`, any name ending in the suffix, and finally the language token
     * on its own (`.chs.srt` → `chs`) for a release that renames its tail.
     */
    fun pickExternal(videoName: String, subNames: List<String>, suffix: String): Int {
        if (suffix.isEmpty()) return -1
        val stem = videoName.substringBeforeLast('.', videoName).lowercase()
        val want = stem + suffix
        subNames.indexOfFirst { it.lowercase() == want }.let { if (it >= 0) return it }
        subNames.indexOfFirst { it.lowercase().endsWith(suffix) }.let { if (it >= 0) return it }
        val token = langToken(suffix)
        if (token.isEmpty()) return -1
        return subNames.indexOfFirst { langToken(subSuffix(videoName, it)) == token }
    }

    /** `.chs.srt` → `chs`; `.srt` → `""` (no language marker, nothing to match on). */
    private fun langToken(suffix: String): String =
        suffix.trimStart('.').substringBeforeLast('.', "").trim('.', ' ', '_', '-')
}
