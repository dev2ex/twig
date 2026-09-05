package com.twig.git

import java.io.File
import java.security.MessageDigest

/**
 * Read-only git repository parsing: HEAD/refs, commit history, worktree status (changes),
 * commit diff. No locking, no writes, safe to view any repository.
 *
 * File access goes through the [GitFs] abstraction: [metaFs] is rooted at the .git
 * directory, [workFs] at the worktree; local uses [LocalGitFs], SMB/WebDAV etc. are
 * adapted by the caller. When [remote] is true, status uses "size comparison" instead
 * of mtime/SHA (remote stat is unreliable, and per-file hashing is too expensive).
 */
class GitRepo(
    private val metaFs: GitFs,
    private val workFs: GitFs,
    private val remote: Boolean = false,
    /** Resolved `.git`; only with this do we know which worktree we are and where the main worktree is (see [worktrees]). */
    private val dirs: GitDirs? = null,
) : java.io.Closeable {

    private val store = ObjectStore(metaFs)

    private fun readText(path: String): String? =
        metaFs.readBytes(path)?.toString(Charsets.UTF_8)

    // ---- refs / HEAD ----

    /** Current branch name; returns short SHA when detached. */
    fun branch(): String = headBranch(readText("HEAD"))

    /** HEAD file content → branch name; short SHA when detached. */
    private fun headBranch(head: String?): String {
        val h = head?.trim() ?: return "?"
        return if (h.startsWith("ref: ")) h.removePrefix("ref: ").substringAfterLast('/') else h.take(7)
    }

    /** Commit SHA HEAD points to; returns null for an empty repo (no commits). */
    fun headSha(): String? {
        val head = readText("HEAD")?.trim() ?: return null
        if (!head.startsWith("ref: ")) return head.takeIf { it.length == 40 }
        val ref = head.removePrefix("ref: ")
        readText(ref)?.let { return it.trim().take(40) }
        readText("packed-refs")?.lineSequence()?.forEach { l ->
            if (l.startsWith("#") || l.startsWith("^")) return@forEach
            val sp = l.indexOf(' ')
            if (sp == 40 && l.length == sp + 1 + ref.length && l.regionMatches(sp + 1, ref, 0, ref.length)) {
                return l.substring(0, 40)
            }
        }
        return null
    }

    // ---- branches ----

    /** All local branches (refs/heads, including unpacked loose refs and packed-refs), sorted by name. */
    fun branches(): List<GitBranch> {
        val cur = branch()
        val out = LinkedHashMap<String, String>() // name -> sha; loose refs take priority over packed-refs
        collectLooseRefs("refs/heads", out)
        readText("packed-refs")?.lineSequence()?.forEach { l ->
            if (l.startsWith("#") || l.startsWith("^")) return@forEach
            val sp = l.indexOf(' ')
            if (sp != 40) return@forEach
            val ref = l.substring(sp + 1)
            if (ref.startsWith("refs/heads/")) out.putIfAbsent(ref.removePrefix("refs/heads/"), l.substring(0, 40))
        }
        return out.map { (name, sha) -> GitBranch(name, sha, name == cur) }.sortedBy { it.name }
    }

    private fun collectLooseRefs(dir: String, out: MutableMap<String, String>) {
        for (e in metaFs.list(dir)) {
            val rel = "$dir/${e.name}"
            if (e.isDir) collectLooseRefs(rel, out)
            else readText(rel)?.trim()?.takeIf { it.length == 40 }?.let { out[rel.removePrefix("refs/heads/")] = it }
        }
    }

    // ---- worktrees (git worktree) ----

    /**
     * All worktrees linked to this repo, with the main worktree first; the current one
     * is also in the list ([GitWorktree.current]).
     *
     * Data all lives in the common directory's `worktrees/<name>/`: `gitdir` records
     * that worktree's `.git` file's absolute path (removing the last segment gives its
     * working directory), `HEAD` is its own, and `locked` present means it is locked.
     * All these reads go through [metaFs]; [WorktreeGitFs] routes `worktrees/...` to
     * the common directory, so standing at the main repo or inside some worktree you
     * see the same list.
     *
     * The main worktree's path can only be inferred from [dirs] (the parent of the
     * common `.git`); when [dirs] is null or the repo is bare, it isn't listed — there
     * is no "main worktree" in that case.
     */
    fun worktrees(): List<GitWorktree> {
        val out = ArrayList<GitWorktree>()
        val d = dirs
        val common = d?.commonDir
        if (d != null && common != null && common.substringAfterLast('/') == ".git") {
            val path = common.substringBeforeLast('/').ifEmpty { "/" }
            out += GitWorktree(
                name = path.substringAfterLast('/').ifEmpty { path },
                path = path,
                // when standing inside a worktree, metaFs's HEAD is its own; the main worktree's is only in the common directory
                branch = headBranch(commonFs.readBytes("HEAD")?.toString(Charsets.UTF_8)),
                locked = false,
                current = !d.split,
                main = true,
            )
        }
        for (e in metaFs.list("worktrees").sortedBy { it.name }) {
            if (!e.isDir) continue
            val gitFile = readText("worktrees/${e.name}/gitdir")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            out += GitWorktree(
                name = e.name,
                path = gitFile.trimEnd('/').substringBeforeLast('/'), // drop the trailing .git segment
                branch = headBranch(readText("worktrees/${e.name}/HEAD")),
                locked = metaFs.stat("worktrees/${e.name}/locked") != null,
                current = d != null && common != null &&
                    d.gitDir == GitLayout.join(common, "worktrees/${e.name}"),
            )
        }
        return out
    }

    /** Common directory (where objects/refs live); for a plain repo it is [metaFs] itself. */
    private val commonFs: GitFs get() = (metaFs as? WorktreeGitFs)?.common ?: metaFs

    // ---- commit history ----

    fun commit(sha: String): GitCommit? {
        val obj = store.read(sha) ?: return null
        if (obj.type != ObjType.COMMIT) return null
        return parseCommit(sha, obj.data)
    }

    /**
     * Walks history from HEAD in commit time descending order, skipping [skip] entries
     * and returning at most [limit]. All parents (merges) are expanded; used for UI pagination.
     */
    fun log(skip: Int, limit: Int): List<GitCommit> = logFrom(headSha(), skip, limit)

    /** Same as [log], but walks starting from the given commit sha (e.g. some branch tip) instead of HEAD. */
    fun logFrom(startSha: String?, skip: Int, limit: Int): List<GitCommit> {
        val start = startSha ?: return emptyList()
        val out = ArrayList<GitCommit>(limit)
        val seen = HashSet<String>()
        val queue = java.util.PriorityQueue<GitCommit>(compareByDescending { it.timeSec })
        commit(start)?.let { queue.add(it); seen.add(it.sha) }
        var index = 0
        while (queue.isNotEmpty() && out.size < limit) {
            val c = queue.poll()
            if (index++ >= skip) out.add(c)
            for (p in c.parents) {
                if (seen.add(p)) commit(p)?.let { queue.add(it) }
            }
        }
        return out
    }

    private fun parseCommit(sha: String, data: ByteArray): GitCommit {
        val text = String(data, Charsets.UTF_8)
        val blank = text.indexOf("\n\n")
        val headers = if (blank >= 0) text.substring(0, blank) else text
        val message = if (blank >= 0) text.substring(blank + 2) else ""
        var tree = ""; val parents = ArrayList<String>(2)
        var author = ""; var email = ""; var time = 0L
        for (line in headers.lineSequence()) {
            when {
                line.startsWith("tree ") -> tree = line.substring(5).trim()
                line.startsWith("parent ") -> parents.add(line.substring(7).trim())
                line.startsWith("author ") -> {
                    // author Name <email> 1699999999 +0800
                    val lt = line.indexOf('<'); val gt = line.indexOf('>')
                    if (lt > 0 && gt > lt) {
                        author = line.substring(7, lt).trim()
                        email = line.substring(lt + 1, gt)
                        time = line.substring(gt + 1).trim().substringBefore(' ').toLongOrNull() ?: 0L
                    }
                }
            }
        }
        return GitCommit(sha, parents, tree, author, email, time, message.trimEnd('\n'))
    }

    // ---- tree / blob / diff ----

    /** Flattens a tree: relative path → blob SHA (skips submodules). */
    fun flattenTree(treeSha: String, prefix: String = "", out: MutableMap<String, String> = HashMap()): Map<String, String> {
        val obj = store.read(treeSha) ?: return out
        if (obj.type != ObjType.TREE) return out
        val d = obj.data
        var i = 0
        while (i < d.size) {
            val sp = indexOf(d, ' '.code.toByte(), i)
            val nul = indexOf(d, 0, sp)
            val mode = String(d, i, sp - i, Charsets.US_ASCII)
            val name = String(d, sp + 1, nul - sp - 1, Charsets.UTF_8)
            val sha = ObjectStore.bytesToHex(d.copyOfRange(nul + 1, nul + 21))
            i = nul + 21
            when {
                mode == "40000" -> flattenTree(sha, "$prefix$name/", out)
                mode == "160000" -> Unit // submodule
                else -> out["$prefix$name"] = sha
            }
        }
        return out
    }

    /** Read blob content; returns null for non-blob or missing. */
    fun readBlob(sha: String): ByteArray? =
        store.read(sha)?.takeIf { it.type == ObjType.BLOB }?.data

    /** Changes of a commit relative to its first parent (for a root commit, all entries are added). */
    fun diff(c: GitCommit): List<GitChange> {
        val cur = flattenTree(c.tree)
        val parent = c.parents.firstOrNull()?.let { commit(it) }
        val par = parent?.let { flattenTree(it.tree) } ?: emptyMap()
        val out = ArrayList<GitChange>()
        for ((path, sha) in cur) {
            val old = par[path]
            when {
                old == null -> out.add(GitChange(path, ChangeKind.ADDED))
                old != sha -> out.add(GitChange(path, ChangeKind.MODIFIED))
            }
        }
        for (path in par.keys) if (path !in cur) out.add(GitChange(path, ChangeKind.DELETED))
        out.sortBy { it.path }
        return out
    }

    private fun indexOf(d: ByteArray, b: Byte, from: Int): Int {
        var i = from
        while (i < d.size && d[i] != b) i++
        return i
    }

    // ---- status ----

    /** Index content (path → entry), used by status and content reads. */
    fun indexEntries(): Map<String, IndexEntry> =
        IndexFile.read(metaFs.readBytes("index"))

    /**
     * Computes worktree status.
     * - staged: HEAD tree vs index
     * - unstaged: index vs worktree (local: matching mtime+size is treated as clean,
     *   compute SHA when in doubt; remote: size comparison only — remote stat is
     *   unreliable and per-file hashing is too expensive)
     * - untracked: not in index and not excluded by .gitignore; when a whole directory
     *   is untracked, fold it as "dir/"
     */
    fun status(): GitStatus {
        val index = indexEntries()
        val headTree: Map<String, String> =
            headSha()?.let { commit(it) }?.let { flattenTree(it.tree) } ?: emptyMap()

        val staged = ArrayList<GitChange>()
        for ((path, sha) in headTree) {
            val e = index[path]
            when {
                e == null -> staged.add(GitChange(path, ChangeKind.DELETED))
                e.sha != sha -> staged.add(GitChange(path, ChangeKind.MODIFIED))
            }
        }
        for (path in index.keys) {
            if (path !in headTree) staged.add(GitChange(path, ChangeKind.ADDED))
        }

        val unstaged = ArrayList<GitChange>()
        for ((path, e) in index) {
            val st = workFs.stat(path)
            when {
                st == null || st.isDir -> unstaged.add(GitChange(path, ChangeKind.DELETED))
                remote -> if (st.size != e.size) unstaged.add(GitChange(path, ChangeKind.MODIFIED))
                st.mtimeSec == e.mtimeSec && st.size == e.size -> Unit // matching stat is treated as clean
                blobSha(path, st.size) != e.sha -> unstaged.add(GitChange(path, ChangeKind.MODIFIED))
            }
        }

        val untracked = ArrayList<String>()
        collectUntracked("", Ignore.forDir(metaFs, workFs, "", null), index.keys, untracked)

        val cmp = compareBy<GitChange> { it.path }
        return GitStatus(branch(), staged.sortedWith(cmp), unstaged.sortedWith(cmp), untracked.sorted())
    }

    private fun collectUntracked(
        prefix: String,
        ignore: Ignore,
        indexed: Set<String>,
        out: MutableList<String>,
    ) {
        for (e in workFs.list(prefix.trimEnd('/'))) {
            val rel = "$prefix${e.name}"
            if (e.name == ".git") continue
            if (ignore.matches(rel, e.isDir)) continue
            if (e.isDir) {
                // directory has no tracked file under it → fold the whole directory as "dir/"
                val tracked = indexed.any { it.startsWith("$rel/") }
                if (!tracked) {
                    if (hasAnyVisibleFile(rel, ignore)) out.add("$rel/")
                } else {
                    collectUntracked("$rel/", Ignore.forDir(metaFs, workFs, rel, ignore), indexed, out)
                }
            } else if (rel !in indexed) {
                out.add(rel)
            }
        }
    }

    private fun hasAnyVisibleFile(rel: String, ignore: Ignore): Boolean {
        for (e in workFs.list(rel)) {
            val r = "$rel/${e.name}"
            if (ignore.matches(r, e.isDir)) continue
            if (!e.isDir) return true
            if (hasAnyVisibleFile(r, ignore)) return true
        }
        return false
    }

    /** Computes a worktree file's SHA-1 by git blob rule ("blob <len>\0" + content). */
    private fun blobSha(path: String, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob $size".toByteArray(Charsets.US_ASCII))
        md.update(0)
        md.update(workFs.readBytes(path) ?: ByteArray(0))
        return ObjectStore.bytesToHex(md.digest())
    }

    /** Read worktree file content (may be null). */
    fun readWorkFile(path: String): ByteArray? = workFs.readBytes(path)

    override fun close() = store.close()

    companion object {
        /** Opens the repo at a local directory; returns null if it isn't a git worktree. Supports worktree/submodule where .git is a file. */
        fun open(dir: File): GitRepo? {
            val dirs = layout(dir) ?: return null
            if (!File(dirs.gitDir, "HEAD").isFile) return null
            val meta =
                if (dirs.split) WorktreeGitFs(LocalGitFs(File(dirs.gitDir)), LocalGitFs(File(dirs.commonDir)))
                else LocalGitFs(File(dirs.gitDir))
            return GitRepo(meta, LocalGitFs(dir), remote = false, dirs = dirs)
        }

        /** Whether the directory is a git worktree root (local); for worktree/submodule the gitdir must actually resolve. */
        fun isRepo(dir: File): Boolean =
            layout(dir)?.let { File(it.gitDir, "HEAD").isFile } ?: false

        private fun layout(dir: File): GitDirs? = GitLayout.resolve(
            dir.path,
            isDir = { File(it).isDirectory },
            readText = { p -> File(p).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } },
        )
    }
}
