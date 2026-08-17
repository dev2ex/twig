package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val SEARCH_MAX_DEPTH = 64
private const val SEARCH_MAX_RESULTS = 5000

/** 含 `*`/`?` 时按通配符全名匹配;否则退化为忽略大小写的子串匹配(输入普通关键字更顺手)。 */
fun matchesSearchPattern(name: String, pattern: String): Boolean {
    if (pattern.none { it == '*' || it == '?' }) return name.contains(pattern, ignoreCase = true)
    val regex = buildString {
        append("(?i)")
        for (c in pattern) when (c) {
            '*' -> append(".*")
            '?' -> append(".")
            else -> append(Regex.escape(c.toString()))
        }
    }
    return regex.toRegex().matches(name)
}

/**
 * 递归通配符搜索:BFS 遍历 [root] 下所有子目录(文件与目录名都参与匹配),边找到边 emit,
 * 供 UI 增量展示而不必等整棵子树扫完。单目录列举失败(网络抖动/权限/已断开的服务器)按
 * [FsRegistry.of] 的既有约定跳过、不中止整体搜索(与 [scanImages]/[TreemapScanner] 一致)。
 * [SEARCH_MAX_RESULTS]/[SEARCH_MAX_DEPTH] 防止超大目录树或环状链接把内存/UI 拖垮。
 */
fun scanSearch(root: XFile, pattern: String): Flow<XFile> = flow {
    val queue = ArrayDeque<Pair<XFile, Int>>()
    queue.addLast(root to 0)
    var count = 0
    while (queue.isNotEmpty() && count < SEARCH_MAX_RESULTS) {
        val (dir, depth) = queue.removeFirst()
        val list = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        for (f in list) {
            if (matchesSearchPattern(f.name, pattern)) {
                emit(f)
                if (++count >= SEARCH_MAX_RESULTS) break
            }
            if (f.isDir && depth < SEARCH_MAX_DEPTH) queue.addLast(f to depth + 1)
        }
    }
}.flowOn(Dispatchers.IO)
