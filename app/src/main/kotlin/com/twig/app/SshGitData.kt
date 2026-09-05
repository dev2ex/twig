package com.twig.app

import com.twig.git.ChangeKind
import com.twig.git.GitBranch
import com.twig.git.GitChange
import com.twig.git.GitCommit
import com.twig.git.GitData
import com.twig.git.GitStatus

/**
 * A [GitData] implementation that runs git commands on the server over SSH:
 * status/log/diff/content are all computed locally on the server side and only
 * the results are sent back, which is an order of magnitude faster than parsing
 * a .git directory block by block over the network.
 * [exec] returns stdout; on failure (non-zero exit / no git) it returns null.
 */
class SshGitData(
    private val exec: (String) -> ByteArray?,
    private val repoPath: String,
    /**
     * Server path → the path the tree shows, for the paths git *prints* back
     * ([worktrees]); null when it lies outside the connection root. Identity by default;
     * a connection rooted at a sub-directory passes `SftpFileSystem::visiblePath`.
     */
    private val visible: (String) -> String? = { it },
) : GitData {

    private val base = "git -C ${sq(repoPath)}"

    private fun run(args: String): ByteArray? = exec("$base $args")

    private fun runText(args: String): String? = run(args)?.toString(Charsets.UTF_8)

    override fun branch(): String {
        val b = runText("rev-parse --abbrev-ref HEAD")?.trim() ?: return "?"
        return if (b == "HEAD") runText("rev-parse --short HEAD")?.trim() ?: "?" else b
    }

    override fun status(): GitStatus {
        val out = run("status --porcelain -z")
            ?: return GitStatus(branch(), emptyList(), emptyList(), emptyList())
        val staged = ArrayList<GitChange>()
        val unstaged = ArrayList<GitChange>()
        val untracked = ArrayList<String>()
        val parts = out.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        var i = 0
        while (i < parts.size) {
            val e = parts[i]; i++
            if (e.length < 4) continue
            val x = e[0]; val y = e[1]
            val path = e.substring(3)
            if (x == '?' && y == '?') { untracked.add(path); continue }
            if (x == 'R' || x == 'C') { // rename/copy: with -z, the old path is the next record
                val old = parts.getOrNull(i); i++
                staged.add(GitChange(path, ChangeKind.ADDED))
                if (x == 'R' && old != null) staged.add(GitChange(old, ChangeKind.DELETED))
            } else {
                when (x) {
                    'A' -> staged.add(GitChange(path, ChangeKind.ADDED))
                    'M', 'T' -> staged.add(GitChange(path, ChangeKind.MODIFIED))
                    'D' -> staged.add(GitChange(path, ChangeKind.DELETED))
                }
            }
            when (y) {
                'M', 'T' -> unstaged.add(GitChange(path, ChangeKind.MODIFIED))
                'D' -> unstaged.add(GitChange(path, ChangeKind.DELETED))
            }
        }
        val cmp = compareBy<GitChange> { it.path }
        return GitStatus(branch(), staged.sortedWith(cmp), unstaged.sortedWith(cmp), untracked.sorted())
    }

    override fun log(skip: Int, limit: Int): List<GitCommit> {
        val out = run("log --skip=$skip -n $limit --format=$FMT") ?: return emptyList()
        return parseCommits(out)
    }

    override fun branches(): List<GitBranch> {
        // %09 (tab escape) is syntax for `log --pretty=format`, but
        // `for-each-ref --format` does not understand it and outputs it verbatim
        // as the literal "%09" — previously the tab was never parsed and the
        // branch list was always empty.
        // Switched to the order: sha (fixed-length 40 hex) + space + refname,
        // with no reliance on escape characters at all.
        // ★ The value of --format must be quoted as a whole: parentheses inside
        // "%(objectname)" are shell metacharacters. Without quoting, splicing it
        // straight into the remote command makes the shell parse it as a
        // subcommand and throw a syntax error — that was the actual reason the
        // branch list came back empty before (the exec() command never even
        // ran, not that the format parsed to no results).
        val out = runText("for-each-ref ${sq("--format=%(objectname) %(refname:short)")} refs/heads/")
            ?: return emptyList()
        val cur = branch()
        return out.lineSequence().mapNotNull { line ->
            if (line.length < 42 || line[40] != ' ') return@mapNotNull null
            val name = line.substring(41)
            if (name.isBlank()) null else GitBranch(name, line.substring(0, 40), name == cur)
        }.sortedBy { it.name }.toList()
    }

    /**
     * A `git worktree list --porcelain` record looks like (separated by blank
     * lines, the first record is always the main worktree):
     * ```
     * worktree /home/u/repo
     * HEAD <sha>
     * branch refs/heads/main        ← when detached, this becomes "detached"
     * locked <reason?>              ← absent when not locked
     * ```
     *
     * ★ The printed paths are the **server's**, while [GitWorktree.path] is consumed as a
     * path in the tree — the two only coincide when the connection is rooted at the
     * server root, so every path goes through [visible] first (`current`
     * is still decided on the server path, which is what [repoPath] is). A path outside the
     * root keeps its server form: it is useless as-is, but the caller's suffix matching can
     * still find the directory from it.
     */
    override fun worktrees(): List<com.twig.git.GitWorktree> {
        val out = runText("worktree list --porcelain") ?: return emptyList()
        val cur = repoPath.trimEnd('/')
        val list = ArrayList<com.twig.git.GitWorktree>()
        var path: String? = null
        var head = ""
        var branch: String? = null
        var locked = false
        fun flush() {
            val p = path?.trimEnd('/') ?: return
            list.add(
                com.twig.git.GitWorktree(
                    name = p.substringAfterLast('/').ifEmpty { p },
                    path = visible(p) ?: p,
                    branch = branch?.substringAfterLast('/') ?: head.take(7).ifEmpty { "?" },
                    locked = locked,
                    current = p == cur,
                    main = list.isEmpty(),
                ),
            )
            path = null; head = ""; branch = null; locked = false
        }
        for (line in out.lineSequence()) {
            when {
                line.startsWith("worktree ") -> { flush(); path = line.removePrefix("worktree ").trim() }
                line.startsWith("HEAD ") -> head = line.removePrefix("HEAD ").trim()
                line.startsWith("branch ") -> branch = line.removePrefix("branch ").trim()
                line.startsWith("locked") -> locked = true
            }
        }
        flush()
        return list
    }

    override fun logRef(ref: String, skip: Int, limit: Int): List<GitCommit> {
        val out = run("log --skip=$skip -n $limit --format=$FMT ${sq(ref)}") ?: return emptyList()
        return parseCommits(out)
    }

    override fun commit(sha: String): GitCommit? {
        val out = run("show -s --format=$FMT ${sq(sha)}") ?: return null
        return parseCommits(out).firstOrNull()
    }

    private fun parseCommits(out: ByteArray): List<GitCommit> =
        out.toString(Charsets.UTF_8).split('\u0002').mapNotNull { rec ->
            val f = rec.trim('\n').split('\u0001')
            if (f.size < 6 || f[0].length != 40) return@mapNotNull null
            GitCommit(
                sha = f[0],
                parents = f[1].split(' ').filter { it.length == 40 },
                tree = "",
                author = f[2],
                email = f[3],
                timeSec = f[4].toLongOrNull() ?: 0L,
                message = f[5].trim('\n'),
            )
        }

    override fun diff(sha: String): List<GitChange> {
        // --no-renames: split renames into A+D so the parsing matches the rest
        val out = runText("show --name-status --format= --no-renames ${sq(sha)}") ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            if (line.length < 3 || line[1] != '\t') return@mapNotNull null
            val kind = when (line[0]) {
                'A' -> ChangeKind.ADDED
                'M', 'T' -> ChangeKind.MODIFIED
                'D' -> ChangeKind.DELETED
                else -> return@mapNotNull null
            }
            GitChange(line.substring(2), kind)
        }.sortedBy { it.path }.toList()
    }

    override fun content(rev: String, path: String): ByteArray? = when (rev) {
        "WORK" -> exec("cat ${sq("$repoPath/$path")}")
        "INDEX" -> run("show ${sq(":$path")}")
        else -> run("show ${sq("$rev:$path")}") // HEAD / sha / sha^
    }

    override fun close() = Unit

    companion object {
        /** Fields separated by 0x01, records by 0x02; neither ever appears in messages. */
        private const val FMT = "%H%x01%P%x01%an%x01%ae%x01%at%x01%B%x02"

        /** POSIX shell single-quote escaping. */
        private fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
