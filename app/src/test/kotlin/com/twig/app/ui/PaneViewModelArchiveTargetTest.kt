package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.SevenZFileSystem
import com.twig.fs.archive.ZipFileSystem
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「展开一个 zip,它就成为对侧复制的目标目录」这条交互。
 *
 * 底层早就支持往包里写(`ZipFileSystem.openOutput` → 追加),缺的一直是**当前目录
 * 落不到包根**:树上那一行的 XFile 是宿主文件(`isDir=false`),而 [PaneViewModel.toggleFile]
 * 只在 `isDir` 时才更新 `currentDir` —— 于是绿框停在 zip 那一行、粘贴目标却还是外面的
 * 目录,展开了包也没法往里复制。只有再点包内的**子目录**才行(包根、以及根本没有子目录的
 * 包,就完全没有入口)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelArchiveTargetTest {

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
        archive = File(ext, "box.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("inside.txt"))
            z.write("hi".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("sub/deep.txt"))
            z.write("deep".toByteArray())
            z.closeEntry()
        }
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        archive.delete()
    }

    private fun nodeFor(path: String) = vm.state.value.rows
        .filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == path }

    private fun openTree() {
        vm.bootstrap(listOf("file\t${ext.path}"))
    }

    @Test
    fun `展开 zip 后当前目录就是包根,可以当复制目标`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        val outside = vm.state.value.currentDir

        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        val dir = vm.state.value.currentDir
        assertEquals(ZipFileSystem.SCHEME, dir?.scheme)
        assertEquals("${archive.path}${ArchiveFileSystem.SEP}", dir?.path)
        assertTrue("包根得是个能写进去的目录,否则复制按钮直接拦下", dir!!.isWritableDir())
        assertTrue("展开前后不该是同一个目录", outside?.path != dir.path)
    }

    @Test
    fun `收起 zip 后目标退回它所在的目录,不停在收起来的包里`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        val dir = vm.state.value.currentDir
        assertEquals("file", dir?.scheme)
        assertEquals(ext.path, dir?.path)
    }

    @Test
    fun `第二次展开吃的是缓存,当前目录照样落到包根`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path)) // 收起
        advanceUntilIdle()

        vm.toggle(nodeFor(archive.path)) // 再展开:走 children 缓存那条分支
        advanceUntilIdle()

        assertEquals(ZipFileSystem.SCHEME, vm.state.value.currentDir?.scheme)
        assertEquals("${archive.path}${ArchiveFileSystem.SEP}", vm.state.value.currentDir?.path)
    }

    @Test
    fun `包内子目录仍然照常能选中`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        val sub = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.scheme == ZipFileSystem.SCHEME && it.file.isDir }
        vm.toggle(sub)
        advanceUntilIdle()

        assertEquals("${archive.path}${ArchiveFileSystem.SEP}sub", vm.state.value.currentDir?.path)
    }

    @Test
    fun `只读格式的包根不会被当成可写目标`() = runTest(dispatcher) {
        // 7z 整个来源只读(FileSystem.writable() == false),包根即使成了当前目录也复制不进去
        val sevenZ = FsRegistry.of(SevenZFileSystem.SCHEME)
        val fake = XFile(SevenZFileSystem.SCHEME, "/x.7z${ArchiveFileSystem.SEP}", isDir = true, canWrite = true)
        assertFalse(sevenZ.writable())
        assertFalse(fake.isWritableDir())
    }
}
