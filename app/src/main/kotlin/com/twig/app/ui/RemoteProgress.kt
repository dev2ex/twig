package com.twig.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.twig.core.FsRegistry
import com.twig.core.PlayState
import com.twig.core.PlaybackProgress
import com.twig.core.XFile
import com.twig.app.R
import java.util.concurrent.Executors

/**
 * The path used when playback progress is delegated to the source itself (Jellyfin / Emby).
 *
 * ★ **Every call is network IO and always runs on a background thread.** The existing
 * path that wrote progress from the player was the main thread (just SharedPreferences,
 * `apply()` flushed asynchronously); copying that verbatim is a `NetworkOnMainThreadException` —
 * and per the terminal-resize rule in `CLAUDE.md`, the worse outcome is that after the
 * exception is swallowed "failure" looks exactly like "success". So here: a single-
 * threaded executor guarantees report ordering (START must come before PROGRESS), and on
 * failure **we logcat + toast on the first failure only** — not silent, not spammy.
 */
class RemoteProgress private constructor(
    private val ctx: Context,
    private val sink: PlaybackProgress,
    private val file: XFile,
) {

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-progress").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Only toast on the first failure: reports fire every 10s, and toasting each one would flood the player page. */
    private var warned = false

    /** Read the resume position recorded on the server (blocking); the caller is responsible for putting this on a background thread. */
    fun position(): Long = sink.positionOf(file)

    fun report(posMs: Long, durMs: Long, state: PlayState) {
        exec.execute {
            runCatching { sink.report(file, posMs, durMs, state) }.onFailure { e ->
                Log.w("twig", "playback: progress report failed (${state.name}): ${e.message}")
                if (!warned) {
                    warned = true
                    main.post {
                        Toast.makeText(
                            ctx, ctx.getString(R.string.err_progress_sync, e.message ?: ""),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    /** Wind down: flush already-queued reports (usually the final STOP), then stop accepting new ones. */
    fun shutdown() {
        exec.shutdown()
    }

    companion object {
        /**
         * Returns one for a file whose source manages its own progress, otherwise null
         * (the caller falls back to the local `PlaybackStore`). The capability is an
         * **optional interface**, not a method on `FileSystem` — when you cannot get
         * one, pretend it does not exist.
         */
        fun of(ctx: Context, file: XFile): RemoteProgress? {
            val fs = runCatching { FsRegistry.of(file) }.getOrNull() ?: return null
            val sink = fs as? PlaybackProgress ?: return null
            return RemoteProgress(ctx.applicationContext, sink, file)
        }
    }
}
