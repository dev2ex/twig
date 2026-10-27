package com.twig.app

import android.content.Context
import com.twig.core.XFile
import org.json.JSONObject

/** Only explicit overrides are stored; missing fields follow recursive ancestors, then global defaults. */
data class DirectoryView(
    val sort: SortSpec? = null,
    val gridMode: Int? = null,
    val sortRecursive: Boolean = false,
    val viewRecursive: Boolean = false,
)

object DirectoryViewStore {
    const val FILE = "twig_directory_views"

    private fun key(dir: XFile): String = "${dir.scheme}:${dir.path.trimEnd('/').ifEmpty { "/" }}"

    /** One snapshot per settings refresh, never JSON parsing during row binding. */
    fun load(ctx: Context): Map<String, DirectoryView> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            runCatching {
                val json = JSONObject(value as String)
                val sort = json.optJSONObject("sort")?.let {
                    SortSpec(
                        FileSortKey.valueOf(it.getString("by")),
                        it.getBoolean("reversed"),
                        FolderSortKey.valueOf(it.getString("folderBy")),
                    )
                }
                val grid = if (json.has("grid")) json.getInt("grid").takeIf { it in 0..2 } else null
                key to DirectoryView(sort, grid,
                    sort != null && json.optBoolean("sortRecursive"),
                    grid != null && json.optBoolean("viewRecursive"))
            }.getOrNull()
        }.toMap()

    fun get(views: Map<String, DirectoryView>, dir: XFile?): DirectoryView? = dir?.let { views[key(it)] }

    /** Pure path lookup; opaque document URIs require parent links from existing listings. */
    fun pathParent(dir: XFile): XFile? {
        if (!dir.path.startsWith("/") || dir.path.trimEnd('/').isEmpty()) return null
        return dir.copy(path = dir.parentPath)
    }

    /**
     * Resolve sorting and view independently; a nonrecursive override only applies to its own directory.
     * Set [includeOwn] to false to preview inherited settings when clearing a directory override.
     */
    fun resolve(
        views: Map<String, DirectoryView>,
        dir: XFile?,
        includeOwn: Boolean = true,
        parentOf: (XFile) -> XFile? = ::pathParent,
    ): DirectoryView {
        if (dir == null || views.isEmpty()) return EMPTY
        val own = if (includeOwn) get(views, dir) else null
        var sort = own?.sort
        var grid = own?.gridMode
        if (own != null && sort != null && grid != null) return own
        var ancestor = parentOf(dir)
        // Providers can expose aliases or malformed parent links; never follow a cycle.
        val seen = hashSetOf(key(dir))
        while (ancestor != null && (sort == null || grid == null)) {
            if (ancestor.scheme != dir.scheme || !seen.add(key(ancestor))) break
            get(views, ancestor)?.let {
                if (sort == null && it.sortRecursive) sort = it.sort
                if (grid == null && it.viewRecursive) grid = it.gridMode
            }
            ancestor = parentOf(ancestor)
        }
        return DirectoryView(sort, grid)
    }

    private val EMPTY = DirectoryView()

    fun save(ctx: Context, dir: XFile, view: DirectoryView) {
        val editor = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (view.sort == null && view.gridMode == null) {
            editor.remove(key(dir))
        } else {
            val json = JSONObject()
            view.sort?.let {
                json.put("sort", JSONObject().put("by", it.by.name)
                    .put("reversed", it.reversed).put("folderBy", it.folderBy.name))
            }
            view.gridMode?.let { json.put("grid", it.coerceIn(0, 2)) }
            if (view.sort != null && view.sortRecursive) json.put("sortRecursive", true)
            if (view.gridMode != null && view.viewRecursive) json.put("viewRecursive", true)
            editor.putString(key(dir), json.toString())
        }
        editor.apply()
    }
}
