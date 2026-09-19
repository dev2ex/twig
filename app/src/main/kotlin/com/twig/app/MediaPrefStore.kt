package com.twig.app

import android.content.Context
import com.twig.core.XFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * One track's identity, projected out of media3's `Format` into plain data.
 *
 * ★ **The index is not the identity.** A remembered "audio track 2" is worthless across
 * episodes: different releases of the same show order their tracks differently, and an
 * episode with one fewer commentary track shifts everything after it. What survives is the
 * language / label / codec, so that is what gets stored and matched; [index] is only
 * consulted when restoring **the same file** (see [TrackMatch.pick]).
 */
data class TrackDesc(
    val lang: String? = null,
    val label: String? = null,
    val mime: String? = null,
    val channels: Int = 0,
    val forced: Boolean = false,
    val isDefault: Boolean = false,
    val index: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        lang?.let { put("lang", it) }
        label?.let { put("label", it) }
        mime?.let { put("mime", it) }
        if (channels > 0) put("ch", channels)
        if (forced) put("forced", true)
        if (isDefault) put("def", true)
        if (index >= 0) put("i", index)
    }

    companion object {
        fun fromJson(o: JSONObject) = TrackDesc(
            lang = o.optString("lang", "").ifEmpty { null },
            label = o.optString("label", "").ifEmpty { null },
            mime = o.optString("mime", "").ifEmpty { null },
            channels = o.optInt("ch", 0),
            forced = o.optBoolean("forced", false),
            isDefault = o.optBoolean("def", false),
            index = o.optInt("i", -1),
        )
    }
}

/**
 * A remembered subtitle choice. Three shapes have to fit in one record, because to the
 * user they are one setting:
 *
 * - [OFF] — ★ this one matters most. Without it, turning subtitles off only lasts for the
 *   current episode and the next one switches them back on, which reads as the player
 *   ignoring you.
 * - [EMBEDDED] — a text track inside the container, matched by [TrackDesc].
 * - [EXTERNAL] — a sidecar file (or a subtitle stream handed over by a media server).
 *   The **file name cannot be stored**: it differs for every episode. What is stored is
 *   the name's tail relative to the video's stem (`.chs.srt`), which is the part that
 *   stays the same across a series — see [TrackMatch.subSuffix].
 */
data class SubMemo(val kind: Int, val track: TrackDesc? = null, val suffix: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        track?.let { put("t", it.toJson()) }
        suffix?.let { put("sfx", it) }
    }

    companion object {
        const val OFF = 0
        const val EMBEDDED = 1
        const val EXTERNAL = 2

        fun fromJson(o: JSONObject) = SubMemo(
            kind = o.optInt("kind", OFF),
            track = o.optJSONObject("t")?.let { TrackDesc.fromJson(it) },
            suffix = o.optString("sfx", "").ifEmpty { null },
        )
    }
}

/**
 * What is remembered for one scope; any field may be absent (the user only ever changed one).
 *
 * [scale] is the player's picture scaling mode (best fit / crop / fill). It rides along with the
 * track choices because it has exactly the same shape of answer — "how this title should be
 * played" — and the same scope: every episode of a show has the same aspect ratio, so the crop
 * that removed the black bars on episode 1 is the right one for episode 2. Unlike a track it
 * needs no matching: the value is just the mode.
 */
data class MediaMemo(val audio: TrackDesc? = null, val sub: SubMemo? = null, val scale: Int? = null) {
    val isEmpty: Boolean get() = audio == null && sub == null && scale == null
}

/**
 * Persistence for "how the user wants this title played" — audio track, subtitle and picture
 * scaling (SharedPreferences + JSON), capped at [MAX] entries, least-recently-used dropped first.
 *
 * **Two scopes, written together and read narrowest-first:**
 * - per file (`f|<name>|<size>`, the same path-independent key as [PlaybackStore]) — you
 *   stopped halfway through a film and came back to it;
 * - per series (`s|<series key>`) — the next episode, which is the whole point of the
 *   feature.
 *
 * ★ Unlike [PlaybackStore] there is **no delete-on-finish**: a position at the end credits
 * is worthless, but "I watch this show with the Japanese audio and Chinese subtitles" stays
 * true after the episode ends, and after the whole show ends. Only the LRU cap evicts.
 *
 * ★ Entries are only ever written from a **deliberate user choice** (the two dialogs in the
 * player). Writing back what the player auto-selected would freeze one episode's wrong
 * default into a preference and repeat it forever.
 */
object MediaPrefStore {
    private const val FILE = "twig_media_prefs"
    private const val KEY = "list"
    const val MAX = 200

    data class Entry(val key: String, val memo: MediaMemo, val updatedAt: Long)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Scope key for a single file; shares [PlaybackStore]'s "name|size" rule so both records describe the same title. */
    fun fileKey(f: XFile): String = "f|" + PlaybackStore.keyOf(f)

    /** Scope key for a series; [raw] comes from the episode queue (see the player's `seriesKey`). */
    fun seriesKey(raw: String): String = "s|$raw"

    fun all(ctx: Context): List<Entry> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("k", "").ifEmpty { return@mapNotNull null }
                Entry(
                    key = k,
                    memo = MediaMemo(
                        audio = o.optJSONObject("a")?.let { TrackDesc.fromJson(it) },
                        sub = o.optJSONObject("s")?.let { SubMemo.fromJson(it) },
                        scale = if (o.has("sc")) o.optInt("sc") else null,
                    ),
                    updatedAt = o.optLong("at", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun count(ctx: Context): Int = all(ctx).size

    /**
     * What applies to this playback: the per-file record wins **field by field**, falling
     * back to the series record for whatever it doesn't cover — someone who only ever
     * changed subtitles on this one file still gets the series' audio choice.
     *
     * ★ A series-level record comes back with its track **index stripped**. The index is only
     * trustworthy for the file it was taken from; carrying it into another episode would let
     * [TrackMatch] score "the second track" as agreement, which is exactly the false positive
     * matching by identity exists to avoid.
     */
    fun memoFor(ctx: Context, fileKey: String, seriesKey: String?): MediaMemo {
        if (!Prefs.rememberMediaPrefs(ctx)) return MediaMemo()
        val list = all(ctx)
        val f = list.firstOrNull { it.key == fileKey }?.memo
        val s = seriesKey?.let { k -> list.firstOrNull { it.key == k }?.memo }
        return MediaMemo(
            audio = f?.audio ?: s?.audio?.copy(index = -1),
            sub = f?.sub ?: s?.sub?.let { it.copy(track = it.track?.copy(index = -1)) },
            scale = f?.scale ?: s?.scale,
        )
    }

    fun putAudio(ctx: Context, fileKey: String, seriesKey: String?, audio: TrackDesc?) =
        update(ctx, fileKey, seriesKey) { it.copy(audio = audio) }

    fun putSub(ctx: Context, fileKey: String, seriesKey: String?, sub: SubMemo?) =
        update(ctx, fileKey, seriesKey) { it.copy(sub = sub) }

    fun putScale(ctx: Context, fileKey: String, seriesKey: String?, scale: Int?) =
        update(ctx, fileKey, seriesKey) { it.copy(scale = scale) }

    fun clear(ctx: Context) = sp(ctx).edit().remove(KEY).apply()

    private fun update(ctx: Context, fileKey: String, seriesKey: String?, edit: (MediaMemo) -> MediaMemo) {
        if (!Prefs.rememberMediaPrefs(ctx)) return
        val now = System.currentTimeMillis()
        val keys = listOfNotNull(fileKey, seriesKey)
        val byKey = all(ctx).associateBy { it.key }.toMutableMap()
        for (k in keys) {
            val memo = edit(byKey[k]?.memo ?: MediaMemo())
            if (memo.isEmpty) byKey.remove(k) else byKey[k] = Entry(k, memo, now)
        }
        persist(ctx, byKey.values.sortedByDescending { it.updatedAt }.take(MAX))
    }

    private fun persist(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("k", e.key)
                    e.memo.audio?.let { put("a", it.toJson()) }
                    e.memo.sub?.let { put("s", it.toJson()) }
                    e.memo.scale?.let { put("sc", it) }
                    put("at", e.updatedAt)
                },
            )
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
