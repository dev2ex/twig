package com.twig.fs.restic

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream

/**
 * Expose an unlocked restic repository as a read-only filesystem.
 * Paths: `/` = snapshot list; `/<shortId>` = one snapshot root; `/<shortId>/a/b` = file/dir inside a snapshot.
 * Snapshots are shown as "time virtual directories" (displayName = time + host).
 * `/latest` is a permanent virtual alias pointing to the newest snapshot
 * (see [ResticRepo.snapshotByShort]); bookmarking it bookmarks "always the latest backup".
 */
class ResticFileSystem(
    private val repo: ResticRepo,
    override val scheme: String,
) : FileSystem {

    override val displayName: String = "restic backup"
    override fun writable(): Boolean = false

    override fun root(): XFile = XFile(scheme, "/", isDir = true, canWrite = false)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true, canWrite = false)

    override fun list(dir: XFile): List<XFile> {
        val segs = dir.path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) {
            val list = repo.snapshots.map { s ->
                XFile(
                    scheme = scheme,
                    path = "/${s.shortId}",
                    isDir = true,
                    lastModified = s.timeMillis,
                    canWrite = false,
                    displayName = buildString {
                        append(s.timeLabel)
                        if (s.hostname.isNotEmpty()) append(" (").append(s.hostname).append(')')
                    },
                )
            }
            // "latest" virtual directory, permanently points at the first entry of
            // repo.snapshots (see snapshotByShort); bookmarking it means bookmarking
            // "always the latest backup", which does not go stale the way bookmarking
            // a specific snapshot does once a new backup appears.
            // This module is pure JVM/Kotlin with no Android Context/string resources;
            // localized copy does not belong here — displayName stays empty so it falls
            // back to the path's last segment "latest" (a language-independent stable id);
            // the localized text shown to users is handled by :app's PaneViewModel.addRestic()
            // when it recognizes this path.
            val latest = repo.snapshots.firstOrNull()?.let { s ->
                XFile(scheme = scheme, path = "/latest", isDir = true, lastModified = s.timeMillis, canWrite = false)
            }
            return if (latest != null) listOf(latest) + list else list
        }
        val snap = repo.snapshotByShort(segs.first()) ?: throw FsException("restic: no such snapshot")
        val tree = repo.resolveTree(snap, segs.drop(1)) ?: throw FsException("restic: no such path")
        return repo.childrenOfTree(tree)
            .map { n ->
                XFile(
                    scheme = scheme,
                    path = join(dir.path, n.name),
                    isDir = n.isDir,
                    size = if (n.isDir) 0L else n.size,
                    lastModified = n.mtime,
                    canWrite = false,
                )
            }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun openInput(file: XFile): InputStream {
        val segs = file.path.trim('/').split('/').filter { it.isNotEmpty() }
        val snap = repo.snapshotByShort(segs.first()) ?: throw FsException("restic: no such snapshot")
        val node = repo.resolveNode(snap, segs.drop(1)) ?: throw FsException("restic: no such file")
        if (node.isDir) throw FsException("restic: not a file")
        return repo.openFile(node)
    }

    override fun exists(file: XFile): Boolean = true

    // ---- read-only ----
    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        throw FsException("restic backup is read-only")
    override fun mkdir(parent: XFile, name: String): XFile = throw FsException("restic backup is read-only")
    override fun delete(file: XFile): Unit = throw FsException("restic backup is read-only")
    override fun rename(file: XFile, newName: String): XFile = throw FsException("restic backup is read-only")

    private fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
}
