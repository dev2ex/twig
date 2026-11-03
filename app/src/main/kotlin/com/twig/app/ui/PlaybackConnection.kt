package com.twig.app.ui

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Native socket opens may ignore coroutine cancellation. Bound the worker count, return on
 * timeout anyway, and close a late result instead of letting it revive an old playback request.
 */
internal object PlaybackConnection {
    private val workers: ExecutorService = ThreadPoolExecutor(
        0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "player-connect").apply { isDaemon = true } },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T : Closeable> open(
        timeoutMs: Long = PlaybackRetryPolicy.CONNECT_TIMEOUT_MS,
        executor: ExecutorService = workers,
        connect: () -> T,
    ): T {
        val pending = AtomicReference<T?>()
        try {
            val value = withTimeout(timeoutMs) {
                suspendCancellableCoroutine<T> { continuation ->
                    try {
                        val future = executor.submit {
                            try {
                                val result = connect()
                                pending.set(result)
                                continuation.resume(result) {
                                    if (pending.compareAndSet(result, null)) closeLater(result)
                                }
                            } catch (e: Exception) {
                                continuation.resumeWith(Result.failure(e))
                            }
                        }
                        continuation.invokeOnCancellation { future.cancel(true) }
                    } catch (e: java.util.concurrent.RejectedExecutionException) {
                        continuation.resumeWith(Result.failure(IOException("Previous connection attempts are still closing", e)))
                    }
                }
            }
            pending.compareAndSet(value, null)
            return value
        } finally {
            // Also cover a timeout between resumption and withTimeout returning its value.
            closeLater(pending.getAndSet(null))
        }
    }

    fun closeLater(resource: Closeable?) {
        if (resource == null) return
        Thread({ runCatching { resource.close() } }, "player-close").apply { isDaemon = true }.start()
    }
}
