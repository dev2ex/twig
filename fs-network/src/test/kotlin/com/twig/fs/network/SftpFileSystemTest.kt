package com.twig.fs.network

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.UnixPermissions
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

class SftpFileSystemTest {

    private lateinit var server: SshServer
    private lateinit var home: File
    private lateinit var fs: SftpFileSystem

    @Volatile private var dropAfterCreate = false
    private val sessions = AtomicInteger()

    @Before
    fun setup() {
        home = File.createTempFile("sftphome", "").let { it.delete(); it.mkdirs(); it }

        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File.createTempFile("hostkey", ".ser").toPath(),
            )
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == "u" && p == "p" }
            subsystemFactories = listOf(
                SftpSubsystemFactory().apply {
                    addSftpEventListener(object : SftpEventListener {
                        override fun created(session: ServerSession, path: Path, attrs: MutableMap<String, *>, thrown: Throwable?) {
                            // Simulates "the request landed, the reply never made it back"
                            if (thrown == null && dropAfterCreate) {
                                dropAfterCreate = false
                                session.close(true)
                            }
                        }
                    })
                },
            )
            addSessionListener(object : SessionListener {
                override fun sessionCreated(session: Session) { sessions.incrementAndGet() }
            })
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

    /** One stat round trip, real metadata — `resolve` here is a non-statting stub by design. */
    @Test
    fun statReportsTypeSizeAndTime() {
        File(home, "a.txt").writeText("hello")
        File(home, "sub").mkdirs()

        val f = fs.stat("/a.txt")!!
        assertEquals(5L, f.size)
        assertFalse(f.isDir)
        assertTrue("mtime should be filled in", f.lastModified > 0)
        assertTrue(fs.stat("/sub")!!.isDir)
        assertNull(fs.stat("/nope.txt"))
    }

    @Test
    fun listingAndStatCarryUnixPermissions() {
        val file = File(home, "a.txt").apply { writeText("hello") }
        val dir = File(home, "sub").apply { mkdirs() }
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-x---"))

        val listed = fs.list(fs.root()).associateBy { it.name }
        for ((name, permissions) in mapOf("a.txt" to "rw-r-----", "sub" to "rwxr-x---")) {
            assertEquals(permissions, UnixPermissions.symbolic(listed.getValue(name).unixMode!!))
            assertEquals(permissions, UnixPermissions.symbolic(fs.stat("/$name")!!.unixMode!!))
        }
    }

    @Test
    fun symbolicLinksKeepTheirIdentityAndFollowTargetsForBrowsingAndReading() {
        File(home, "target.txt").writeText("payload")
        File(home, "target-dir").mkdirs()
        File(home, "target-dir/keep.txt").writeText("keep")
        Files.createSymbolicLink(File(home, "file-link").toPath(), Path.of("target.txt"))
        Files.createSymbolicLink(File(home, "dir-link").toPath(), Path.of("target-dir"))
        Files.createSymbolicLink(File(home, "broken-link").toPath(), Path.of("missing.txt"))

        val listed = fs.list(fs.root()).associateBy { it.name }
        for (name in listOf("file-link", "dir-link", "broken-link")) {
            val entry = listed.getValue(name)
            assertTrue(entry.isSymbolicLink)
            assertEquals(0xA000, entry.unixMode!! and 0xF000)
            assertEquals(entry, fs.stat("/$name"))
        }
        assertEquals("target.txt", listed.getValue("file-link").linkTarget)
        assertEquals(7L, listed.getValue("file-link").size)
        assertEquals("payload", fs.openInput(listed.getValue("file-link")).bufferedReader().use { it.readText() })
        assertTrue(listed.getValue("dir-link").isDir)
        assertEquals(listOf("keep.txt"), fs.list(listed.getValue("dir-link")).map { it.name })
        assertEquals("missing.txt", listed.getValue("broken-link").linkTarget)
        assertFalse(listed.getValue("broken-link").isDir)
        assertEquals(-1L, listed.getValue("broken-link").size)
        assertTrue(fs.exists(listed.getValue("broken-link")))
        assertFalse(listed.getValue("target.txt").isSymbolicLink)

        // Deletion must be safe even for callers holding a navigation stub without link metadata.
        fs.delete(XFile("sftp", "/dir-link", isDir = true))
        assertEquals("keep", File(home, "target-dir/keep.txt").readText())
        assertNull(fs.stat("/dir-link"))
        val renamed = fs.rename(listed.getValue("broken-link"), "renamed-link")
        assertNull(fs.stat("/broken-link"))
        assertTrue(fs.exists(renamed))
        assertEquals("missing.txt", fs.stat("/renamed-link")!!.linkTarget)
        fs.delete(renamed)
        assertNull(fs.stat("/renamed-link"))
    }

    @Test
    fun rootedConnectionPreservesRawLinkTargets() {
        File(home, "srv").mkdirs()
        File(home, "srv/target.txt").writeText("data")
        Files.createSymbolicLink(File(home, "srv/link").toPath(), Path.of("target.txt"))
        val sub = SftpFileSystem(SftpConfig("localhost", server.port, "u", "p", path = "srv"))
        try {
            val link = sub.list(sub.root()).single { it.name == "link" }
            assertEquals("/link", link.path)
            assertEquals("target.txt", link.linkTarget)
            assertEquals(4L, link.size)
            assertEquals(link, sub.stat("/link"))
        } finally {
            sub.disconnect()
        }
    }

    // ---- retries (2026-09-17 review) ----

    /** A server's "no such file" is an answer, not a dropped connection: no reconnect, no second try. */
    @Test
    fun aServerErrorIsNotRetried() {
        fs.list(fs.root())
        val before = sessions.get()
        assertThrows(Exception::class.java) { fs.delete(XFile("sftp", "/missing.txt", false)) }
        assertEquals("no new session was opened", before, sessions.get())
    }

    /**
     * ★ The connection drops after mkdir was carried out but before the reply arrived. The
     * old retry repeated the mkdir on a new connection and reported "already exists" for an
     * operation that had succeeded.
     */
    @Test
    fun aWriteThatLandedBeforeTheDropIsNotRepeated() {
        fs.list(fs.root())
        dropAfterCreate = true
        fs.mkdir(fs.root(), "made") // must not throw
        assertTrue(File(home, "made").isDirectory)
        assertFalse("the drop really happened", dropAfterCreate)
        // and the file system is usable afterwards
        assertEquals(listOf("made"), fs.list(fs.root()).map { it.name })
    }
}
