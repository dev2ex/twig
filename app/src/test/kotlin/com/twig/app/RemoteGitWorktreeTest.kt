package com.twig.app

import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.git.ChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 远程来源(SMB/WebDAV)里的 git **worktree**:`.git` 是文件、`gitdir:` 里写的是
 * **另一台机器上的绝对路径**,在这边一定找不到——[openRemoteRepo] 得靠"仓库内相对
 * 位置"兜回去,再把 gitdir/commondir 拆两段读,历史与变更才出得来。
 *
 * 这条链路上真实的服务器换成 [MountFs](把本地一个目录当共享根),但**仓库本身是真
 * git 造的**:对象库、packfile、index 全是真字节,才测得出路由错没错(改这块前先看
 * CLAUDE.md 里 WiFi 共享"所有来源"那条教训——新增一个模式就要有覆盖它的测试)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteGitWorktreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    /** 把本地目录 [base] 当成某台服务器的共享根暴露出去(路径一律相对它)。 */
    private class MountFs(
        override val scheme: String,
        private val base: File,
        /** 复刻 `SftpFileSystem.resolve`:不 stat、不报错,任何路径都说是目录。 */
        private val lyingResolve: Boolean = false,
    ) : FileSystem {
        override val displayName = "Mount($scheme)"
        private fun f(path: String) = File(base, path.trimStart('/'))
        private fun x(path: String, f: File) =
            XFile(scheme, path, f.isDirectory, size = f.length(), lastModified = f.lastModified())

        override fun root(): XFile = XFile(scheme, "/", isDir = true)
        override fun resolve(path: String): XFile =
            if (lyingResolve) XFile(scheme, path, isDir = true)
            else f(path).takeIf { it.exists() }?.let { x(path, it) } ?: throw FsException("no such path: $path")

        override fun list(dir: XFile): List<XFile> {
            val kids = f(dir.path).listFiles() ?: throw FsException("no such directory: ${dir.path}")
            val prefix = if (dir.path.endsWith("/")) dir.path else "${dir.path}/"
            return kids.map { x("$prefix${it.name}", it) }
        }

        override fun openInput(file: XFile): InputStream = f(file.path).inputStream()
        override fun exists(file: XFile): Boolean = f(file.path).exists()
        override fun writable(): Boolean = false
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("read-only")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("read-only")
        override fun delete(file: XFile) = throw FsException("read-only")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("read-only")
    }

    private fun git(cwd: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue("git ${args.joinToString(" ")} 失败: $out", p.waitFor() == 0)
        return out
    }

    @Before
    fun setUp() {
        assumeTrue(runCatching {
            ProcessBuilder("git", "--version").start().waitFor() == 0
        }.getOrDefault(false))
        root = tmp.newFolder("repo")
        git(root, "init", "-b", "main")
        git(root, "config", "user.email", "t@t"); git(root, "config", "user.name", "T")
    }

    private fun commitFile(path: String, content: String, msg: String) {
        File(root, path).apply { parentFile?.mkdirs() }.writeText(content)
        git(root, "add", path); git(root, "commit", "-m", msg)
    }

    /** 把 [root] 挂成一台服务器,返回其上某个路径的 [XFile]。 */
    private fun mount(scheme: String, path: String, lyingResolve: Boolean = false): XFile {
        FsRegistry.register(MountFs(scheme, root, lyingResolve))
        return XFile(scheme, path, isDir = true)
    }

    @Test
    fun `远程 worktree 能看历史与变更`() {
        commitFile("a.txt", "1", "c1")
        commitFile("a.txt", "2", "c2")
        git(root, "worktree", "add", "-b", "feat", "wt")
        File(root, "wt/a.txt").writeText("changed")   // unstaged
        File(root, "wt/new.txt").writeText("n")       // untracked
        git(root, "gc", "--prune=now")                // 对象进主仓库 packfile,更贴近真实仓库

        val repo = openRemoteRepo(mount("mnt1", "/wt"))!!
        repo.use {
            assertEquals("feat", it.branch())
            assertEquals(listOf("c2", "c1"), it.log(0, 10).map { c -> c.title })
            assertEquals(setOf("main", "feat"), it.branches().map { b -> b.name }.toSet())
            val s = it.status()
            assertEquals(listOf("a.txt" to ChangeKind.MODIFIED), s.unstaged.map { c -> c.path to c.kind })
            assertEquals(listOf("new.txt"), s.untracked)
            assertTrue(s.staged.isEmpty())
            // 内容读得出来才说明 objects 确实路由到了 commondir
            val tree = it.flattenTree(it.commit(it.log(0, 1)[0].sha)!!.tree)
            assertEquals("2", it.readBlob(tree["a.txt"]!!)!!.decodeToString())
        }
    }

    @Test
    fun `远程普通仓库不受影响`() {
        commitFile("a.txt", "1", "c1")
        openRemoteRepo(mount("mnt2", "/"))!!.use {
            assertEquals("main", it.branch())
            assertEquals(listOf("c1"), it.log(0, 10).map { c -> c.title })
        }
    }

    @Test
    fun `远程目录不是仓库时返回 null`() {
        File(root, "plain").mkdirs()
        assertNull(openRemoteRepo(mount("mnt3", "/plain")))
    }

    /**
     * 虚拟树里的「工作区」:从主仓库那套 git 视图列出其他工作区,点进去是**另一套**
     * 完整的 git 视图(更改/其他分支/历史)。远程这条最容易错的是路径映射——仓库里记的
     * 是那台机器上的绝对路径(`/tmp/junitXXX/repo/wt`),这边的挂载根却是 `/`。
     */
    @Test
    fun `工作区节点能列出并点进另一条 worktree`() {
        commitFile("a.txt", "1", "c1")
        git(root, "worktree", "add", "-b", "feat", "wt")
        File(root, "wt/a.txt").writeText("changed")

        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val host = mount("mnt4", "/")           // 挂载根 = 主仓库
        val fs = GitFileSystem(ctx, gitDataFor(host)!!.first, "gtest4", "Git (main)", host = host)
        FsRegistry.register(fs)

        // 根下多了「工作区 (1)」:自己那条不重复列,只有 wt
        val rootKids = fs.list(fs.root()).map { it.path }
        assertTrue(rootKids.toString(), rootKids.contains("/worktrees"))
        val wts = fs.list(XFile("gtest4", "/worktrees", isDir = true))
        assertEquals(1, wts.size)
        assertTrue(wts[0].name, wts[0].name.startsWith("wt (feat)"))

        // 点进去:是另一套 GitFileSystem,看到的是 wt 自己的分支与改动
        val sub = FsRegistry.of(wts[0]) as GitFileSystem
        sub.invalidate() // 填 branchCache,树根标题跟着这条 worktree 的分支走
        assertEquals("Git (feat)", sub.displayName)
        val changes = sub.list(XFile(wts[0].scheme, "/changes/unstaged", isDir = true))
        assertEquals(listOf("M a.txt"), changes.map { it.displayName })
        // 嵌套那层不再列工作区,免得 A→B→A 无限套
        assertTrue(sub.list(sub.root()).none { it.path == "/worktrees" })
    }

    /**
     * ★ 真机上就栽在这:SFTP 的 `resolve()` 不 stat、任何路径都回 isDir=true,
     * worktree 的 `.git` **文件**被当成目录,一个字节都读不出来 —— 「工作区 (n)」
     * 有个数、展开一条都没有。判存在/类型一律走"列父目录按名字找",不碰 resolve。
     */
    @Test
    fun `resolve 不 stat 的来源(SFTP 那种)照样能列出工作区`() {
        commitFile("a.txt", "1", "c1")
        git(root, "worktree", "add", "-b", "feat", "wt")

        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val host = mount("mnt6", "/", lyingResolve = true)
        // .git 是文件这件事必须看得出来,否则整条 worktree 都识别不了
        openRemoteRepo(XFile("mnt6", "/wt", isDir = true))!!.use { assertEquals("feat", it.branch()) }

        val fs = GitFileSystem(ctx, gitDataFor(host)!!.first, "gtest6", "Git (main)", host = host)
        FsRegistry.register(fs)
        val wts = fs.list(XFile("gtest6", "/worktrees", isDir = true))
        assertEquals(1, wts.size)
        assertTrue(wts[0].name, wts[0].name.startsWith("wt (feat)"))
    }

    /** SSH 仓库的工作区清单走 `git worktree list --porcelain`,这里拿真 git 验解析。 */
    @Test
    fun `porcelain 输出解析得出工作区清单`() {
        commitFile("a.txt", "1", "c1")
        git(root, "worktree", "add", "-b", "feat", "wt")
        git(root, "worktree", "add", "--detach", "det")
        git(root, "worktree", "lock", "wt")

        val data = SshGitData({ cmd ->
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes()
            if (p.waitFor() == 0) out else null
        }, root.path)
        val all = data.worktrees()
        assertEquals(listOf(root.name, "det", "wt").sorted(), all.map { it.name }.sorted())
        assertEquals("main", all.single { it.main }.branch)
        assertTrue(all.single { it.main }.current) // repoPath 就是主仓库
        assertEquals("feat", all.single { it.name == "wt" }.branch)
        assertTrue(all.single { it.name == "wt" }.locked)
        // detached:没有 branch 行,退回短 sha
        val head = git(root, "rev-parse", "--short=7", "HEAD").trim()
        assertEquals(head, all.single { it.name == "det" }.branch)
    }

    @Test
    fun `没有其他工作区时不显示工作区节点`() {
        commitFile("a.txt", "1", "c1")
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val host = mount("mnt5", "/")
        val fs = GitFileSystem(ctx, gitDataFor(host)!!.first, "gtest5", "Git (main)", host = host)
        assertTrue(fs.list(fs.root()).none { it.path == "/worktrees" })
    }
}
