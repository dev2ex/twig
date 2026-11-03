package com.twig.app.sqlite

import android.content.Context
import com.twig.core.XFile
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local SQL history; saved statements are only loaded into the editor, never executed automatically. */
internal class SqliteQueryHistory(context: Context) {
    private val prefs = context.getSharedPreferences("twig_sqlite_queries", Context.MODE_PRIVATE)
    private fun key(file: XFile) = "${file.scheme}:${file.path}"
    private fun entries() = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrElse { JSONArray() }
    fun recent(file: XFile): List<String> {
        val entries = entries()
        val entry = (0 until entries.length()).map { entries.getJSONObject(it) }.firstOrNull { it.optString("source") == key(file) }
        val sql = entry?.optJSONArray("sql") ?: return emptyList()
        return (0 until sql.length()).map { sql.getString(it) }
    }
    fun remember(file: XFile, sql: String) {
        if (sql.length > 16384) return
        val history = listOf(sql) + recent(file).filter { it != sql }
        val entries = entries()
        val updated = JSONArray().put(JSONObject().put("source", key(file)).put("sql", JSONArray(history.take(10))))
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.optString("source") != key(file) && updated.length() < 32) updated.put(entry)
        }
        prefs.edit().putString("history", updated.toString()).apply()
    }
    fun clear(file: XFile) {
        val entries = entries()
        val updated = JSONArray()
        for (index in 0 until entries.length()) entries.getJSONObject(index).takeIf { it.optString("source") != key(file) }?.let(updated::put)
        prefs.edit().putString("history", updated.toString()).apply()
    }
}
