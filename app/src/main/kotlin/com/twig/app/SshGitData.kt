package com.twig.app

import com.twig.git.ChangeKind
import com.twig.git.GitBranch
import com.twig.git.GitChange
import com.twig.git.GitCommit
import com.twig.git.GitData
import com.twig.git.GitStatus

/**
 * 经 SSH 在服务器上执行 git 命令的 [GitData] 实现:status/log/diff/内容
 * 都在服务端本地算好只回传结果,远程仓库比逐块解析 .git 快一个量级。
 * [exec] 返回 stdout,失败(非零退出/无 git)返回 null。
 */
class SshGitData(
    private val exec: (String) -> ByteArray?,
    private val repoPath: String,
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
            if (x == 'R' || x == 'C') { // rename/copy:-z 下旧路径是下一个记录
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
        // %09(制表符转义)是 log --pretty=format 的语法,for-each-ref --format 不认,
        // 会被原样当字面量 "%09" 输出——之前一直解析不出制表符,分支列表永远是空的。
        // 换成 sha(定长 40 hex)+ 空格 + refname 的顺序,不依赖任何转义字符。
        // ★ --format 的值必须整体加引号:"%(objectname)" 里的括号是 shell 元字符,
        // 不加引号直接拼进远程命令行,shell 当子命令解析直接语法错误——之前分支列表
        // 空的真正原因就是这个(exec() 命令都没跑起来,不是格式解析出了空结果)。
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
     * `git worktree list --porcelain` 的记录形如(空行分隔,第一条永远是主工作区):
     * ```
     * worktree /home/u/repo
     * HEAD <sha>
     * branch refs/heads/main        ← detached 时换成 "detached"
     * locked <原因?>                ← 没锁就没这行
     * ```
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
                    path = p,
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
        // --no-renames:重命名拆成 A+D,与解析实现口径一致
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
        /** 字段用 0x01 分隔、记录用 0x02 分隔,消息里不会出现。 */
        private const val FMT = "%H%x01%P%x01%an%x01%ae%x01%at%x01%B%x02"

        /** POSIX shell 单引号转义。 */
        private fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
