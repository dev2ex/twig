package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** 一对条目的比对结论。 */
enum class PairState {
    /** 两侧都有且认定相同。 */
    SAME,

    /** 两侧都有但不同(大小/时间/内容)。 */
    DIFF,

    /** 只在左侧存在。 */
    LEFT_ONLY,

    /** 只在右侧存在。 */
    RIGHT_ONLY,

    /** 目录:子树还没扫完,汇总状态未知。 */
    SCANNING,
}

/**
 * 判定选项。默认值是"跨来源也不误报"的一套,理由见各字段——
 * 直接 `lastModified ==` 比较会让本地↔FTP / 本地↔zip 几乎全红。
 */
data class CompareOptions(
    /**
     * 时间容差。各来源的 mtime 精度差得很远:本地 ms、SMB 100ns、WebDAV 的
     * Last-Modified 秒级、FTP 常只到秒甚至分钟,**zip 条目是 DOS 时间(2 秒粒度)**,
     * exFAT/FAT32 同样 2 秒。默认 2s 正好盖住最粗的那档。
     */
    val timeToleranceMs: Long = 2_000L,
    /**
     * 允许整小时偏移。FTP 的 MDTM/LIST 经常分不清 GMT 还是服务器本地时,
     * 整个目录会齐刷刷差若干小时——那不是内容变了,是时区错配。
     */
    val allowHourShift: Boolean = true,
    /** 配对时忽略大小写:ext4 敏感而 SMB/exFAT 不敏感,否则 README.md/Readme.md 会各成孤儿。 */
    val ignoreCase: Boolean = true,
    /**
     * 内容比对的大小上限,**分本地与网络两档**:本地读几乎无代价,网络要两边整份下载,
     * 与"免整读原则"冲突,所以默认关(0)。一对文件里只要有一侧超过它那档的上限,
     * 这一对就不比内容、退回按时间判。
     */
    val contentLimitLocal: Long = 1L shl 20,
    val contentLimitNetwork: Long = 0L,
    /**
     * 只在**时间不同**时才读内容:大小与时间都一致的直接判定相同,不读。
     * 绝大多数文件属于这一类,省掉的读取量很可观;关掉则只要大小相同就读,
     * 能抓到"时间戳被保留、内容被改过"的情况(代价是每一对都要整读)。
     */
    val contentOnlyIfTimeDiffers: Boolean = true,
    /** 排除规则,见 [matchesExclude];扫描时就剪枝,不是扫完再过滤。 */
    val excludes: List<String> = emptyList(),
) {
    /** 任一侧开了内容比对。 */
    val contentEnabled: Boolean get() = contentLimitLocal > 0 || contentLimitNetwork > 0
}

/** 扫描出的一个条目:两侧至少有一个非空。 */
data class CompareEntry(
    /** 用于配对与显示的名字(取存在的那一侧)。 */
    val name: String,
    val left: XFile?,
    val right: XFile?,
    val isDir: Boolean,
    val state: PairState,
) {
    /** 任一侧的代表条目,取图标/扩展名等用。 */
    val any: XFile get() = left ?: right!!
}

/** 扫描过程中的增量事件;父目录的 [Children] 一定先于其子目录的事件到达。 */
sealed interface CompareEvent {
    /** 某个目录的子项列出来了(先序,立即可见)。[dirKey] 为相对根的路径,根是空串。 */
    data class Children(val dirKey: String, val rows: List<CompareEntry>) : CompareEvent

    /** 某个目录的整棵子树扫完了,回填汇总状态(有任一子孙非 SAME 即 DIFF)。 */
    data class DirDone(val dirKey: String, val state: PairState) : CompareEvent

    /** 进度:已比对条目数、其中有差异的、当前正在扫的相对路径。 */
    data class Progress(val entries: Int, val diffs: Int, val path: String) : CompareEvent

    /**
     * 某一项被排除规则挡下了。记着它是为了"删掉这条规则时能只恢复它挡掉的那些",
     * 不必整树重扫。[dirKey] 是所在目录,[name] 是项名。
     */
    data class Excluded(val dirKey: String, val name: String, val rule: String) : CompareEvent

    /** 条目数触顶,结果不完整。 */
    data object Truncated : CompareEvent
}

/** 防环/防爆:与 [scanSearch]、[scanDirStat] 取同一量级的护栏。 */
private const val COMPARE_MAX_DEPTH = 64
private const val COMPARE_MAX_ENTRIES = 100_000
private const val COMPARE_CONTENT_BUF = 64 * 1024

/**
 * 排除规则匹配。含 `/` 的规则按**相对路径**整体匹配(如 "build" 加斜杠加 "*.o"),否则按**文件名**
 * 匹配(`*.tmp`)。两种都是全名通配符语义——不像 [matchesSearchPattern] 那样无通配符时
 * 退化成子串匹配:排除规则里写 `build` 若按子串匹配会连 `rebuild.log` 一起误伤。
 */
fun matchesExclude(name: String, relPath: String, patterns: List<String>): Boolean =
    excludeRuleFor(name, relPath, patterns) != null

/**
 * 同 [matchesExclude],但返回**是哪条规则**挡下的。删掉某条规则时要靠它精确定位
 * "当初被这条规则挡掉的项",只恢复那些,而不是整树重扫。
 */
fun excludeRuleFor(name: String, relPath: String, patterns: List<String>): String? =
    patterns.firstOrNull { p ->
        val pat = p.trim()
        if (pat.isEmpty()) false
        else if (pat.contains('/')) globMatches(relPath, pat) else globMatches(name, pat)
    }

/** `*` / `?` 通配符的全名匹配(忽略大小写);无通配符时要求全等。 */
internal fun globMatches(text: String, pattern: String): Boolean {
    val regex = buildString {
        append("(?i)")
        for (c in pattern) when (c) {
            '*' -> append(".*")
            '?' -> append(".")
            else -> append(Regex.escape(c.toString()))
        }
    }
    return regex.toRegex().matches(text)
}

/**
 * 两个时间戳是否算"同一时刻":先看容差,再看是否只差整数个小时(时区错配)。
 * 半小时时区(印度 +5:30 等)不在这条规则里——那种情况请把容差调大或关掉时间判定。
 */
internal fun sameTime(a: Long, b: Long, o: CompareOptions): Boolean {
    val d = abs(a - b)
    if (d <= o.timeToleranceMs) return true
    if (!o.allowHourShift) return false
    val hour = 3_600_000L
    val k = (d + hour / 2) / hour
    return k > 0 && abs(d - k * hour) <= o.timeToleranceMs
}

/** 该来源是否算"本地"。压缩包内的项按网络对待——外层可能挂在 SMB 上(与缩略图同一约定)。 */
internal fun isLocalSide(f: XFile): Boolean = f.scheme == "file"

/** 这一对是否够小、可以比内容;两侧各按自己那档上限,任一侧超了就不比。 */
internal fun contentComparable(l: XFile, r: XFile, o: CompareOptions): Boolean {
    fun limit(f: XFile) = if (isLocalSide(f)) o.contentLimitLocal else o.contentLimitNetwork
    val lim = minOf(limit(l), limit(r))
    return lim > 0 && l.size <= lim && r.size <= lim
}

/**
 * 逐块流式比对两个文件,**遇到第一处不同立刻返回**。比"两边各算一遍哈希"省一半以上——
 * 不同的文件通常在很靠前的地方就分叉,而哈希必须把两边都读完。
 * 读失败(权限/断线)当作"不同",宁可让用户看见一条差异去查,也不要谎报相同。
 */
internal suspend fun contentEquals(l: XFile, r: XFile): Boolean {
    if (l.size != r.size) return false
    return runCatching {
        FsRegistry.of(l).openInput(l).use { a ->
            FsRegistry.of(r).openInput(r).use { b -> streamsEqual(a, b) }
        }
    }.getOrDefault(false)
}

private suspend fun streamsEqual(a: InputStream, b: InputStream): Boolean {
    val ba = ByteArray(COMPARE_CONTENT_BUF)
    val bb = ByteArray(COMPARE_CONTENT_BUF)
    while (true) {
        coroutineContext.ensureActive()
        val na = a.readFully(ba)
        val nb = b.readFully(bb)
        if (na != nb) return false
        if (na == 0) return true
        for (i in 0 until na) if (ba[i] != bb[i]) return false
    }
}

/** 尽量填满 buf(流可能短读),返回实际读到的字节数,0 = 到末尾。 */
private fun InputStream.readFully(buf: ByteArray): Int {
    var n = 0
    while (n < buf.size) {
        val k = read(buf, n, buf.size - n)
        if (k < 0) break
        n += k
    }
    return n
}

/**
 * 判定两个**文件**(非目录)的状态。开了内容比对且这一对够小、又没被
 * [CompareOptions.contentOnlyIfTimeDiffers] 放过的话以内容为准,否则按大小 + 时间。
 * 大小不同必然不同,这时连时间都不用看、更不用读内容。
 */
internal suspend fun compareFiles(l: XFile, r: XFile, o: CompareOptions): PairState {
    if (l.size != r.size) return PairState.DIFF
    val timeSame = sameTime(l.lastModified, r.lastModified, o)
    // 大小时间都一样的那批占绝大多数,[CompareOptions.contentOnlyIfTimeDiffers] 开着时
    // 直接放过,省下的正是最大头的那部分读取
    val skipByTime = o.contentOnlyIfTimeDiffers && timeSame
    if (!skipByTime && contentComparable(l, r, o)) {
        return if (contentEquals(l, r)) PairState.SAME else PairState.DIFF
    }
    return if (timeSame) PairState.SAME else PairState.DIFF
}

/**
 * 把两侧的子项按名字配对。同名多项(ext4 上 `A.txt` 与 `a.txt` 可以并存而忽略大小写
 * 配对时会撞在一起)按出现顺序依次配,配不上的各自成孤儿——**一个都不能丢**。
 * 目录与文件同名视为两种键,不会互相配对。
 */
internal fun pairEntries(
    left: List<XFile>,
    right: List<XFile>,
    ignoreCase: Boolean,
): List<Pair<XFile?, XFile?>> {
    fun key(f: XFile) = (if (ignoreCase) f.name.lowercase() else f.name) + (if (f.isDir) "/" else "")
    val buckets = LinkedHashMap<String, ArrayDeque<XFile>>()
    for (f in right) buckets.getOrPut(key(f)) { ArrayDeque() }.addLast(f)
    val out = ArrayList<Pair<XFile?, XFile?>>(left.size + right.size)
    for (f in left) out += f to buckets[key(f)]?.removeFirstOrNull()
    for (rest in buckets.values) for (f in rest) out += null to f
    return out
}

/**
 * 递归对比 [leftRoot] 与 [rightRoot],边扫边发事件。
 *
 * **先序 emit 子项、后序回填目录状态**:顶层目录一列完用户就能看见内容(顶层第一个
 * 目录很大时不至于长时间空屏),该目录的汇总状态等它整棵子树扫完再更新。
 *
 * 并发只做"左右两侧的 list 并行",**不并行扫多个目录**:`smb2_context` 靠一把可重入锁
 * 串行化、FTP 只有一条控制连接,多目录齐发不会更快,只会把锁堵死或打爆连接。
 *
 * 单侧独有的目录仍然递归列举——用户要看得到里面有什么、也要能整个复制过去,
 * 而且不列的话进度里的条目数会失真。
 *
 * 取消由调用方 cancel 协程完成;阻塞在 `list()`/`openInput()` 里的那一次网络往返
 * 不会被打断,会在返回后的检查点退出(与 [scanDirStat] 同一约定)。
 */
fun scanCompare(
    leftRoot: XFile?,
    rightRoot: XFile?,
    options: CompareOptions,
    io: CoroutineDispatcher = Dispatchers.IO,
): Flow<CompareEvent> = flow {
    var entries = 0
    var diffs = 0
    var truncated = false

    suspend fun list(f: XFile?): List<XFile> =
        if (f == null) emptyList()
        else runCatching { FsRegistry.of(f).list(f) }.getOrDefault(emptyList())

    /** 返回该目录子树的汇总状态。 */
    suspend fun walk(
        left: XFile?,
        right: XFile?,
        dirKey: String,
        depth: Int,
        forced: PairState?,
    ): PairState {
        coroutineContext.ensureActive()
        if (truncated) return forced ?: PairState.SCANNING
        emit(CompareEvent.Progress(entries, diffs, dirKey))

        // 两侧 list 并行:不同来源时是真并行,同来源时底层自己会串行化,无害。
        val (ls, rs) = coroutineScope {
            val a = async { list(left) }
            val b = async { list(right) }
            a.await() to b.await()
        }

        val rows = ArrayList<CompareEntry>()
        val subDirs = ArrayList<Triple<CompareEntry, String, PairState?>>()
        for ((l, r) in pairEntries(ls, rs, options.ignoreCase)) {
            coroutineContext.ensureActive()
            val rep = l ?: r!!
            val rel = if (dirKey.isEmpty()) rep.name else "$dirKey/${rep.name}"
            val rule = excludeRuleFor(rep.name, rel, options.excludes)
            if (rule != null) {
                emit(CompareEvent.Excluded(dirKey, rep.name, rule))
                continue
            }
            if (entries >= COMPARE_MAX_ENTRIES) { truncated = true; break }
            entries++

            // 单侧独有,以及"一边是目录另一边是文件"这种配不上的,都按孤儿处理。
            val side = forced ?: when {
                l == null -> PairState.RIGHT_ONLY
                r == null -> PairState.LEFT_ONLY
                else -> null
            }
            if (rep.isDir) {
                // 单侧独有(或父级已经定死)的目录,这一刻状态就已经确定,不必先报 SCANNING
                // 再等子树扫完改口——UI 上那是先闪一个"…"再变成"◀"
                val e = CompareEntry(rep.name, l, r, isDir = true, state = side ?: PairState.SCANNING)
                rows += e
                if (depth < COMPARE_MAX_DEPTH) subDirs += Triple(e, rel, side)
            } else {
                val st = side ?: compareFiles(l!!, r!!, options)
                if (st != PairState.SAME) diffs++
                rows += CompareEntry(rep.name, l, r, isDir = false, state = st)
            }
        }
        emit(CompareEvent.Children(dirKey, rows))
        if (truncated) emit(CompareEvent.Truncated)

        var worst = PairState.SAME
        for (row in rows) if (!row.isDir && row.state != PairState.SAME) worst = PairState.DIFF
        for ((e, rel, side) in subDirs) {
            val sub = walk(e.left, e.right, rel, depth + 1, side)
            if (sub != PairState.SAME) worst = PairState.DIFF
        }
        // 单侧独有的目录:自身状态是"只在某侧",不因为子树内容而改写。
        val self = forced ?: when {
            left == null -> PairState.RIGHT_ONLY
            right == null -> PairState.LEFT_ONLY
            else -> worst
        }
        emit(CompareEvent.DirDone(dirKey, self))
        return self
    }

    walk(leftRoot, rightRoot, "", 0, null)
    emit(CompareEvent.Progress(entries, diffs, ""))
}.flowOn(io)
