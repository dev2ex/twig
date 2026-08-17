package com.twig.git

/**
 * 工作区目录下 `.git` 的解析结果。
 *
 * - 普通仓库:`.git` 是目录,[gitDir] == [commonDir]。
 * - worktree(`git worktree add`)/子模块:`.git` 是文本文件 `gitdir: <path>`,
 *   [gitDir] 指向 `<主仓库>/.git/worktrees/<名>`(子模块是 `.git/modules/<名>`)。
 *   worktree 的那个目录里**只有** HEAD / index / ORIG_HEAD / logs/HEAD 这些"每工作区一份"
 *   的东西,objects、refs、packed-refs、info/exclude 全在 [commonDir](由 gitDir 下的
 *   `commondir` 文件指出)。分开取才既能拿到本工作区的 HEAD/index,又读得到对象库。
 */
class GitDirs(val gitDir: String, val commonDir: String) {
    /** 是否需要 [WorktreeGitFs] 这种"两段拼"的元数据视图(子模块 commondir 缺省=自身,不需要)。 */
    val split: Boolean get() = gitDir != commonDir
}

/**
 * 解析 `.git`。路径一律 '/' 分隔(本地 Android 与 SMB/WebDAV/SFTP 都是),
 * 所以本地与远程共用这一份实现,只是把"看目录/读文本"两个动作作为参数传进来。
 */
object GitLayout {

    /**
     * @param workDir 工作区根目录路径(不带尾 '/')
     * @param isDir   该路径是否为存在的目录
     * @param readText 读该路径的文本;不是文件/不存在返回 null
     * @return 解析不出(不是仓库、gitdir 指向的目录找不到)返回 null
     */
    fun resolve(workDir: String, isDir: (String) -> Boolean, readText: (String) -> String?): GitDirs? {
        val base = workDir.trimEnd('/').ifEmpty { "/" }
        val dotGit = join(base, ".git")
        val gitDir = when {
            isDir(dotGit) -> dotGit
            else -> {
                val text = readText(dotGit) ?: return null
                val ref = text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("gitdir:") }
                    ?.removePrefix("gitdir:")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return null
                resolveDir(base, ref, isDir) ?: return null
            }
        }
        val common = readText(join(gitDir, "commondir"))?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolveDir(gitDir, it, isDir) }
            ?: gitDir
        return GitDirs(gitDir, common)
    }

    /**
     * 把 `.git` / `commondir` 里写的路径解析成真实目录。
     *
     * ★ `gitdir:` 里 git 写的是**绝对路径**,而它是"建这个 worktree 的那台机器"上的绝对
     * 路径 —— 经 SMB/WebDAV 挂载看到的只是那台机器的某棵子树,拿它直接找必然落空。
     * 所以直取不中时再兜一层:worktree 常常就建在仓库自己里面(`repo/.claude/worktrees/x`),
     * 从工作区目录逐级往上找同样的尾巴(`.git/worktrees/x`)就能对上。
     */
    private fun resolveDir(base: String, ref: String, isDir: (String) -> Boolean): String? {
        val direct = join(base, ref)
        if (isDir(direct)) return direct
        val idx = direct.indexOf("/.git/")
        if (idx < 0) return null
        val tail = direct.substring(idx + 1) // ".git/worktrees/<名>"
        var dir = base
        while (true) {
            val cand = join(dir, tail)
            if (isDir(cand)) return cand
            if (dir.isEmpty() || dir == "/") return null
            dir = dir.substringBeforeLast('/', "").ifEmpty { "/" } // 挂载点可能就在根下一层
        }
    }

    /** 拼接并规范化(消掉 "." 与 ".." 段);[rel] 是绝对路径时忽略 [base]。 */
    fun join(base: String, rel: String): String {
        val abs = rel.startsWith("/")
        val raw = if (abs) rel else "$base/$rel"
        val out = ArrayList<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1)
                        else if (!raw.startsWith("/")) out.add("..")
                else -> out.add(seg)
            }
        }
        val joined = out.joinToString("/")
        return if (raw.startsWith("/")) "/$joined" else joined
    }
}
