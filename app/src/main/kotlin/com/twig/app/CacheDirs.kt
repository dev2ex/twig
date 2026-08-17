package com.twig.app

import android.content.Context
import java.io.File

/**
 * 应用缓存目录的容量管理。
 *
 * 会往 `cacheDir` 里堆东西的地方:
 * - `arc/` —— 必须落到本地才读得了的归档(RAR:junrar 只认本地文件;压缩率高的嵌套包:
 *   隔着外层解压流 seek 会退化成反复全量解压),见 [ui.PaneViewModel] 的 localArchive;
 * - `open/` —— "用其他应用打开"时物化的副本,见 [OpenFiles.materialize];
 * - `thumbs/` —— 缩略图,自带 LRU(见 `Thumbs.trim`),不归这里管。
 *
 * 前两处原来**只增不减**。浏览十几个 SMB 上的大 rar,`cacheDir` 就能涨到几个 GB;
 * 系统只在存储紧张时才会清 cacheDir,在那之前用户在系统设置里看到的是
 * "这个文件管理器占了 5 个 G"。这里给它们一个上限,超了按最后访问时间从旧到新删。
 */
object CacheDirs {

    const val ARCHIVES = "arc"
    const val OPEN = "open"

    /** 物化缓存的上限(每个子目录各算各的)。 */
    private const val CAP = 512L * 1024 * 1024

    /** 超限后删到这个水位,别每次只删一个文件、下次立刻又超。 */
    private const val LOW_WATER = 8 / 10.0

    fun dir(ctx: Context, name: String): File =
        File(ctx.cacheDir, name).apply { mkdirs() }

    /**
     * 按需裁剪:总量超过 [CAP] 时,按 `lastModified`(读取时会刷新,近似 LRU)
     * 从旧到新删到 80% 水位。调用方在**写入之后**调一次即可——两处调用点本来就在
     * 做一次大文件拷贝,多一次 listFiles() 可以忽略。
     *
     * [keep] 是本次刚写好、马上要用的那个文件,一定不能删:单个文件就超过上限时
     * (比如一个 1GB 的 rar,而上限是 512MB),不排除的话循环会一路删到把它也删掉,
     * 调用方随即拿到一个指向已删除文件的路径。
     */
    fun trim(dir: File, keep: File? = null, cap: Long = CAP) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= cap) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (keep != null && f.absolutePath == keep.absolutePath) continue
            val len = f.length()
            if (!f.delete()) continue
            total -= len
            if (total <= cap * LOW_WATER) break
        }
    }

    /** 当前占用(设置页展示 / 排查用)。 */
    fun bytes(ctx: Context, name: String): Long =
        File(ctx.cacheDir, name).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
