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
 * Whether a worktree produced by `git worktree add` (`.git` is a file, the object store lives
 * in the main repo) can read history and changes correctly.
 * Fixtures are built with real git too (skipped when git is unavailable).
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
        assertTrue("git ${args.joinToString(" ")} failed: $out", p.waitFor() == 0)
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

    /** Create a worktree at [rel] (relative to the main repo) and return its directory. */
    private fun addWorktree(rel: String, branch: String): File {
        git(root, "worktree", "add", "-b", branch, rel)
        return File(root, rel)
    }

    @Test
    fun `worktree can read the main repo's history`() {
        commitFile("a.txt", "1", "c1")
        commitFile("a.txt", "2", "c2")
        val wt = addWorktree("wt", "feat")

        assertTrue(GitRepo.isRepo(wt))
        GitRepo.open(wt)!!.use { repo ->
            assertEquals("feat", repo.branch()) // HEAD is this worktree's own, not the main repo's main
            val log = repo.log(0, 10)
            assertEquals(listOf("c2", "c1"), log.map { it.title })
            assertEquals(git(wt, "rev-parse", "HEAD").trim(), log[0].sha)
            // the branch list comes from the shared directory, so the main repo's branch is there too
            assertEquals(setOf("main", "feat"), repo.branches().map { it.name }.toSet())
            assertTrue(repo.branches().single { it.name == "feat" }.current)
            // the object store lives in the main repo; being able to read content proves the routing is right
            val tree = repo.flattenTree(repo.commit(log[0].sha)!!.tree)
            assertEquals("2", repo.readBlob(tree["a.txt"]!!)!!.decodeToString())
        }
    }

    @Test
    fun `worktree is still readable once packed into a packfile`() {
        commitFile("a.txt", "1", "c1")
        repeat(20) { commitFile("a.txt", "x$it", "c${it + 2}") }
        val wt = addWorktree("wt", "feat")
        git(root, "gc", "--prune=now") // objects all move into the main repo's objects/pack

        GitRepo.open(wt)!!.use { repo ->
            assertEquals(21, repo.log(0, 100).size)
            assertEquals("c21", repo.log(0, 1)[0].title)
        }
    }

    @Test
    fun `worktree status reflects only its own worktree`() {
        commitFile("a.txt", "1", "c1")
        commitFile("dir/b.txt", "2", "c2")
        val wt = addWorktree("wt", "feat")

        File(wt, "a.txt").writeText("changed")      // unstaged
        File(wt, "new.txt").writeText("n")          // staged addition
        git(wt, "add", "new.txt")
        File(wt, "loose.txt").writeText("u")        // untracked
        File(root, "onlymain.txt").writeText("x")   // a change in the main repo should not show up here

        GitRepo.open(wt)!!.use { repo ->
            val s = repo.status()
            assertEquals("feat", s.branch)
            assertEquals(listOf("new.txt" to ChangeKind.ADDED), s.staged.map { it.path to it.kind })
            assertEquals(listOf("a.txt" to ChangeKind.MODIFIED), s.unstaged.map { it.path to it.kind })
            assertEquals(listOf("loose.txt"), s.untracked)
        }
        // the main repo side is still fine too (commondir resolution didn't scramble the main repo's HEAD/index).
        // wt/ shows as an untracked directory in the main repo -- real git reports the same, we're not over-counting
        GitRepo.open(root)!!.use { repo ->
            assertEquals("main", repo.branch())
            assertEquals(setOf("onlymain.txt", "wt/"), repo.status().untracked.toSet())
            assertTrue(git(root, "status", "--porcelain").contains("?? wt/"))
        }
    }

    @Test
    fun `a commit made in a worktree shows up in diff`() {
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
        // the main repo's history is unaffected (still sitting at c1)
        GitRepo.open(root)!!.use { assertEquals(listOf("c1"), it.log(0, 10).map { c -> c.title }) }
    }

    @Test
    fun `falls back to the repo-relative location when the gitdir's absolute path is stale`() {
        // simulate "the whole repo mounted over SMB/WebDAV": the .git file records an absolute
        // path from another machine that can never be found here, so the only way back is the
        // relative relationship <ancestor>/.git/worktrees/<name>
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
    fun `a gitdir file with no commondir is treated as a whole repo (submodule layout)`() {
        commitFile("a.txt", "1", "c1")
        // a submodule's .git is also a "gitdir: ..." file, but the directory it points to already
        // contains a complete objects/refs, with no commondir -- so it must not be split into two parts
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
    fun `worktree list -- the main repo and every worktree see the same list`() {
        commitFile("a.txt", "1", "c1")
        addWorktree("wt", "feat")
        addWorktree("nested/wt2", "feat2")
        git(root, "worktree", "lock", "wt")

        fun names(dir: File) = GitRepo.open(dir)!!.use { repo ->
            repo.worktrees().map { it.name to it.branch }
        }
        // the main repo sorts first (main=true), the rest by name
        assertEquals(
            listOf(root.name to "main", "wt" to "feat", "wt2" to "feat2"),
            names(root),
        )
        assertEquals(names(root), names(File(root, "wt"))) // the same list seen from inside the worktree

        GitRepo.open(root)!!.use { repo ->
            val all = repo.worktrees()
            assertEquals(listOf(true, false, false), all.map { it.current })
            assertEquals(listOf(true, false, false), all.map { it.main })
            assertEquals(listOf(false, true, false), all.map { it.locked })
            // paths are each worktree's own working directory (the trailing /.git recorded in gitdir is stripped)
            assertEquals(File(root, "wt").canonicalPath, File(all[1].path).canonicalPath)
            assertEquals(File(root, "nested/wt2").canonicalPath, File(all[2].path).canonicalPath)
            // matches real git's listing (order aside: git sorts by path, we sort by name)
            val real = git(root, "worktree", "list", "--porcelain").lineSequence()
                .filter { it.startsWith("worktree ") }
                .map { File(it.removePrefix("worktree ").trim()).canonicalPath }.toSet()
            assertEquals(real, all.map { File(it.path).canonicalPath }.toSet())
        }
        // from inside the worktree: current lands on itself, and the main worktree's branch is read from the main repo's HEAD
        GitRepo.open(File(root, "wt"))!!.use { repo ->
            val all = repo.worktrees()
            assertEquals("wt", all.single { it.current }.name)
            assertEquals("main", all.single { it.main }.branch)
        }
    }

    @Test
    fun `with no worktrees the list has only the main working tree`() {
        commitFile("a.txt", "1", "c1")
        GitRepo.open(root)!!.use { repo ->
            val all = repo.worktrees()
            assertEquals(1, all.size)
            assertTrue(all[0].main && all[0].current)
        }
    }

    @Test
    fun `a directory that is not a repo returns null`() {
        val plain = tmp.newFolder("plain")
        assertFalse(GitRepo.isRepo(plain))
        assertNull(GitRepo.open(plain))
        // .git is a file but points nowhere that exists -> not a repo (previously it was treated as one just because .git was a file)
        File(plain, ".git").writeText("gitdir: /nope/nowhere\n")
        assertFalse(GitRepo.isRepo(plain))
        assertNull(GitRepo.open(plain))
    }

    @Test
    fun `path joining collapses relative segments`() {
        assertEquals("/a/b/c", GitLayout.join("/a/b", "c"))
        assertEquals("/a/.git", GitLayout.join("/a/.git/worktrees/x", "../.."))
        assertEquals("/x", GitLayout.join("/a/b", "/x"))
        assertEquals("/a/b", GitLayout.join("/a/b/", "."))
        assertEquals("/", GitLayout.join("/a", ".."))
    }

    @Test
    fun `the resolved result distinguishes gitdir from commondir`() {
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
        // for a plain repo the two are the same
        val plain = GitLayout.resolve(
            root.path,
            isDir = { File(it).isDirectory },
            readText = { p -> File(p).takeIf { it.isFile }?.readText() },
        )!!
        assertFalse(plain.split)
    }
}
