package com.twig.fs.network

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class FtpFileSystemTest {

    private lateinit var server: FtpServer
    private lateinit var home: File
    private lateinit var fs: FtpFileSystem

    @Before
    fun setup() {
        home = File.createTempFile("ftphome", "").let { it.delete(); it.mkdirs(); it }
        val port = ServerSocket(0).use { it.localPort }

        val sf = FtpServerFactory()
        val lf = ListenerFactory().apply { this.port = port }
        sf.addListener("default", lf.createListener())
        val user = BaseUser().apply {
            name = "u"; password = "p"
            homeDirectory = home.absolutePath
            authorities = listOf(WritePermission())
        }
        sf.userManager.save(user)
        server = sf.createServer().also { it.start() }

        fs = FtpFileSystem(FtpConfig("localhost", port, "u", "p"))
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(fs)
    }

    @After
    fun tearDown() {
        server.stop()
        home.deleteRecursively()
    }

    @Test
    fun uploadListDownload() {
        fs.openOutput(XFile("ftp", "/hello.txt", false)).use { it.write("hello".toByteArray()) }

        val listed = fs.list(fs.root()).map { "${it.name}:${it.size}" }
        assertEquals(listOf("hello.txt:5"), listed)

        val text = fs.openInput(XFile("ftp", "/hello.txt", false))
            .bufferedReader().use { it.readText() }
        assertEquals("hello", text)
    }

    @Test
    fun listsNestedDirs() {
        File(home, "a/b").mkdirs()
        File(home, "a/b/deep.txt").writeText("x")
        File(home, "a/empty").mkdirs()

        assertEquals(listOf("/a"), fs.list(fs.root()).map { it.path })
        assertEquals(
            listOf("/a/b" to true, "/a/empty" to true),
            fs.list(XFile("ftp", "/a", true)).map { it.path to it.isDir },
        )
        assertEquals(listOf("/a/b/deep.txt"), fs.list(XFile("ftp", "/a/b", true)).map { it.path })
        assertTrue(fs.list(XFile("ftp", "/a/empty", true)).isEmpty()) // 空目录是空,不是报错
    }

    @Test
    fun listMissingDirFails() {
        // 静默返回空目录会让 UI 表现成"展开是空的",必须抛错
        runCatching { fs.list(XFile("ftp", "/nope", true)) }
            .onSuccess { error("列不存在的目录应该失败,却返回了 $it") }
    }

    @Test
    fun existsTellsEmptyDirAndMissingApart() {
        fs.mkdir(fs.root(), "e")
        assertTrue(fs.exists(XFile("ftp", "/e", true)))
        assertFalse(fs.exists(XFile("ftp", "/e2", true)))
        fs.openOutput(XFile("ftp", "/f.txt", false)).use { it.write("y".toByteArray()) }
        assertTrue(fs.exists(XFile("ftp", "/f.txt", false)))
        assertFalse(fs.exists(XFile("ftp", "/g.txt", false)))
    }

    @Test
    fun mkdirRenameDelete() {
        val d = fs.mkdir(fs.root(), "sub")
        assertTrue(File(home, "sub").isDirectory)

        val renamed = fs.rename(d, "sub2")
        assertEquals("/sub2", renamed.path)
        assertTrue(File(home, "sub2").isDirectory)

        fs.delete(renamed)
        assertFalse(File(home, "sub2").exists())
    }

    @Test
    fun copyLocalToFtpViaCopyEngine() {
        val local = File.createTempFile("upf", ".txt").apply { writeText("payload-123") }
        val localX = FsRegistry.of("file").resolve(local.absolutePath)

        CopyEngine.transfer(listOf(localX), fs.root(), move = false)

        assertEquals("payload-123", File(home, local.name).readText())
    }

    @Test
    fun copyFtpToLocalViaCopyEngine() {
        fs.openOutput(XFile("ftp", "/remote.bin", false)).use { it.write("from-ftp".toByteArray()) }
        val out = File.createTempFile("dlf", "").let { it.delete(); it.mkdirs(); it }
        val destX = FsRegistry.of("file").resolve(out.absolutePath)

        CopyEngine.transfer(listOf(XFile("ftp", "/remote.bin", false, size = 8)), destX, move = false)

        assertEquals("from-ftp", File(out, "remote.bin").readText())
    }

    @Test
    fun deleteRecursiveRemovesNonEmptyDir() {
        fs.mkdir(fs.root(), "d")
        fs.openOutput(XFile("ftp", "/d/inner.txt", false)).use { it.write("x".toByteArray()) }

        fs.delete(XFile("ftp", "/d", true))
        assertFalse(File(home, "d").exists())
    }
}
