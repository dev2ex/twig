package com.twig.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Build a fixture repo with real git and cross-check the parsed results against it (skipped when git is unavailable). */
class GitRepoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args)
            .directory(root)
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
        git("init", "-b", "main")
        git("config", "user.email", "t@t"); git("config", "user.name", "T")
    }

    private fun commitFile(path: String, content: String, msg: String) {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(content)
        git("add", path)
        git("commit", "-m", msg)
    }

    @Test
    fun `history and commit content`() {
        commitFile("a.txt", "1", "first")
        commitFile("b/c.txt", "2", "second")
        commitFile("a.txt", "3", "第三次 提交\n\n带正文")

        GitRepo.open(root)!!.use { repo ->
            assertEquals("main", repo.branch())
            val log = repo.log(0, 10)
            assertEquals(3, log.size)
            assertEquals("第三次 提交", log[0].title)
            assertEquals("T", log[0].author)
            assertEquals(listOf("second", "first"), log.drop(1).map { it.title })
            assertEquals(log[1].sha, log[0].parents[0])
            // sha order matches real git
            val real = git("log", "--format=%H").trim().lines()
            assertEquals(real, log.map { it.sha })
        }
    }

    @Test
    fun `packed history is still readable`() {
        commitFile("a.txt", "1", "c1")
        repeat(30) { commitFile("a.txt", "x$it", "c${it + 2}") }
        git("gc", "--aggressive", "--prune=now") // everything goes into a packfile
        GitRepo.open(root)!!.use { repo ->
            val log = repo.log(0, 100)
            assertEquals(31, log.size)
            assertEquals("c31", log[0].title)
            assertEquals("c1", log.last().title)
        }
        // pagination
        GitRepo.open(root)!!.use { repo ->
            val page2 = repo.log(10, 5)
            assertEquals(listOf("c21", "c20", "c19", "c18", "c17"), page2.map { it.title })
        }
    }

    @Test
    fun `status matches real git`() {
        commitFile("a.txt", "1", "c1")
        commitFile("dir/b.txt", "2", "c2")
        // unstaged modification
        File(root, "a.txt").writeText("changed")
        // staged addition
        File(root, "new.txt").writeText("n")
        git("add", "new.txt")
        // staged deletion
        git("rm", "--cached", "dir/b.txt")
        // untracked + ignored
        File(root, "loose.log").writeText("x")
        File(root, "junk").mkdirs()
        File(root, "junk/z.txt").writeText("z")
        File(root, ".gitignore").writeText("*.log\n")
        git("add", ".gitignore")

        GitRepo.open(root)!!.use { repo ->
            val s = repo.status()
            assertEquals(
                setOf(
                    "new.txt" to ChangeKind.ADDED,
                    ".gitignore" to ChangeKind.ADDED,
                    "dir/b.txt" to ChangeKind.DELETED,
                ),
                s.staged.map { it.path to it.kind }.toSet(),
            )
            assertEquals(listOf("a.txt" to ChangeKind.MODIFIED), s.unstaged.map { it.path to it.kind })
            // dir/b.txt was removed from the index but is still in the worktree -> untracked; junk collapses as a whole directory
            assertEquals(setOf("dir/", "junk/"), s.untracked.toSet())
        }
    }

    @Test
    fun `clean repo has no changes`() {
        commitFile("a.txt", "1", "c1")
        GitRepo.open(root)!!.use { repo ->
            val s = repo.status()
            assertTrue(s.staged.isEmpty() && s.unstaged.isEmpty() && s.untracked.isEmpty())
        }
    }
}
