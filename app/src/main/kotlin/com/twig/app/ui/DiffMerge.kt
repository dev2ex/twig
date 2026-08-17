package com.twig.app.ui

import com.twig.git.Diff

/**
 * 双栏 diff「把这段差异搬到对侧」的纯逻辑。抽出来是为了能单测锁住——这里错一点
 * 就是"改一段、毁全文",而写回不可逆。
 */

/**
 * 文本切成行。末尾的换行不算一行(不然每份文件末尾都会多出一个空行);
 * 与 [joinLines] 严格互逆,任意文本切开再拼回去逐字节不变。
 */
internal fun toLines(text: String): List<String> =
    text.split('\n').let {
        if (it.lastOrNull() == "") it.dropLast(1) else it
    }.ifEmpty { emptyList() }

/**
 * 差异块([DiffActivity] 的 `blocks` 里存的是起始下标)覆盖的连续变更行区间(闭区间)。
 * [Diff.rows] 把相邻的删/增段两两配对成"修改"行,所以一段差异就是一串 `changed` 相连的行。
 */
internal fun blockRange(rows: List<Diff.Row>, start: Int): IntRange {
    var e = start
    while (e + 1 < rows.size && rows[e + 1].changed) e++
    return start..e
}

/**
 * 目标侧应用完这段差异之后的完整行序列:[range] 内取源侧的行(源侧是占位 = 目标侧这
 * 几行被删掉),区间外原样保留目标侧的行。
 *
 * 整侧重建而不是就地拼接:[Diff.Row] 本来就是两侧对齐的完整序列,顺着它走一遍
 * 既不会漏行也不会错位,不用自己维护"目标侧第几行对应第几个 row"这种偏移量。
 */
internal fun mergedLines(rows: List<Diff.Row>, range: IntRange, toRight: Boolean): List<String> {
    val out = ArrayList<String>(rows.size)
    for (i in rows.indices) {
        // 区间内要源侧、区间外要目标侧;toRight 时源侧是左、目标侧是右
        val take = if ((i in range) == toRight) rows[i].left else rows[i].right
        if (take != null) out.add(take)
    }
    return out
}

/**
 * 行序列拼回文本。
 *
 * ★ **不需要探测行尾**:`DiffActivity.toLines` 是按 `\n` 切的,CRLF 文件每行末尾的
 * `\r` 本来就留在行里(diff 视图里看不见,但它在),这里再按 `\n` 拼回去就是逐字节
 * 原样——混着 LF/CRLF 的文件也不会被整篇改写行尾。切行时唯一丢掉的信息是**文件末尾
 * 有没有换行**,所以那个得单独带着走:凭空补一个换行,在 git 里就是整文件末行的变更。
 */
internal fun joinLines(lines: List<String>, finalNewline: Boolean): String =
    if (lines.isEmpty()) "" else lines.joinToString("\n") + if (finalNewline) "\n" else ""
