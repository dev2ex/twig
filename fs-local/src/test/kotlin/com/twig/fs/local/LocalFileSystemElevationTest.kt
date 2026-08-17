package com.twig.fs.local

import com.twig.core.XFile
import com.twig.fs.local.priv.PrivilegedFs
import com.twig.fs.local.priv.PrivilegedLauncher
import com.twig.fs.local.priv.PrivilegedProcess
import com.twig.fs.local.priv.PrivilegedShell
import com.twig.fs.local.priv.SuLauncher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The elevation hook on [LocalFileSystem].
 *
 * ★ What this cannot cover: the case elevation exists *for* — a directory the
 * process genuinely may not read, which the privileged shell then reads anyway.
 * On a dev machine the fallback shell runs as the same uid, so it fails on exactly
 * the same paths and no asymmetry can be manufactured. That half is verified on a
 * rooted device (or with Shizuku); what is pinned down here is everything that can
 * regress silently on every other device: that installing the hook changes no
 * behaviour on paths that already worked, and that the hot paths do not start
 * paying for a shell round trip per file.
 */
class LocalFileSystemElevationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = LocalFileSystem()
    private var shell: PrivilegedShell? = null

    @After
    fun tearDown() {
        LocalFileSystem.elevation = null
        shell?.close()
    }

    /** Installs a working fallback and counts how many processes it spawns. */
    private fun installElevation(): AtomicInteger {
        val spawns = AtomicInteger()
        val real = SuLauncher("/bin/sh")
        val counting = PrivilegedLauncher { cmd: String? ->
            spawns.incrementAndGet()
            real.start(cmd)
        }
        val s = PrivilegedShell(counting, timeoutMs = 10_000)
        assertTrue(s.connect(grantTimeoutMs = 10_000))
        shell = s
        LocalFileSystem.elevation = PrivilegedFs(s)
        spawns.set(0) // ignore the session itself
        return spawns
    }

    @Test
    fun `listing a readable directory is unchanged by the hook`() {
        val dir = tmp.newFolder("plain")
        File(dir, "b.txt").writeText("bb")
        File(dir, "a.txt").writeText("a")
        File(dir, "sub").mkdirs()
        val before = fs.list(XFile("file", dir.absolutePath, isDir = true))

        installElevation()
        val after = fs.list(XFile("file", dir.absolutePath, isDir = true))

        assertEquals(before, after)
        // Directories first, then case-insensitive by name — the ordering the tree
        // relies on, which the fallback path has to reproduce too.
        assertEquals(listOf("sub", "a.txt", "b.txt"), after.map { it.name })
    }

    /**
     * `exists` is called once per file while copying. If a plain missing target
     * consulted the shell, every copy into ordinary storage would pay a round trip
     * per file for nothing — the parent is readable, so "not there" is the truth.
     */
    @Test
    fun `a missing file under a readable parent never reaches the shell`() {
        val spawns = installElevation()
        val missing = XFile("file", File(tmp.root, "nope.txt").absolutePath, isDir = false)

        repeat(50) { assertFalse(fs.exists(missing)) }

        assertEquals("should not have spawned anything", 0, spawns.get())
    }

    @Test
    fun `reading and writing readable paths does not spawn helper processes`() {
        val spawns = installElevation()
        val f = XFile("file", File(tmp.root, "data.txt").absolutePath, isDir = false)

        fs.openOutput(f, append = false).use { it.write("hello".toByteArray()) }
        val read = fs.openInput(f).use { String(it.readBytes()) }

        assertEquals("hello", read)
        assertEquals("plain java.io should have handled both", 0, spawns.get())
    }

    @Test
    fun `rename and delete on readable paths stay on the plain path`() {
        val spawns = installElevation()
        val src = File(tmp.root, "one.txt").apply { writeText("x") }

        val renamed = fs.rename(XFile("file", src.absolutePath, isDir = false), "two.txt")
        assertEquals("two.txt", renamed.name)
        fs.delete(renamed)

        assertFalse(File(tmp.root, "two.txt").exists())
        assertEquals(0, spawns.get())
    }

    /** The overwrite guard must hold on the fallback path too, not just in java.io. */
    @Test
    fun `rename still refuses an existing target with the hook installed`() {
        installElevation()
        val src = File(tmp.root, "src.txt").apply { writeText("src") }
        File(tmp.root, "dst.txt").writeText("dst")

        val e = runCatching { fs.rename(XFile("file", src.absolutePath, isDir = false), "dst.txt") }
        assertTrue(e.isFailure)
        assertEquals("dst", File(tmp.root, "dst.txt").readText())
    }

    @Test
    fun `uninstalling the hook restores the original behaviour`() {
        installElevation()
        LocalFileSystem.elevation = null
        val missing = XFile("file", "/proc/1/definitely-not-here", isDir = false)
        assertFalse(fs.exists(missing))
        assertTrue(runCatching { fs.list(XFile("file", "/proc/1/fd", isDir = true)) }.isFailure)
    }

    /** Guards the fun-interface wiring: a launcher may be a plain lambda. */
    @Test
    fun `launcher can be supplied as a lambda`() {
        val real = SuLauncher("/bin/sh")
        val lambda = PrivilegedLauncher { cmd: String? -> real.start(cmd) as PrivilegedProcess }
        val s = PrivilegedShell(lambda, timeoutMs = 10_000)
        shell = s
        assertTrue(s.connect(grantTimeoutMs = 10_000))
        assertEquals("ok", s.exec("echo ok").text)
    }
}
