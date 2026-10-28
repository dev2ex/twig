package com.twig.app

/** Stable preference ids for top-level tree entries, shared by both panes. */
object RootItems {
    data class Item(val id: String, val label: Int, val icon: Int, val visibleByDefault: Boolean = true)

    val all = listOf(
        Item("fav", R.string.group_fav, R.drawable.ic_star),
        Item("root", R.string.group_root, R.drawable.ic_storage_root),
        Item("private", R.string.private_space, R.drawable.ic_private_space),
        Item("lan", R.string.group_lan, R.drawable.ic_lan),
        Item("ftp", R.string.group_ftp, R.drawable.ic_ftp),
        Item("sftp", R.string.group_sftp, R.drawable.ic_sftp),
        Item("dav", R.string.group_webdav, R.drawable.ic_webdav),
        Item("s3", R.string.group_s3, R.drawable.ic_s3),
        Item("media", R.string.group_media, R.drawable.ic_media_server),
        Item("saf", R.string.group_saf, R.drawable.ic_document_tree),
        Item("websites", R.string.group_websites, R.drawable.ic_website),
        Item("apps", R.string.apps_root, R.drawable.ic_file_apk),
    )

    private val byId = all.associateBy { it.id }

    /** Ignore stale/duplicate ids and append roots introduced after the saved order. */
    fun ordered(ids: List<String>): List<Item> {
        return (ids + all.map { it.id }).distinct().mapNotNull { byId[it] }
    }
}
