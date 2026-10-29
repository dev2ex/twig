package com.twig.fs.local

import com.twig.core.UnixMetadata
import com.twig.core.XFile
import com.twig.fs.local.priv.PrivilegedFs
import com.twig.fs.local.priv.PrivilegedShell
import com.twig.fs.local.priv.SuLauncher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime

class UnixMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun stat(path: String): UnixMetadata {
        val a = Files.readAttributes(File(path).toPath(), "unix:*", LinkOption.NOFOLLOW_LINKS)
        fun n(key: String) = (a.getValue(key) as Number).toLong()
        fun time(key: String) = (a.getValue(key) as FileTime).toMillis() / 1000 * 1000
        return UnixMetadata(n("mode").toInt(), n("uid"), n("gid"), n("size"),
            time("lastModifiedTime"), time("lastAccessTime"), time("ctime"))
    }

    @Test fun `ordinary listings use stat without context reads and links retain stream sizes`() {
        val target = tmp.newFile("target").apply { writeText("target bytes are longer than the link") }
        val preciseTime = 1600000000123L
        Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(preciseTime))
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        var contexts = 0
        var readLinks = 0
        val fs = LocalFileSystem(tmp.root,
            readUnix = ::stat,
            readLink = { readLinks++; Files.readSymbolicLink(File(it).toPath()).toString() },
            readContext = { contexts++; "u:object_r:app_data_file:s0" },
        )
        val listed = fs.list(fs.root()).associateBy { it.name }
        assertEquals(0, contexts)
        assertEquals("only links need readlink", 1, readLinks)
        assertEquals(stat(target.path).mode, listed.getValue("target").unixMode)
        assertEquals(preciseTime, listed.getValue("target").lastModified)
        assertTrue(listed.getValue("link").isSymbolicLink)
        assertEquals(stat(link.path).mode, listed.getValue("link").unixMode)
        assertEquals(target.length(), listed.getValue("link").size)
        assertEquals(link.path, listed.getValue("link").path)
        val metadata = fs.unixMetadata(listed.getValue("link"))!!
        assertEquals(stat(link.path).uid, metadata.uid)
        assertEquals("u:object_r:app_data_file:s0", metadata.selinuxContext)
        assertEquals(1, contexts)
    }

    @Test fun `privileged properties use lstat with quoted newline paths and unreadable attributes stay unknown`() {
        val file = tmp.newFile("quoted ' pipe| newline\nfile").apply { writeText("hello") }
        val link = File(tmp.root, "broken ' link\n")
        Files.createSymbolicLink(link.toPath(), File(tmp.root, "missing").toPath())
        val shell = PrivilegedShell(SuLauncher("/bin/sh"), timeoutMs = 10000)
        assertTrue(shell.connect(grantTimeoutMs = 10000))
        try {
            val privileged = PrivilegedFs(shell)
            for (entry in listOf(file, link)) {
                val expected = stat(entry.path)
                val actual = privileged.unixMetadata(entry.path)!!
                assertEquals(expected.copy(selinuxContext = actual.selinuxContext), actual)
                assertEquals(expected.mode, privileged.stat(entry.path)!!.unixMode)
            }
            val plain = tmp.newFile("plain")
            Files.setAttribute(plain.toPath(), "unix:mode", "6750".toInt(8))
            assertEquals(stat(plain.path).mode, privileged.list(tmp.root.path)!!.first { it.path == plain.path }.unixMode)
            val scanned = ArrayList<XFile>()
            privileged.walk(tmp.root.path, 1, { false }, scanned::add)
            assertEquals(stat(plain.path).mode, scanned.first { it.path == plain.path }.unixMode)
            assertNull(privileged.unixMetadata(File(tmp.root, "missing").path))
            LocalFileSystem.elevation = privileged
            val ordinary = LocalFileSystem(readUnix = { null }, readContext = { null })
            assertEquals(stat(file.path).mode, ordinary.unixMetadata(XFile("file", file.path, false))!!.mode)
            LocalFileSystem.withoutElevation {
                assertNull(ordinary.unixMetadata(XFile("file", file.path, false)))
            }
        } finally {
            LocalFileSystem.elevation = null
            shell.close()
        }
    }

    @Test fun `SELinux parsing only accepts the context before the filename`() {
        assertEquals("u:object_r:system_file:s0", PrivilegedFs.parseContext("u:object_r:system_file:s0 /a file\n"))
        assertEquals("u:object_r:app_data_file:s0:c1,c2", PrivilegedFs.parseContext("  u:object_r:app_data_file:s0:c1,c2 /a"))
        assertNull(PrivilegedFs.parseContext("? /a"))
        assertNull(PrivilegedFs.parseContext("? u:object_r:looks_like_a_context:s0"))
        assertNull(PrivilegedFs.parseContext("ls: cannot access /a"))
    }
}
