package com.twig.app.ui

import com.twig.git.Diff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「把这段差异搬到对侧」的逻辑。合并的结果是要**写回原文件**的,算错一处就是
 * 改一段毁全文,所以这里连行尾、末尾换行这种看不见的字节都要盯住。
 */
class DiffMergeTest {

    private fun rowsOf(left: String, right: String) = Diff.rows(toLines(left), toLines(right))

    /** 走一遍界面上的实际流程:切行 → diff → 合并第 [block] 处 → 拼回文本。 */
    private fun merge(left: String, right: String, toRight: Boolean, block: Int = 0): String {
        val rows = rowsOf(left, right)
        val starts = rows.indices.filter { rows[it].changed && (it == 0 || !rows[it - 1].changed) }
        val range = blockRange(rows, starts[block])
        val target = if (toRight) right else left
        return joinLines(mergedLines(rows, range, toRight), target.endsWith("\n"))
    }

    @Test
    fun `切行与拼回严格互逆`() {
        // 少一条都会让"只改了一段"变成"整个文件都动了"
        for (t in listOf("", "a", "a\n", "a\nb", "a\nb\n", "\n", "\n\n", "a\n\n", "a\r\nb\r\n", "a\r\nb")) {
            assertEquals(t, joinLines(toLines(t), t.endsWith("\n")))
        }
    }

    @Test
    fun `CRLF 不用探测行尾,回车符本来就留在行里`() {
        // ★ 按 '\n' 切,CRLF 的 \r 留在每行末尾;再按 '\n' 拼就是原样。
        // 若改成先 trim 掉 \r 再拼,整个文件的行尾会被悄悄改写成 LF
        assertEquals(listOf("a\r", "b\r"), toLines("a\r\nb\r\n"))
        assertEquals("a\r\nb\r\n", joinLines(listOf("a\r", "b\r"), finalNewline = true))
    }

    @Test
    fun `末尾没有换行的文件不会被补上一个`() {
        // 凭空补一个换行,在 git 里就是整文件末行的变更
        assertEquals("x\ny", merge(left = "x\ny", right = "x\nz", toRight = true))
        // 目标侧末尾没换行,源侧有,也不能把源侧那个带过来
        assertEquals("x\nz", merge(left = "x\ny", right = "x\nz\n", toRight = false))
    }

    @Test
    fun `改动块搬到右侧`() {
        assertEquals("a\nX\nc\n", merge(left = "a\nX\nc\n", right = "a\nY\nc\n", toRight = true))
    }

    @Test
    fun `改动块搬到左侧`() {
        assertEquals("a\nY\nc\n", merge(left = "a\nX\nc\n", right = "a\nY\nc\n", toRight = false))
    }

    @Test
    fun `左侧多出来的行搬过去是新增`() {
        assertEquals("a\nb\nc\n", merge(left = "a\nb\nc\n", right = "a\nc\n", toRight = true))
    }

    @Test
    fun `源侧是占位就等于把目标侧这几行删掉`() {
        // 左边没有 b,把这一块搬到右边 = 右边的 b 消失
        assertEquals("a\nc\n", merge(left = "a\nc\n", right = "a\nb\nc\n", toRight = true))
    }

    @Test
    fun `只动指定的那一块,别的差异原样留着`() {
        val left = "1\nA\n3\nB\n5\n"
        val right = "1\nx\n3\ny\n5\n"
        // 合第一块:A 顶掉 x,第二块的 y 不动
        assertEquals("1\nA\n3\ny\n5\n", merge(left, right, toRight = true, block = 0))
        // 合第二块:反过来
        assertEquals("1\nx\n3\nB\n5\n", merge(left, right, toRight = true, block = 1))
    }

    @Test
    fun `一整块相邻的变更行是一次搬完的`() {
        val rows = rowsOf("1\nA\nB\n4\n", "1\nx\ny\n4\n")
        // A/B 与 x/y 配成两行"修改",它们相连,属于同一块
        assertEquals(1..2, blockRange(rows, 1))
        assertEquals("1\nA\nB\n4\n", merge("1\nA\nB\n4\n", "1\nx\ny\n4\n", toRight = true))
    }

    @Test
    fun `把所有块都合过去就等于整份复制`() {
        var right = "a\nq\nc\nq\n"
        val left = "a\nb\nc\nd\n"
        // 一块一块合,每合完一次要重新 diff——块的行下标全变了
        repeat(2) {
            val rows = rowsOf(left, right)
            val start = rows.indices.first { rows[it].changed }
            right = joinLines(
                mergedLines(rows, blockRange(rows, start), toRight = true),
                finalNewline = true,
            )
        }
        assertEquals(left, right)
    }
}
