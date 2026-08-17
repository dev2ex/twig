package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.core.FsRegistry
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.ZipFileSystem
import com.twig.fs.archive.ZipWriter
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 展开加密压缩包这条控制流:要密码 → UI 弹框 → 校验 → 展开 → (可选)记住密码。
 *
 * `fs-archive` 那边的测试只管"给了密码能不能解出字节",管不到这里:密码是从哪儿来的、
 * 密码错了界面怎么知道、保存过的密码下次还认不认——全是 VM 的控制流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelArchivePasswordTest {

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
        archive = File(ext, "secret.zip")
        archive.outputStream().use { os ->
            ZipWriter(os, PASSWORD).use { zw ->
                zw.putNextEntry("inside.txt", System.currentTimeMillis(), sizeHint = 5)
                zw.write("boo!".toByteArray())
                zw.closeEntry()
            }
        }
        vm = PaneViewModel(app).apply { io = dispatcher }
        // 上一个用例可能已经把密码留在了单例 FileSystem 上
        zipFs().setPassword(archive.path, null)
        Prefs.setArchivePassword(app, "file:${archive.path}", null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        archive.delete()
    }

    private fun zipFs() = FsRegistry.of(ZipFileSystem.SCHEME) as ArchiveFileSystem

    /** 展开外部存储,找到那个包对应的行。 */
    private fun archiveNode(): PaneViewModel.FileNode =
        vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == archive.path }

    private fun innerNames() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        .map { it.file.name }

    @Test
    fun `展开加密包会请求密码,而不是弹一句英文报错`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggle(archiveNode())
        advanceUntilIdle()

        val st = vm.state.value
        assertEquals(archive.path, st.passwordFor?.path)
        assertNull("第一次问密码不该同时报错", st.error)
        assertFalse("没解开就不该露出包里的东西", innerNames().contains("inside.txt"))
    }

    @Test
    fun `密码不对时告诉界面重来,对了就展开`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        val node = archiveNode()
        vm.toggle(node)
        advanceUntilIdle()

        var result: Boolean? = null
        vm.unlockArchive(node.file, "not-it", save = false) { result = it }
        advanceUntilIdle()
        assertEquals(false, result)
        assertFalse(innerNames().contains("inside.txt"))

        vm.unlockArchive(node.file, PASSWORD, save = false) { result = it }
        advanceUntilIdle()
        assertEquals(true, result)
        assertTrue("解开后包内条目应该就地展开", innerNames().contains("inside.txt"))
    }

    @Test
    fun `勾了保存的密码下次直接用,不再弹框`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        val node = archiveNode()
        vm.toggle(node)
        advanceUntilIdle()
        vm.unlockArchive(node.file, PASSWORD, save = true) {}
        advanceUntilIdle()
        assertNotNull(Prefs.archivePassword(app, "file:${archive.path}"))

        // 换一个 VM,并且把进程内已解锁的状态清掉——只剩"保存过的密码"这一条线索
        zipFs().setPassword(archive.path, null)
        val vm2 = PaneViewModel(app).apply { io = dispatcher }
        vm2.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        val n2 = vm2.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == archive.path }
        vm2.toggle(n2)
        advanceUntilIdle()

        assertNull("有保存的密码就不该再问", vm2.state.value.passwordFor)
        assertTrue(
            vm2.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .any { it.file.name == "inside.txt" },
        )
    }

    @Test
    fun `保存的密码失效时清掉并重新问`() = runTest(dispatcher) {
        Prefs.setArchivePassword(app, "file:${archive.path}", "stale")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.toggle(archiveNode())
        advanceUntilIdle()

        val st = vm.state.value
        assertEquals(archive.path, st.passwordFor?.path)
        assertNotNull("这次要提示密码错", st.error)
        assertNull("不能一直拿失效的密码去试", Prefs.archivePassword(app, "file:${archive.path}"))
    }

    private companion object {
        const val PASSWORD = "s3cret"
    }
}
