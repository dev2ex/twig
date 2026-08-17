package com.twig.app.ui

import com.twig.core.XFile

/**
 * 列表的**分组排序规则**。
 *
 * 从 [PaneViewModel.sortList] 里抽出来,是为了能用纯 JVM 单测覆盖:原来那段会现读
 * `Prefs`(SharedPreferences)、调 `Thumbs.canThumb`(`Thumbs` 这个 object 的初始化
 * 里有 `android.util.LruCache`),在 :app 的纯 JVM 测试里一 load 就 `Stub!`。
 * 这里把"要不要分组""是不是可展开归档""能不能出缩略图"全部变成**入参**,
 * 决策逻辑就跟 Android 脱钩了;去哪儿问这些开关仍由 [PaneViewModel] 决定。
 */
object SortRules {

    /**
     * 一个条目属于哪一组,数字越小越靠前:
     *
     * | 组 | 内容 | 为什么 |
     * |---|---|---|
     * | 0 | 目录 | 文件管理器的常规 |
     * | 1 | 可展开的压缩包(仅 [archivesFirst]) | 网格「全部文件」下它渲染成整行,夹在格子中间会把网格切断 |
     * | 2 | 能出缩略图的文件 | 缩略图开着时图文混排很难扫,聚在一起才像相册 |
     * | 3 | 其余 | |
     */
    fun groupOf(
        isDir: Boolean,
        isExpandableArchive: Boolean,
        canThumb: Boolean,
        archivesFirst: Boolean,
    ): Int = when {
        isDir -> 0
        archivesFirst && isExpandableArchive -> 1
        canThumb -> 2
        else -> 3
    }

    /**
     * 按 [cmp] 排序;[groupOf] 非空时先按组再按 [cmp](传 null = 不分组,
     * 与"缩略图和网格都关着"时历来的行为一致)。
     *
     * **每项的分组只算一次**。原来是在 Comparator 里现算的,那是 O(n log n) 次,
     * 而分组结果只跟条目自己有关、跟拿它跟谁比毫无关系——`canThumb` 里还带一次
     * `split('/')`,几千条目的目录白白多跑上万次。
     */
    fun sorted(list: List<XFile>, cmp: Comparator<XFile>, groupOf: ((XFile) -> Int)?): List<XFile> {
        if (groupOf == null) return list.sortedWith(cmp)
        return list.map { it to groupOf(it) }
            .sortedWith(
                Comparator { a, b ->
                    val g = a.second - b.second
                    if (g != 0) g else cmp.compare(a.first, b.first)
                },
            )
            .map { it.first }
    }
}
