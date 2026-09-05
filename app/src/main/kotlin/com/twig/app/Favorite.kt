package com.twig.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A favourite (pointing at a directory). For cross-session recoverability we don't store
 * the dynamic scheme, but "how to reach it":
 * - local:  path = absolute local path
 * - conn:   connLabel = the corresponding SavedConnection.label(), path = the path within that connection
 * - restic: repoPath = the repository directory path, repoConnLabel = the connection that
 *           holds the repository (empty = local), path = /<snapshot short id>/path inside the snapshot
 * - saf:    path = the entire document URI. The grant was taken via takePersistableUriPermission
 *           and remains valid across sessions, so this path can be stored (same as the saf
 *           tracks in "now playing").
 */
data class Favorite(
    val label: String,
    val kind: String,           // "local" | "conn" | "restic" | "saf"
    val path: String,
    /**
     * Display name of the directory at the time it was favourited. **Only needed when the
     * last segment of [path] is not the source of the name** — SAF's path is an entire
     * document URI (every '/' between parent and child is encoded as %2F), and slicing
     * off the last segment yields a percent-encoded string; that's exactly how
     * [defaultFavoriteName] derives the name. When empty, fall back to slicing path.
     */
    val pathName: String = "",
    val connLabel: String = "",
    val repoPath: String = "",
    val repoConnLabel: String = "", // network connection that holds the restic repository (empty = local)
    /**
     * Display name after the user manually renamed it; empty = never renamed, fall back
     * to [com.twig.app.defaultFavoriteName] computed on the fly (follows the connection's
     * rename). Once renamed, it sticks and no longer tracks the connection — that is the
     * very meaning of "rename": the user has taken ownership of the name.
     */
    val customLabel: String = "",
) {
    /** Stable unique id. */
    val id: String get() = "$kind|$connLabel|$repoConnLabel|$repoPath|$path"

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label); put("kind", kind); put("path", path)
        put("connLabel", connLabel); put("repoPath", repoPath)
        put("repoConnLabel", repoConnLabel); put("customLabel", customLabel)
        put("pathName", pathName)
    }

    companion object {
        fun fromJson(o: JSONObject) = Favorite(
            label = o.getString("label"),
            kind = o.getString("kind"),
            path = o.getString("path"),
            connLabel = o.optString("connLabel", ""),
            repoPath = o.optString("repoPath", ""),
            repoConnLabel = o.optString("repoConnLabel", ""),
            customLabel = o.optString("customLabel", ""),
            pathName = o.optString("pathName", ""),
        )
    }
}

/** Persistence for favourites (SharedPreferences + JSON). */
object FavoritesStore {
    private const val FILE = "twig_favorites"
    private const val KEY = "list"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Favorite> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Favorite.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, fav: Favorite) {
        val list = all(ctx).filter { it.id != fav.id } + fav
        persist(ctx, list)
    }

    fun remove(ctx: Context, fav: Favorite) {
        persist(ctx, all(ctx).filter { it.id != fav.id })
    }

    /** Rename (set / clear the custom display name; empty string restores the auto-generated name). */
    fun rename(ctx: Context, fav: Favorite, newLabel: String) {
        persist(ctx, all(ctx).map { if (it.id == fav.id) it.copy(customLabel = newLabel) else it })
    }

    fun contains(ctx: Context, id: String): Boolean = all(ctx).any { it.id == id }

    private fun persist(ctx: Context, list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
