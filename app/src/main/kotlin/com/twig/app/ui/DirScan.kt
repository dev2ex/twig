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

/** 目录递归统计的实时结果(属性卡片边扫边显示)。 */
data class DirStat(val files: Int = 0, val dirs: Int = 0, val bytes: Long = 0)

private const val DIR_SCAN_MAX_DEPTH = 64
private const val DIR_SCAN_MAX_ENTRIES = 1_000_000
/** 每列完一层目录最多刷一次 UI 的间隔:每条目 emit 会把整树 rebuild 打爆。 */
private const val DIR_SCAN_UI_MS = 200L

/**
 * 转圈最短显示时长。本地小目录几十毫秒就扫完,转圈撑不过一帧 —— 用户看到的是
 * "根本没有转圈"(2026-08-04 实测反馈)。宁可多转半秒,也别让状态提示形同虚设。
 */
internal const val DIR_SCAN_MIN_SPIN_MS = 600L

/**
 * 递归统计 [root] 下的文件数/目录数/总字节,BFS 遍历,边扫边 emit 中间结果
 * (最快 [DIR_SCAN_UI_MS] 一次,最后一条必发且是完整值)。
 * 单目录列举失败(权限/网络抖动)按 [scanSearch]/[TreemapScanner] 的既有约定跳过,不中止整体统计。
 * 取消由调用方 cancel 协程完成——注意阻塞在 `list()` 里的那一次网络往返不会被打断,
 * 会在返回后的检查点才退出。
 *
 * 只"取数据",累计与落表都在收集方(主线程)做,符合 [PaneViewModel] 那条
 * "树状态只在主线程写"的规则;[io] 可注入是为了单测能用测试调度器推完。
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
