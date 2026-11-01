package com.twig.app

/** Stable identities for configurable actions; pinned landscape navigation stays available. */
object StripItems {
    data class Item(val id: String, val viewId: Int, val label: Int, val icon: Int, val visibleByDefault: Boolean = true)

    val all = listOf(
        Item("up", R.id.as_up, R.string.strip_up, R.drawable.ic_parent_directory),
        Item("sort", R.id.as_sort, R.string.action_sort, R.drawable.ic_sort),
        Item("refresh", R.id.as_refresh, R.string.strip_refresh, R.drawable.ic_refresh),
        Item("new_folder", R.id.as_new_folder, R.string.strip_new_folder, R.drawable.ic_new_folder),
        Item("copy", R.id.as_copy, R.string.strip_copy, R.drawable.ic_copy),
        Item("duplicate", R.id.as_duplicate, R.string.strip_duplicate, R.drawable.ic_duplicate, visibleByDefault = false),
        Item("move", R.id.as_move, R.string.strip_move, R.drawable.ic_move),
        Item("compress", R.id.as_compress, R.string.strip_compress, R.drawable.ic_compress),
        Item("rename", R.id.as_rename, R.string.strip_rename, R.drawable.ic_rename),
        Item("delete", R.id.as_delete, R.string.strip_delete, R.drawable.ic_delete),
        Item("info", R.id.as_info, R.string.file_info, R.drawable.ic_info, visibleByDefault = false),
        Item("compare", R.id.as_compare, R.string.strip_compare, R.drawable.ic_compare),
        Item("map", R.id.as_map, R.string.strip_map, R.drawable.ic_treemap),
        Item("search", R.id.as_search, R.string.strip_search, R.drawable.ic_search),
        Item("history", R.id.as_history, R.string.strip_history, R.drawable.ic_history),
        Item("share", R.id.as_share, R.string.strip_share, R.drawable.ic_share_wifi),
        Item("exit", R.id.as_exit, R.string.action_exit, R.drawable.ic_exit, visibleByDefault = false),
    )
    private val byId = all.associateBy { it.id }

    fun ordered(ids: List<String>): List<Item> =
        (ids + all.map { it.id }).distinct().mapNotNull { byId[it] }
}
