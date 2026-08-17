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

/** 把 core-fs 的任意来源(SMB/WebDAV/本地…)适配成 git-lite 的 [GitFs];[base] 为根目录。 */
class XFileGitFs(private val base: XFile) : GitFs {

    private val fs = FsRegistry.of(base)
    private val listCache = HashMap<String, List<XFile>>() // 目录清单缓存(一次解析会话内复用)

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
 * 打开远程(SMB/WebDAV/无 git 命令的 SFTP)目录里的 git 仓库;不是仓库返回 null。
 *
 * `.git` 既可能是目录,也可能是 worktree/子模块那种 `gitdir: <路径>` 文件——后者
 * 元数据要拆成 gitdir(HEAD/index)+ commondir(objects/refs)两段,见
 * [com.twig.git.GitLayout] 与 [com.twig.git.WorktreeGitFs]。
 */
/**
 * 取 [path] 的条目:**列父目录按名字找**,不用 `resolve()`;不存在返回 null。
 *
 * ★ 这里不能图省事用 [FileSystem.resolve]:`SftpFileSystem` 的实现是
 * `XFile(scheme, path, isDir = true)` —— 既不 stat 也不报错,照单全收还一律说是目录。
 * 拿它判存在/类型,worktree 那个 `.git` **文件**会被当成目录,内容一个字节都读不出来
 * (表现正是「工作区」有个数、展开却一条都没有)。
 */
private fun statEntry(fs: FileSystem, path: String): XFile? {
    val p = path.trimEnd('/').ifEmpty { "/" }
    if (p == "/") return runCatching { fs.root() }.getOrNull()
    val parent = p.substringBeforeLast('/', "").ifEmpty { "/" }
    val name = p.substringAfterLast('/')
    return runCatching { fs.list(XFile(fs.scheme, parent, isDir = true)) }.getOrNull()
        ?.firstOrNull { it.name == name }
}

/** 读一个小文本文件(`.git`、`commondir`,都只有一行);读不到返回 null。 */
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
 * 为一个仓库目录建 [GitData];不是仓库返回 null。第二值:该数据源是否"廉价"
 * (本地 / SSH 远程执行,见 `PaneViewModel.cheapGitSchemes`)。
 *
 * 放在这里而不是 `PaneViewModel` 里,是因为 [GitFileSystem] 列「工作区」时也要用
 * 同一套判定为**另一条 worktree** 建数据源。
 */
fun gitDataFor(dir: XFile): Pair<GitData, Boolean>? {
    if (dir.scheme == "file") {
        return com.twig.git.GitRepo.open(java.io.File(dir.path))?.let { com.twig.git.RepoGitData(it) to true }
    }
    val hostFs = FsRegistry.of(dir)
    // SFTP:服务器上有 git 命令就远程执行(服务端本地算,快一个量级)
    if (hostFs is com.twig.fs.network.SftpFileSystem) {
        val q = "'" + dir.path.replace("'", "'\\''") + "'"
        if (hostFs.exec("git -C $q rev-parse --is-inside-work-tree") != null) {
            return SshGitData({ cmd -> hostFs.exec(cmd) }, dir.path) to true
        }
    }
    // 其余远程(SMB/WebDAV/无 git 的 SFTP):自己解析 .git
    return openRemoteRepo(dir)?.let { com.twig.git.RepoGitData(it) to false }
}

/**
 * 把一个 git 仓库暴露为只读虚拟文件系统,直接挂进目录树:
 * ```
 * /                    更改 (N) | 其他分支 | 工作区 (n) | 历史
 * /changes             已暂存 (n) | 未暂存 (n) | 未跟踪 (n)
 * /changes/<组>        变更文件(A/M/D 标记,点击看内容)
 * /worktrees           **其他**工作区(git worktree);每条是另一套 GitFileSystem 的根
 * /history             最近 200 条提交
 * /history/<sha>       · 提交信息 + 该提交的变更文件
 * ```
 */
class GitFileSystem(
    private val ctx: android.content.Context,
    private val data: GitData,
    override val scheme: String,
    private val initialLabel: String, // 注册时的树根显示名,如 "Git (main)";切分支后见下
    /**
     * 该仓库的工作目录(本地 file:// 或某台服务器上的路径)。用来把仓库里记的绝对路径
     * (另一台机器的)映射回这边可达的路径。**为 null 就不列「工作区」**——从某条
     * worktree 点进另一条时建的就是 null 的实例,否则 A→B→A 无限套娃。
     */
    private val host: XFile? = null,
) : FileSystem {

    /** 树节点名/错误文案全部走字符串资源(这一层在 :app 里,拿得到 Context)。 */
    private fun s(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    /**
     * 树根显示名跟着当前分支走,读 [branchCache](只读内存,UI 线程 rebuild() 时调用
     * 安全,不触发 IO);缓存还没填过时退回注册时的初始值。
     * ★ 不能借用 statusCache 里的分支名——那个只有 list("/") / list("/changes*")
     * 才会填,单独点开"历史""其他分支"只会 invalidate() 清缓存、不会重新填 status,
     * 标题就会退回冻结的 initialLabel(表现为"点历史/分支,标题变回旧分支名")。
     * branchCache 由 [invalidate] 统一刷新,不管这次 fresh 是为了 status/log/branches
     * 里的哪一种,标题都跟着更新。
     */
    override val displayName: String get() = branchCache?.let { "Git ($it)" } ?: initialLabel
    override fun writable(): Boolean = false

    // IO 线程在 invalidate() 里写、主线程 rebuild() 经 displayName 不加锁裸读,须 volatile 保证可见
    @Volatile private var branchCache: String? = null
    private var statusCache: Pair<Long, GitStatus>? = null
    private var logCache: List<GitCommit>? = null
    private var branchesCache: List<GitBranch>? = null
    private var worktreesCache: List<GitWorktree>? = null
    private val branchLogCache = HashMap<String, List<GitCommit>>() // 分支 tip sha -> 该分支的历史
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

    /** 除当前这条以外的工作区——自己那条就是眼前这个视图,列出来只是重复一遍。 */
    private fun otherWorktrees(): List<GitWorktree> = worktreesNow().filter { !it.current }

    /**
     * 一条工作区在树里的入口:它自己**又是一整套 git 视图**,所以按需建一个
     * [GitFileSystem] 注册成独立 scheme,返回它的根。这样更改/历史/diff 那套逻辑
     * 原样复用,一行不用改(压缩包挂载也是这个套路:子项属于另一个 FileSystem)。
     * 目录在这边不可达(已删除、或没挂载到)时返回 null —— 列一条点不开的更糟。
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
            // host 传 null:那条视图里不再列工作区,否则 A→B→A 无限套下去
            FsRegistry.register(GitFileSystem(ctx, d, sub, label))
        }
        return XFile(sub, "/", isDir = true, displayName = label, canWrite = false)
    }

    /**
     * 把仓库里记的工作区路径映射成这边可达的 [XFile];映射不出来(目录已删、或不在
     * 挂载范围内)返回 null。
     *
     * 本地与 SSH 记的就是真路径,直接用。SMB/WebDAV 就不行了:那是**建 worktree 那台
     * 机器**上的绝对路径,这边看到的只是它的某棵子树。这时拿主工作区在这边的路径当锚,
     * 从**最短的尾巴**开始逐段往回试(`…/repo/nested/wt` → `wt`、`nested/wt`、…),
     * 并用那个目录的 `.git` 是否确实指向 `worktrees/<名>` 来确认没认错人 —— 只看
     * "目录存在"会把同名的无关目录当成它。
     */
    private fun worktreeDir(w: GitWorktree): XFile? {
        val h = host ?: return null
        val fs = runCatching { FsRegistry.of(h) }.getOrNull() ?: return null
        fun dirAt(path: String): XFile? = statEntry(fs, path)
            ?.takeIf { it.isDir }?.let { XFile(h.scheme, path, isDir = true) }
        // 主工作区那条的路径本来就是这边算出来的(公共 .git 的父目录),不用映射
        if (w.main) return dirAt(w.path)
        // 记录的路径能直接落地(本地/SSH:那本来就是真路径)就用它,不必再验
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

    /** [dir] 的 `.git` 是否正是 `worktrees/<[name]>` 那条工作区。 */
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
                // 和「其他分支」一个道理:自己这条已经就在眼前,只在有别的工作区时才列
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
        // 当前分支的提交已经在"历史"里看得到,这里只列其他分支,不重复自己
        dir.path == "/branches" -> branchesNow().filter { !it.current }.map { b ->
            dirX("/branches/${encodeBranch(b.name)}", b.name)
        }
        dir.path.startsWith("/branches/") -> {
            val name = decodeBranch(dir.path.removePrefix("/branches/"))
            val sha = branchesNow().firstOrNull { it.name == name }?.sha
            // 分支下的提交复用 /history/<sha> 这套虚拟路径(内容/diff 逻辑零改动共享)
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
                    // 该提交里的版本;删除的文件给父提交里的版本
                    data.content(sha, rel) ?: data.content("$sha^", rel) ?: deletedNote()
                }
            }
            else -> throw FsException(s(R.string.git_unreadable, p))
        }
        return bytes.inputStream()
    }

    /**
     * 该虚拟路径对应的 diff 两侧内容(旧, 新);null 侧表示该侧不存在
     * (新增/删除)。不可 diff 的路径(#info、目录等)返回 null。
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

    /** 文件在该版本里已被删除时的占位内容(随 locale)。 */
    private fun deletedNote(): ByteArray = s(R.string.git_file_deleted).toByteArray(Charsets.UTF_8)

    private fun dirX(path: String, name: String) =
        XFile(scheme, path, isDir = true, displayName = name, canWrite = false)

    private fun historyEntry(c: GitCommit) = XFile(
        scheme, "/history/${c.sha}", isDir = true,
        lastModified = c.timeSec * 1000,
        displayName = c.title.ifEmpty { c.shortSha }, canWrite = false,
    )

    // 分支名可能含 '/'(如 feature/foo),路径段里转义掉,避免和目录层级混淆
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

    // ---- 只读 ----

    override fun openOutput(file: XFile, append: Boolean) = throw FsException(s(R.string.git_read_only))
    override fun mkdir(parent: XFile, name: String) = throw FsException(s(R.string.git_read_only))
    override fun delete(file: XFile) = throw FsException(s(R.string.git_read_only))
    override fun rename(file: XFile, newName: String) = throw FsException(s(R.string.git_read_only))
    override fun exists(file: XFile): Boolean = true

    fun closeRepo() {
        runCatching { data.close() }
    }

    /**
     * 丢弃 status/log/branches/diff 缓存,下次 list() 重新读仓库当前状态;
     * 顺带刷新 [branchCache](树根标题),不管这次是为了刷新哪一块内容,标题都跟着更新。
     * 只能在后台线程调用(内部会跑一次 data.branch(),SSH 侧是一次远程 exec)。
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
         * 提交时间的格式化器。**每次现建,不共享实例**——[SimpleDateFormat] 不是线程
         * 安全的,而 commitInfo() 是在 IO 线程上被并发调用的(两个面板各自展开 git 视图)。
         * 每次 new 的开销可以忽略,共享出来的错乱结果排查起来可不便宜。
         */
        private fun dateFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
