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
 * A git **worktree** on a remote source (SMB/WebDAV): `.git` is a file, and what
 * `gitdir:` points at is **an absolute path on another machine**, which can never be
 * found here — [openRemoteRepo] has to fall back on "the relative position within the
 * repo" and read gitdir/commondir as two separate pieces before history and changes can
 * come out at all.
 *
 * The real server in this chain is swapped for [MountFs] (exposing a local directory as
 * a share root), but **the repository itself is built by real git**: object store,
 * packfile and index are all genuine bytes, which is the only way to actually test
 * whether the routing is right (before touching this area, read the "all sources" lesson
 * about WiFi sharing in CLAUDE.md — a new mode needs a test that covers it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteGitWorktreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    /** Exposes the local directory [base] as some server's share root (paths are always relative to it). */
    private class MountFs(
        override val scheme: String,
        private val base: File,
        /** Mimics `SftpFileSystem.resolve`: never stats, never errors, claims every path is a directory. */
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
        File(root, path).apply { parentFile?.mkdirs() }.writeText(content)
        git(root, "add", path); git(root, "commit", "-m", msg)
    }

    /** Mounts [root] as a server, returning the [XFile] for some path on it. */
    private fun mount(scheme: String, path: String, lyingResolve: Boolean = false): XFile {
        FsRegistry.register(MountFs(scheme, root, lyingResolve))
        return XFile(scheme, path, isDir = true)
    }

    @Test
    fun `a remote worktree can see history and changes`() {
        commitFile("a.txt", "1", "c1")
        commitFile("a.txt", "2", "c2")
        git(root, "worktree", "add", "-b", "feat", "wt")
        File(root, "wt/a.txt").writeText("changed")   // unstaged
        File(root, "wt/new.txt").writeText("n")       // untracked
        git(root, "gc", "--prune=now")                // move objects into the main repo's packfile, closer to a real repo

        val repo = openRemoteRepo(mount("mnt1", "/wt"))!!
        repo.use {
            assertEquals("feat", it.branch())
            assertEquals(listOf("c2", "c1"), it.log(0, 10).map { c -> c.title })
            assertEquals(setOf("main", "feat"), it.branches().map { b -> b.name }.toSet())
            val s = it.status()
            assertEquals(listOf("a.txt" to ChangeKind.MODIFIED), s.unstaged.map { c -> c.path to c.kind })
            assertEquals(listOf("new.txt"), s.untracked)
            assertTrue(s.staged.isEmpty())
            // being able to read the content proves objects really did route to commondir
            val tree = it.flattenTree(it.commit(it.log(0, 1)[0].sha)!!.tree)
            assertEquals("2", it.readBlob(tree["a.txt"]!!)!!.decodeToString())
        }
    }

    @Test
    fun `an ordinary remote repository is unaffected`() {
        commitFile("a.txt", "1", "c1")
        openRemoteRepo(mount("mnt2", "/"))!!.use {
            assertEquals("main", it.branch())
            assertEquals(listOf("c1"), it.log(0, 10).map { c -> c.title })
        }
    }

    @Test
    fun `returns null when the remote directory is not a repository`() {
        File(root, "plain").mkdirs()
        assertNull(openRemoteRepo(mount("mnt3", "/plain")))
    }

    /**
     * "Worktrees" in the virtual tree: the main repository's git view lists the other
     * worktrees, and clicking into one gives **another full** git view (changes / other
     * branches / history). The easiest thing to get wrong remotely is path mapping — what
     * the repository records is an absolute path on that machine
     * (`/tmp/junitXXX/repo/wt`), while the mount root here is `/`.
     */
    @Test
    fun `the worktrees node can list and click into another worktree`() {
        commitFile("a.txt", "1", "c1")
        git(root, "worktree", "add", "-b", "feat", "wt")
        File(root, "wt/a.txt").writeText("changed")

        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val host = mount("mnt4", "/")           // mount root = main repository
        val fs = GitFileSystem(ctx, gitDataFor(host)!!.first, "gtest4", "Git (main)", host = host)
        FsRegistry.register(fs)

        // the root now has an extra "Worktrees (1)": itself is not listed again, only wt
        val rootKids = fs.list(fs.root()).map { it.path }
        assertTrue(rootKids.toString(), rootKids.contains("/worktrees"))
        val wts = fs.list(XFile("gtest4", "/worktrees", isDir = true))
        assertEquals(1, wts.size)
        assertTrue(wts[0].name, wts[0].name.startsWith("wt (feat)"))

        // click in: it's a separate GitFileSystem, showing wt's own branch and changes
        val sub = FsRegistry.of(wts[0]) as GitFileSystem
        sub.invalidate() // fills branchCache, the tree root title follows this worktree's branch
        assertEquals("Git (feat)", sub.displayName)
        val changes = sub.list(XFile(wts[0].scheme, "/changes/unstaged", isDir = true))
        assertEquals(listOf("M a.txt"), changes.map { it.displayName })
        // the nested level no longer lists worktrees, to avoid an infinite A->B->A nesting
        assertTrue(sub.list(sub.root()).none { it.path == "/worktrees" })
    }

    /**
     * ★ This is exactly what broke on a real device: SFTP's `resolve()` never stats and
     * reports isDir=true for any path, so the worktree's `.git` **file** was treated as a
     * directory and not one byte could be read from it — "Worktrees (n)" had a count but
     * expanding it showed nothing. Existence/type checks always go through "list the
     * parent directory and look up by name", never touching resolve.
     */
    @Test
    fun `a source whose resolve does not stat (like SFTP) can still list worktrees`() {
        commitFile("a.txt", "1", "c1")
        git(root, "worktree", "add", "-b", "feat", "wt")

        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val host = mount("mnt6", "/", lyingResolve = true)
        // it must be possible to tell that .git is a file, otherwise the whole worktree goes unrecognized
        openRemoteRepo(XFile("mnt6", "/wt", isDir = true))!!.use { assertEquals("feat", it.branch()) }

        val fs = GitFileSystem(ctx, gitDataFor(host)!!.first, "gtest6", "Git (main)", host = host)
        FsRegistry.register(fs)
        val wts = fs.list(XFile("gtest6", "/worktrees", isDir = true))
        assertEquals(1, wts.size)
        assertTrue(wts[0].name, wts[0].name.startsWith("wt (feat)"))
    }

    /** An SSH repository's worktree listing goes through `git worktree list --porcelain`; this verifies parsing against real git. */
    @Test
    fun `porcelain output parses into a worktree listing`() {
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
        assertTrue(all.single { it.main }.current) // repoPath is the main repository
        assertEquals("feat", all.single { it.name == "wt" }.branch)
        assertTrue(all.single { it.name == "wt" }.locked)
        // detached: no branch line, falls back to the short sha
        val head = git(root, "rev-parse", "--short=7", "HEAD").trim()
        assertEquals(head, all.single { it.name == "det" }.branch)
    }

    @Test
    fun `the worktrees node is hidden when there are no other worktrees`() {
        commitFile("a.txt", "1", "c1")
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val host = mount("mnt5", "/")
        val fs = GitFileSystem(ctx, gitDataFor(host)!!.first, "gtest5", "Git (main)", host = host)
        assertTrue(fs.list(fs.root()).none { it.path == "/worktrees" })
    }
}
