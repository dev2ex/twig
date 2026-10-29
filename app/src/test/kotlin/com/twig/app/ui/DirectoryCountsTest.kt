package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryCountsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val root = XFile("smb-test", "/", true)
    private fun child(name: String, dir: Boolean) = XFile(root.scheme, "/$name", dir)
    private fun view() = View(app)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("background count did not complete", condition())
    }
    @After fun tearDown() { DirectoryCounts.clear(); FsRegistry.unregister(root.scheme) }

    @Test fun `directories and files are separate and hidden items follow the display setting`() {
        DirectoryCounts.remember(root, listOf(child("sub", true), child(".hidden-dir", true),
            child("a", false), child("b", false), child(".hidden-file", false)))
        assertEquals(DirectoryCounts.Count(1, 2), DirectoryCounts.known(root, false))
        assertEquals(DirectoryCounts.Count(2, 3), DirectoryCounts.known(root, true))
    }

    @Test fun `SMB probe lists only immediate children and subsequent binds reuse the count`() {
        val fs = FakeFileSystem(root.scheme, mapOf("/" to listOf("sub", "a"), "/sub" to listOf("nested")),
            mapOf("/a" to "file", "/sub/nested" to "nested"))
        FsRegistry.register(fs)
        var result: DirectoryCounts.Count? = null
        DirectoryCounts.request(view(), root, false) { result = it }
        await { result != null }
        assertEquals(DirectoryCounts.Count(1, 1), result)
        assertEquals(listOf("/"), fs.listed)
        DirectoryCounts.request(view(), root, false) { result = it }
        assertEquals(listOf("/"), fs.listed)
    }

    @Test fun `media servers and regular files are excluded`() {
        for (file in listOf(XFile("jellyfin-test", "/", true), XFile("emby-test", "/", true), child("file", false))) {
            assertFalse(DirectoryCounts.supports(file))
            DirectoryCounts.remember(file, emptyList())
            DirectoryCounts.request(view(), file, false) { fail("unsupported count delivered") }
            assertNull(DirectoryCounts.known(file, false))
        }
        assertTrue(DirectoryCounts.supports(XFile("file", "/storage", true)))
    }

    @Test fun `other file services count direct children and reuse their listing`() {
        for (scheme in listOf("sftp-test", "ftp-test", "webdav-test", "s3-test", "zip", "restic-test", "saf")) {
            val root = XFile(scheme, "/", true)
            val fs = FakeFileSystem(scheme, mapOf("/" to listOf("sub", "a"), "/sub" to listOf("nested")),
                mapOf("/a" to "file", "/sub/nested" to "nested"))
            FsRegistry.register(fs)
            try {
                var result: DirectoryCounts.Count? = null
                DirectoryCounts.request(view(), root, false) { result = it }
                await { result != null }
                assertEquals(DirectoryCounts.Count(1, 1), result)
                assertEquals(listOf("/"), fs.listed)
                DirectoryCounts.request(view(), root, false) { result = it }
                assertEquals(listOf("/"), fs.listed)
            } finally { FsRegistry.unregister(scheme) }
        }
    }

    @Test fun `local counts include only direct entries and respect hidden files`() {
        val dir = Files.createTempDirectory("twig-counts").toFile()
        val previous = runCatching { FsRegistry.of("file") }.getOrNull()
        try {
            dir.resolve("sub").mkdir()
            dir.resolve("sub/nested.txt").writeText("not counted")
            dir.resolve(".hidden-dir").mkdir()
            dir.resolve("visible.txt").writeText("visible")
            dir.resolve(".hidden-file").writeText("hidden")
            val fs = LocalFileSystem(dir)
            FsRegistry.register(fs)
            var result: DirectoryCounts.Count? = null
            DirectoryCounts.request(view(), fs.root(), false) { result = it }
            await { result != null }
            assertEquals(DirectoryCounts.Count(1, 1), result)
            assertEquals(DirectoryCounts.Count(2, 2), DirectoryCounts.known(fs.root(), true))
        } finally {
            if (previous == null) FsRegistry.unregister("file") else FsRegistry.register(previous)
            dir.deleteRecursively()
        }
    }

    @Test fun `failed enumeration is unknown rather than an empty folder`() {
        val reads = AtomicInteger()
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> { reads.incrementAndGet(); error("permission denied") }
        })
        var result: DirectoryCounts.Count? = null
        DirectoryCounts.request(view(), root, false) { result = it }
        await { reads.get() > 0 }
        // A successful ordinary listing can resolve the unknown state, including a truly empty folder.
        assertNull(result)
        assertNull(DirectoryCounts.known(root, false))
        DirectoryCounts.remember(root, emptyList())
        assertEquals(DirectoryCounts.Count(0, 0), DirectoryCounts.known(root, false))
    }

    @Test fun `a fresh listing supersedes a slow probe and recycled views never receive its result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                entered.countDown()
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); return listOf(child("old", false)) }
                finally { done.countDown() }
            }
        })
        val owner = view()
        try {
            DirectoryCounts.request(owner, root, false) { fail("recycled row received a count") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.setTag(com.twig.app.R.id.directory_count, null)
            DirectoryCounts.remember(root, listOf(child("one", true), child("two", true)))
        } finally { release.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DirectoryCounts.Count(2, 0), DirectoryCounts.known(root, false))
    }

    @Test fun `directory metadata changes and explicit invalidation expire cached counts`() {
        val dir = root.copy(lastModified = 10)
        DirectoryCounts.remember(dir, emptyList())
        assertNull(DirectoryCounts.known(dir.copy(lastModified = 20), false))
        DirectoryCounts.invalidate(dir)
        assertNull(DirectoryCounts.known(dir, false))
    }

    @Test fun `cache keeps only a bounded number of directories`() {
        repeat(513) { DirectoryCounts.remember(root.copy(path = "/$it"), emptyList()) }
        assertNull(DirectoryCounts.known(root.copy(path = "/0"), false))
        assertEquals(DirectoryCounts.Count(0, 0), DirectoryCounts.known(root.copy(path = "/512"), false))
    }
}
