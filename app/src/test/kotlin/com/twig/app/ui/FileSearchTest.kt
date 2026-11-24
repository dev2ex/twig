package com.twig.app.ui

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.SearchSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.local.priv.JvmPrivilegedProcess
import com.twig.fs.local.priv.PrivilegedFs
import com.twig.fs.local.priv.PrivilegedLauncher
import com.twig.fs.local.priv.PrivilegedShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileSearchTest {
    @get:Rule val temp = TemporaryFolder()
    private var previous: FileSystem? = null
    private var elevation: PrivilegedFs? = null

    @Before fun setup() {
        previous = FsRegistry.unregister("file")
        elevation = LocalFileSystem.elevation
        LocalFileSystem.elevation = null
        FsRegistry.register(LocalFileSystem())
    }

    @After fun cleanup() {
        LocalFileSystem.elevation = elevation
        FsRegistry.unregister("file")
        previous?.let(FsRegistry::register)
    }

    private fun root() = XFile("file", temp.root.path, isDir = true)

    @Test fun localSearchPreservesKeywordWildcardsAndFullMetadata() = runBlocking {
        val directory = temp.newFolder("photo-album")
        val jpg = File(directory, "photo[1].JPG").apply { writeText("payload") }
        File(directory, "video.mp4").writeText("movie")
        val keyword = scanSearch(root(), "PHOTO").toList()
        assertEquals(setOf(directory.path, jpg.path), keyword.map { it.path }.toSet())
        assertEquals(FsRegistry.of("file").stat(jpg.path), keyword.single { !it.isDir })
        assertEquals(listOf(jpg.path), scanSearch(root(), "photo[?].jp*").toList().map { it.path })
        assertTrue(scanSearch(root(), "*.txt").toList().isEmpty())
    }

    @Test fun `keyword search requires every word in any order while globs preserve spaces`() = runBlocking {
        val reordered = temp.newFile("Vacation_2024_Photo.JPG")
        val spaced = temp.newFile("2024 photo.jpg")
        temp.newFile("photo.jpg")
        temp.newFile("2024.jpg")

        val hits = scanSearch(root(), "photo   \t2024\u3000JPG").toList()
        assertEquals(setOf(reordered.path, spaced.path), hits.map { it.path }.toSet())
        assertEquals(listOf(spaced.path), scanSearch(root(), "*2024 photo.jp?").toList().map { it.path })
    }

    @Test fun slowCollectorReceivesEveryHitAndEarlyStopCancelsWalk() = runBlocking {
        repeat(220) { File(temp.root, "hit$it").writeText("x") }
        withTimeout(10_000) {
            val hits = ArrayList<XFile>()
            scanSearch(root(), "hit").collect { delay(1); hits += it }
            assertEquals(220, hits.map { it.path }.toSet().size)
            assertEquals(1, scanSearch(root(), "hit").take(1).toList().size)
        }
    }

    @Test fun localSearchKeepsExistingDepthAndResultLimits() = runBlocking {
        var directory = temp.root
        repeat(64) { directory = File(directory, "d").apply { mkdir() } }
        val included = File(directory, "needle.txt").apply { writeText("x") }
        File(File(directory, "d").apply { mkdir() }, "needle-too-deep.txt").writeText("x")
        assertEquals(listOf(included.path), scanSearch(root(), "needle").toList().map { it.path })
        repeat(5020) { File(temp.root, "limit$it").writeText("x") }
        assertEquals(5000, scanSearch(root(), "limit").toList().size)
    }

    @Test fun restrictedSearchStreamsOneProcessAndClosesItOnEarlyStop() = runBlocking {
        val denied = File(temp.root, "denied")
        val names = listOf("nested", "nested/photo|one.JPG", "nested/line\nphoto.png", "other.txt")
        val records = names.mapIndexed { index, name ->
            val mode = if (index == 0) "drwxr-xr-x" else "-rw-r--r--"
            "$mode|${index + 1}|1700000000.0|$denied/$name"
        }.joinToString("\\0", postfix = "\\0")
        var launches = 0
        var closed = 0
        val shell = PrivilegedShell(PrivilegedLauncher { command ->
            if (command == null) JvmPrivilegedProcess(ProcessBuilder("/bin/sh").start()) else {
                assertTrue(command.startsWith("exec find -H "))
                launches++
                val process = JvmPrivilegedProcess(ProcessBuilder("/bin/sh", "-c",
                    "printf '%b' ${PrivilegedShell.quote(records)}").start())
                object : com.twig.fs.local.priv.PrivilegedProcess by process {
                    override fun destroy() { closed++; process.destroy() }
                }
            }
        })
        LocalFileSystem.elevation = PrivilegedFs(shell)
        try {
            val target = XFile("file", denied.path, isDir = true)
            val hits = scanSearch(target, "PHOTO").toList()
            assertEquals(names.drop(1).take(2).map { "$denied/$it" }.toSet(), hits.map { it.path }.toSet())
            assertEquals(listOf(2L, 3L), hits.map { it.size })
            assertEquals(1, launches)
            withTimeout(10_000) { assertEquals(1, scanSearch(target, "").take(1).toList().size) }
            assertEquals(2, launches)
            assertTrue("both scan processes must be closed", closed >= 2)
            assertTrue(shell.exec("echo session-usable").ok)
        } finally { LocalFileSystem.elevation = null; shell.close() }
    }

    @Test fun remoteSearchSkipsFailedDirectoriesAndNativeSearchPreservesOtherFieldHits() = runBlocking {
        val remote = FakeFileSystem("search-remote", mapOf("/" to listOf("bad", "ok"),
            "/bad" to listOf("photo1"), "/ok" to listOf("photo2")),
            mapOf("/bad/photo1" to "x", "/ok/photo2" to "x"), failing = setOf("/bad"))
        FsRegistry.register(remote)
        val native = object : FileSystem by remote, SearchSource {
            override val scheme = "search-native"
            override fun search(root: XFile, query: String, limit: Int) =
                listOf(XFile(scheme, "/movie", isDir = false, displayName = "Different title"))
        }
        FsRegistry.register(native)
        try {
            assertEquals(listOf("/ok/photo2"), scanSearch(remote.root(), "photo").toList().map { it.path })
            assertEquals(1, scanSearch(XFile(native.scheme, "/", true), "original-title").toList().size)
            assertTrue(scanSearch(XFile(native.scheme, "/", true), "*original-title*").toList().isEmpty())
        } finally { FsRegistry.unregister(remote.scheme); FsRegistry.unregister(native.scheme) }
    }
}
