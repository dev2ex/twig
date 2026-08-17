package com.twig.git

/**
 * 行级 diff(patience 算法):双侧唯一行做锚点 + LIS,递归分治;
 * 产出左右对齐的行序列,直接供双栏 diff 视图渲染。
 */
object Diff {

    /**
     * 对齐后的一行:[left]/[right] 为 null 表示该侧占位(对侧新增/删除);
     * [changed] 为 false 时两侧相同(上下文行)。行号 1 起,占位为 0。
     */
    class Row(
        val leftNo: Int,
        val left: String?,
        val rightNo: Int,
        val right: String?,
        val changed: Boolean,
    )

    fun rows(old: List<String>, new: List<String>): List<Row> {
        val ops = ArrayList<IntArray>(old.size + new.size) // [kind(0同/1删/2增), aIdx, bIdx]
        solve(old, new, 0, old.size, 0, new.size, ops)

        // del/add 相邻段两两配对为"修改"行,长短差补占位
        val rows = ArrayList<Row>(ops.size)
        var i = 0
        while (i < ops.size) {
            if (ops[i][0] == 0) {
                val op = ops[i]
                rows.add(Row(op[1] + 1, old[op[1]], op[2] + 1, new[op[2]], changed = false))
                i++
            } else {
                val dels = ArrayList<Int>()
                val adds = ArrayList<Int>()
                while (i < ops.size && ops[i][0] != 0) {
                    if (ops[i][0] == 1) dels.add(ops[i][1]) else adds.add(ops[i][2])
                    i++
                }
                for (k in 0 until maxOf(dels.size, adds.size)) {
                    val d = dels.getOrNull(k)
                    val a = adds.getOrNull(k)
                    rows.add(
                        Row(
                            d?.plus(1) ?: 0, d?.let { old[it] },
                            a?.plus(1) ?: 0, a?.let { new[it] },
                            changed = true,
                        ),
                    )
                }
            }
        }
        return rows
    }

    private fun solve(
        a: List<String>, b: List<String>,
        aLo0: Int, aHi0: Int, bLo0: Int, bHi0: Int,
        out: MutableList<IntArray>,
    ) {
        var aLo = aLo0; var aHi = aHi0
        var bLo = bLo0; var bHi = bHi0
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) { // 公共前缀
            out.add(intArrayOf(0, aLo, bLo)); aLo++; bLo++
        }
        var suffix = 0
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) { // 公共后缀,最后补
            aHi--; bHi--; suffix++
        }
        when {
            aLo == aHi -> for (j in bLo until bHi) out.add(intArrayOf(2, 0, j))
            bLo == bHi -> for (j in aLo until aHi) out.add(intArrayOf(1, j, 0))
            else -> {
                val anchors = anchors(a, b, aLo, aHi, bLo, bHi)
                if (anchors.isEmpty()) { // 无锚点:整段视为替换
                    for (j in aLo until aHi) out.add(intArrayOf(1, j, 0))
                    for (j in bLo until bHi) out.add(intArrayOf(2, 0, j))
                } else {
                    var pa = aLo; var pb = bLo
                    for (p in anchors) {
                        solve(a, b, pa, p[0], pb, p[1], out)
                        out.add(intArrayOf(0, p[0], p[1]))
                        pa = p[0] + 1; pb = p[1] + 1
                    }
                    solve(a, b, pa, aHi, pb, bHi, out)
                }
            }
        }
        for (k in 0 until suffix) out.add(intArrayOf(0, aHi + k, bHi + k))
    }

    /** 双侧唯一行的位置对,按最长递增子序列取一致的锚点链。 */
    private fun anchors(
        a: List<String>, b: List<String>,
        aLo: Int, aHi: Int, bLo: Int, bHi: Int,
    ): List<IntArray> {
        val countA = HashMap<String, Int>()
        for (i in aLo until aHi) countA.merge(a[i], 1, Int::plus)
        val countB = HashMap<String, Int>()
        val posB = HashMap<String, Int>()
        for (i in bLo until bHi) {
            countB.merge(b[i], 1, Int::plus)
            posB[b[i]] = i
        }
        val pairs = ArrayList<IntArray>() // 按 aIdx 递增
        for (i in aLo until aHi) {
            val line = a[i]
            if (countA[line] == 1 && countB[line] == 1) pairs.add(intArrayOf(i, posB[line]!!))
        }
        if (pairs.isEmpty()) return emptyList()

        // LIS(按 bIdx),带回溯
        val tails = ArrayList<Int>() // pairs 下标
        val prev = IntArray(pairs.size) { -1 }
        for (i in pairs.indices) {
            val x = pairs[i][1]
            var lo = 0; var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pairs[tails[mid]][1] < x) lo = mid + 1 else hi = mid
            }
            if (lo > 0) prev[i] = tails[lo - 1]
            if (lo == tails.size) tails.add(i) else tails[lo] = i
        }
        var cur = tails.last()
        val res = ArrayList<IntArray>()
        while (cur != -1) {
            res.add(pairs[cur]); cur = prev[cur]
        }
        res.reverse()
        return res
    }
}
