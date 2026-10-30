package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.DirectoryCountSource
import com.twig.core.ChildCounts
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.time.Duration

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
    @After fun tearDown() {
        DirectoryCounts.clear()
        await { DirectoryCounts.isIdle() }
        FsRegistry.unregister(root.scheme)
    }

    @Test fun `directories and files are separate and hidden items follow the display setting`() {
        DirectoryCounts.remember(root, listOf(child("sub", true), child(".hidden-dir", true),
            child("a", false), child("b", false), child(".hidden-file", false)))
        assertEquals(DirectoryCounts.Count(1, 2), DirectoryCounts.known(root, false))
        assertEquals(DirectoryCounts.Count(2, 3), DirectoryCounts.known(root, true))
    }

    @Test fun `capable backends use bounded counts instead of ordinary listings`() {
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake, DirectoryCountSource {
            override fun list(dir: XFile): List<XFile> = error("count must not request a full listing")
            override fun countChildren(dir: XFile, limit: Int, keepGoing: () -> Boolean): ChildCounts {
                assertEquals(root.path, dir.path)
                assertEquals(1000, limit)
                assertTrue(keepGoing())
                return ChildCounts(1000, 1000, 3, 1000)
            }
        })
        var result: DirectoryCounts.Count? = null
        DirectoryCounts.request(view(), root, false) { result = it }
        await { result != null }
        assertEquals(DirectoryCounts.Count(3, 1000), result)
        assertEquals(DirectoryCounts.Count(1000, 1000), DirectoryCounts.known(root, true))
    }

    @Test fun `queued counts follow live display positions rather than request order`() {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                calls.add(dir.path)
                return emptyList()
            }
        })
        val positions = mutableMapOf("/Zulu" to 8, "/Alpha" to 1, "/Middle" to 4)
        var completed = 0
        for (path in listOf("/Middle", "/Zulu", "/Alpha")) {
            DirectoryCounts.request(view(), root.copy(path = path), false,
                order = { positions.getValue(path) }) { completed++ }
        }
        // A resort before the worker runs must use the new positions.
        positions["/Zulu"] = 0
        await { completed == 3 }
        assertEquals(listOf("/Zulu", "/Alpha", "/Middle"), calls.toList())
    }

    @Test fun `streaming counts stop at three seconds and queued work gets its own budget`() {
        val fake = FakeFileSystem(root.scheme, emptyMap())
        val reads = AtomicInteger()
        val beforeDeadline = AtomicBoolean()
        val afterDeadline = AtomicBoolean(true)
        val next = root.copy(path = "/next")
        FsRegistry.register(object : FileSystem by fake, DirectoryCountSource {
            override fun countChildren(dir: XFile, limit: Int, keepGoing: () -> Boolean): ChildCounts {
                reads.incrementAndGet()
                assertTrue(keepGoing())
                if (dir.path == root.path) {
                    ShadowSystemClock.advanceBy(Duration.ofMillis(2999))
                    beforeDeadline.set(keepGoing())
                    ShadowSystemClock.advanceBy(Duration.ofMillis(1))
                    afterDeadline.set(keepGoing())
                }
                // Even a backend that returns a value after its deadline must be ignored.
                return ChildCounts(0, 1, 0, 1)
            }
        })
        var result: DirectoryCounts.Count? = null
        DirectoryCounts.request(view(), root, false) { fail("timed out count was delivered") }
        DirectoryCounts.request(view(), next, false) { result = it }
        await { result != null && DirectoryCounts.isIdle() }
        assertTrue("2999 ms is still within the count budget", beforeDeadline.get())
        assertFalse("3000 ms expires the count budget", afterDeadline.get())
        assertNull(DirectoryCounts.known(root, false))
        assertEquals(DirectoryCounts.Count(0, 1), result)
        assertEquals(2, reads.get())
        DirectoryCounts.request(view(), root, false) { fail("timeout must not become zero") }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DirectoryCounts.isIdle())
        assertEquals("timeout must cool down rather than immediately retry", 2, reads.get())
    }

    @Test fun `late blocking listings stay unknown and explicit invalidation permits retry`() {
        val fake = FakeFileSystem(root.scheme, emptyMap())
        val reads = AtomicInteger()
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                if (reads.incrementAndGet() == 1) ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                return emptyList()
            }
        })
        DirectoryCounts.request(view(), root, false) { fail("late empty listing must not become zero") }
        await { reads.get() == 1 && DirectoryCounts.isIdle() }
        assertNull(DirectoryCounts.known(root, false))
        DirectoryCounts.invalidate(root)
        var retried: DirectoryCounts.Count? = null
        DirectoryCounts.request(view(), root, false) { retried = it }
        await { retried != null && DirectoryCounts.isIdle() }
        assertEquals(DirectoryCounts.Count(0, 0), retried)
        assertEquals(2, reads.get())
    }

    @Test fun `ordinary listings resolve timed out counts without the probe deadline`() {
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
                return emptyList()
            }
        })
        DirectoryCounts.request(view(), root, false) { fail("late count was delivered") }
        await { DirectoryCounts.isIdle() }
        DirectoryCounts.remember(root, listOf(child("from-normal-expansion", false)))
        var result: DirectoryCounts.Count? = null
        DirectoryCounts.request(view(), root, false) { result = it }
        assertEquals(DirectoryCounts.Count(0, 1), result)
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
            await { entered.count == 0L }
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
    @Test fun `blocked remote enumeration never holds up local counts`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                entered.countDown()
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); return emptyList() }
                finally { done.countDown() }
            }
        })
        val dir = Files.createTempDirectory("twig-independent-count").toFile()
        val previous = runCatching { FsRegistry.of("file") }.getOrNull()
        try {
            dir.resolve("local.txt").writeText("local")
            val local = LocalFileSystem(dir)
            FsRegistry.register(local)
            DirectoryCounts.request(view(), root, false) { }
            await { entered.count == 0L }
            var result: DirectoryCounts.Count? = null
            DirectoryCounts.request(view(), local.root(), false) { result = it }
            await { result != null }
            assertEquals(DirectoryCounts.Count(0, 1), result)
            assertEquals("remote request must still be blocked", 1L, done.count)
        } finally {
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            if (previous == null) FsRegistry.unregister("file") else FsRegistry.register(previous)
            dir.deleteRecursively()
        }
    }

    @Test fun `released queued rows are never enumerated after a slow request finishes`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                calls.add(dir.path)
                if (dir.path == "/") {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                return emptyList()
            }
        })
        try {
            DirectoryCounts.request(view(), root, false) { }
            await { entered.count == 0L }
            val abandoned = view()
            DirectoryCounts.request(abandoned, root.copy(path = "/abandoned"), false) { fail("abandoned row updated") }
            DirectoryCounts.release(abandoned)
            var completed = false
            DirectoryCounts.request(view(), root.copy(path = "/current"), false) { completed = true }
            release.countDown()
            await { completed }
            assertEquals(listOf("/", "/current"), calls.toList())
            assertNull(DirectoryCounts.known(root.copy(path = "/abandoned"), false))
        } finally { release.countDown() }
    }

    @Test fun `releasing one pane preserves the other panes subscription to the same directory`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                reads.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return listOf(child("shared", false))
            }
        })
        try {
            val firstPane = view()
            val otherPane = view()
            DirectoryCounts.request(firstPane, root, false) { fail("closed pane updated") }
            await { entered.count == 0L }
            var result: DirectoryCounts.Count? = null
            DirectoryCounts.request(otherPane, root, false) { result = it }
            DirectoryCounts.release(firstPane)
            release.countDown()
            await { result != null }
            assertEquals(DirectoryCounts.Count(0, 1), result)
            assertEquals(1, reads.get())
        } finally { release.countDown() }
    }

    @Test fun `rebinding the same row replaces its callback without repeating the directory read`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val fake = FakeFileSystem(root.scheme, emptyMap())
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                reads.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return listOf(child(".hidden", false))
            }
        })
        try {
            val owner = view()
            DirectoryCounts.request(owner, root, false) { fail("old binding received a result") }
            await { entered.count == 0L }
            var result: DirectoryCounts.Count? = null
            DirectoryCounts.request(owner, root, true) { result = it }
            release.countDown()
            await { result != null }
            assertEquals(DirectoryCounts.Count(0, 1), result)
            assertEquals(1, reads.get())
        } finally { release.countDown() }
    }

}
