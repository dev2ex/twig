package com.twig.app.ui

import com.twig.app.OpenFiles
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val MAX_DEPTH = 8

/**
 * Recursively scan a directory for images, BFS layer by layer via [FileSystem.list], emitting each image as it's
 * found — for the slideshow's "scan and play simultaneously" (no need to wait for the whole tree to be scanned
 * before showing the first image; the benefit is especially noticeable for network sources). If a single directory's
 * list() fails (permissions / network error), skip it and continue other branches without aborting the whole scan.
 * [maxImages] prevents endless scanning in huge directory trees / deeply nested network paths.
 */
fun scanImages(root: XFile, maxImages: Int): Flow<XFile> = flow {
    val queue = ArrayDeque<Pair<XFile, Int>>()
    queue.addLast(root to 0)
    var count = 0
    while (queue.isNotEmpty() && count < maxImages) {
        val (dir, depth) = queue.removeFirst()
        val list = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        for (f in list) {
            if (f.isDir) {
                if (depth < MAX_DEPTH) queue.addLast(f to depth + 1)
            } else if (OpenFiles.isImage(f)) {
                emit(f)
                if (++count >= maxImages) break
            }
        }
    }
}.flowOn(Dispatchers.IO)
