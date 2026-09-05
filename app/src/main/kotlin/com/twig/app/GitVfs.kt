package com.twig.app

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.git.ChangeKind
import com.twig.git.GitBranch
import com.twig.git.GitChange
import com.twig.git.GitCommit
import com.twig.git.GitData
import com.twig.git.GitFs
import com.twig.git.GitRandom
import com.twig.git.GitStatus
import com.twig.git.GitWorktree
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Adapt any core-fs source (SMB/WebDAV/local…) as git-lite's [GitFs]; [base] is the root. */
class XFileGitFs(private val base: XFile) : GitFs {

    private val fs = FsRegistry.of(base)
    private val listCache = HashMap<String, List<XFile>>() // directory listing cache (reused within one resolve session)

    private fun join(p: String): String {
        if (p.isEmpty()) return base.path
        val sep = if (base.path.endsWith("/")) "" else "/"
        return "${base.path}$sep$p"
    }

    @Synchronized
    private fun kids(dirRel: String): List<XFile> = listCache.getOrPut(dirRel) {
        runCatching { fs.list(XFile(base.scheme, join(dirRel), isDir = true)) }.getOrDefault(emptyList())
    }

    override fun list(path: String): List<GitFs.Entry> =
        kids(path).map { GitFs.Entry(it.name, it.isDir, it.size, it.lastModified / 1000) }

    override fun stat(path: String): GitFs.Entry? {
        if (path.isEmpty()) return GitFs.Entry(base.name, true, 0, 0)
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return kids(dir).firstOrNull { it.name == name }
            ?.let { GitFs.Entry(it.name, it.isDir, it.size, it.lastModified / 1000) }
    }

    override fun readBytes(path: String): ByteArray? {
        val st = stat(path) ?: return null
        if (st.isDir) return null
        val x = XFile(base.scheme, join(path), isDir = false, size = st.size)
        return runCatching { fs.openInput(x).use { OpenFiles.readAllBytes(it, 1 shl 18) } }.getOrNull()
    }

    override fun openRandom(path: String): GitRandom? {
        val st = stat(path) ?: return null
        val x = XFile(base.scheme, join(path), isDir = false, size = st.size)
        val src = runCatching { fs.openRandom(x) }.getOrNull() ?: return null
        return object : GitRandom {
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int =
                src.readAt(pos, buf, off, len)
            override val size: Long = if (st.size > 0) st.size else src.length()
            override fun close() {
                runCatching { src.close() }
            }
        }
    }
}

/**
 * Open a git repository inside a remote (SMB/WebDAV/SFTP without git) directory; returns
 * null when it isn't a repository.
 *
 * `.git` can be either a directory or a worktree/submodule `gitdir: <path>` file — for
 * the latter the metadata splits into gitdir (HEAD/index) + commondir (objects/refs);
 * see [com.twig.git.GitLayout] and [com.twig.git.WorktreeGitFs].
 */
/**
 * Fetch the entry at [path]: **list the parent directory and find by name**, don't use
 * `resolve()`; returns null when not present.
 *
 * ★ Don't take the shortcut here and use [FileSystem.resolve]: `SftpFileSystem`'s
 * implementation is `XFile(scheme, path, isDir = true)` — it doesn't stat, doesn't error,
 * accepts everything and labels it a directory. Using it to test existence / type treats
 * a worktree's `.git` **file** as a directory and not a single byte can be read (the
 * symptom is exactly "Worktree (N)" expands into nothing).
 */
private fun statEntry(fs: FileSystem, path: String): XFile? {
    val p = path.trimEnd('/').ifEmpty { "/" }
    if (p == "/") return runCatching { fs.root() }.getOrNull()
    val parent = p.substringBeforeLast('/', "").ifEmpty { "/" }
    val name = p.substringAfterLast('/')
    return runCatching { fs.list(XFile(fs.scheme, parent, isDir = true)) }.getOrNull()
        ?.firstOrNull { it.name == name }
}

/** Read a small text file (`.git`, `commondir` — all single-line); returns null when unreadable. */
private fun readSmallText(fs: FileSystem, path: String): String? {
    val x = statEntry(fs, path)?.takeIf { !it.isDir } ?: return null
    return runCatching { fs.openInput(x).use { OpenFiles.readAllBytes(it, 4096) }.decodeToString() }.getOrNull()
}

fun openRemoteRepo(dir: XFile): com.twig.git.GitRepo? {
    val host = FsRegistry.of(dir)
    val dirs = com.twig.git.GitLayout.resolve(
        dir.path,
        isDir = { p -> statEntry(host, p)?.isDir == true },
        readText = { p -> readSmallText(host, p) },
    ) ?: return null
    fun at(path: String) = XFileGitFs(XFile(dir.scheme, path, isDir = true))
    val meta =
        if (dirs.split) com.twig.git.WorktreeGitFs(at(dirs.gitDir), at(dirs.commonDir))
        else at(dirs.gitDir)
    return com.twig.git.GitRepo(meta, XFileGitFs(dir), remote = true, dirs = dirs)
}

/**
 * Build a [GitData] for a repository directory; returns null when it isn't a repository.
 * Second value: whether the data source is "cheap" (local / SSH-remote-exec, see
 * `PaneViewModel.cheapGitSchemes`).
 *
 * Kept here rather than in `PaneViewModel` because [GitFileSystem] needs the same
 * judgement when listing "worktrees" to build a data source for **another** worktree.
 */
fun gitDataFor(dir: XFile): Pair<GitData, Boolean>? {
    if (dir.scheme == "file") {
        return com.twig.git.GitRepo.open(java.io.File(dir.path))?.let { com.twig.git.RepoGitData(it) to true }
    }
    val hostFs = FsRegistry.of(dir)
    // SFTP: when the server has a git binary, run it remotely (compute on the server, an order of magnitude faster)
    if (hostFs is com.twig.fs.network.SftpFileSystem) {
        // ★ The repository has to be addressed by its **path on the server**: an SFTP
        // connection may be rooted at a sub-directory, in which case the path on screen
        // is not the one `git -C` needs, and the check below would simply never match.
        val real = hostFs.serverPath(dir.path)
        val q = "'" + real.replace("'", "'\\''") + "'"
        if (hostFs.exec("git -C $q rev-parse --is-inside-work-tree") != null) {
            // …and the paths git prints back have to make the same trip in reverse,
            // or "Worktrees (n)" expands into nothing (see SshGitData.worktrees).
            return SshGitData({ cmd -> hostFs.exec(cmd) }, real, { p -> hostFs.visiblePath(p) }) to true
        }
    }
    // Other remote sources (SMB/WebDAV/SFTP without git): parse .git ourselves
    return openRemoteRepo(dir)?.let { com.twig.git.RepoGitData(it) to false }
}

/**
 * Expose a git repository as a read-only virtual filesystem, mounted directly into the tree:
 * ```
 * /                    Changes (N) | Other branches | Worktrees (n) | History
 * /changes             Staged (n) | Unstaged (n) | Untracked (n)
 * /changes/<group>     Changed file (A/M/D marker; click to see content)
 * /worktrees           **Other** worktrees (git worktree); each is the root of another GitFileSystem
 * /history             Last 200 commits
 * /history/<sha>       · commit info + files changed in that commit
 * ```
 */
class GitFileSystem(
    private val ctx: android.content.Context,
    private val data: GitData,
    override val scheme: String,
    private val initialLabel: String, // tree-root display name at registration, e.g. "Git (main)"; see below after switching branches
    /**
     * The working directory of this repository (a local file:// path or a path on some
     * server). Used to map absolute paths recorded by the repository (on another
     * machine) back to a path reachable here. **When null, "Worktrees" is not listed** —
     * when entering another worktree we construct an instance with null, otherwise A→B→A
     * would recurse forever.
     */
    private val host: XFile? = null,
) : FileSystem {

    /** Tree node names and error copy all go through string resources (this layer lives in :app and has a Context). */
    private fun s(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    /**
     * The tree root's display name follows the current branch; reads [branchCache] (memory
     * only, safe to call from UI thread's rebuild() without triggering I/O); falls back to
     * the initial value when the cache hasn't been populated yet.
     * ★ Don't borrow the branch name from statusCache — that only gets filled by
     * list("/") / list("/changes*"); opening "History" or "Other branches" alone just
     * invalidate()s the cache and doesn't re-fill status, so the title would fall back to
     * the frozen initialLabel (symptom: "tap History / branches and the title reverts to
     * the old branch name").
     * branchCache is refreshed uniformly by [invalidate], regardless of whether this
     * fresh is for status / log / branches, the title always tracks the latest.
     */
    override val displayName: String get() = branchCache?.let { "Git ($it)" } ?: initialLabel
    override fun writable(): Boolean = false

    // IO thread writes in invalidate(), main thread's displayName reads without sync — must be volatile for visibility
    @Volatile private var branchCache: String? = null
    private var statusCache: Pair<Long, GitStatus>? = null
    private var logCache: List<GitCommit>? = null
    private var branchesCache: List<GitBranch>? = null
    private var worktreesCache: List<GitWorktree>? = null
    private val branchLogCache = HashMap<String, List<GitCommit>>() // branch tip sha -> history of that branch
    private val diffCache = HashMap<String, List<GitChange>>()

    @Synchronized
    private fun statusNow(): GitStatus {
        val c = statusCache
        val now = System.currentTimeMillis()
        if (c != null && now - c.first < STATUS_TTL_MS) return c.second
        return data.status().also { statusCache = now to it }
    }

    @Synchronized
    private fun logNow(): List<GitCommit> =
        logCache ?: data.log(0, LOG_LIMIT).also { logCache = it }

    @Synchronized
    private fun branchesNow(): List<GitBranch> =
        branchesCache ?: data.branches().also { branchesCache = it }

    @Synchronized
    private fun branchLogNow(sha: String): List<GitCommit> =
        branchLogCache.getOrPut(sha) { data.logRef(sha, 0, LOG_LIMIT) }

    @Synchronized
    private fun worktreesNow(): List<GitWorktree> = worktreesCache ?: run {
        val list =
            if (host == null) emptyList()
            else runCatching { data.worktrees() }.getOrDefault(emptyList())
        list.also { worktreesCache = it }
    }

    /** Worktrees other than this one — ours is the view in front of us, listing it would just be a duplicate. */
    private fun otherWorktrees(): List<GitWorktree> = worktreesNow().filter { !it.current }

    /**
     * A worktree's entry in the tree: it is itself **a whole other git view**, so we build
     * a [GitFileSystem] on demand, register it as its own scheme, and return its root.
     * That way the changes / history / diff logic is reused as-is without a single change
     * (archive mounts use the same trick: children belong to a different FileSystem).
     * Returns null when the directory is unreachable from this side (deleted or not
     * mounted) — listing an entry that can't be opened is worse.
     */
    private fun worktreeChild(w: GitWorktree): XFile? {
        val dir = worktreeDir(w) ?: return null
        val sub = "${scheme}w${Integer.toHexString(dir.path.hashCode())}"
        val label = buildString {
            append(w.name).append(" (").append(w.branch).append(')')
            if (w.main) append(" · ").append(s(R.string.git_worktree_main))
            if (w.locked) append(" · ").append(s(R.string.git_worktree_locked))
        }
        if (runCatching { FsRegistry.of(sub) }.isFailure) {
            val d = runCatching { gitDataFor(dir) }.getOrNull()?.first ?: return null
            // pass host = null: that view won't list worktrees itself, otherwise A→B→A recurses forever
            FsRegistry.register(GitFileSystem(ctx, d, sub, label))
        }
        return XFile(sub, "/", isDir = true, displayName = label, canWrite = false)
    }

    /**
     * Map the worktree path recorded inside the repository into an [XFile] reachable
     * from this side; returns null when mapping fails (directory deleted or out of mount
     * range).
     *
     * Local and SSH record the real path and use it directly. SMB/WebDAV don't work that
     * way: those are absolute paths on the **machine where the worktree was created**,
     * and all we see here is one of its subtrees. In that case we anchor on the main
     * worktree's path here and try progressively longer suffixes from the **shortest tail**
     * upward (`…/repo/nested/wt` → `wt`, `nested/wt`, …), and verify each candidate's
     * `.git` actually points to `worktrees/<name>` to avoid mistaking a same-named
     * unrelated directory — checking "directory exists" alone would accept those.
     */
    private fun worktreeDir(w: GitWorktree): XFile? {
        val h = host ?: return null
        val fs = runCatching { FsRegistry.of(h) }.getOrNull() ?: return null
        fun dirAt(path: String): XFile? = statEntry(fs, path)
            ?.takeIf { it.isDir }?.let { XFile(h.scheme, path, isDir = true) }
        // The main worktree's path is itself derived here (parent of the shared .git), no mapping needed
        if (w.main) return dirAt(w.path)
        // If the recorded path lands directly (local/SSH: it's the real path), use it without further checks
        dirAt(w.path)?.let { return it }
        val mainRoot = worktreesNow().firstOrNull { it.main }?.path ?: return null
        val segs = w.path.split('/').filter { it.isNotEmpty() }
        for (i in segs.indices.reversed()) {
            val cand = dirAt(com.twig.git.GitLayout.join(mainRoot, segs.subList(i, segs.size).joinToString("/")))
                ?: continue
            if (isWorktreeOf(fs, cand, w.name)) return cand
        }
        return null
    }

    /** Whether [dir]'s `.git` is exactly the `worktrees/<[name]>` entry. */
    private fun isWorktreeOf(fs: FileSystem, dir: XFile, name: String): Boolean {
        val sep = if (dir.path.endsWith("/")) "" else "/"
        val text = readSmallText(fs, "${dir.path}$sep.git")?.trim() ?: return false
        return text.trimEnd('/').endsWith("worktrees/$name")
    }

    private fun diffOf(sha: String): List<GitChange> {
        synchronized(diffCache) { diffCache[sha]?.let { return it } }
        val d = data.diff(sha)
        synchronized(diffCache) { diffCache[sha] = d }
        return d
    }

    override fun root(): XFile = XFile(scheme, "/", isDir = true, displayName = displayName, canWrite = false)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true, canWrite = false)

    override fun list(dir: XFile): List<XFile> = when {
        dir.path == "/" -> {
            val st = statusNow()
            val n = st.staged.size + st.unstaged.size + st.untracked.size
            buildList {
                add(dirX("/changes", s(R.string.git_changes_n, n)))
                add(dirX("/branches", s(R.string.git_other_branches)))
                // Same reasoning as "other branches": this one is already in front of you, only list
// it when there are other worktrees.
                val wt = otherWorktrees()
                if (wt.isNotEmpty()) add(dirX("/worktrees", s(R.string.git_worktrees_n, wt.size)))
                add(dirX("/history", s(R.string.git_history)))
            }
        }
        dir.path == "/worktrees" -> otherWorktrees().mapNotNull { worktreeChild(it) }
        dir.path == "/changes" -> {
            val st = statusNow()
            buildList {
                if (st.staged.isNotEmpty()) add(dirX("/changes/staged", s(R.string.git_staged, st.staged.size)))
                if (st.unstaged.isNotEmpty()) add(dirX("/changes/unstaged", s(R.string.git_unstaged, st.unstaged.size)))
                if (st.untracked.isNotEmpty()) add(dirX("/changes/untracked", s(R.string.git_untracked, st.untracked.size)))
            }
        }
        dir.path == "/changes/staged" -> statusNow().staged.map { changeX("staged", it) }
        dir.path == "/changes/unstaged" -> statusNow().unstaged.map { changeX("unstaged", it) }
        dir.path == "/changes/untracked" -> statusNow().untracked.map {
            XFile(scheme, "/changes/untracked/$it", isDir = false, displayName = "? $it", canWrite = false)
        }
        // The current branch's commits are already visible under "History"; this only lists other branches, not itself
        dir.path == "/branches" -> branchesNow().filter { !it.current }.map { b ->
            dirX("/branches/${encodeBranch(b.name)}", b.name)
        }
        dir.path.startsWith("/branches/") -> {
            val name = decodeBranch(dir.path.removePrefix("/branches/"))
            val sha = branchesNow().firstOrNull { it.name == name }?.sha
            // Commits under a branch reuse the /history/<sha> virtual path (content / diff logic shared with zero changes)
            if (sha == null) emptyList() else branchLogNow(sha).map { historyEntry(it) }
        }
        dir.path == "/history" -> logNow().map { historyEntry(it) }
        isCommitDir(dir.path) -> {
            val sha = dir.path.removePrefix("/history/")
            val c = data.commit(sha)
            buildList {
                add(
                    XFile(
                        scheme, "/history/$sha/$INFO", isDir = false,
                        displayName = s(R.string.git_commit_info), canWrite = false,
                        lastModified = (c?.timeSec ?: 0) * 1000,
                    ),
                )
                diffOf(sha).forEach {
                    add(
                        XFile(
                            scheme, "/history/$sha/${it.path}", isDir = false,
                            displayName = "${mark(it.kind)} ${it.path}", canWrite = false,
                        ),
                    )
                }
            }
        }
        else -> emptyList()
    }

    override fun openInput(file: XFile): InputStream {
        val p = file.path
        val bytes: ByteArray = when {
            p.startsWith("/changes/") -> {
                val rest = p.removePrefix("/changes/")
                val group = rest.substringBefore('/')
                val rel = rest.substringAfter('/')
                when (group) {
                    "staged" -> data.content("INDEX", rel)
                        ?: data.content("WORK", rel) ?: deletedNote()
                    else -> data.content("WORK", rel) ?: deletedNote()
                }
            }
            p.startsWith("/history/") -> {
                val rest = p.removePrefix("/history/")
                val sha = rest.substringBefore('/')
                val rel = rest.substringAfter('/', "")
                val c = data.commit(sha) ?: throw FsException(s(R.string.git_commit_missing))
                if (rel == INFO || rel.isEmpty()) {
                    commitInfo(c).toByteArray(Charsets.UTF_8)
                } else {
                    // The version in that commit; for deleted files, give the version in the parent commit
                    data.content(sha, rel) ?: data.content("$sha^", rel) ?: deletedNote()
                }
            }
            else -> throw FsException(s(R.string.git_unreadable, p))
        }
        return bytes.inputStream()
    }

    /**
     * Diff side content (old, new) for the given virtual path; null side means that side
     * doesn't exist (additions / deletions). Returns null for paths that can't be diffed
     * (#info, directories, etc.).
     */
    fun diffSides(path: String): Pair<ByteArray?, ByteArray?>? = when {
        path.startsWith("/changes/") -> {
            val rest = path.removePrefix("/changes/")
            val group = rest.substringBefore('/')
            val rel = rest.substringAfter('/', "")
            if (rel.isEmpty() || rel.endsWith("/")) null else {
                val headBlob = data.content("HEAD", rel)
                val indexBlob = data.content("INDEX", rel)
                when (group) {
                    "staged" -> headBlob to indexBlob
                    "unstaged" -> (indexBlob ?: headBlob) to data.content("WORK", rel)
                    "untracked" -> null to data.content("WORK", rel)
                    else -> null
                }
            }
        }
        path.startsWith("/history/") -> {
            val rest = path.removePrefix("/history/")
            val sha = rest.substringBefore('/')
            val rel = rest.substringAfter('/', "")
            if (rel.isEmpty() || rel == INFO) null else {
                val newBlob = data.content(sha, rel)
                val oldBlob = data.content("$sha^", rel)
                oldBlob to newBlob
            }
        }
        else -> null
    }

    private fun commitInfo(c: GitCommit): String = buildString {
        append("commit ").append(c.sha).append('\n')
        append(c.author).append(" <").append(c.email).append(">\n")
        append(dateFmt().format(Date(c.timeSec * 1000))).append('\n')
        if (c.parents.isNotEmpty()) append("parent ").append(c.parents.joinToString(" ") { it.take(7) }).append('\n')
        append('\n').append(c.message).append('\n')
    }

    /** Placeholder content when the file has been deleted in this revision (locale-aware). */
    private fun deletedNote(): ByteArray = s(R.string.git_file_deleted).toByteArray(Charsets.UTF_8)

    private fun dirX(path: String, name: String) =
        XFile(scheme, path, isDir = true, displayName = name, canWrite = false)

    private fun historyEntry(c: GitCommit) = XFile(
        scheme, "/history/${c.sha}", isDir = true,
        lastModified = c.timeSec * 1000,
        displayName = c.title.ifEmpty { c.shortSha }, canWrite = false,
    )

    // Branch names can contain '/' (e.g. feature/foo); escape it in path segments to avoid colliding with directory hierarchy
    private fun encodeBranch(name: String) = name.replace("/", "%2F")
    private fun decodeBranch(seg: String) = seg.replace("%2F", "/")

    private fun changeX(group: String, ch: GitChange) = XFile(
        scheme, "/changes/$group/${ch.path}", isDir = false,
        displayName = "${mark(ch.kind)} ${ch.path}", canWrite = false,
    )

    private fun mark(k: ChangeKind) = when (k) {
        ChangeKind.ADDED -> "A"
        ChangeKind.MODIFIED -> "M"
        ChangeKind.DELETED -> "D"
    }

    private fun isCommitDir(p: String) =
        p.startsWith("/history/") && !p.removePrefix("/history/").contains('/')

    // ---- Read-only ----

    override fun openOutput(file: XFile, append: Boolean) = throw FsException(s(R.string.git_read_only))
    override fun mkdir(parent: XFile, name: String) = throw FsException(s(R.string.git_read_only))
    override fun delete(file: XFile) = throw FsException(s(R.string.git_read_only))
    override fun rename(file: XFile, newName: String) = throw FsException(s(R.string.git_read_only))
    override fun exists(file: XFile): Boolean = true

    fun closeRepo() {
        runCatching { data.close() }
    }

    /**
     * Discard the status/log/branches/diff cache; the next list() will re-read the repo's
     * current state; also refreshes [branchCache] (the tree root's title), regardless of
     * which block triggered this fresh, the title always tracks the latest.
     * Must be called on a background thread (internally runs data.branch(), which on SSH
     * is one remote exec).
     */
    @Synchronized
    fun invalidate() {
        statusCache = null
        logCache = null
        branchesCache = null
        worktreesCache = null
        branchLogCache.clear()
        diffCache.clear()
        branchCache = runCatching { data.branch() }.getOrNull() ?: branchCache
    }

    companion object {
        private const val STATUS_TTL_MS = 8_000L
        private const val LOG_LIMIT = 200
        private const val INFO = "#info"

        /**
         * Formatter for commit timestamps. **Created on each call, no shared instance** —
         * [SimpleDateFormat] is not thread-safe, and commitInfo() is called concurrently
         * from IO threads (each pane opens its own git view). The cost of a fresh `new` is
         * negligible; the corrupted output from sharing one is not cheap to debug.
         */
        private fun dateFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
