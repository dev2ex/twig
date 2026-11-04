package com.twig.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A "recent location" entry. Records a **directory** rather than a file — opening a file
 * inside a directory, or entering one of its Git virtual nodes, records that directory so
 * you can jump back in one tap.
 *
 * Same rationale as [Favorite]: store "how to reach it" (local path / connection label +
 * path) rather than the session-scoped dynamic scheme, so it stays locatable across
 * sessions and reconnects.
 * - kind = "dir": jump to that directory itself
 * - kind = "git": jump to that directory and expand its Git virtual node
 * - kind = "restic": reconnect/unlock a repository and locate a snapshot directory
 */
data class HistoryEntry(
    val kind: String,           // "dir" | "git" | "restic"
    val path: String,
    val connLabel: String = "", // empty = local for dir/git
    val repoPath: String = "",
    val repoConnLabel: String = "",
    val snapshotLabel: String = "",
) {
    val id: String get() = if (kind == "restic") "$kind|$repoConnLabel|$repoPath|$path"
        else "$kind|$connLabel|$path"

    fun resticFavorite(): Favorite = Favorite(
        label = "", kind = "restic", path = path, repoPath = repoPath,
        repoConnLabel = repoConnLabel, snapshotLabel = snapshotLabel,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind); put("path", path); put("connLabel", connLabel)
        if (kind == "restic") {
            put("repoPath", repoPath); put("repoConnLabel", repoConnLabel); put("snapshotLabel", snapshotLabel)
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            kind = o.getString("kind"),
            path = o.getString("path"),
            connLabel = o.optString("connLabel", ""),
            repoPath = o.optString("repoPath", ""),
            repoConnLabel = o.optString("repoConnLabel", ""),
            snapshotLabel = o.optString("snapshotLabel", ""),
        )
    }
}

/** Persistence for recent-location history (SharedPreferences + JSON); most recent first, kept to [MAX] entries. */
object HistoryStore {
    private const val FILE = "twig_history"
    private const val KEY = "list"
    const val MAX = 16

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<HistoryEntry> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Record one: dedupe the same location and put it on top; discard the oldest beyond [MAX]. */
    fun add(ctx: Context, e: HistoryEntry) {
        val list = (listOf(e) + all(ctx).filter { it.id != e.id }).take(MAX)
        persist(ctx, list)
    }

    fun remove(ctx: Context, e: HistoryEntry) {
        persist(ctx, all(ctx).filter { it.id != e.id })
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }

    private fun persist(ctx: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
