package com.twig.fs.archive

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **非本地宿主**上的压缩包(SMB/WebDAV/SFTP/S3 那一类)。
 *
 * 这条路一直没有测试,而它和本地包差着一件要命的事:归档的字节是经
 * `FsRegistry.of(host).openRandom(host)` 拿的,而"host 是谁"是 [ArchiveFileSystem.rootOf]
 * **挂载那一刻才登记**的。任何在 `rootOf` 之前就去读归档的调用(比如
 * [ArchiveFileSystem.needsPassword] 要扫一遍看有没有加密条目),都会把远程路径
 * 当成本地文件去开 —— 本地包一切正常,远程包直接 FileNotFound。
 */
class RemoteArchiveMountTest {

    /** 最小的"远程"来源:scheme 不是 file,内容其实落在本地一个目录里。 */
    private class MountFs(private val rootDir: File) : FileSystem {
        override val scheme = "remote"
        override val displayName = "Remote"
        override fun root() = XFile(scheme, "/", isDir = true)
        private fun real(path: String) = File(rootDir, path.trimStart('/'))
        override fun resolve(path: String): XFile {
            val f = real(path)
            return XFile(scheme, path, isDir = f.isDirectory, size = f.length(), lastModified = f.lastModified())
        }
        override fun list(dir: XFile): List<XFile> =
            real(dir.path).listFiles()?.map { resolve("${dir.path.trimEnd('/')}/${it.name}") } ?: emptyList()
        override fun openInput(file: XFile): InputStream = real(file.path).inputStream()
        override fun openOutput(file: XFile, append: Boolean): OutputStream = real(file.path).outputStream()
        override fun mkdir(parent: XFile, name: String): XFile {
            File(real(parent.path), name).mkdirs()
            return resolve("${parent.path.trimEnd('/')}/$name")
        }
        override fun delete(file: XFile) { real(file.path).deleteRecursively() }
        override fun rename(file: XFile, newName: String): XFile {
            val dst = File(real(file.path).parentFile, newName)
            real(file.path).renameTo(dst)
            return resolve("${file.parentPath.trimEnd('/')}/$newName")
        }
        override fun exists(file: XFile) = real(file.path).exists()
        override fun randomAccessEfficient() = true
        override fun openRandom(file: XFile): RandomSource {
            val raf = RandomAccessFile(real(file.path), "r")
            return object : RandomSource {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    raf.seek(position)
                    return raf.read(buffer, offset, length)
                }
                override fun length() = raf.length()
                override fun close() = raf.close()
            }
        }
    }

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

    /** 远程宿主上那个 zip 文件(路径是"共享里的路径",本地并不存在)。 */
    private fun remoteArchive() =
        XFile("remote", "/box.zip", isDir = false, size = archive.length(), lastModified = archive.lastModified())

    @Test
    fun `远程包挂载后能列目录能读内容`() {
        val root = zfs.rootOf(remoteArchive())
        val kids = zfs.list(root)
        assertEquals(listOf("a.txt"), kids.map { it.name })
        assertEquals("remote content", zfs.openInput(kids[0]).use { it.readBytes() }.toString(Charsets.UTF_8))
    }

    /**
     * ★ **探测加密必须在 [ArchiveFileSystem.rootOf] 之后**。宿主是挂载那一刻才登记的,
     * 在那之前 `hostOf()` 会把远程路径当本地文件开 —— 读不到字节,而
     * [ArchiveFileSystem.needsPassword] 又把异常吞成"不需要密码"(见 `firstEncrypted`
     * 里的 runCatching),于是**远程的加密包不会弹密码框**,要等到点开里面的文件才报错。
     * 不报错但答案是错的,正是最难查的那种。
     */
    @Test
    fun `远程加密包——挂载后才探得出要密码`() {
        val enc = File(share, "enc.zip")
        enc.outputStream().use { os ->
            ZipWriter(os, "secret").use { zw ->
                zw.putNextEntry("s.txt", System.currentTimeMillis(), sizeHint = 3)
                zw.write("hi!".toByteArray())
                zw.closeEntry()
            }
        }
        val remote = XFile("remote", "/enc.zip", isDir = false, size = enc.length(), lastModified = enc.lastModified())

        // 挂载前:宿主还没登记,探不出来(但也不能抛)
        assertFalse(zfs.needsPassword(remote.path))

        // 挂载后:这才是真答案
        zfs.rootOf(remote)
        org.junit.Assert.assertTrue(zfs.needsPassword(remote.path))
        org.junit.Assert.assertTrue(zfs.checkPassword(remote.path, "secret"))
        assertFalse(zfs.checkPassword(remote.path, "nope"))
    }

    @Test
    fun `远程包只读,不给追加也不给重写`() {
        val root = zfs.rootOf(remoteArchive())
        assertFalse("远程宿主不可定位写,包内条目不该显示成可写", root.canWrite)
        assertFalse(zfs.list(root).first().canWrite)
    }
}
