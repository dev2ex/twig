package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 目录属性卡片的**递归统计**([scanDirStat] + `PaneViewModel` 的 dirScan 生命周期)。
 *
 * 两件事必须罩住:数字对(递归到底、目录不计入大小),以及**卡片关掉/所在目录折叠
 * 时扫描真的停**——后者是这个功能的主要风险,大目录树(尤其网络来源)扫起来很贵,
 * 卡片都看不见了还在跑就是白烧流量和电。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirScanTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun local(f: File) = XFile("file", f.path, isDir = f.isDirectory, size = f.length())

    /** 深 3 层:根 2 文件 + 子目录 1 文件 + 孙目录 1 文件,共 4 文件 / 2 目录。 */
    private fun tree(name: String): File {
        val root = File(ext, name).apply { mkdirs() }
        File(root, "a.txt").writeText("12345") // 5B
        File(root, "b.txt").writeText("123") // 3B
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "c.txt").writeText("1234567890") // 10B
        val deep = File(sub, "deep").apply { mkdirs() }
        File(deep, "d.bin").writeText("1234567") // 7B
        return root
    }

    // ---- 扫描本身 ----

    @Test
    fun `递归统计到底层-文件数目录数与总字节都对`() = runTest(dispatcher) {
        val root = tree("统计")

        val stats = scanDirStat(local(root), dispatcher).toList()

        val last = stats.last()
        assertEquals(4, last.files)
        assertEquals(2, last.dirs)
        // 目录项自身不计入大小,只累加文件字节
        assertEquals(25L, last.bytes)
    }

    @Test
    fun `空目录统计为全零`() = runTest(dispatcher) {
        val root = File(ext, "空的").apply { mkdirs() }

        assertEquals(DirStat(), scanDirStat(local(root), dispatcher).toList().last())
    }

    // ---- 卡片生命周期 ----

    private fun infoNode(dir: File) = vm.state.value.rows
        .filterIsInstance<PaneViewModel.InfoNode>()
        .firstOrNull { it.file.path == dir.path }

    @Test
    fun `打开目录属性卡片即出递归统计-扫完不再转圈`() = runTest(dispatcher) {
        val root = tree("卡片")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggleInfo(local(root))
        advanceUntilIdle()

        val n = infoNode(root)
        assertNotNull("属性卡片该挂在目录行下方", n)
        assertEquals(4, n!!.dirStat?.files)
        assertEquals(2, n.dirStat?.dirs)
        assertEquals(25L, n.dirStat?.bytes)
        assertFalse("扫完了就不该再转圈", n.scanning)
    }

    /**
     * ★ 回归:**转圈要真的看得见**。2026-08-04 第一版用户反馈"没看到转圈",两个原因:
     * 本地小目录几十毫秒扫完(这条由 [DIR_SCAN_MIN_SPIN_MS] 兜底,本用例验的就是它),
     * 以及转圈曾放在 tab 条那一行被挤出面板右边界(见 `视图上转圈落在卡片可视范围内`)。
     */
    @Test
    fun `扫得再快转圈也留够最短时长`() = runTest(dispatcher) {
        val root = tree("最短时长")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggleInfo(local(root))
        advanceTimeBy(DIR_SCAN_MIN_SPIN_MS / 2) // 树很小,这时早扫完了

        val mid = infoNode(root)!!
        assertEquals("数字该已经是最终值", 4, mid.dirStat?.files)
        assertTrue("扫完了也得再转一会儿,否则用户根本看不见", mid.scanning)

        advanceUntilIdle()
        assertFalse("过了最短时长就停", infoNode(root)!!.scanning)
    }

    /**
     * ★ 关掉卡片(✕ / 再选一次"属性")扫描立刻停。
     *
     * 故意**不** `advanceUntilIdle()` 就关——这样关的时候扫描任务还没跑完(仍 active),
     * 取消漏了的话 [PaneViewModel.activeDirScans] 就不是 0,断言才有意义。
     */
    @Test
    fun `关闭卡片即取消扫描`() = runTest(dispatcher) {
        val root = tree("关卡片")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggleInfo(local(root)) // rebuild 是同步的,卡片行当场就有
        assertNotNull(infoNode(root))
        assertEquals(1, vm.activeDirScans())

        vm.toggleInfo(local(root)) // 再点一次 = 关闭
        assertEquals("卡片关了就不该还有扫描在跑", 0, vm.activeDirScans())

        advanceUntilIdle()
        assertTrue("卡片行该消失", infoNode(root) == null)
    }

    /**
     * ★ **所在目录折叠后扫描要停**。卡片行不再被建出来(用户也看不见),
     * 这时还留着任务就是在后台白扫一棵大树——[PaneViewModel.rebuild] 的清理负责取消它。
     */
    @Test
    fun `所在目录折叠后卡片消失且扫描被取消`() = runTest(dispatcher) {
        val root = tree("折叠")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        val extNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == ext.path }
        vm.toggleInfo(local(root))
        assertNotNull(infoNode(root))
        assertEquals(1, vm.activeDirScans())

        vm.toggle(extNode) // 折叠外部存储 → 目录行连同卡片一起从树上消失
        assertEquals("卡片看不见了就不该还在扫", 0, vm.activeDirScans())

        advanceUntilIdle()
        assertTrue("卡片行该随所在目录折叠而消失", infoNode(root) == null)
        // 再展开回来也不该"自动续上"——扫描随卡片一起丢弃,要重新打开属性才有
        vm.toggle(extNode.copy(expanded = false))
        advanceUntilIdle()
        assertTrue(infoNode(root) == null)
    }
}
