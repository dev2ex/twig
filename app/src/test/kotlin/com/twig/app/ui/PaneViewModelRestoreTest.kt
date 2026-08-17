package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Favorite
import com.twig.app.FavoritesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `PaneViewModel` 的**恢复上次位置 / 展开**链路。
 *
 * 这条链路一直没有测试,而它出过真实的 bug:2026-08-04「从收藏展开的位置,重开后
 * 没有绿框」——`currentDescriptor()` 只存得下"哪个目录",丢了"经由收藏到达"这件事,
 * 恢复时 key 指向一个树上根本不存在的行。当时是靠真机走查发现的,而那时
 * `TreeKeys`/`SortRules` 的纯函数测试已经在跑了:**它们覆盖不到控制流**。
 * 这个类补的就是那一块。
 *
 * 跑得起来靠两样(见 app/build.gradle.kts 里的说明):
 * - **Robolectric**:`PaneViewModel` 是 `AndroidViewModel`,构造要 Application,
 *   里面还有 20 多处 `getApplication()` 和一堆 SharedPreferences;
 * - **coroutines-test**:`viewModelScope` 跑在 Main 上(单测里没有),而且要能
 *   确定性地把排队的协程推完再断言。★ `PaneViewModel.io` 也必须换成同一个测试
 *   调度器,否则那 14 处 `withContext(io)` 会跑到真实线程池上,`advanceUntilIdle()`
 *   管不着,测试就成了碰运气。
 *
 * 用真实的 `LocalFileSystem` + Robolectric 提供的临时外部存储,不造假文件系统——
 * 恢复链路本来就该连着真实的列目录一起验。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelRestoreTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        // TwigApp.onCreate 已经把 LocalFileSystem 等基础来源注册好了(Robolectric 会
        // 按 manifest 里的 android:name 真的实例化 Application)
        ext = Environment.getExternalStorageDirectory()
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun dir(name: String): File = File(ext, name).apply { mkdirs() }

    private fun rowKeys() = vm.state.value.rows.map { it.key }

    private fun expandedFileNames() = vm.state.value.rows
        .filterIsInstance<PaneViewModel.FileNode>()
        .filter { !it.file.isDir }
        .map { it.file.name }

    // ---- 展开本地目录 ----

    @Test
    fun `恢复上次展开的本地目录并列出其中的文件`() = runTest(dispatcher) {
        val photos = dir("照片")
        File(photos, "a.jpg").writeText("x")
        File(photos, "b.jpg").writeText("y")

        vm.bootstrap(listOf("file\t${ext.path}", "file\t${photos.path}"))
        advanceUntilIdle()

        assertTrue("照片目录该在树上展开", "f:file:${photos.path}" in rowKeys())
        assertTrue(expandedFileNames().containsAll(listOf("a.jpg", "b.jpg")))
    }

    @Test
    fun `恢复当前目录时高亮框指向它自己`() = runTest(dispatcher) {
        val docs = dir("文档")
        vm.bootstrap(listOf("file\t${ext.path}", "file\t${docs.path}"), currentDesc = "file\t${docs.path}")
        advanceUntilIdle()

        assertEquals("f:file:${docs.path}", vm.state.value.currentKey)
        assertEquals(docs.path, vm.state.value.currentDir?.path)
    }

    /**
     * ★ 回归:**上次停在收藏的根目录上,恢复后绿框要框住收藏那一行**。
     *
     * 收藏(和服务器)的根目录在树上没有自己的 FileNode —— 子项直接挂在 `fav:` 行
     * 下面。而存盘只存得下 currentDir 这个目录本身,所以恢复时如果照直用
     * `fileKey(dir)`,得到的 key 在树上找不到对应行,绿框就消失了。
     */
    @Test
    fun `从收藏恢复时高亮框落在收藏行而不是一个不存在的目录行`() = runTest(dispatcher) {
        val backup = dir("备份")
        File(backup, "x.txt").writeText("x")
        val fav = Favorite(label = "备份", kind = "local", path = backup.path)
        FavoritesStore.add(app, fav)

        vm.bootstrap(
            listOf("group\tfav", "fav\t${fav.id}"),
            currentDesc = "file\t${backup.path}",
        )
        advanceUntilIdle()

        assertEquals("fav:${fav.id}", vm.state.value.currentKey)
        assertTrue("收藏行本身要在树上", "fav:${fav.id}" in rowKeys())
        // currentDir 仍是那个真实目录(新建/粘贴的落点靠它)
        assertEquals(backup.path, vm.state.value.currentDir?.path)
    }

    /** 恢复期间 restoring 为真,结束后必须落下来——UI 靠它决定何时停止反复锚定滚动。 */
    @Test
    fun `恢复结束后 restoring 落回 false`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        assertFalse(vm.state.value.restoring)
    }

    /** 描述符对应的目录已经不存在(用户在别处删了)时,不能崩,其余照常恢复。 */
    @Test
    fun `失效的描述符被跳过而不影响其它项`() = runTest(dispatcher) {
        val alive = dir("还在")
        vm.bootstrap(listOf("file\t${ext.path}", "file\t${alive.path}", "file\t${ext.path}/早没了"))
        advanceUntilIdle()

        assertTrue("f:file:${alive.path}" in rowKeys())
    }

    // ---- 展开 / 折叠 ----

    @Test
    fun `展开目录后再折叠_子项从行里消失`() = runTest(dispatcher) {
        val music = dir("音乐")
        File(music, "s.mp3").writeText("m")

        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == music.path }
        vm.toggle(node)
        advanceUntilIdle()
        assertTrue("s.mp3" in expandedFileNames())

        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == music.path })
        advanceUntilIdle()
        assertFalse("s.mp3" in expandedFileNames())
    }

    /**
     * 手风琴:展开一个目录会把**不在它祖先链上**的其它分支折起来。
     * 这条走的是 `accordionExpand` → `TreeKeys.ancestorKeys`,是纯函数测试与
     * 真实树状态的接缝——纯函数那边测的是算法,这里测的是"接对了没有"。
     */
    @Test
    fun `展开一个目录会折叠同级的另一个目录`() = runTest(dispatcher) {
        val a = dir("甲").also { File(it, "1.txt").writeText("1") }
        val b = dir("乙").also { File(it, "2.txt").writeText("2") }

        vm.bootstrap(listOf("file\t${ext.path}", "file\t${a.path}"))
        advanceUntilIdle()
        assertTrue("1.txt" in expandedFileNames())

        val bNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == b.path }
        vm.toggle(bNode)
        advanceUntilIdle()

        assertTrue("2.txt" in expandedFileNames())
        assertFalse("展开乙之后,甲该被折起来", "1.txt" in expandedFileNames())
    }

    // ---- 描述符往返 ----

    /**
     * 存盘 → 恢复 → 再存盘,描述符要稳定。不稳定的话每次冷启动位置都会漂,
     * 而这种漂移在真机上极难察觉。
     */
    @Test
    fun `展开状态存盘再恢复后描述符不变`() = runTest(dispatcher) {
        val d = dir("往返")
        vm.bootstrap(listOf("file\t${ext.path}", "file\t${d.path}"), currentDesc = "file\t${d.path}")
        advanceUntilIdle()

        val saved = vm.expandedDescriptors().sorted()
        val savedCur = vm.currentDescriptor()

        val vm2 = PaneViewModel(app).apply { io = dispatcher }
        vm2.bootstrap(saved, savedCur)
        advanceUntilIdle()

        assertEquals(saved, vm2.expandedDescriptors().sorted())
        assertEquals(savedCur, vm2.currentDescriptor())
    }
}
