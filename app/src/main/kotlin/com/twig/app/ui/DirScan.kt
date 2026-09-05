package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

/** Realtime result of a recursive directory scan (displayed by the properties card as the scan progresses). */
data class DirStat(val files: Int = 0, val dirs: Int = 0, val bytes: Long = 0)

private const val DIR_SCAN_MAX_DEPTH = 64
private const val DIR_SCAN_MAX_ENTRIES = 1_000_000
/** Maximum interval between UI refreshes after each directory level is listed: emitting per entry would rebuild the entire tree and overwhelm the UI. */
private const val DIR_SCAN_UI_MS = 200L

/**
 * Minimum spinner display duration. A small local directory finishes in tens of
 * milliseconds, so the spinner cannot survive even a single frame — what the user sees
 * is "no spinner at all" (real-world feedback on 2026-08-04). Better to spin an extra
 * half-second than to make the status indicator look meaningless.
 */
internal const val DIR_SCAN_MIN_SPIN_MS = 600L

/**
 * Recursively count files / directories / total bytes under [root], BFS traversal,
 * emitting intermediate results as it scans (at most once every [DIR_SCAN_UI_MS], with
 * a guaranteed final emission that is the complete value).
 * A single directory listing failure (permission / network glitch) is skipped per the
 * existing convention of [scanSearch] / [TreemapScanner] and does not abort the whole
 * tally.
 * Cancellation is handled by the caller cancelling the coroutine — note that the one
 * network round-trip blocked inside `list()` is not interrupted and only exits at the
 * checkpoint after it returns.
 *
 * This only "fetches data"; accumulation and persistence are done by the collector
 * (main thread), per [PaneViewModel]'s "tree state is only written on the main thread"
 * rule; [io] is injectable so unit tests can use a test dispatcher to drive it through.
 */
fun scanDirStat(root: XFile, io: CoroutineDispatcher = Dispatchers.IO): Flow<DirStat> = flow {
    var files = 0
    var dirs = 0
    var bytes = 0L
    var lastUi = 0L
    val queue = ArrayDeque<Pair<XFile, Int>>()
    queue.addLast(root to 0)
    while (queue.isNotEmpty() && files + dirs < DIR_SCAN_MAX_ENTRIES) {
        coroutineContext.ensureActive()
        val (dir, depth) = queue.removeFirst()
        val list = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        for (f in list) {
            if (f.isDir) {
                dirs++
                if (depth < DIR_SCAN_MAX_DEPTH) queue.addLast(f to depth + 1)
            } else {
                files++
                bytes += f.size.coerceAtLeast(0)
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastUi > DIR_SCAN_UI_MS) {
            lastUi = now
            emit(DirStat(files, dirs, bytes))
        }
    }
    emit(DirStat(files, dirs, bytes))
}.flowOn(io)
