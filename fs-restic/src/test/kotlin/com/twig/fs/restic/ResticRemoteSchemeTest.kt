package com.twig.fs.restic

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import io.airlift.compress.zstd.ZstdDecompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 验证 restic 读取器与底层 scheme 无关:把 fixture 注册到一个非 "file" 的
 * "remote" scheme(模拟仓库在 SMB/SFTP 上),仍应能打开并读文件。
 */
class ResticRemoteSchemeTest {

    /** 用另一个 scheme 包装本地文件系统(路径直通)。 */
    private class RemoteFs(private val local: LocalFileSystem) : FileSystem by local {
        override val scheme = "remote"
        override fun root() = XFile("remote", "/", true)
        override fun list(dir: XFile) =
            local.list(XFile("file", dir.path, true)).map { it.copy(scheme = "remote") }
        override fun openInput(file: XFile): InputStream = local.openInput(XFile("file", file.path, false))
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw UnsupportedOperationException()
    }

    private val z = Zstd { input, expected ->
        val size = if (expected >= 0) expected
        else ZstdDecompressor.getDecompressedSize(input, 0, input.size).toInt()
        ByteArray(size).also { ZstdDecompressor().decompress(input, 0, input.size, it, 0, it.size) }
    }

    private lateinit var repoDir: XFile

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(RemoteFs(LocalFileSystem()))
        val path = javaClass.classLoader.getResource("repo")!!.path
        repoDir = XFile("remote", File(path).absolutePath, isDir = true)
    }

    @Test
    fun opensAndReadsOverRemoteScheme() {
        val repo = ResticRepo.open(FsRegistry.of("remote"), repoDir, "test123", z)
        assertEquals(1, repo.snapshots.size)

        val rfs = ResticFileSystem(repo, "resticR")
        val snap = rfs.list(rfs.root()).first().path
        val hello = XFile("resticR", "$snap/tmp/twig_rsrc/hello.txt", false)
        assertEquals("hello restic\n", rfs.openInput(hello).bufferedReader().use { it.readText() })
        assertTrue(true)
    }
}
