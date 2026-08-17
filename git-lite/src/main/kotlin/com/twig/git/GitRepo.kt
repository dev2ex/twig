package com.twig.git

import java.io.File
import java.security.MessageDigest

/**
 * 只读 git 仓库解析:HEAD/refs、提交历史、工作区状态(changes)、提交 diff。
 * 不加锁、不写任何文件,可安全查看任意仓库。
 *
 * 文件访问经 [GitFs] 抽象:[metaFs] 以 .git 目录为根,[workFs] 以工作区为根;
 * 本地用 [LocalGitFs],SMB/WebDAV 等由调用方适配。[remote] 为 true 时
 * status 用"大小对比"替代 mtime/SHA(远程 stat 不可信、逐文件哈希太贵)。
 */
class GitRepo(
    private val metaFs: GitFs,
    private val workFs: GitFs,
    private val remote: Boolean = false,
    /** `.git` 的解析结果;只有它在,才知道自己是哪条工作区、主工作区在哪(见 [worktrees])。 */
    private val dirs: GitDirs? = null,
) : java.io.Closeable {

    private val store = ObjectStore(metaFs)

    private fun readText(path: String): String? =
        metaFs.readBytes(path)?.toString(Charsets.UTF_8)

    // ---- refs / HEAD ----

    /** 当前分支名;detached 时返回短 SHA。 */
    fun branch(): String = headBranch(readText("HEAD"))

    /** HEAD 文件内容 → 分支名;detached 时短 SHA。 */
    private fun headBranch(head: String?): String {
        val h = head?.trim() ?: return "?"
        return if (h.startsWith("ref: ")) h.removePrefix("ref: ").substringAfterLast('/') else h.take(7)
    }

    /** HEAD 指向的 commit SHA;空仓库(无提交)返回 null。 */
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

    // ---- 分支 ----

    /** 所有本地分支(refs/heads,含未 pack 的松散 ref 与 packed-refs),按名字排序。 */
    fun branches(): List<GitBranch> {
        val cur = branch()
        val out = LinkedHashMap<String, String>() // name -> sha,松散 ref 优先于 packed-refs
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

    // ---- 工作区(git worktree) ----

    /**
     * 本仓库关联的所有工作区,主工作区排在最前;当前这条也在里面([GitWorktree.current])。
     *
     * 数据全在公共目录的 `worktrees/<名>/` 下:`gitdir` 记着那条工作区 `.git` 文件的绝对
     * 路径(去掉末段就是它的工作目录),`HEAD` 是它自己的、`locked` 在则被锁定。这几个
     * 读取都走 [metaFs],[WorktreeGitFs] 会把 `worktrees/…` 路由到公共目录,所以站在
     * 主仓库还是站在某条 worktree 里看到的是同一份列表。
     *
     * 主工作区的路径只能从 [dirs] 推(公共 `.git` 的父目录),[dirs] 为空或仓库是 bare
     * 的就不列它 —— 那种情况下没有"主工作区"这回事。
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
                // 站在 worktree 里时 metaFs 的 HEAD 是自己的,主工作区那份只在公共目录里
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
                path = gitFile.trimEnd('/').substringBeforeLast('/'), // 去掉末段的 .git
                branch = headBranch(readText("worktrees/${e.name}/HEAD")),
                locked = metaFs.stat("worktrees/${e.name}/locked") != null,
                current = d != null && common != null &&
                    d.gitDir == GitLayout.join(common, "worktrees/${e.name}"),
            )
        }
        return out
    }

    /** 公共目录(objects/refs 所在);普通仓库就是 [metaFs] 自己。 */
    private val commonFs: GitFs get() = (metaFs as? WorktreeGitFs)?.common ?: metaFs

    // ---- 提交历史 ----

    fun commit(sha: String): GitCommit? {
        val obj = store.read(sha) ?: return null
        if (obj.type != ObjType.COMMIT) return null
        return parseCommit(sha, obj.data)
    }

    /**
     * 从 HEAD 按提交时间倒序遍历历史,跳过 [skip] 条,最多返回 [limit] 条。
     * 多父(merge)全部展开;用于 UI 分页。
     */
    fun log(skip: Int, limit: Int): List<GitCommit> = logFrom(headSha(), skip, limit)

    /** 同 [log],但从指定 commit sha(如某分支的 tip)开始遍历,而非 HEAD。 */
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

    /** 展平一棵 tree:相对路径 → blob SHA(跳过子模块)。 */
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
                mode == "160000" -> Unit // 子模块
                else -> out["$prefix$name"] = sha
            }
        }
        return out
    }

    /** 读 blob 内容;非 blob 或不存在返回 null。 */
    fun readBlob(sha: String): ByteArray? =
        store.read(sha)?.takeIf { it.type == ObjType.BLOB }?.data

    /** 某提交相对首个父提交的变更(根提交为全部新增)。 */
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

    /** index 内容(路径 → 条目),供 status 与内容读取。 */
    fun indexEntries(): Map<String, IndexEntry> =
        IndexFile.read(metaFs.readBytes("index"))

    /**
     * 计算工作区状态。
     * - staged:HEAD tree vs index
     * - unstaged:index vs 工作区(本地:mtime+size 一致视为未改,可疑时算 SHA;
     *   远程:仅比大小——远端 stat 不可信、逐文件哈希代价过高)
     * - untracked:不在 index 且未被 .gitignore 排除;整目录未跟踪时折叠为 "dir/"
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
                st.mtimeSec == e.mtimeSec && st.size == e.size -> Unit // stat 一致视为干净
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
                // 目录下没有任何已跟踪文件 → 整目录折叠为 "dir/"
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

    /** 工作区文件按 git blob 规则求 SHA-1("blob <len>\0" + 内容)。 */
    private fun blobSha(path: String, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob $size".toByteArray(Charsets.US_ASCII))
        md.update(0)
        md.update(workFs.readBytes(path) ?: ByteArray(0))
        return ObjectStore.bytesToHex(md.digest())
    }

    /** 读工作区文件内容(可为 null)。 */
    fun readWorkFile(path: String): ByteArray? = workFs.readBytes(path)

    override fun close() = store.close()

    companion object {
        /** 打开本地目录下的仓库;不是 git 工作区返回 null。支持 .git 为文件的 worktree/子模块。 */
        fun open(dir: File): GitRepo? {
            val dirs = layout(dir) ?: return null
            if (!File(dirs.gitDir, "HEAD").isFile) return null
            val meta =
                if (dirs.split) WorktreeGitFs(LocalGitFs(File(dirs.gitDir)), LocalGitFs(File(dirs.commonDir)))
                else LocalGitFs(File(dirs.gitDir))
            return GitRepo(meta, LocalGitFs(dir), remote = false, dirs = dirs)
        }

        /** 目录是否是 git 工作区根(本地);worktree/子模块要 gitdir 真解析得出才算。 */
        fun isRepo(dir: File): Boolean =
            layout(dir)?.let { File(it.gitDir, "HEAD").isFile } ?: false

        private fun layout(dir: File): GitDirs? = GitLayout.resolve(
            dir.path,
            isDir = { File(it).isDirectory },
            readText = { p -> File(p).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } },
        )
    }
}
