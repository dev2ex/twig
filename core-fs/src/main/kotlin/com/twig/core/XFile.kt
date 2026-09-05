package com.twig.core

/**
 * A unified "entry" — could be a local file, an item inside an archive, a file on
 * FTP, or an object on some cloud drive.
 *
 * For the UI, every source is an [XFile]; which [FileSystem] interprets it is
 * decided by [scheme]. Design goals: lightweight, immutable, holds no IO resources
 * (request streams from [FileSystem] only when actually reading or writing).
 */
data class XFile(
    /** The scheme of the owning file system, e.g. "file" / "zip" / "ftp". */
    val scheme: String,
    /**
     * Absolute path within that file system. Convention: '/' is the separator, '/' is
     * the root. Note: this `path` does not include the scheme prefix; the scheme is
     * stored separately so each FileSystem can interpret it in its own way.
     */
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    /** Whether it can be read / written; the UI can grey out operations accordingly. true when unknown. */
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    /**
     * Display name. When [path] is an opaque identifier (e.g. a SAF document URI or
     * a cloud drive object id) from which no human-readable name can be sliced, the
     * FileSystem fills this in from metadata; when null, fall back to the last
     * segment of `path`.
     */
    val displayName: String? = null,
) {
    /** File name; prefer [displayName], otherwise the last segment of `path`; returns "/" for the root. */
    val name: String
        get() {
            displayName?.let { return it }
            if (path == "/" || path.isEmpty()) return "/"
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx < 0) trimmed else trimmed.substring(idx + 1)
        }

    /** Parent directory path; the parent of root is still root. */
    val parentPath: String
        get() {
            if (path == "/" || path.isEmpty()) return "/"
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx <= 0) "/" else trimmed.substring(0, idx)
        }

    /** Extension (lowercase, without the dot); empty string if no extension. */
    val extension: String
        get() {
            val n = name
            val dot = n.lastIndexOf('.')
            return if (dot <= 0) "" else n.substring(dot + 1).lowercase()
        }

    /** Full URI form, e.g. "file:///sdcard/a.txt"; used for logs and navigation history. */
    fun toUri(): String = "$scheme://$path"
}

/**
 * Whether this is a "real writable directory" that can be used as the destination of
 * copy / move / share: the entry's own [XFile.canWrite] (computed by resolve()/list(),
 * unreliable in scenarios like favorites that bypass them and assemble XFile
 * directly) + the owning [FileSystem.writable] (the fallback for "does this whole
 * source support writing", which is what keeps sources that are read-only end-to-end
 * such as restic / 7z / RAR / git view out).
 */
fun XFile.isWritableDir(): Boolean =
    isDir && canWrite && runCatching { FsRegistry.of(this).writable() }.getOrDefault(false)

/**
 * Whether this entry **itself** can be modified — renamed, deleted, and the
 * "delete source" step of a move.
 *
 * This is a different question from [isWritableDir]: that one asks "can I write
 * into it" (the destination of paste / new file); this one asks "can it itself
 * be changed" (the source of an operation). **Read-only sources may still be
 * copied, compressed, and shared — that limitation does not apply to them.**
 *
 * Sources like media servers, restic, 7z/RAR, the git view, and "Apps" report
 * `writable() == false`; these entry points should not be offered in the UI —
 * offering them just lets the user tap something that fails with an error.
 */
fun XFile.isMutable(): Boolean =
    canWrite && runCatching { FsRegistry.of(this).writable() }.getOrDefault(false)
