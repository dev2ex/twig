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
 * 递归扫描目录下的图片,BFS 逐层 [FileSystem.list],找到一张就 emit 一张——供幻灯片
 * "边扫边播"(不必等整棵树扫完才显示第一张,网络来源尤其明显)。单个目录 list() 失败
 * (权限/网络错误)跳过继续其它分支,不中断整体扫描。[maxImages] 防止超大目录树/深层网络
 * 路径无限扫描。
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
