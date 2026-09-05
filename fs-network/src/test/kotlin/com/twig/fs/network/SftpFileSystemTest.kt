package com.twig.fs.network

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SftpFileSystemTest {

    private lateinit var server: SshServer
    private lateinit var home: File
    private lateinit var fs: SftpFileSystem

    @Before
    fun setup() {
        home = File.createTempFile("sftphome", "").let { it.delete(); it.mkdirs(); it }

        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File.createTempFile("hostkey", ".ser").toPath(),
            )
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == "u" && p == "p" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(home.toPath())
        }
        server.start()

        fs = SftpFileSystem(SftpConfig("localhost", server.port, "u", "p"))
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(fs)
    }

    @After
    fun tearDown() {
        fs.disconnect()
        server.stop()
        home.deleteRecursively()
    }

    @Test
    fun uploadListDownload() {
        fs.openOutput(XFile("sftp", "/hello.txt", false)).use { it.write("hi-sftp".toByteArray()) }

        val listed = fs.list(fs.root()).map { "${it.name}:${it.size}" }
        assertEquals(listOf("hello.txt:7"), listed)

        val text = fs.openInput(XFile("sftp", "/hello.txt", false))
            .bufferedReader().use { it.readText() }
        assertEquals("hi-sftp", text)
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
     * Same as the FTP case: a connection rooted at a sub-directory has to translate every
     * request argument, and [SftpFileSystem.serverPath] is also what the git viewer, the
     * terminal and remote commands call to get back to a real path.
     */
    @Test
    fun rootedAtSubdirectory() {
        File(home, "srv/inner").mkdirs()
        File(home, "srv/inner/deep.txt").writeText("deep")
        File(home, "outside.txt").writeText("must stay invisible")

        val sub = SftpFileSystem(SftpConfig("localhost", server.port, "u", "p", path = "srv"))
        try {
            assertEquals(listOf("/inner"), sub.list(sub.root()).map { it.path })
            assertEquals(listOf("/inner/deep.txt"), sub.list(XFile("sftp", "/inner", true)).map { it.path })
            assertTrue(sub.exists(XFile("sftp", "/inner/deep.txt", false)))
            assertFalse(sub.exists(XFile("sftp", "/outside.txt", false)))

            assertEquals(
                "deep",
                sub.openInput(XFile("sftp", "/inner/deep.txt", false)).bufferedReader().use { it.readText() },
            )

            sub.openOutput(XFile("sftp", "/written.txt", false)).use { it.write("w".toByteArray()) }
            assertEquals("w", File(home, "srv/written.txt").readText())
            assertFalse(File(home, "written.txt").exists())

            sub.mkdir(sub.root(), "made")
            assertTrue(File(home, "srv/made").isDirectory)

            sub.rename(XFile("sftp", "/written.txt", false), "moved.txt")
            assertTrue(File(home, "srv/moved.txt").isFile)

            sub.delete(XFile("sftp", "/moved.txt", false))
            assertFalse(File(home, "srv/moved.txt").exists())
            assertTrue(File(home, "outside.txt").isFile)

            // What a shell command would be handed, as opposed to what the tree shows
            assertEquals("/srv/inner", sub.serverPath("/inner"))
            assertEquals("/srv", sub.serverPath("/"))

            // …and back: a command's output names server paths (git worktree list), which
            // have to return to tree paths; outside the root there is nothing to show
            assertEquals("/inner", sub.visiblePath("/srv/inner"))
            assertEquals("/", sub.visiblePath("/srv"))
            assertNull(sub.visiblePath("/outside.txt"))
            assertNull(sub.visiblePath("/srvother/x"))
        } finally {
            sub.disconnect()
        }
    }

    @Test
    fun copyLocalToSftpViaCopyEngine() {
        val local = File.createTempFile("upsftp", ".txt").apply { writeText("payload-sftp") }
        val localX = FsRegistry.of("file").resolve(local.absolutePath)

        CopyEngine.transfer(listOf(localX), fs.root(), move = false)

        assertEquals("payload-sftp", File(home, local.name).readText())
    }

    @Test
    fun deleteRecursiveRemovesNonEmptyDir() {
        fs.mkdir(fs.root(), "d")
        fs.openOutput(XFile("sftp", "/d/inner.txt", false)).use { it.write("x".toByteArray()) }

        fs.delete(XFile("sftp", "/d", true))
        assertFalse(File(home, "d").exists())
    }
}
