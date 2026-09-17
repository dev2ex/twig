package com.twig.core

import java.io.OutputStream

/**
 * Writing a file so that a failure never costs the copy that was already there.
 *
 * `FileSystem.openOutput` truncates on open in nearly every backend, so "open the target
 * and stream into it" destroys the old content the moment it starts: a dropped connection,
 * a cancelled copy or an upload whose client went away leaves a fragment where a whole
 * file used to be. The shape used here is the one the text editor settled on first
 * (`TextSave`): write a sibling temp file, and only once it is complete delete the old
 * entry and rename the temp into place.
 *
 * Shared by every path that overwrites: the text editor, `CopyEngine` on OVERWRITE, and
 * the WiFi share's uploads (browser POST and WebDAV PUT).
 */
object SafeWrite {

    /** Suffix of the temp sibling. Visible if a replace is interrupted — the user can rename it back. */
    const val PART_SUFFIX = ".twigpart"

    /**
     * The new content is complete in [tmpName], the old entry is already gone, and the
     * final rename failed. The temp file is deliberately **kept** — it is now the only copy.
     */
    class RenameFailed(val tmpName: String, val targetName: String, cause: Throwable) :
        FsException("Saved as $tmpName but could not rename it to $targetName: ${cause.message}", cause)

    /**
     * Writes [name] in [parent] through [write] and returns the written entry.
     *
     * - [existing] null (nothing there): write the target directly; on failure the partial
     *   file is removed. There is nothing to protect, so no temp file.
     * - backend with [FileSystem.atomicOverwrite]: write straight over [existing].
     * - otherwise: temp sibling → delete [existing] → rename. [existing] must be the entry
     *   as the backend listed it — SAF paths are opaque URIs and cannot be rebuilt from a name.
     *
     * [fallbackToDirect]: some shares let a user modify a file but not create one in its
     * directory (SMB's FILE_WRITE_DATA vs FILE_ADD_FILE). When the temp file cannot even be
     * opened, overwrite in place rather than refuse — no worse than before this helper. A
     * failure *while writing* the temp never falls back: the old file is still intact then,
     * and failing is the honest answer.
     *
     * [write] must throw on any failure (including a truncated source); returning normally
     * is taken as "the content is complete".
     */
    fun replace(
        fs: FileSystem,
        parent: XFile,
        name: String,
        existing: XFile?,
        fallbackToDirect: Boolean = true,
        write: (OutputStream) -> Unit,
    ): XFile {
        if (existing == null) {
            val target = fs.createFile(parent, name)
            writeOrDelete(fs, target, write)
            return target
        }
        if (fs.atomicOverwrite()) {
            fs.openOutput(existing, append = false).use(write)
            return existing
        }

        // A leftover from an interrupted attempt needs no cleanup: path-based backends
        // truncate it on open, and SAF simply creates "name (1)" next to it.
        val tmpName = name + PART_SUFFIX
        val opened = runCatching {
            val tmp = fs.createFile(parent, tmpName)
            tmp to fs.openOutput(tmp, append = false)
        }.getOrNull()
        if (opened == null) {
            if (!fallbackToDirect) throw FsException("Cannot create $tmpName next to $name")
            fs.openOutput(existing, append = false).use(write)
            return existing
        }
        val (tmp, out) = opened
        try {
            out.use(write)
        } catch (t: Throwable) {
            runCatching { fs.delete(tmp) }
            throw t
        }

        // ★ From here on tmp is the only complete copy: never delete it again. A failed
        // delete is not fatal by itself — the rename below then fails on the existing
        // target, and that error names the temp file the content is in.
        runCatching { fs.delete(existing) }
        return try {
            fs.rename(tmp, name)
        } catch (t: Throwable) {
            throw RenameFailed(tmp.name, name, t)
        }
    }

    /** Stream into [target]; on any failure remove what was written, then rethrow. */
    fun writeOrDelete(fs: FileSystem, target: XFile, write: (OutputStream) -> Unit) {
        try {
            fs.openOutput(target, append = false).use(write)
        } catch (t: Throwable) {
            runCatching { fs.delete(target) }
            throw t
        }
    }
}
