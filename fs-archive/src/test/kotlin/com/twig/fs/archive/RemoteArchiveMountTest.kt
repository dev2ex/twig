package com.twig.fs.archive

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Archives on a **non-local host** (the SMB/WebDAV/SFTP/S3 kind).
 *
 * This path had never been tested, and it differs from local archives in one crucial
 * way: the archive bytes are fetched via `FsRegistry.of(host).openRandom(host)`, and
 * "who the host is" is only **registered at the moment of mounting** by
 * [ArchiveFileSystem.rootOf]. Any call that reads the archive before `rootOf` runs
 * (e.g. [ArchiveFileSystem.needsPassword] scanning for encrypted entries) will try to
 * open the remote path as a local file — local archives work fine while remote ones
 * fail with a plain FileNotFound.
 */
class RemoteArchiveMountTest {

    private lateinit var share: File
    private lateinit var archive: File
    private val zfs = ZipFileSystem()

    @Before
    fun setup() {
        share = File.createTempFile("twigremote", "").let { it.delete(); it.mkdirs(); it }
        archive = File(share, "box.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("a.txt"))
            z.write("remote content".toByteArray())
            z.closeEntry()
        }
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(MountFs(share))
        FsRegistry.register(zfs)
    }

    /** The zip file on the remote host (the path is "the path within the share"; it does not exist locally). */
    private fun remoteArchive() =
        XFile("remote", "/box.zip", isDir = false, size = archive.length(), lastModified = archive.lastModified())

    @Test
    fun remoteArchiveListsAndReadsAfterMounting() {
        val root = zfs.rootOf(remoteArchive())
        val kids = zfs.list(root)
        assertEquals(listOf("a.txt"), kids.map { it.name })
        assertEquals("remote content", zfs.openInput(kids[0]).use { it.readBytes() }.toString(Charsets.UTF_8))
    }

    /**
     * ★ **Encryption detection must happen after [ArchiveFileSystem.rootOf]**. The host
     * is only registered at the moment of mounting; before that, `hostOf()` opens the
     * remote path as a local file — it cannot read the bytes, and
     * [ArchiveFileSystem.needsPassword] swallows the exception into "no password needed"
     * (see the runCatching in `firstEncrypted`), so **a remote encrypted archive never
     * pops up the password dialog** — the error only surfaces once the user opens a file
     * inside it. Not erroring, but giving the wrong answer, is the hardest kind of bug to
     * track down.
     */
    @Test
    fun remoteEncryptedArchiveOnlyDetectedAsNeedingAPasswordAfterMounting() {
        val enc = File(share, "enc.zip")
        enc.outputStream().use { os ->
            ZipWriter(os, "secret").use { zw ->
                zw.putNextEntry("s.txt", System.currentTimeMillis(), sizeHint = 3)
                zw.write("hi!".toByteArray())
                zw.closeEntry()
            }
        }
        val remote = XFile("remote", "/enc.zip", isDir = false, size = enc.length(), lastModified = enc.lastModified())

        // Before mounting: the host is not registered yet, so it cannot be detected (but must not throw either)
        assertFalse(zfs.needsPassword(remote.path))

        // After mounting: this is the real answer
        zfs.rootOf(remote)
        org.junit.Assert.assertTrue(zfs.needsPassword(remote.path))
        org.junit.Assert.assertTrue(zfs.checkPassword(remote.path, "secret"))
        assertFalse(zfs.checkPassword(remote.path, "nope"))
    }

    @Test
    fun remoteArchiveIsReadOnlyNoAppendNoRewrite() {
        val root = zfs.rootOf(remoteArchive())
        assertFalse("the remote host cannot be positionally written to, so entries inside the archive must not show as writable", root.canWrite)
        assertFalse(zfs.list(root).first().canWrite)
    }
}
