package com.twig.git

/**
 * File access abstraction for git-lite: local uses [LocalGitFs], while remote backends
 * like SMB/WebDAV are adapted by the caller. Paths are always '/' separated and relative
 * to their root ("" means the root itself).
 */
interface GitFs {
    class Entry(val name: String, val isDir: Boolean, val size: Long, val mtimeSec: Long)

    /** List a directory; returns an empty list if it does not exist. */
    fun list(path: String): List<Entry>

    /** Read an entire file; returns null if it does not exist. */
    fun readBytes(path: String): ByteArray?

    /** Stat a file/directory; returns null if it does not exist. */
    fun stat(path: String): Entry?

    /** Open a seekable read (for packfile use); returns null if it does not exist. */
    fun openRandom(path: String): GitRandom?
}

/** Seekable read source. */
interface GitRandom : java.io.Closeable {
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    val size: Long
}

/**
 * Worktree's metadata view: some paths live in this worktree's gitdir ([own],
 * `<main repo>/.git/worktrees/<name>`), the rest live in the common directory
 * ([common], the main repo's `.git`).
 *
 * The dividing line follows git's "per-worktree file" definition (see gitrepository-layout):
 * HEAD, index, ORIG_HEAD, logs/HEAD, rebase state, refs/bisect and refs/worktree are
 * one-per-worktree; objects, refs/heads, packed-refs and info/exclude are shared globally.
 * Mixing them up has very direct consequences — reading HEAD from the common directory
 * becomes the main repo's branch, and reading objects from the worktree directory is
 * **empty** (there is no object database in that directory), so history, diff and
 * status's HEAD tree will all become empty.
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

    /** The root directory listing is the union of both sides (own wins): callers list the root only in very few scenarios, merging is more honest than choosing one side. */
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

/** Local file implementation; [root] is this fs's root directory. */
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
