package com.twig.app

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The **only** entry point for going from text bytes to a String: the text
 * viewer, subtitles, lyrics, and m3u all go through it.
 *
 * Previously each of these spots rewrote "BOM first, then try UTF-8, fall
 * back to GBK", and the versions were inconsistent (some used a strict
 * decoder, others checked the result for U+FFFD), so the same file would
 * look right in the viewer but be mojibake in subtitles. The order is now
 * unified as:
 *
 *  1. **BOM wins** (UTF-8 / UTF-16LE / UTF-16BE) — the encoding the file
 *     declares for itself leaves nothing to guess;
 *  2. **Strict UTF-8** — the vast majority of today's text is in it, and
 *     illegal UTF-8 sequences are very easy to recognise;
 *  3. **The list of encodings the user has ordered in Settings** (see
 *     [Prefs.textCharsets]); each is tried **strictly** in order, and the
 *     first one that decodes successfully *and* does not look like mojibake
 *     ([plausible]) wins;
 *  4. If none of them work → lenient UTF-8 (illegal bytes become U+FFFD),
 *     and [Decoded.charset] being null means "unrecognised".
 *
 * ★ Order is priority, and **encodings like GB18030 / windows-125x /
 * ISO-8859-x will accept almost any byte sequence** — anything after them
 * basically never gets a turn. The Settings page therefore lists candidates
 * in a default order of "the more strict, the higher up", and users who
 * rearrange them do so at their own risk. [plausible] can only filter out
 * the most blatant cases (a string full of control characters); it cannot
 * tell GBK↔Big5 apart when both decode cleanly but produce mojibake — there
 * is no reliable general test for that, and a real one would need a
 * statistical model, which conflicts with size-first.
 */
object TextCodec {

    /**
     * Candidate encodings offered in the Settings page; **the default order is
     * this order**. Only encodings for which real legacy files still exist in
     * the wild are listed; ones not supported at runtime (theoretically
     * impossible — Android ships ICU) are filtered out automatically.
     */
    val CANDIDATES: List<String> = listOf(
        // Multi-byte first: they reject illegal byte sequences, so being tried early costs
        // the ones below nothing. GBK is deliberately absent — GB18030 is a strict superset
        // of it (same bytes for every GBK character), so listing both would only make the
        // user pick between two spellings of the same answer.
        "GB18030", "Big5", "Big5-HKSCS", "Shift_JIS", "EUC-JP", "ISO-2022-JP", "EUC-KR",
        // Single-byte: these accept nearly any byte sequence, so order among them decides
        // everything and whatever sits last never gets a turn.
        "windows-874", "KOI8-R",
        "windows-1250", "windows-1251", "windows-1252", "windows-1253",
        "windows-1254", "windows-1255", "windows-1256", "windows-1257",
        "ISO-8859-2", "ISO-8859-15",
    ).filter { runCatching { Charset.isSupported(it) }.getOrDefault(false) }

    /** Decoding result. */
    class Decoded(
        val text: String,
        /**
         * The encoding used to produce it; **null = nothing decoded it strictly**,
         * and [text] is the result of lenient UTF-8 (containing U+FFFD). When
         * writing back, this value must be used — blindly writing back as UTF-8
         * silently changes the file's encoding.
         */
        val charset: Charset?,
        /** The leading BOM bytes; they are stripped during decoding (so they don't mix into the body) and must be prepended again on write-back. */
        val bom: ByteArray?,
    ) {
        /** Strict decoding succeeded = every byte has an exact meaning, so writing back loses no information. */
        val strict: Boolean get() = charset != null
    }

    // ---- Global priority (the list from Settings) ----

    @Volatile
    private var pref: List<Charset> = listOf(Charset.forName("GB18030"))

    /** The currently active priority list. Cached in process — decoding is a hot path and we cannot re-read SharedPreferences on every call. */
    val preferred: List<Charset> get() = pref

    /** Reload from preferences; [TwigApp.onCreate] and the Settings page each call this once after edits. */
    fun load(ctx: Context) {
        pref = Prefs.textCharsets(ctx).mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
    }

    // ---- Decoding ----

    fun decode(
        bytes: ByteArray,
        off: Int = 0,
        len: Int = bytes.size - off,
        charsets: List<Charset> = pref,
    ): Decoded {
        // 1) BOM. ★ Use strict decoding here too: a BOM only says "this is the
        // encoding the author originally saved in" — it does not guarantee the
        // bytes after it are intact. If lenient U+FFFD is mistaken for "we
        // recognised the encoding", the bad bytes get baked in on write-back.
        bomOf(bytes, off, len)?.let { (bom, cs) ->
            val n = bom.size
            strict(bytes, off + n, len - n, cs)?.let { return Decoded(it, cs, bom) }
        }
        // 2) Strict UTF-8; 3) the user's ordered list
        for (cs in listOf(Charsets.UTF_8) + charsets) {
            val s = strict(bytes, off, len, cs) ?: continue
            if (!plausible(s)) continue
            return Decoded(s, cs, null)
        }
        // 4) Unrecognised: decode leniently so at least *something* is visible
        // (the ASCII portion stays readable)
        return Decoded(String(bytes, off, len, Charsets.UTF_8), null, null)
    }

    /** Strict decoding — if even one byte does not make sense, return null (no lenient substitution). */
    fun strict(bytes: ByteArray, off: Int, len: Int, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, off, len))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: Exception) {
        null // The encoding itself is broken (shouldn't happen) — treat as "cannot decode"
    }

    /**
     * Strict encode-to-bytes (used when writing back to a file); if any character
     * cannot be represented in this encoding, return null — silently substituting
     * `?` is irreversible damage, and the caller has to ask the user. [bom] is
     * prepended as-is.
     */
    fun encode(text: String, cs: Charset, bom: ByteArray? = null): ByteArray? = try {
        val buf = cs.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        if (bom == null) body else bom + body
    } catch (_: Exception) {
        null
    }

    /** Characters this encoding cannot represent (used to tell the user where it got stuck); capped at a few. */
    fun unmappable(text: String, cs: Charset, limit: Int = 6): List<Char> {
        val enc = cs.newEncoder()
        return text.toSet().filter { !enc.canEncode(it) }.take(limit)
    }

    // ---- Internal ----

    private fun bomOf(b: ByteArray, off: Int, len: Int): Pair<ByteArray, Charset>? {
        fun at(i: Int) = b[off + i].toInt() and 0xFF
        if (len >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
            return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to Charsets.UTF_8
        }
        if (len >= 2 && at(0) == 0xFF && at(1) == 0xFE) {
            return byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE
        }
        if (len >= 2 && at(0) == 0xFE && at(1) == 0xFF) {
            return byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE
        }
        return null
    }

    /**
     * "Decoded" does not mean "decoded correctly". Control characters are the
     * strongest giveaway: a normal text file should have no C0 control codes
     * other than tab/line-feed/form-feed, but using a single-byte encoding to
     * decode UTF-16 or arbitrary binary yields a string half full of them.
     * The threshold is set very loose (1%) — better to let a normal file with
     * a stray control char pass than to falsely reject it.
     */
    private fun plausible(s: String): Boolean {
        if (s.isEmpty()) return true
        var bad = 0
        for (c in s) {
            if (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\u000C') bad++
        }
        return bad <= 1 || bad * 100 < s.length
    }
}
