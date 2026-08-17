package com.twig.git

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * index 解析的健壮性。这里全是裸下标的二进制解析,而 `.git/index` 完全可能读到
 * 半截(另一个进程正在写、网络仓库传输中断),越界异常会顺着 `GitRepo.status()`
 * 一路冒到 UI 把应用带崩 —— 所以坏输入必须降级成"能解多少算多少",不能抛。
 */
class IndexFileTest {

    @Test
    fun emptyAndNonIndexInputs() {
        assertTrue(IndexFile.read(null).isEmpty())
        assertTrue(IndexFile.read(ByteArray(0)).isEmpty())
        assertTrue(IndexFile.read("not an index".toByteArray()).isEmpty())
    }

    /** 版本号不认识 → 空表(不是异常)。 */
    @Test
    fun unsupportedVersion() {
        val b = header(version = 9, count = 3)
        assertTrue(IndexFile.read(b).isEmpty())
    }

    /**
     * 回归:头里声称有 5 个条目,实际字节在第一个条目中间就断了。
     * 老实现会 ArrayIndexOutOfBounds;现在应该安静地返回已经解出来的部分。
     */
    @Test
    fun truncatedEntriesDoNotThrow() {
        val b = header(version = 2, count = 5) + ByteArray(30) // 一个条目至少 62 字节
        val result = IndexFile.read(b) // 不抛就算过
        assertTrue(result.isEmpty())
    }

    /**
     * 条目数吹得离谱(损坏的头)同样不能崩,而且要**能停下来**——
     * 声称有 21 亿条、实际只有 200 字节,解析必须在数据用完时收手而不是一直转。
     *
     * 这里只断言"不抛、能返回",不断言条数:全零字节喂进去会先被当成一条合法条目
     * (空文件名、全零 sha)解出来,那是"能解多少算多少"的应有行为——比起因为末尾
     * 坏了就把整份 index 丢掉(status 会把所有文件报成已删除,更吓人),partial
     * 结果对用户更有用。
     */
    @Test
    fun absurdCountDoesNotThrowAndTerminates() {
        val b = header(version = 2, count = Int.MAX_VALUE) + ByteArray(200)
        val result = IndexFile.read(b)
        assertTrue("解析应在数据用完时收手", result.size < 10)
    }

    private fun header(version: Int, count: Int): ByteArray =
        "DIRC".toByteArray(Charsets.US_ASCII) + be32(version) + be32(count)

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
}
