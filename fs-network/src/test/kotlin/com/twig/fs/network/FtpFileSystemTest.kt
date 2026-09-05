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
    private var port = 0

    @Before
    fun setup() {
        home = File.createTempFile("ftphome", "").let { it.delete(); it.mkdirs(); it }
        // ★ Keep this in a local: inside `ListenerFactory().apply { }` a bare `port`
        // resolves to the factory's own property, not this field, and the listener would
        // silently stay on 21 (same shape as the `run` trap in CLAUDE.md).
        val freePort = ServerSocket(0).use { it.localPort }
        port = freePort

        val sf = FtpServerFactory()
        val lf = ListenerFactory().apply { this.port = freePort }
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
        assertTrue(fs.list(XFile("ftp", "/a/empty", true)).isEmpty()) // an empty directory is empty, not an error
    }

    @Test
    fun listMissingDirFails() {
        // silently returning an empty directory would make the UI look like "expanding shows nothing" — this must throw
        runCatching { fs.list(XFile("ftp", "/nope", true)) }
            .onSuccess { error("Listing a nonexistent directory should fail, but returned $it") }
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

    /**
     * A connection rooted at a sub-directory: the whole point is that **every** command
     * argument gets translated, so this walks list / write / read / mkdir / rename /
     * delete / exists rather than just listing — a single missed call site would write
     * to, or delete, the wrong directory, and listing alone would never show it.
     */
    @Test
    fun rootedAtSubdirectory() {
        File(home, "pub/inner").mkdirs()
        File(home, "pub/inner/deep.txt").writeText("deep")
        File(home, "outside.txt").writeText("must stay invisible")

        val sub = FtpFileSystem(FtpConfig("localhost", port, "u", "p", path = "pub"))

        // The root is "pub", and what sits above it is not reachable through it
        assertEquals(listOf("/inner"), sub.list(sub.root()).map { it.path })
        assertEquals(listOf("/inner/deep.txt"), sub.list(XFile("ftp", "/inner", true)).map { it.path })
        assertTrue(sub.exists(XFile("ftp", "/inner/deep.txt", false)))
        assertFalse(sub.exists(XFile("ftp", "/outside.txt", false)))

        assertEquals(
            "deep",
            sub.openInput(XFile("ftp", "/inner/deep.txt", false)).bufferedReader().use { it.readText() },
        )

        sub.openOutput(XFile("ftp", "/written.txt", false)).use { it.write("w".toByteArray()) }
        assertEquals("w", File(home, "pub/written.txt").readText())
        assertFalse(File(home, "written.txt").exists()) // not at the server root

        sub.mkdir(sub.root(), "made")
        assertTrue(File(home, "pub/made").isDirectory)

        sub.rename(XFile("ftp", "/written.txt", false), "moved.txt")
        assertTrue(File(home, "pub/moved.txt").isFile)

        sub.delete(XFile("ftp", "/moved.txt", false))
        assertFalse(File(home, "pub/moved.txt").exists())
        assertTrue(File(home, "outside.txt").isFile) // nothing above the root was touched
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
