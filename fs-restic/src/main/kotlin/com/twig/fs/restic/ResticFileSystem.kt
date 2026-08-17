package com.twig.fs.restic

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream

/**
 * 把一个已解锁的 restic 仓库当作只读文件系统。
 * 路径:`/` = 快照列表;`/<shortId>` = 某快照根;`/<shortId>/a/b` = 快照内目录/文件。
 * 快照以"时间虚拟目录"呈现(displayName = 时间 + 主机)。`/latest` 是恒指向最新快照的
 * 虚拟别名(见 [ResticRepo.snapshotByShort]),收藏它就能收藏"永远最新的备份"。
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
            // "最新"虚拟目录,恒指向 repo.snapshots 首项(见 snapshotByShort);收藏它即收藏
            // "永远最新的备份",不会像收藏某个具体快照那样在新备份产生后就过时。
            // 这个模块是纯 JVM/Kotlin,没有 Android Context/字符串资源可用,不在这里挂多语言
            // 文案——displayName 留空回退到 path 末段 "latest"(语言无关的稳定标识);
            // 真正展示给用户的本地化文案由 :app 的 PaneViewModel.addRestic() 按此路径识别后接管。
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

    // ---- 只读 ----
    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        throw FsException("restic backup is read-only")
    override fun mkdir(parent: XFile, name: String): XFile = throw FsException("restic backup is read-only")
    override fun delete(file: XFile): Unit = throw FsException("restic backup is read-only")
    override fun rename(file: XFile, newName: String): XFile = throw FsException("restic backup is read-only")

    private fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
}
