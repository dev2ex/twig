package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

private const val SEARCH_MAX_DEPTH = 64
private const val SEARCH_MAX_RESULTS = 5000

/** When `*`/`?` are present, match as wildcards against the full name; otherwise fall back to case-insensitive substring match (more natural for plain keywords). */
fun matchesSearchPattern(name: String, pattern: String): Boolean = searchMatcher(pattern)(name)

/** Compile wildcards once per scan, rather than once per visited entry. */
private fun searchMatcher(pattern: String): (String) -> Boolean {
    if (pattern.none { it == '*' || it == '?' }) return { it.contains(pattern, ignoreCase = true) }
    val regex = buildString {
        append("(?i)")
        for (c in pattern) when (c) {
            '*' -> append(".*")
            '?' -> append(".")
            else -> append(Regex.escape(c.toString()))
        }
    }
    val compiled = regex.toRegex()
    return compiled::matches
}

/**
 * Recursive wildcard search under [root] (both file and directory names
 * participate in matching), emitting each hit as it's found so the UI can show incremental progress without
 * waiting for the entire subtree to finish scanning. If a single directory listing fails (network blip /
 * permissions / disconnected server), skip it per the existing [FsRegistry.of] convention; don't abort the whole
 * search (consistent with [scanImages] / [TreemapScanner]). [SEARCH_MAX_RESULTS] / [SEARCH_MAX_DEPTH] protect
 * against huge directory trees or cyclic links overwhelming memory / UI.
 */
fun scanSearch(root: XFile, pattern: String): Flow<XFile> = channelFlow {
    val matches = searchMatcher(pattern)
    // If the source can search itself, hand off to it (media servers): the BFS below would mean one HTTP round
    // trip per layer of the virtual tree, so a single search pulls down the entire library.
    // ★ The wildcard syntax is our own; the server doesn't understand it — strip those characters and send the
    // keyword, then filter the results against the original pattern (so `*night*` still works).
    val native = runCatching {
        (FsRegistry.of(root) as? com.twig.core.SearchSource)
            ?.search(root, pattern.filter { it != '*' && it != '?' }.trim(), SEARCH_MAX_RESULTS)
    }.getOrNull()
    if (native != null) {
        // ★ The server searches across **multiple fields** (verified: SearchTerm can hit OriginalTitle — searching
        // "ice" for a movie scraped as "Ice Age" still finds it). Filtering the results again by `name` would
        // discard all those hits — that's exactly why "the pre-scrape name can't be found": not because it
        // wasn't found, but because we filtered it out ourselves. So only re-filter when **the user explicitly
        // used wildcards** (then they want name-pattern matching).
        val byName = pattern.any { it == '*' || it == '?' }
        for (f in native) if (!byName || matches(f.name)) send(f)
        return@channelFlow
    }
    val fs = FsRegistry.of(root)
    if (fs is LocalFileSystem) {
        val context = coroutineContext
        var count = 0
        // Like the old BFS, list children of directories at depth 64, but do not
        // descend into their directory children (maximum entry depth is 65).
        fs.walk(root, SEARCH_MAX_DEPTH + 1,
            stopped = { context.ensureActive(); count >= SEARCH_MAX_RESULTS },
        ) { _, file ->
            if (matches(file.name)) {
                // The walker is synchronous. Preserve backpressure and every hit;
                // trySend alone would drop results when the collector is slower.
                trySendBlocking(file).getOrThrow()
                count++
            }
        }
        return@channelFlow
    }
    val queue = ArrayDeque<Pair<XFile, Int>>()
    queue.addLast(root to 0)
    var count = 0
    while (queue.isNotEmpty() && count < SEARCH_MAX_RESULTS) {
        coroutineContext.ensureActive()
        val (dir, depth) = queue.removeFirst()
        val list = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        for (f in list) {
            if (matches(f.name)) {
                send(f)
                if (++count >= SEARCH_MAX_RESULTS) break
            }
            if (f.isDir && depth < SEARCH_MAX_DEPTH) queue.addLast(f to depth + 1)
        }
    }
}.flowOn(Dispatchers.IO)
