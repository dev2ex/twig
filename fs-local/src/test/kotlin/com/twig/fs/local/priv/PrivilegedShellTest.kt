package com.twig.fs.local.priv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the real thing against `/bin/sh`.
 *
 * `su` and Shizuku only differ in how the process is spawned — the framing, the
 * quoting and the `stat` parsing are identical, and those are where the bugs live.
 * Running them against a genuine shell catches quoting and parsing mistakes that a
 * mocked launcher would only confirm we imagined correctly.
 */
class PrivilegedShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var shell: PrivilegedShell
    private lateinit var fs: PrivilegedFs

    @Before
    fun setUp() {
        shell = PrivilegedShell(SuLauncher("/bin/sh"), timeoutMs = 10_000)
        assertTrue("could not start /bin/sh", shell.connect(grantTimeoutMs = 10_000))
        fs = PrivilegedFs(shell)
    }

    @After
    fun tearDown() {
        shell.close()
    }

    @Test
    fun `connect reads back a uid`() {
        assertTrue(shell.uid >= 0)
        assertTrue(shell.alive())
    }

    @Test
    fun `exec returns exit code and stdout`() {
        val r = shell.exec("echo hello; echo world")
        assertTrue(r.ok)
        assertEquals(listOf("hello", "world"), r.lines)

        // A subshell, because a bare `exit` would end the session itself — see
        // `commands work again after the session process is killed`.
        val bad = shell.exec("(exit 3)")
        assertEquals(3, bad.code)

        assertFalse(shell.exec("ls /definitely/not/here").ok)
    }

    @Test
    fun `session is reused across commands`() {
        shell.exec("TWIG_MARK=kept")
        assertEquals("kept", shell.exec("echo \$TWIG_MARK").text)
    }

    @Test
    fun `a failing command does not poison the session`() {
        assertFalse(shell.exec("cat /definitely/not/here").ok)
        assertEquals("still alive", shell.exec("echo 'still alive'").text)
    }

    /**
     * Output that looks like the end marker must not end the command early. The
     * marker is unique per command precisely so a file full of previous markers
     * cannot truncate a later read.
     */
    @Test
    fun `output resembling a marker is not treated as one`() {
        val r = shell.exec("echo __twig_0_123__ 0; echo after")
        assertTrue(r.ok)
        assertEquals(listOf("__twig_0_123__ 0", "after"), r.lines)
    }

    /**
     * A command writing more stderr than the pipe buffer holds must still finish.
     * It only does if the drain thread never contends with the lock `exec` holds
     * while waiting — otherwise nobody reads stderr, the writer blocks at 64 KB,
     * the end marker never arrives, and the command dies of timeout instead.
     */
    @Test
    fun `a command flooding stderr still completes`() {
        val r = shell.exec("i=0; while [ \$i -lt 4000 ]; do echo 'noise noise noise noise noise' 1>&2; i=\$((i+1)); done; echo done", timeout = 20_000)
        assertTrue("stderr flood should not have stalled the command", r.ok)
        assertEquals("done", r.text)
        assertTrue(shell.alive())
    }

    /** Large stdout must not be truncated by the framing either. */
    @Test
    fun `a command with thousands of output lines is read whole`() {
        val r = shell.exec("i=0; while [ \$i -lt 5000 ]; do echo line\$i; i=\$((i+1)); done")
        assertTrue(r.ok)
        assertEquals(5000, r.lines.size)
        assertEquals("line4999", r.lines.last())
    }

    /** Many symlinks are re-stat'd in batches; the results must all come back. */
    @Test
    fun `listing many symlinks resolves every one of them`() {
        val dir = tmp.newFolder("manylinks")
        val target = File(dir, "target").apply { mkdirs() }
        val n = 450 // more than one batch
        repeat(n) { i ->
            shell.exec("ln -s ${PrivilegedShell.quote(target.absolutePath)} ${PrivilegedShell.quote(File(dir, "l$i").absolutePath)}")
        }
        val items = fs.list(dir.absolutePath)!!.filter { it.name.startsWith("l") }
        assertEquals(n, items.size)
        assertTrue("every link should resolve to a directory", items.all { it.isDir })
    }

    @Test
    fun `quote survives every shell metacharacter`() {
        val nasty = listOf(
            "plain.txt",
            "with space.txt",
            "quote's.txt",
            "\$(whoami).txt",
            "back`tick`.txt",
            "semi;rm -rf /.txt",
            "pipe|bar.txt",
            "star*.txt",
            "new\"quote\".txt",
        )
        for (name in nasty) {
            val echoed = shell.exec("echo ${PrivilegedShell.quote(name)}").text
            assertEquals("mangled: $name", name, echoed)
        }
    }

    @Test
    fun `list reports type size and mtime`() {
        val dir = tmp.newFolder("listing")
        File(dir, "a.txt").writeText("12345")
        File(dir, "sub").mkdirs()

        val items = fs.list(dir.absolutePath)!!.associateBy { it.name }
        assertEquals(2, items.size)
        assertEquals(5L, items["a.txt"]!!.size)
        assertFalse(items["a.txt"]!!.isDir)
        assertTrue(items["sub"]!!.isDir)
        assertTrue(items["a.txt"]!!.lastModified > 0L)
    }

    /**
     * `%n` is last in the stat format so a name containing the field separator is
     * still recovered intact — the parser splits off three fields and takes the
     * rest verbatim rather than splitting the whole line.
     */
    @Test
    fun `list handles names containing the separator and spaces`() {
        val dir = tmp.newFolder("odd")
        val names = listOf("pipe|in|name.txt", "with space.txt", "quote's.txt", "\$dollar.txt")
        names.forEach { File(dir, it).writeText("x") }

        val got = fs.list(dir.absolutePath)!!.map { it.name }.toSet()
        assertEquals(names.toSet(), got)
    }

    @Test
    fun `list of a hidden entry is included`() {
        val dir = tmp.newFolder("hidden")
        File(dir, ".secret").writeText("x")
        assertEquals(listOf(".secret"), fs.list(dir.absolutePath)!!.map { it.name })
    }

    @Test
    fun `empty directory lists as empty not as failure`() {
        val dir = tmp.newFolder("empty")
        assertEquals(emptyList<String>(), fs.list(dir.absolutePath)!!.map { it.name })
    }

    /**
     * A symlink pointing at a directory has to come back as a directory or the tree
     * will not offer to expand it. lstat alone cannot say that, hence the second
     * `stat -L` pass.
     */
    @Test
    fun `symlink to a directory is reported as a directory`() {
        val dir = tmp.newFolder("links")
        val target = File(dir, "realdir").apply { mkdirs() }
        shell.exec("ln -s ${PrivilegedShell.quote(target.absolutePath)} ${PrivilegedShell.quote(File(dir, "link").absolutePath)}")
        shell.exec("ln -s /nowhere/at/all ${PrivilegedShell.quote(File(dir, "broken").absolutePath)}")

        val items = fs.list(dir.absolutePath)!!.associateBy { it.name }
        assertTrue("link to dir should be a directory", items["link"]!!.isDir)
        assertFalse("broken link must not claim to be a directory", items["broken"]!!.isDir)
    }

    @Test
    fun `stat of a missing path is null`() {
        assertNull(fs.stat(tmp.root.absolutePath + "/nope"))
        assertNotNull(fs.stat(tmp.root.absolutePath))
    }

    @Test
    fun `read and write round trip through one-shot processes`() {
        val f = File(tmp.root, "data.bin")
        val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }

        fs.openOutput(f.absolutePath, append = false).use { it.write(payload) }
        assertArrayEqualsMsg(payload, f.readBytes())

        val readBack = fs.openInput(f.absolutePath).use { it.readBytes() }
        assertArrayEqualsMsg(payload, readBack)
    }

    /** Binary content must survive verbatim — it is never decoded as text. */
    @Test
    fun `binary content with newlines and nulls survives`() {
        val f = File(tmp.root, "raw.bin")
        val payload = byteArrayOf(0, 10, 13, 0, 26, -1, 65, 10, 10, -128)
        fs.openOutput(f.absolutePath, append = false).use { it.write(payload) }
        assertArrayEqualsMsg(payload, fs.openInput(f.absolutePath).use { it.readBytes() })
    }

    @Test
    fun `append adds to the end`() {
        val f = File(tmp.root, "log.txt")
        fs.openOutput(f.absolutePath, append = false).use { it.write("one".toByteArray()) }
        fs.openOutput(f.absolutePath, append = true).use { it.write("two".toByteArray()) }
        assertEquals("onetwo", f.readText())
    }

    @Test
    fun `mkdir delete and exists`() {
        val d = File(tmp.root, "made")
        assertTrue(fs.mkdir(d.absolutePath))
        assertTrue(d.isDirectory)
        assertTrue(fs.exists(d.absolutePath))
        // Not -p: creating something that is already there must be reported.
        assertFalse(fs.mkdir(d.absolutePath))

        File(d, "child.txt").writeText("x")
        assertTrue(fs.delete(d.absolutePath))
        assertFalse(d.exists())
        assertFalse(fs.exists(d.absolutePath))
    }

    /**
     * `mv` replaces an existing target without a word. The FileSystem contract says
     * that must never happen behind the user's back, so rename refuses instead.
     */
    @Test
    fun `rename refuses to overwrite an existing target`() {
        val src = File(tmp.root, "src.txt").apply { writeText("src") }
        val dst = File(tmp.root, "dst.txt").apply { writeText("dst") }

        assertFalse(fs.rename(src.absolutePath, dst.absolutePath))
        assertEquals("dst", dst.readText())
        assertEquals("src", src.readText())

        val fresh = File(tmp.root, "fresh.txt")
        assertTrue(fs.rename(src.absolutePath, fresh.absolutePath))
        assertFalse(src.exists())
        assertEquals("src", fresh.readText())
    }

    @Test
    fun `setModifiedTime writes the requested time`() {
        val f = File(tmp.root, "t.txt").apply { writeText("x") }
        val want = 1_600_000_000_000L
        assertTrue(fs.setModifiedTime(f.absolutePath, want))
        // touch -t has one-second granularity.
        assertEquals(want / 1000, f.lastModified() / 1000)
    }

    /**
     * A timeout leaves the reader parked mid-stream, so the session's framing is
     * gone and it must be torn down. The next command has to transparently rebuild
     * it — the same recovery that a revoked Magisk grant relies on.
     */
    @Test
    fun `timeout kills the session and the next command recovers`() {
        val r = shell.exec("sleep 30", timeout = 700)
        assertFalse(r.ok)
        assertFalse("session should have been torn down", shell.alive())

        assertEquals("back", shell.exec("echo back").text)
        assertTrue(shell.alive())
    }

    @Test
    fun `commands work again after the session process is killed`() {
        shell.exec("exit 0") // ends the interactive shell
        assertEquals("revived", shell.exec("echo revived").text)
    }

    private fun assertArrayEqualsMsg(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        val at = expected.indices.firstOrNull { expected[it] != actual[it] }
        if (at != null) throw AssertionError("byte $at: ${expected[at]} != ${actual[at]}")
    }
}
