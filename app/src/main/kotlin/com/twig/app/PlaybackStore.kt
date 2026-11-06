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
    /** Reopenable location of the latest play; absent in old or session-only records. */
    val path: String = "",
    val connLabel: String = "",
    val hidden: Boolean = false,
    /** A next-episode destination, independent of actual watched progress. */
    val pendingNext: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("name", name); put("pos", posMs); put("dur", durMs); put("at", updatedAt)
        put("path", path); put("connLabel", connLabel); put("hidden", hidden); put("pendingNext", pendingNext)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlaybackMark(
            key = o.getString("key"),
            name = o.optString("name", ""),
            posMs = o.optLong("pos", 0L),
            durMs = o.optLong("dur", 0L),
            updatedAt = o.optLong("at", 0L),
            path = o.optString("path", ""),
            connLabel = o.optString("connLabel", ""),
            hidden = o.optBoolean("hidden", false),
            pendingNext = o.optBoolean("pendingNext", false),
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
 * "half-watched long videos" and their next-episode destinations. The 100-entry
 * cap comfortably covers both.
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
        return all(ctx).firstOrNull { it.key == key && !it.pendingNext }?.posMs ?: 0L
    }

    /** Old records still resume normally, but cannot be reopened from history. */
    fun recent(ctx: Context): List<PlaybackMark> =
        if (!Prefs.resumePlayback(ctx)) emptyList()
        else all(ctx).filter { it.path.isNotEmpty() && !it.hidden }.sortedByDescending { it.updatedAt }

    /** Hiding an entry must not discard its playback position. Playing it again unhides it. */
    fun hide(ctx: Context, key: String) {
        persist(ctx, all(ctx).map { if (it.key == key) it.copy(hidden = true) else it })
    }

    fun hideRecent(ctx: Context) {
        persist(ctx, all(ctx).map { it.copy(hidden = true) })
    }

    /**
     * Record one position. Short, barely started and finished videos do not retain
     * progress. A barely started episode keeps its pending destination until real
     * progress replaces it; finishing removes either kind of record.
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
            // Barely starting the next episode must not erase its destination.
            val finished = durMs >= MIN_DURATION_MS && posMs >= durMs - endSlack(durMs)
            if (!finished && list.any { it.key == key && it.pendingNext }) return
            if (rest.size != list.size) persist(ctx, rest) // only write back if there was a record to remove
            return
        }
        val mark = markOf(ctx, f, posMs, durMs)
        persist(ctx, (listOf(mark) + rest).take(MAX))
    }

    /** Called only after confirmed playback completion, never for a manual episode change. */
    fun completeEpisode(ctx: Context, current: XFile, next: XFile?) {
        if (!Prefs.resumePlayback(ctx)) return
        val rest = all(ctx).filter { it.key != keyOf(current) }
        val nextMark = next?.let { markOf(ctx, it, 0L, 0L).copy(pendingNext = true) }
        val existing = nextMark?.let { mark -> rest.firstOrNull { it.key == mark.key } }
        val replacement = when {
            nextMark == null || nextMark.path.isEmpty() -> null
            // Never replace genuine progress with an unstarted destination.
            existing != null && !existing.pendingNext -> existing.copy(
                updatedAt = nextMark.updatedAt, hidden = false)
            else -> nextMark.copy(durMs = existing?.durMs ?: 0L)
        }
        persist(ctx, if (replacement == null) rest else
            (listOf(replacement) + rest.filter { it.key != replacement.key }).take(MAX))
    }

    /** Metadata alone can fill the total without accepting any watched progress. */
    fun updatePendingDuration(ctx: Context, file: XFile, durMs: Long) {
        if (!Prefs.resumePlayback(ctx) || durMs <= 0) return
        val key = keyOf(file)
        val list = all(ctx)
        if (list.none { it.key == key && it.pendingNext && it.durMs != durMs }) return
        persist(ctx, list.map {
            if (it.key == key && it.pendingNext) it.copy(durMs = durMs) else it
        })
    }

    private fun markOf(ctx: Context, f: XFile, posMs: Long, durMs: Long): PlaybackMark {
        // Persist connection identity rather than a process-scoped mount scheme.
        val conn = Connections.ofScheme(f.scheme)
        val reopenable = f.scheme == "file" || (conn != null &&
            ConnectionStore.all(ctx).any { it.label() == conn.label() })
        return PlaybackMark(keyOf(f), f.name, posMs, durMs, System.currentTimeMillis(),
            path = if (reopenable) f.path else "", connLabel = conn?.label().orEmpty())
    }

    fun clear(ctx: Context) = sp(ctx).edit().remove(KEY).apply()

    private fun persist(ctx: Context, list: List<PlaybackMark>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
