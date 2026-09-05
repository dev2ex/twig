package com.twig.app

import android.content.Context
import com.twig.core.XFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * One playback position record.
 *
 * The key is **path-independent** — "filename + byte size", same idea as the
 * thumbnail cache: opening the same video from a different source (watch on SMB
 * first, later copied to local) resumes from the same position; conversely, if
 * the file is replaced with a same-named different version (size must change),
 * the record becomes invalid and won't jump to a nonsensical position. Size
 * unknown (size <= 0, e.g. some content:// sources) falls back to `scheme|path`.
 */
data class PlaybackMark(
    val key: String,
    val name: String,
    val posMs: Long,
    val durMs: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("name", name); put("pos", posMs); put("dur", durMs); put("at", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlaybackMark(
            key = o.getString("key"),
            name = o.optString("name", ""),
            posMs = o.optLong("pos", 0L),
            durMs = o.optLong("dur", 0L),
            updatedAt = o.optLong("at", 0L),
        )
    }
}

/**
 * Persistence for playback positions (SharedPreferences + JSON), most-recent first,
 * capped at [MAX] entries.
 *
 * Retention trade-off: the cap is just a safety net; the real reason the list
 * doesn't grow is **delete-on-finish** — a record sitting at the end credits has
 * no value, the next play should start from the top. Combined with "too short to
 * bother" and "barely started", what survives day-to-day is essentially
 * "half-watched long videos", which 100 entries covers easily (~100 bytes each,
 * worst case 10KB).
 */
object PlaybackStore {
    private const val FILE = "twig_playback"
    private const val KEY = "list"
    const val MAX = 100

    /** Shorter than this (short clips / memes) — not worth remembering: the few
     * seconds saved by skipping back are better spent just rewatching. */
    private const val MIN_DURATION_MS = 90_000L

    /** Right at the start = nothing watched yet; recording it just causes one more
     * bizarre jump-to-position on next open. */
    private const val MIN_POS_MS = 15_000L

    /** How close to the end counts as "finished": 5% of duration, clamped to
     * 10s..60s (don't let an entire credits sequence on a long movie count). */
    private fun endSlack(durMs: Long) = (durMs / 20).coerceIn(10_000L, 60_000L)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun keyOf(f: XFile): String =
        if (f.size > 0) "${f.name}|${f.size}" else "${f.scheme}|${f.path}"

    fun all(ctx: Context): List<PlaybackMark> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PlaybackMark.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Last position; 0 if no record or the feature is off (start from the top). */
    fun positionFor(ctx: Context, f: XFile): Long {
        if (!Prefs.resumePlayback(ctx)) return 0L
        val key = keyOf(f)
        return all(ctx).firstOrNull { it.key == key }?.posMs ?: 0L
    }

    /**
     * Record one position. "Too short / barely started / finished" are all treated
     * as **not worth recording** — and any existing record is also removed, so
     * finishing a video doesn't make the next open jump back to the credits.
     */
    fun save(ctx: Context, f: XFile, posMs: Long, durMs: Long) {
        if (!Prefs.resumePlayback(ctx)) return
        val key = keyOf(f)
        val worth = durMs >= MIN_DURATION_MS &&
            posMs >= MIN_POS_MS &&
            posMs < durMs - endSlack(durMs)
        val list = all(ctx)
        val rest = list.filter { it.key != key }
        if (!worth) {
            if (rest.size != list.size) persist(ctx, rest) // only write back if there was a record to remove
            return
        }
        val mark = PlaybackMark(key, f.name, posMs, durMs, System.currentTimeMillis())
        persist(ctx, (listOf(mark) + rest).take(MAX))
    }

    fun clear(ctx: Context) = sp(ctx).edit().remove(KEY).apply()

    private fun persist(ctx: Context, list: List<PlaybackMark>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
