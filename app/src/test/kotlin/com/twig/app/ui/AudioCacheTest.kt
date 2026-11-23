package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioCacheTest {
    private val scheme = "audio-cache-test"
    private val worker = Executors.newCachedThreadPool()
    private val unblock = CountDownLatch(1)
    private var open: (XFile) -> RandomSource = { error("Unexpected open") }

    @Before fun setup() {
        AudioCache.init(ApplicationProvider.getApplicationContext<Application>())
        AudioCache.releaseAll()
        AudioCache.setMax(1)
        val fs = Proxy.newProxyInstance(FileSystem::class.java.classLoader,
            arrayOf(FileSystem::class.java)) { _, method, args ->
            when (method.name) {
                "getScheme" -> scheme
                "openRandom" -> open(args!![0] as XFile)
                else -> error("Unexpected call: ${method.name}")
            }
        } as FileSystem
        FsRegistry.register(fs)
    }

    @After fun cleanup() {
        unblock.countDown()
        worker.shutdown()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        AudioCache.releaseAll()
        AudioCache.setMax(AudioCache.DEFAULT_MAX)
        FsRegistry.unregister(scheme)
    }

    private fun file(path: String = "/one.mp3", size: Long = 16) =
        XFile(scheme, path, isDir = false, size = size)

    private fun source(failRead: Boolean = false, closing: CountDownLatch) = object : RandomSource {
        override fun length() = 16L
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (failRead) throw IOException("Disconnected")
            val n = minOf(length, (16 - position).toInt())
            if (n <= 0) return -1
            buffer.fill(7, offset, offset + n)
            return n
        }
        override fun close() {
            closing.countDown()
            check(unblock.await(5, TimeUnit.SECONDS)) { "Close was not released" }
        }
    }

    @Test fun `eviction and cache clearing return while failed reader waits for remote close`() {
        val closing = CountDownLatch(1)
        open = { source(failRead = true, closing = closing) }
        val old = AudioCache.source(file())
        val read = worker.submit<Int> { old.readAt(0, ByteArray(1), 0, 1) }
        assertTrue(closing.await(2, TimeUnit.SECONDS))
        // Reproduce the ANR: a new track evicts the entry whose reader is closing SMB.
        worker.submit {
            AudioCache.source(file("/two.mp3"))
            AudioCache.setMax(1)
            AudioCache.releaseAll()
        }.get(2, TimeUnit.SECONDS)
        unblock.countDown()
        assertEquals(-1, read.get(2, TimeUnit.SECONDS))
    }

    @Test fun `evicted remote close does not block replacement cache or retention changes`() {
        val closing = CountDownLatch(1)
        open = { source(closing = closing) }
        val old = AudioCache.source(file())
        assertEquals(1, old.readAt(0, ByteArray(1), 0, 1))
        worker.submit { AudioCache.source(file("/two.mp3")) }.get(2, TimeUnit.SECONDS)
        assertTrue(closing.await(2, TimeUnit.SECONDS))
        worker.submit {
            AudioCache.setMax(1)
            AudioCache.releaseAll()
            val replacement = AudioCache.source(file())
            val bytes = ByteArray(1)
            assertEquals(1, replacement.readAt(0, bytes, 0, 1))
            assertEquals(7.toByte(), bytes[0])
        }.get(2, TimeUnit.SECONDS)
        assertEquals(-1, old.readAt(0, ByteArray(1), 0, 1))
    }

    @Test fun `unknown length opening outside lock cannot revive an evicted entry`() {
        val opening = CountDownLatch(1)
        val closing = CountDownLatch(1)
        open = {
            opening.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            source(closing = closing)
        }
        val old = AudioCache.source(file(size = 0))
        val read = worker.submit<Boolean> { runCatching { old.length() }.isFailure }
        assertTrue(opening.await(2, TimeUnit.SECONDS))
        worker.submit { AudioCache.releaseAll() }.get(2, TimeUnit.SECONDS)
        unblock.countDown()
        assertTrue(read.get(2, TimeUnit.SECONDS))
        assertTrue(closing.await(2, TimeUnit.SECONDS))
    }
}
