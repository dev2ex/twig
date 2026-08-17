package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 树上**不能出现两行同一个 key**。
 *
 * 行的 key 是 DiffUtil 的身份依据,重复了就会渲染错乱——症状是"同一个压缩包,
 * 一处展开好好的,另一处展开却是空的"。最容易撞上的组合:别的 App「用 Twig 打开」
 * 一个压缩包(挂到树顶的 [PaneViewModel.mountExternal]),而这个包**原本就在**
 * 某个已展开的目录里,于是同一个 key 在树顶和原位置各出现一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelDuplicateRowTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private lateinit var archive: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        archive = File(ext, "dup.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("a.txt"))
            z.write("aaa".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("b.txt"))
            z.write("bbb".toByteArray())
            z.closeEntry()
        }
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        archive.delete()
    }

    private fun keys() = vm.state.value.rows.map { it.key }

    private fun dupes() = keys().groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun archiveX() = XFile(
        "file", archive.absolutePath, isDir = false,
        size = archive.length(), lastModified = archive.lastModified(),
    )

    @Test
    fun `外部打开的包与它在树里的原位置不会撞 key`() = runTest(dispatcher) {
        // 外部存储已展开 —— 那个 zip 本来就在这棵树里看得见
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        assertTrue("前提:这个包本来就在树里", keys().any { it.contains(archive.absolutePath) })

        // 别的 App「用 Twig 打开」同一个包:挂到树顶
        vm.mountExternal(archiveX())
        advanceUntilIdle()

        assertEquals("同一个 key 出现了两行,DiffUtil 会认错行", emptySet<String>(), dupes())
    }

    /**
     * ★ 真实现场:外部打开挂到树顶之后,用户**又把那个包原本所在的目录展开**
     * (手风琴在 mountExternal 时会把它收起来,所以上一个用例撞不到)。
     * 这时同一个 key 的行在树顶和原位置各来一次。
     */
    @Test
    fun `外部打开后再展开包所在的目录,仍然不能撞 key`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.mountExternal(archiveX())
        advanceUntilIdle()

        // 把外部存储重新展开(手风琴刚把它收起来了)
        val storage = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == ext.path }
        if (!storage.expanded) {
            vm.toggle(storage)
            advanceUntilIdle()
        }

        val zipRows = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.path == archive.absolutePath }
        assertTrue("前提:树顶和原位置应该各有一行", zipRows.size >= 2)
        assertEquals("两行撞了 key —— DiffUtil 会认错行,表现就是有一处展开是空的", emptySet<String>(), dupes())
    }

    /**
     * 两个面板 = 两个 ViewModel,但 `FsRegistry` 里的 `ZipFileSystem` 是**同一个实例**
     * (挂载登记的宿主表、各种缓存都在那上面)。一侧打开过之后另一侧再打开同一个包,
     * 不能因为共享状态被前一次改过就列成空的。
     */
    @Test
    fun `两侧面板先后展开同一个包,两边都有内容`() = runTest(dispatcher) {
        val other = PaneViewModel(app).apply { io = dispatcher }

        vm.bootstrap(listOf("file\t${ext.path}"))
        other.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        fun PaneViewModel.zipNode() = state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == archive.absolutePath }
        fun PaneViewModel.innerNames() = state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.scheme == "zip" }.map { it.file.name }

        vm.toggle(vm.zipNode())
        advanceUntilIdle()
        assertEquals(listOf("a.txt", "b.txt"), vm.innerNames())

        other.toggle(other.zipNode())
        advanceUntilIdle()
        assertEquals("另一侧展开同一个包却是空的", listOf("a.txt", "b.txt"), other.innerNames())

        // 反过来再刷一次:先展开的那侧刷新后也不能变空
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a.txt", "b.txt"), vm.innerNames())
    }

    @Test
    fun `外部打开的包照样能展开出内容`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.mountExternal(archiveX())
        advanceUntilIdle()

        val names = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.scheme == "zip" }.map { it.file.name }
        assertTrue("包内条目一个都没出来:$names", names.containsAll(listOf("a.txt", "b.txt")))
    }
}
