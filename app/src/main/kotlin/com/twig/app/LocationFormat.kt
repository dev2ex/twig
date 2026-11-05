package com.twig.app

/**
 * Display format for a network location: `type://server/path` (e.g. `smb://pi/docs/photos`),
 * shared by the path bar, recent locations and directory settings.
 * The server portion uses the alias ([SavedConnection.shortLabel], custom name preferred).
 * When [connLabel] is empty (local), returns the absolute path directly. When [conn] is null
 * the corresponding connection has been deleted — fall back to the frozen connLabel prefix,
 * at least it's still recognisable.
 */
fun formatLocationPath(connLabel: String, path: String, conn: SavedConnection?): String {
    if (connLabel.isEmpty()) return path
    val type = conn?.type ?: connLabel.substringBefore("://")
    val name = conn?.shortLabel() ?: connLabel.substringAfter("://")
    return "$type://$name" + if (path.startsWith("/")) path else "/$path"
}

/**
 * Same as [formatLocationPath], but the server portion never honours custom aliases and
 * always gives the real address ([SavedConnection.rawShortLabel]) — used for the "path"
 * beneath a favourite row and for "show the two directories' paths" in saved comparisons:
 * a path needs to be directly locatable, and the alias belongs on the name side.
 */
fun formatRawLocationPath(connLabel: String, path: String, conn: SavedConnection?): String {
    if (connLabel.isEmpty()) return path
    val type = conn?.type ?: connLabel.substringBefore("://")
    val addr = conn?.rawShortLabel() ?: connLabel.substringAfter("://")
    return "$type://$addr" + if (path.startsWith("/")) path else "/$path"
}

/**
 * Favourite names follow live connection aliases for ordinary network sources. Restic
 * prefixes the target directory or snapshot with its repository name; the subtitle adds context.
 */
fun defaultFavoriteName(fav: Favorite, conn: SavedConnection?): String {
    // ★ Slicing the last segment of path only works for sources whose path is "just a path";
    // SAF's path is an entire document URI, so slicing yields something like
    // `primary%3ADCIM%2FPhotos`, which is why the name is stored alongside when favouriting.
    val itemName = fav.pathName.ifEmpty {
        fav.path.trimEnd('/').substringAfterLast('/').ifEmpty { fav.path }
    }
    return when (fav.kind) {
        "conn" -> "${conn?.displayLabel() ?: fav.connLabel}:$itemName"
        "restic" -> resticLocation(fav, conn).favoriteName()
        else -> itemName
    }
}

/** The name actually shown on a favourite row: the custom name if any, otherwise [defaultFavoriteName]. */
fun favoriteDisplayName(fav: Favorite, conn: SavedConnection?): String =
    fav.customLabel.ifEmpty { defaultFavoriteName(fav, conn) }

/** Favourite subtitle: full address for ordinary sources, repository/snapshot context for restic. */
fun favoriteFullPath(fav: Favorite, conn: SavedConnection?): String = when (fav.kind) {
    "restic" -> resticLocation(fav, conn).compact()
    "saf" -> "saf:" + safReadablePath(fav.path)
    else -> formatRawLocationPath(fav.connLabel, fav.path, conn)
}

/**
 * The human-readable half of a document URI: the document id after `/document/`, decoded
 * to something like `primary:DCIM/Photos`. Laying out the entire URI under a favourite
 * row is long and information-free, while the document id precisely names "which card,
 * which directory". Falls back to the URI as-is when the shape isn't recognised
 * (third-party providers' custom ids).
 *
 * ★ Don't use `URLDecoder`: it decodes `+` to space (that's form encoding's rule, see
 * the S3 lesson), but in document ids `+` is a literal plus sign, so directories whose
 * name contains '+' would get corrupted.
 */
internal fun safReadablePath(uri: String): String =
    percentDecode(uri.substringAfterLast("/document/", "").ifEmpty { uri })

/** Decode only `%XX` (UTF-8 byte grouping), leave `+` alone. */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val hex = if (s[i] == '%' && i + 3 <= s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            out.write(hex); i += 3
        } else {
            out.write(s[i].toString().toByteArray(Charsets.UTF_8)); i++
        }
    }
    return out.toString(Charsets.UTF_8.name())
}
