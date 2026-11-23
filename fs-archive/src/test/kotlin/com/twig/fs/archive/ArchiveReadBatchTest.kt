package com.twig.fs.archive

import com.twig.core.*
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveReadBatchTest {
    @get:Rule val tmp = TemporaryFolder()
    private val local = LocalFileSystem()

    private fun zip(): File = tmp.newFile("batch.zip").also { archive ->
        ZipOutputStream(archive.outputStream()).use { out ->
            repeat(100) { index ->
                out.putNextEntry(ZipEntry("dir/f$index.txt"))
                out.write("payload-$index".toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun mount(archive: File, fs: ArchiveFileSystem): Pair<MountFs, XFile> {
        val host = MountFs(tmp.root)
        FsRegistry.register(local)
        FsRegistry.register(host)
        FsRegistry.register(fs)
        return host to fs.rootOf(host.resolve("/${archive.name}"))
    }

    @Test fun zipCopiesUseOneReaderAndCloseIt() {
        val fs = ZipFileSystem()
        val (host, root) = mount(zip(), fs)
        val items = fs.list(root)
        val before = host.opens.get()
        val dest = tmp.newFolder("out")
        CopyEngine.transfer(items, local.resolve(dest.path), false, plannedBytes = 1000,
            checkpoint = TransferCheckpoint())
        repeat(100) { assertEquals("payload-$it", File(dest, "dir/f$it.txt").readText()) }
        assertEquals("one archive channel for the whole recursive transfer", 1, host.opens.get() - before)
        assertEquals(host.opens.get(), host.closes.get())
    }

    @Test fun recursivePlanningUsesOneReaderAndClosesIt() {
        val fs = ZipFileSystem()
        val (host, root) = mount(zip(), fs)
        val before = host.opens.get()
        val plan = CopyEngine.plan(listOf(root))
        assertEquals(100, plan.files)
        assertEquals(2, plan.dirs)
        // The first ZIP write-capability probe has its own short-lived reader.
        assertTrue(host.opens.get() - before <= 2)
        assertEquals(host.opens.get(), host.closes.get())
    }

    @Test fun cancelledBatchClosesItsReaderAndRetryUsesANewOne() {
        val fs = ZipFileSystem()
        val (host, root) = mount(zip(), fs)
        val items = fs.list(root)
        val dest = tmp.newFolder("cancelled")
        val checkpoint = TransferCheckpoint()
        var cancel = false
        CopyEngine.transfer(items, local.resolve(dest.path), false,
            listener = object : CopyEngine.ProgressListener {
                override fun onBytes(copiedTotal: Long, totalBytes: Long) { cancel = true }
            }, cancelled = { cancel }, plannedBytes = 1000, checkpoint = checkpoint)
        assertEquals(host.opens.get(), host.closes.get())
        CopyEngine.transfer(items, local.resolve(dest.path), false, plannedBytes = 1000, checkpoint = checkpoint)
        repeat(100) { assertEquals("payload-$it", File(dest, "dir/f$it.txt").readText()) }
        assertEquals(host.opens.get(), host.closes.get())
    }

    @Test fun failedWriteClosesTheBatchReader() {
        val fs = ZipFileSystem()
        val (host, root) = mount(zip(), fs)
        val dest = tmp.newFolder("failure")
        val failing = object : FileSystem by local {
            override val scheme = "failing-batch-output"
            override fun list(dir: XFile) = local.list(dir.copy(scheme = "file")).map { it.copy(scheme = scheme) }
            override fun delete(file: XFile) = local.delete(file.copy(scheme = "file"))
            override fun createFile(parent: XFile, name: String) =
                local.createFile(parent.copy(scheme = "file"), name).copy(scheme = scheme)
            override fun mkdir(parent: XFile, name: String) =
                local.mkdir(parent.copy(scheme = "file"), name).copy(scheme = scheme)
            override fun openOutput(file: XFile, append: Boolean): java.io.OutputStream =
                throw FsException("Write failed")
        }
        FsRegistry.register(failing)
        try {
            assertThrows(FsException::class.java) {
                CopyEngine.transfer(fs.list(root), local.resolve(dest.path).copy(scheme = failing.scheme),
                    false, plannedBytes = 1000)
            }
            assertEquals(host.opens.get(), host.closes.get())
        } finally { FsRegistry.unregister(failing.scheme) }
    }

    @Test fun solidSevenZReusesOneReaderAndSupportsBackwardEntryReads() {
        val archive = tmp.newFile("solid.7z")
        archive.writeBytes(java.util.Base64.getDecoder().decode(SOLID_7Z))
        val fs = SevenZFileSystem()
        val (host, root) = mount(archive, fs)
        val files = fs.list(root)
        val before = host.opens.get()
        val dest = tmp.newFolder("solid-out")
        // Deliberately go backwards, then forwards through a shared solid block.
        val reordered = files.filterIndexed { i, _ -> i % 2 == 0 }.reversed() +
            files.filterIndexed { i, _ -> i % 2 != 0 }
        CopyEngine.transfer(reordered, local.resolve(dest.path), false,
            plannedBytes = files.sumOf { it.size }, checkpoint = TransferCheckpoint())
        repeat(20) { assertEquals("payload-$it\n".repeat(2000), File(dest, "f%02d.txt".format(it)).readText()) }
        assertEquals(1, host.opens.get() - before)
        assertEquals(host.opens.get(), host.closes.get())
        fs.openReadBatch().use { batch ->
            for (file in reordered) {
                val index = file.name.substring(1, 3).toInt()
                assertEquals("payload-$index\n".repeat(2000), batch.openInput(file).bufferedReader().use { it.readText() })
            }
        }
        assertEquals(host.opens.get(), host.closes.get())
    }

    companion object {
        // Independent fixture: 7z a -ms=on -mx=1 solid.7z .
        // f00.txt..f19.txt each contain ("payload-N\n").repeat(2000).
        private const val SOLID_7Z = "N3q8ryccAASbyW7L8AEAAAAAAAAjAAAAAAAAAGv0W6zmaJ8A2F0AOBhLmXUPRghqzjK8ySt7RmsL5qUK7CI4PjI5NTi6mAUA4a1Xrabv5R0TJZVLilUcD+3RWQTid+3nTt84+k7zcaXkE2ipowTlHmmCULjtytsyzlXzGrCa13ShWiWkUdeAX/eOXLjesepLKcBg+bHDXh2Ux/QuuTW5WtmsD3f7W9/TSpbxAdPBWCiMaUuiJJ0L2cSrU/kDvAA60SZZG3IQtWxWJoWqgtCD4FVD4pOUg7qUq5DQKpq1VAM57G2BLzyDNHw5ZJp+v380rPSUUjU3ocUFUVxJpcgAAAAAgTMHrg/VMcgJFyVHVwXTGtN9bEsh53SGDhf/zGLRLkHwUQnMRLND3QbJnWdcxmzazL6wBgC17FlUP45J3avWfkc7oBPxNs/Vpu/HvsqDUuX2SH+9+xTRwrBhkmFjbHTlqw4iaNG276Ncnaw5jOO+7PsiAN49va95NGtVYvRsatYb6lxH5iVjtgmqfY4U4wi1bmFGGhNmXEaCqhZM7NPngO4PSzbbg5IVy47c+ZrK4RTckL6y0QkDUxvu++BKz2KJfOtuBrubMSmhpR7tJiInTUnjNyZdoBpSOiK7/kOWMLdpqSLRBupvMLKLkdckh3O1RvwE+9AcMfjei9UcG+IAxXTW6QnRENYhhAcwgAAAFwaA4AEJgRAABwsBAAEjAwEBBV0AEAAADILuCgFTUXgCAAA="
    }
}
