package com.twig.git

/**
 * git-lite 的文件访问抽象:本地用 [LocalGitFs],SMB/WebDAV 等远程由调用方适配。
 * 路径一律 '/' 分隔、相对各自的根("" 表示根本身)。
 */
interface GitFs {
    class Entry(val name: String, val isDir: Boolean, val size: Long, val mtimeSec: Long)

    /** 列目录;不存在返回空表。 */
    fun list(path: String): List<Entry>

    /** 整读文件;不存在返回 null。 */
    fun readBytes(path: String): ByteArray?

    /** 文件/目录 stat;不存在返回 null。 */
    fun stat(path: String): Entry?

    /** 打开定位读(packfile 用);不存在返回 null。 */
    fun openRandom(path: String): GitRandom?
}

/** 定位读源。 */
interface GitRandom : java.io.Closeable {
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    val size: Long
}

/**
 * worktree 的元数据视图:一部分路径落在本工作区的 gitdir([own],
 * `<主仓库>/.git/worktrees/<名>`),其余落在公共目录([common],主仓库的 `.git`)。
 *
 * 分界线按 git 的 "per-worktree file" 定义(见 gitrepository-layout):HEAD、index、
 * ORIG_HEAD、logs/HEAD、rebase 状态、refs/bisect 与 refs/worktree 是每个工作区一份;
 * objects、refs/heads、packed-refs、info/exclude 全局共用。分错的后果很直接——
 * 把 HEAD 读到公共目录就成了主仓库的分支,把 objects 读到 worktree 目录则是**空的**
 * (那目录里根本没有对象库),历史、diff、status 的 HEAD 树会一起变空。
 */
class WorktreeGitFs(private val own: GitFs, val common: GitFs) : GitFs {

    private fun fs(path: String): GitFs = if (perWorktree(path)) own else common

    private fun perWorktree(path: String): Boolean {
        val head = path.substringBefore('/')
        return when (head) {
            "HEAD", "ORIG_HEAD", "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_HEAD",
            "FETCH_HEAD", "index", "commondir", "gitdir", "COMMIT_EDITMSG", "MERGE_MSG",
            "rebase-merge", "rebase-apply", "sequencer",
            -> true
            "refs" -> path.startsWith("refs/bisect") || path.startsWith("refs/worktree") ||
                path.startsWith("refs/rewritten")
            "logs" -> path == "logs/HEAD" || path.startsWith("logs/HEAD/")
            else -> false
        }
    }

    /** 根目录的清单两边合并(own 优先):调用方只在极少数场景列根,合并比二选一诚实。 */
    override fun list(path: String): List<GitFs.Entry> {
        if (path.isNotEmpty()) return fs(path).list(path)
        val out = LinkedHashMap<String, GitFs.Entry>()
        for (e in common.list("")) out[e.name] = e
        for (e in own.list("")) out[e.name] = e
        return out.values.toList()
    }

    override fun readBytes(path: String): ByteArray? = fs(path).readBytes(path)

    override fun stat(path: String): GitFs.Entry? = fs(path).stat(path)

    override fun openRandom(path: String): GitRandom? = fs(path).openRandom(path)
}

/** 本地文件实现;[root] 为该 fs 的根目录。 */
class LocalGitFs(private val root: java.io.File) : GitFs {

    private fun f(p: String) = if (p.isEmpty()) root else java.io.File(root, p)

    override fun list(path: String): List<GitFs.Entry> =
        f(path).listFiles()?.map {
            GitFs.Entry(it.name, it.isDirectory, it.length(), it.lastModified() / 1000)
        } ?: emptyList()

    override fun readBytes(path: String): ByteArray? =
        f(path).takeIf { it.isFile }?.readBytes()

    override fun stat(path: String): GitFs.Entry? =
        f(path).takeIf { it.exists() }?.let {
            GitFs.Entry(it.name, it.isDirectory, it.length(), it.lastModified() / 1000)
        }

    override fun openRandom(path: String): GitRandom? {
        val file = f(path).takeIf { it.isFile } ?: return null
        val raf = java.io.RandomAccessFile(file, "r")
        val len = file.length()
        return object : GitRandom {
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int =
                synchronized(raf) {
                    raf.seek(pos)
                    raf.read(buf, off, len)
                }
            override val size: Long = len
            override fun close() = raf.close()
        }
    }
}
