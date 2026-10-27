package com.twig.app

import android.content.Context
import com.twig.core.FsException
import com.twig.core.XFile
import java.io.File

/** App-owned files, browsed through the ordinary local filesystem. */
object PrivateSpace {
    const val NAME = "private_space"

    fun directory(ctx: Context): File = File(ctx.applicationInfo.dataDir, NAME)

    /** Called on the listing worker, never during tree rendering. */
    fun ensureDirectory(ctx: Context) {
        val dir = directory(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) throw FsException(ctx.getString(R.string.private_space_create_failed))
    }

    /**
     * Android can allow access to our directory without allowing enumeration of
     * its ancestors. These are UI navigation hints only, never filesystem listings.
     */
    fun navigationChild(ctx: Context, parent: String): XFile? = navigationChild(
        File(ctx.applicationInfo.dataDir), parent, File("/data/data", ctx.packageName),
    )

    internal fun navigationChild(own: File, parent: String, alias: File): XFile? {
        val prefix = parent.trimEnd('/') + "/"
        // User 0 normally has a /data/data alias for /data/user/0. Prefer the
        // familiar route only when it is readable and really points to OUR data;
        // work profiles and secondary users must retain their own actual path.
        for (candidate in listOf(alias, own).distinctBy { it.path }) {
            val path = candidate.absolutePath
            if (!path.startsWith(prefix) || path == parent) continue
            if (candidate == alias && candidate != own &&
                (!alias.isDirectory || !alias.canRead() ||
                    !runCatching { alias.canonicalPath == own.canonicalPath }.getOrDefault(false))) continue
            val child = prefix + path.removePrefix(prefix).substringBefore('/')
            return XFile("file", child, isDir = true)
        }
        return null
    }
}
