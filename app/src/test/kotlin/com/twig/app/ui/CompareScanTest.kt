package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录对比的判定与扫描([CompareScan.kt])。这里罩住的都是**跨来源时最容易误报**的点:
 * 时间精度/时区、大小写敏感性、排除规则的匹配语义,以及"排除的目录真的没被下探"。
 * 纯逻辑,不碰 UI,所以不需要 Robolectric。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompareScanTest {

    private val registered = mutableListOf<String>()

    private fun fakeFs(
        scheme: String,
        dirs: Map<String, List<String>>,
        files: Map<String, String> = emptyMap(),
        times: Map<String, Long> = emptyMap(),
    ): FakeFileSystem {
        val fs = FakeFileSystem(scheme, dirs, files, times)
        FsRegistry.register(fs)
        registered += scheme
        return fs
    }

    @After
    fun tearDown() {
        registered.forEach { FsRegistry.unregister(it) }
    }

    // ---- 时间判定 ----

    @Test
    fun `2 秒容差盖住 FAT 与 zip 的粗粒度时间`() {
        val o = CompareOptions()
        assertTrue(sameTime(1_000_000L, 1_001_999L, o))
        assertFalse(sameTime(1_000_000L, 1_003_000L, o))
    }

    @Test
    fun `整小时偏移当作时区错配而不是内容变更`() {
        val o = CompareOptions()
        val hour = 3_600_000L
        assertTrue("差 1 小时", sameTime(1_000_000L, 1_000_000L + hour, o))
        assertTrue("差 8 小时(东八区)", sameTime(1_000_000L, 1_000_000L - 8 * hour, o))
        assertTrue("整小时上下再浮动 1 秒也算", sameTime(1_000_000L, 1_000_000L + hour + 1_000L, o))
        assertFalse("差半小时不算", sameTime(1_000_000L, 1_000_000L + hour / 2, o))
    }

    @Test
    fun `关掉整小时偏移后差一小时就是差异`() {
        val o = CompareOptions(allowHourShift = false)
        assertFalse(sameTime(1_000_000L, 1_000_000L + 3_600_000L, o))
    }

    // ---- 排除规则 ----

    @Test
    fun `排除规则是全名匹配,不会把 rebuild 当成 build 误伤`() {
        val pats = listOf("build")
        assertTrue(matchesExclude("build", "src/build", pats))
        assertFalse("子串匹配会误伤,这里必须是全名", matchesExclude("rebuild.log", "src/rebuild.log", pats))
    }

    @Test
    fun `含斜杠的规则匹配相对路径,不含的匹配文件名`() {
        assertTrue(matchesExclude("a.o", "build/a.o", listOf("build/*")))
        assertFalse(matchesExclude("a.o", "src/a.o", listOf("build/*")))
        assertTrue(matchesExclude("a.tmp", "deep/nested/a.tmp", listOf("*.tmp")))
    }

    // ---- 配对 ----

    @Test
    fun `忽略大小写配对,且同名多项一个都不丢`() {
        val l = listOf(file("a", "/README.md"), file("a", "/Readme.md"))
        val r = listOf(file("b", "/readme.md"))
        val pairs = pairEntries(l, r, ignoreCase = true)
        assertEquals(2, pairs.size)
        assertEquals("第一个配上", "/readme.md", pairs[0].second?.path)
        assertNull("第二个没得配,成孤儿而不是被吞掉", pairs[1].second)
    }

    @Test
    fun `目录与文件同名不互相配对`() {
        val pairs = pairEntries(
            listOf(XFile("a", "/x", isDir = true)),
            listOf(file("b", "/x")),
            ignoreCase = true,
        )
        assertEquals(2, pairs.size)
        assertTrue(pairs.all { it.first == null || it.second == null })
    }

    // ---- 扫描 ----

    @Test
    fun `四种状态与目录汇总`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("same.txt", "diff.txt", "only-l.txt", "sub"), "/sub" to listOf("k.txt")),
            files = mapOf("/same.txt" to "aaa", "/diff.txt" to "aaa", "/only-l.txt" to "x", "/sub/k.txt" to "aaa"),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("same.txt", "diff.txt", "only-r.txt", "sub"), "/sub" to listOf("k.txt")),
            files = mapOf("/same.txt" to "aaa", "/diff.txt" to "aaaaa", "/only-r.txt" to "y", "/sub/k.txt" to "aaa"),
        )

        val events = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList()

        val root = events.filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }
        fun stateOf(name: String) = root.rows.first { it.name == name }.state
        assertEquals(PairState.SAME, stateOf("same.txt"))
        assertEquals("大小不同即差异", PairState.DIFF, stateOf("diff.txt"))
        assertEquals(PairState.LEFT_ONLY, stateOf("only-l.txt"))
        assertEquals(PairState.RIGHT_ONLY, stateOf("only-r.txt"))

        val done = events.filterIsInstance<CompareEvent.DirDone>().associate { it.dirKey to it.state }
        assertEquals("子树全同的目录汇总为相同", PairState.SAME, done["sub"])
        assertEquals("根下有差异,汇总为差异", PairState.DIFF, done[""])
    }

    @Test
    fun `被排除的目录不会被下探`() = runTest {
        val left = fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("keep", "build"), "/keep" to listOf(), "/build" to listOf("a.o")),
            files = mapOf("/build/a.o" to "x"),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("keep", "build"), "/keep" to listOf(), "/build" to listOf("a.o")),
            files = mapOf("/build/a.o" to "x"),
        )

        val events = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(excludes = listOf("build")),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList()

        assertFalse("排除的目录必须在扫描时剪枝,不能扫完再过滤", left.listed.contains("/build"))
        assertTrue(left.listed.contains("/keep"))
        val root = events.filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }
        assertTrue("排除项不出现在结果里", root.rows.none { it.name == "build" })
    }

    @Test
    fun `被排除的项会上报是哪条规则挡的`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("keep.txt", "a.tmp", "build"), "/build" to listOf()),
            files = mapOf("/keep.txt" to "x", "/a.tmp" to "y"),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("keep.txt", "a.tmp", "build"), "/build" to listOf()),
            files = mapOf("/keep.txt" to "x", "/a.tmp" to "y"),
        )
        val ex = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(excludes = listOf("*.tmp", "build")),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Excluded>()

        assertEquals(2, ex.size)
        // 删掉某条规则时要靠这个精确定位"当初被它挡掉的项",不必整树重扫
        assertEquals("*.tmp", ex.first { it.name == "a.tmp" }.rule)
        assertEquals("build", ex.first { it.name == "build" }.rule)
        assertTrue("所在目录也要带上", ex.all { it.dirKey == "" })
    }

    @Test
    fun `一侧为空时整棵树都判成只在另一侧`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("deep"), "/deep" to listOf("a.txt")),
            files = mapOf("/deep/a.txt" to "x"),
        )
        // 右侧根本没传(恢复"只在一侧"的目录时就是这种调用)
        val rows = scanCompare(
            XFile("cl", "/", isDir = true),
            null,
            CompareOptions(),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Children>()

        assertEquals(PairState.LEFT_ONLY, rows.first { it.dirKey == "" }.rows.single().state)
        assertEquals(PairState.LEFT_ONLY, rows.first { it.dirKey == "deep" }.rows.single().state)
    }

    @Test
    fun `单侧独有的目录仍然递归列举,子孙全部标同一侧`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("gone"), "/gone" to listOf("deep"), "/gone/deep" to listOf("a.txt")),
            files = mapOf("/gone/deep/a.txt" to "x"),
        )
        fakeFs("cr", dirs = mapOf("/" to listOf()))

        val events = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList()

        val deep = events.filterIsInstance<CompareEvent.Children>().first { it.dirKey == "gone/deep" }
        assertEquals(PairState.LEFT_ONLY, deep.rows.single().state)
        val done = events.filterIsInstance<CompareEvent.DirDone>().associate { it.dirKey to it.state }
        assertEquals("只在左的目录不因子树内容改写自身状态", PairState.LEFT_ONLY, done["gone"])
    }

    @Test
    fun `大小相同时间不同,开内容对比判相同,关掉则判差异`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 1_000_000L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 9_000_000L),
        )
        val l = XFile("cl", "/", isDir = true)
        val r = XFile("cr", "/", isDir = true)

        suspend fun stateWith(o: CompareOptions) = scanCompare(l, r, o, UnconfinedTestDispatcher(testScheduler))
            .toList().filterIsInstance<CompareEvent.Children>()
            .first { it.dirKey == "" }.rows.single().state

        // 假来源的 scheme 不是 file,走的是"网络"那档上限。
        assertEquals(PairState.DIFF, stateWith(CompareOptions()))
        assertEquals(PairState.SAME, stateWith(CompareOptions(contentLimitNetwork = 1024)))
    }

    @Test
    fun `内容不同但大小时间都一样,开内容对比才抓得到`() = runTest {
        fakeFs("cl", dirs = mapOf("/" to listOf("a.txt")), files = mapOf("/a.txt" to "hello"))
        fakeFs("cr", dirs = mapOf("/" to listOf("a.txt")), files = mapOf("/a.txt" to "world"))
        val l = XFile("cl", "/", isDir = true)
        val r = XFile("cr", "/", isDir = true)

        suspend fun stateWith(o: CompareOptions) = scanCompare(l, r, o, UnconfinedTestDispatcher(testScheduler))
            .toList().filterIsInstance<CompareEvent.Children>()
            .first { it.dirKey == "" }.rows.single().state

        assertEquals("只看大小时间会漏判", PairState.SAME, stateWith(CompareOptions()))
        // ★ 还要关掉"仅在时间不同时读内容"那道闸门:这一对时间也一样,开着就直接放过了
        assertEquals(
            PairState.DIFF,
            stateWith(CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = false)),
        )
    }

    @Test
    fun `仅在时间不同时读内容,大小时间都同就不读`() = runTest {
        val left = fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 1_000_000L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "world"), // 内容不同,但大小与时间都一样
            times = mapOf("/a.txt" to 1_000_000L),
        )
        val l = XFile("cl", "/", isDir = true)
        val r = XFile("cr", "/", isDir = true)

        suspend fun stateWith(o: CompareOptions) = scanCompare(l, r, o, UnconfinedTestDispatcher(testScheduler))
            .toList().filterIsInstance<CompareEvent.Children>()
            .first { it.dirKey == "" }.rows.single().state

        assertEquals(
            "开着这道闸门就不该去读,于是判成相同",
            PairState.SAME,
            stateWith(CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = true)),
        )
        assertFalse("而且真的没读过文件", left.opened.contains("/a.txt"))

        assertEquals(
            "关掉后照读不误,内容不同就是不同",
            PairState.DIFF,
            stateWith(CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = false)),
        )
        assertTrue(left.opened.contains("/a.txt"))
    }

    @Test
    fun `时间不同则照读,内容一样仍判相同`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 1_000_000L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("a.txt")),
            files = mapOf("/a.txt" to "hello"),
            times = mapOf("/a.txt" to 9_000_000L),
        )
        val state = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(contentLimitNetwork = 1024, contentOnlyIfTimeDiffers = true),
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }.rows.single().state
        assertEquals(PairState.SAME, state)
    }

    @Test
    fun `超过内容对比上限的一对退回按时间判`() = runTest {
        fakeFs(
            "cl",
            dirs = mapOf("/" to listOf("big.bin")),
            files = mapOf("/big.bin" to "0123456789"),
            times = mapOf("/big.bin" to 0L),
        )
        fakeFs(
            "cr",
            dirs = mapOf("/" to listOf("big.bin")),
            files = mapOf("/big.bin" to "abcdefghij"),
            times = mapOf("/big.bin" to 0L),
        )
        val rows = scanCompare(
            XFile("cl", "/", isDir = true),
            XFile("cr", "/", isDir = true),
            CompareOptions(contentLimitNetwork = 5), // 文件 10 字节,超上限
            io = UnconfinedTestDispatcher(testScheduler),
        ).toList().filterIsInstance<CompareEvent.Children>().first { it.dirKey == "" }.rows
        assertEquals("内容其实不同,但超限就只按大小时间判", PairState.SAME, rows.single().state)
    }

    private fun file(scheme: String, path: String) = XFile(scheme, path, isDir = false, size = 1)
}
