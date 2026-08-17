package com.twig.fs.restic

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.airlift.compress.zstd.ZstdDecompressor
import java.io.File
import java.security.MessageDigest

/** 测试用 zstd(纯 Java aircompressor,JVM 上可用)。 */
private val TEST_ZSTD = Zstd { input, expected ->
    val size = if (expected >= 0) expected
    else ZstdDecompressor.getDecompressedSize(input, 0, input.size).toInt()
    val out = ByteArray(size)
    ZstdDecompressor().decompress(input, 0, input.size, out, 0, out.size)
    out
}

/**
 * 用真实 restic 生成的 fixture(src/test/resources/repo,密码 test123,v2/zstd)验证读取器。
 * 备份树:tmp/twig_rsrc/{big.txt(4800B), hello.txt, sub/note.md}
 */
class ResticRepoTest {

    private lateinit var repoDir: XFile
    private val fs = LocalFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(fs)
        val path = javaClass.classLoader.getResource("repo")!!.path
        repoDir = XFile("file", File(path).absolutePath, isDir = true)
    }

    private fun open(pw: String) = ResticRepo.open(fs, repoDir, pw, TEST_ZSTD)

    @Test
    fun detectsRepo() {
        assertTrue(ResticRepo.looksLikeRepo(fs.list(repoDir)))
    }

    @Test
    fun wrongPasswordThrows() {
        assertThrows(Exception::class.java) { open("wrong") }
    }

    @Test
    fun listsSnapshots() {
        val repo = open("test123")
        assertEquals(1, repo.snapshots.size)
        assertTrue(repo.snapshots[0].timeLabel.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
        assertEquals("testhost", repo.snapshots[0].hostname)
    }

    @Test
    fun walksTreeViaFileSystem() {
        val repo = open("test123")
        val rfs = ResticFileSystem(repo, "restic")
        FsRegistry.register(rfs)

        val snapDir = rfs.list(rfs.root()).first()
        // tmp -> twig_rsrc -> 文件
        val tmp = rfs.list(snapDir).first { it.name == "tmp" }
        val src = rfs.list(tmp).first { it.name == "twig_rsrc" }
        val names = rfs.list(src).map { "${it.name}:${it.isDir}" }.toSet()
        assertTrue("hello.txt:false" in names)
        assertTrue("big.txt:false" in names)
        assertTrue("sub:true" in names)
    }

    @Test
    fun readsFileContent() {
        val repo = open("test123")
        val rfs = ResticFileSystem(repo, "restic")
        val snap = rfs.list(rfs.root()).first().path

        val hello = XFile("restic", "$snap/tmp/twig_rsrc/hello.txt", false)
        assertEquals("hello restic\n", rfs.openInput(hello).bufferedReader().use { it.readText() })

        val note = XFile("restic", "$snap/tmp/twig_rsrc/sub/note.md", false)
        assertEquals("nested content here\n", rfs.openInput(note).bufferedReader().use { it.readText() })
    }

    @Test
    fun readsCompressedMultiBlobFile() {
        // big.txt 4800B,v2 下会压缩;校验 sha256 == 基准
        val repo = open("test123")
        val rfs = ResticFileSystem(repo, "restic")
        val snap = rfs.list(rfs.root()).first().path
        val big = XFile("restic", "$snap/tmp/twig_rsrc/big.txt", false)
        val bytes = rfs.openInput(big).use { it.readBytes() }
        assertEquals(4800, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("a0b0878ec4dbfdaa74e6e1729e59d28862c4b5d44bca534c0a19f859fccd4f15", sha)
    }
}
