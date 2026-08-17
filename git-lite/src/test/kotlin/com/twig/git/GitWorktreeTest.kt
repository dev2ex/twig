package com.twig.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `git worktree add` 出来的工作区(`.git` 是文件、对象库在主仓库)能不能正常看历史与变更。
 * 同样用真 git 造 fixture(无 git 时跳过)。
 */
class GitWorktreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun git(cwd: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
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
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(content)
        git(root, "add", path)
        git(root, "commit", "-m", msg)
    }

    /** 在 [rel](相对主仓库)建一条 worktree,返回其目录。 */
    private fun addWorktree(rel: String, branch: String): File {
        git(root, "worktree", "add", "-b", branch, rel)
        return File(root, rel)
    }

    @Test
    fun `worktree 能读到主仓库的历史`() {
        commitFile("a.txt", "1", "c1")
        commitFile("a.txt", "2", "c2")
        val wt = addWorktree("wt", "feat")

        assertTrue(GitRepo.isRepo(wt))
        GitRepo.open(wt)!!.use { repo ->
            assertEquals("feat", repo.branch()) // HEAD 取的是本工作区的,不是主仓库的 main
            val log = repo.log(0, 10)
            assertEquals(listOf("c2", "c1"), log.map { it.title })
            assertEquals(git(wt, "rev-parse", "HEAD").trim(), log[0].sha)
            // 分支列表来自公共目录,主仓库的分支也在
            assertEquals(setOf("main", "feat"), repo.branches().map { it.name }.toSet())
            assertTrue(repo.branches().single { it.name == "feat" }.current)
            // 对象库在主仓库里,读得到内容才说明路由对了
            val tree = repo.flattenTree(repo.commit(log[0].sha)!!.tree)
            assertEquals("2", repo.readBlob(tree["a.txt"]!!)!!.decodeToString())
        }
    }

    @Test
    fun `worktree 打包成 packfile 后仍可读`() {
        commitFile("a.txt", "1", "c1")
        repeat(20) { commitFile("a.txt", "x$it", "c${it + 2}") }
        val wt = addWorktree("wt", "feat")
        git(root, "gc", "--prune=now") // 对象全进主仓库的 objects/pack

        GitRepo.open(wt)!!.use { repo ->
            assertEquals(21, repo.log(0, 100).size)
            assertEquals("c21", repo.log(0, 1)[0].title)
        }
    }

    @Test
    fun `worktree 的 status 只反映本工作区`() {
        commitFile("a.txt", "1", "c1")
        commitFile("dir/b.txt", "2", "c2")
        val wt = addWorktree("wt", "feat")

        File(wt, "a.txt").writeText("changed")      // unstaged
        File(wt, "new.txt").writeText("n")          // staged 新增
        git(wt, "add", "new.txt")
        File(wt, "loose.txt").writeText("u")        // untracked
        File(root, "onlymain.txt").writeText("x")   // 主仓库的改动不该出现在这里

        GitRepo.open(wt)!!.use { repo ->
            val s = repo.status()
            assertEquals("feat", s.branch)
            assertEquals(listOf("new.txt" to ChangeKind.ADDED), s.staged.map { it.path to it.kind })
            assertEquals(listOf("a.txt" to ChangeKind.MODIFIED), s.unstaged.map { it.path to it.kind })
            assertEquals(listOf("loose.txt"), s.untracked)
        }
        // 主仓库那侧同时也还是好的(commondir 解析没把主仓库的 HEAD/index 搅乱)。
        // wt/ 在主仓库里是未跟踪目录 —— 真 git 也这么报,不是我们多算的
        GitRepo.open(root)!!.use { repo ->
            assertEquals("main", repo.branch())
            assertEquals(setOf("onlymain.txt", "wt/"), repo.status().untracked.toSet())
            assertTrue(git(root, "status", "--porcelain").contains("?? wt/"))
        }
    }

    @Test
    fun `worktree 里的提交能看到 diff`() {
        commitFile("a.txt", "1", "c1")
        val wt = addWorktree("wt", "feat")
        File(wt, "a.txt").writeText("2")
        File(wt, "n.txt").writeText("n")
        git(wt, "add", "-A")
        git(wt, "commit", "-m", "wt-commit")

        GitRepo.open(wt)!!.use { repo ->
            val head = repo.log(0, 1).single()
            assertEquals("wt-commit", head.title)
            assertEquals(
                setOf("a.txt" to ChangeKind.MODIFIED, "n.txt" to ChangeKind.ADDED),
                repo.diff(head).map { it.path to it.kind }.toSet(),
            )
        }
        // 主仓库的历史不受影响(仍停在 c1)
        GitRepo.open(root)!!.use { assertEquals(listOf("c1"), it.log(0, 10).map { c -> c.title }) }
    }

    @Test
    fun `gitdir 的绝对路径失效时按仓库内相对位置兜底`() {
        // 模拟"整个仓库经 SMB/WebDAV 挂载":.git 文件里记的是另一台机器上的绝对路径,
        // 在这边一定找不到,只能靠 <祖先>/.git/worktrees/<名> 这条相对关系找回去
        commitFile("a.txt", "1", "c1")
        addWorktree("nested/wt", "feat")
        val moved = File(root.parentFile, "mounted")
        assertTrue(root.renameTo(moved))
        val wt = File(moved, "nested/wt")

        assertFalse(File(File(wt, ".git").readText().trim().removePrefix("gitdir:").trim()).exists())
        assertTrue(GitRepo.isRepo(wt))
        GitRepo.open(wt)!!.use { repo ->
            assertEquals("feat", repo.branch())
            assertEquals(listOf("c1"), repo.log(0, 10).map { it.title })
        }
    }

    @Test
    fun `没有 commondir 的 gitdir 文件按整个仓库用(子模块布局)`() {
        commitFile("a.txt", "1", "c1")
        // 子模块的 .git 也是 "gitdir: …" 文件,但指向的目录里就是完整的 objects/refs,
        // 没有 commondir —— 此时不能拆两段
        val meta = tmp.newFolder("elsewhere")
        File(root, ".git").copyRecursively(File(meta, "git"))
        File(root, ".git").deleteRecursively()
        File(root, ".git").writeText("gitdir: ${File(meta, "git").absolutePath}\n")

        GitRepo.open(root)!!.use { repo ->
            assertEquals("main", repo.branch())
            assertEquals(listOf("c1"), repo.log(0, 10).map { it.title })
        }
    }

    @Test
    fun `工作区列表——主仓库与各 worktree 看到的是同一份`() {
        commitFile("a.txt", "1", "c1")
        addWorktree("wt", "feat")
        addWorktree("nested/wt2", "feat2")
        git(root, "worktree", "lock", "wt")

        fun names(dir: File) = GitRepo.open(dir)!!.use { repo ->
            repo.worktrees().map { it.name to it.branch }
        }
        // 主仓库自己排最前(main=true),其余按名字
        assertEquals(
            listOf(root.name to "main", "wt" to "feat", "wt2" to "feat2"),
            names(root),
        )
        assertEquals(names(root), names(File(root, "wt"))) // 站在 worktree 里看到的一样

        GitRepo.open(root)!!.use { repo ->
            val all = repo.worktrees()
            assertEquals(listOf(true, false, false), all.map { it.current })
            assertEquals(listOf(true, false, false), all.map { it.main })
            assertEquals(listOf(false, true, false), all.map { it.locked })
            // 路径是各自的工作目录(去掉了 gitdir 记的那截 /.git)
            assertEquals(File(root, "wt").canonicalPath, File(all[1].path).canonicalPath)
            assertEquals(File(root, "nested/wt2").canonicalPath, File(all[2].path).canonicalPath)
            // 与真 git 的清单一致(顺序另说:git 按路径排,我们按名字排)
            val real = git(root, "worktree", "list", "--porcelain").lineSequence()
                .filter { it.startsWith("worktree ") }
                .map { File(it.removePrefix("worktree ").trim()).canonicalPath }.toSet()
            assertEquals(real, all.map { File(it.path).canonicalPath }.toSet())
        }
        // 站在 worktree 里:current 落在自己身上,主工作区那条的分支读的是主仓库 HEAD
        GitRepo.open(File(root, "wt"))!!.use { repo ->
            val all = repo.worktrees()
            assertEquals("wt", all.single { it.current }.name)
            assertEquals("main", all.single { it.main }.branch)
        }
    }

    @Test
    fun `没有 worktree 时列表只有主工作区`() {
        commitFile("a.txt", "1", "c1")
        GitRepo.open(root)!!.use { repo ->
            val all = repo.worktrees()
            assertEquals(1, all.size)
            assertTrue(all[0].main && all[0].current)
        }
    }

    @Test
    fun `不是仓库的目录返回 null`() {
        val plain = tmp.newFolder("plain")
        assertFalse(GitRepo.isRepo(plain))
        assertNull(GitRepo.open(plain))
        // .git 是文件但指向哪儿都不存在 → 不算仓库(以前只看 .git 是不是文件就说是)
        File(plain, ".git").writeText("gitdir: /nope/nowhere\n")
        assertFalse(GitRepo.isRepo(plain))
        assertNull(GitRepo.open(plain))
    }

    @Test
    fun `路径拼接消掉相对段`() {
        assertEquals("/a/b/c", GitLayout.join("/a/b", "c"))
        assertEquals("/a/.git", GitLayout.join("/a/.git/worktrees/x", "../.."))
        assertEquals("/x", GitLayout.join("/a/b", "/x"))
        assertEquals("/a/b", GitLayout.join("/a/b/", "."))
        assertEquals("/", GitLayout.join("/a", ".."))
    }

    @Test
    fun `解析结果分得清 gitdir 与 commondir`() {
        commitFile("a.txt", "1", "c1")
        val wt = addWorktree("wt", "feat")
        val dirs = GitLayout.resolve(
            wt.path,
            isDir = { File(it).isDirectory },
            readText = { p -> File(p).takeIf { it.isFile }?.readText() },
        )
        assertNotNull(dirs)
        assertEquals(File(root, ".git/worktrees/wt").canonicalPath, File(dirs!!.gitDir).canonicalPath)
        assertEquals(File(root, ".git").canonicalPath, File(dirs.commonDir).canonicalPath)
        assertTrue(dirs.split)
        // 普通仓库两者相同
        val plain = GitLayout.resolve(
            root.path,
            isDir = { File(it).isDirectory },
            readText = { p -> File(p).takeIf { it.isFile }?.readText() },
        )!!
        assertFalse(plain.split)
    }
}
