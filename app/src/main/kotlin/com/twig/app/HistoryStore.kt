package com.twig.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条"最近位置"。记的是**目录**而不是文件——在某个目录里打开过文件、或进入过它的 Git
 * 虚拟节点,就把这个目录记下来,下次一键跳回去。
 *
 * 与 [Favorite] 同理:存"如何到达"(本地路径 / 连接标签 + 路径)而不是会话内动态生成的
 * scheme,跨会话/重连后仍能定位。
 * - kind = "dir":跳到该目录本身
 * - kind = "git":跳到该目录并展开它下面的 Git 虚拟节点
 */
data class HistoryEntry(
    val kind: String,           // "dir" | "git"
    val path: String,
    val connLabel: String = "", // 空 = 本地
) {
    val id: String get() = "$kind|$connLabel|$path"

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind); put("path", path); put("connLabel", connLabel)
    }

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            kind = o.getString("kind"),
            path = o.getString("path"),
            connLabel = o.optString("connLabel", ""),
        )
    }
}

/** 最近位置历史的持久化(SharedPreferences + JSON),最近的在前,只留 [MAX] 条。 */
object HistoryStore {
    private const val FILE = "twig_history"
    private const val KEY = "list"
    const val MAX = 10

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<HistoryEntry> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** 记一条:同一位置去重后置顶,超出 [MAX] 的最旧几条丢弃。 */
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
