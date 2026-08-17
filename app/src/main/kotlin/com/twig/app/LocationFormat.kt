package com.twig.app

/**
 * 网络位置的显示格式:`类型:/服务器/路径`(如 `smb:/pi/docs/photos`),与路径栏/最近位置
 * ([com.twig.app.ui.PaneFragment] 的 `historyLabel`)同一写法,服务器部分用别名([SavedConnection.shortLabel]
 * 优先自定义名)。[connLabel] 为空(本地)时直接是绝对路径。[conn] 为 null 表示对应连接
 * 已被删除,退回冻结的 connLabel 头部,至少还认得出来。
 */
fun formatLocationPath(connLabel: String, path: String, conn: SavedConnection?): String {
    if (connLabel.isEmpty()) return path
    val type = conn?.type ?: connLabel.substringBefore("://")
    val name = conn?.shortLabel() ?: connLabel
    return "$type:/$name" + if (path.startsWith("/")) path else "/$path"
}

/**
 * 同 [formatLocationPath],但服务器部分不认自定义别名,永远给真实地址
 * ([SavedConnection.rawShortLabel])——收藏行下方的"路径"与对比收藏的"显示两个目录的
 * 路径"用它:路径要能直接定位,别名放名称那边就够了。
 */
fun formatRawLocationPath(connLabel: String, path: String, conn: SavedConnection?): String {
    if (connLabel.isEmpty()) return path
    val type = conn?.type ?: connLabel.substringBefore("://")
    val addr = conn?.rawShortLabel() ?: connLabel.substringAfter("://")
    return "$type:/$addr" + if (path.startsWith("/")) path else "/$path"
}

/**
 * 收藏行的"名称"——与重命名功能加入前完全一致的老格式(连接别名:条目名)。
 * [conn] 是收藏所在连接当前的已保存配置,取实时别名而不是收藏创建时冻结的旧名。
 */
fun defaultFavoriteName(fav: Favorite, conn: SavedConnection?): String {
    val itemName = fav.path.trimEnd('/').substringAfterLast('/').ifEmpty { fav.path }
    return when (fav.kind) {
        "conn" -> "${conn?.displayLabel() ?: fav.connLabel}:$itemName"
        "restic" -> if (fav.repoConnLabel.isEmpty()) {
            "restic:$itemName"
        } else {
            "${conn?.displayLabel() ?: fav.repoConnLabel}:restic:$itemName"
        }
        else -> itemName
    }
}

/** 收藏行实际展示的名称:重命名过就用自定义名,否则用 [defaultFavoriteName]。 */
fun favoriteDisplayName(fav: Favorite, conn: SavedConnection?): String =
    fav.customLabel.ifEmpty { defaultFavoriteName(fav, conn) }

/** 收藏行下方的完整路径(不用别名,本地/网络都给能直接定位的完整地址)。 */
fun favoriteFullPath(fav: Favorite, conn: SavedConnection?): String = when (fav.kind) {
    "restic" -> "restic:" + formatRawLocationPath(fav.repoConnLabel, fav.path, conn)
    else -> formatRawLocationPath(fav.connLabel, fav.path, conn)
}
