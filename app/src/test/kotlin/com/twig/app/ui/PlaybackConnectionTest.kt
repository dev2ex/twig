package com.twig.app.ui

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackConnectionTest {
    @Test fun `timeout returns even when native connect ignores interruption and closes late result`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        try {
            val attempt = async {
                runCatching {
                    PlaybackConnection.open(100, executor) {
                        entered.countDown()
                        while (true) {
                            try { release.await(); break } catch (_: InterruptedException) { }
                        }
                        Closeable { closed.countDown() }
                    }
                }
            }
            yield()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(withTimeout(2000) { attempt.await() }.exceptionOrNull() is TimeoutCancellationException)
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `cancellation cannot deliver a late connection to a different episode`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var installed = false
        try {
            val attempt = launch {
                PlaybackConnection.open(executor = executor) {
                    entered.countDown()
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                    Closeable { closed.countDown() }
                }
                installed = true
            }
            yield()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            attempt.cancelAndJoin()
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertFalse(installed)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
