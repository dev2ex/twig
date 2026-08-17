package com.twig.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个收藏(指向某个目录)。为跨会话可恢复,不存动态 scheme,而存"如何到达":
 * - local:  path = 本地绝对路径
 * - conn:   connLabel = 对应 SavedConnection.label(),path = 该连接内路径
 * - restic: repoPath = 仓库目录路径,repoConnLabel = 仓库所在连接(空=本地),
 *           path = /<快照短id>/包内路径
 */
data class Favorite(
    val label: String,
    val kind: String,           // "local" | "conn" | "restic"
    val path: String,
    val connLabel: String = "",
    val repoPath: String = "",
    val repoConnLabel: String = "", // restic 仓库所在的网络连接(空=本地)
    /**
     * 用户手动重命名后的显示名;空 = 没改过,用 [com.twig.app.defaultFavoriteName] 实时生成
     * (跟着连接改名走)。一旦重命名就固定下来,不再跟连接改名联动——这正是"重命名"
     * 的语义:用户接管了这个名字。
     */
    val customLabel: String = "",
) {
    /** 稳定唯一 id。 */
    val id: String get() = "$kind|$connLabel|$repoConnLabel|$repoPath|$path"

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label); put("kind", kind); put("path", path)
        put("connLabel", connLabel); put("repoPath", repoPath)
        put("repoConnLabel", repoConnLabel); put("customLabel", customLabel)
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
        )
    }
}

/** 收藏的持久化(SharedPreferences + JSON)。 */
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

    /** 重命名(设置/清空自定义显示名;传空串等于恢复自动名)。 */
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
