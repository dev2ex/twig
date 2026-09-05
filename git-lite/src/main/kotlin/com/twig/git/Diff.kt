package com.twig.git

/**
 * Line-level diff (patience algorithm): unique lines on both sides are used as anchors
 * combined with LIS, recursively dividing and conquering; produces a left/right-aligned
 * sequence of rows, ready for the two-column diff view to render.
 */
object Diff {

    /**
     * An aligned row: when [left]/[right] is null it is a placeholder on that side
     * (add/delete on the other side); when [changed] is false, both sides match (context line).
     * Line numbers start at 1; placeholders are 0.
     */
    class Row(
        val leftNo: Int,
        val left: String?,
        val rightNo: Int,
        val right: String?,
        val changed: Boolean,
    )

    fun rows(old: List<String>, new: List<String>): List<Row> {
        val ops = ArrayList<IntArray>(old.size + new.size) // [kind(0 same / 1 delete / 2 add), aIdx, bIdx]
        solve(old, new, 0, old.size, 0, new.size, ops)

        // adjacent del/add segments are paired into "modified" rows; padding with placeholders for length differences
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
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) { // common prefix
            out.add(intArrayOf(0, aLo, bLo)); aLo++; bLo++
        }
        var suffix = 0
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) { // common suffix; appended last
            aHi--; bHi--; suffix++
        }
        when {
            aLo == aHi -> for (j in bLo until bHi) out.add(intArrayOf(2, 0, j))
            bLo == bHi -> for (j in aLo until aHi) out.add(intArrayOf(1, j, 0))
            else -> {
                val anchors = anchors(a, b, aLo, aHi, bLo, bHi)
                if (anchors.isEmpty()) { // no anchors: treat the whole segment as a replacement
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

    /** Position pairs of lines unique on both sides, picking a consistent anchor chain by longest increasing subsequence. */
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
        val pairs = ArrayList<IntArray>() // ordered by aIdx ascending
        for (i in aLo until aHi) {
            val line = a[i]
            if (countA[line] == 1 && countB[line] == 1) pairs.add(intArrayOf(i, posB[line]!!))
        }
        if (pairs.isEmpty()) return emptyList()

        // LIS (by bIdx), with backtracking
        val tails = ArrayList<Int>() // pair indices
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
