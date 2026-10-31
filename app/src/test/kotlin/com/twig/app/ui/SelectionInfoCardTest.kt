package com.twig.app.ui

import android.app.Application
import android.os.Environment
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionInfoCardTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After fun teardown() = Dispatchers.resetMain()

    private fun file(f: File) = XFile("file", f.path, f.isDirectory, f.length(), f.lastModified())
    private fun card() = vm.state.value.rows.filterIsInstance<PaneViewModel.InfoNode>().single { it.selection }

    @Test fun `aggregate card sits above the first selected row and uses the shared Basic tab without hashes`() = runTest(dispatcher) {
        val a = File(ext, "a.txt").apply { parentFile.mkdirs(); writeText("abc") }
        val b = File(ext, "b.txt").apply { writeText("12345") }
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.toggleInfo(file(a))
        advanceUntilIdle()
        vm.toggleSelectionInfo(listOf(file(b), file(a)))
        advanceUntilIdle()

        val rows = vm.state.value.rows
        val info = card()
        val index = rows.indexOf(info)
        val anchor = (rows[index + 1] as PaneViewModel.FileNode).file
        assertEquals("file", anchor.scheme)
        assertEquals(a.path, anchor.path)
        assertFalse(info.scanning)
        val fields = info.details!!.sections.single().rows.toMap()
        assertEquals("2", fields[app.getString(R.string.info_selected)])
        assertFalse(fields.containsKey(app.getString(R.string.info_name)))
        assertEquals(app.getString(R.string.info_contains_value, 0, 2), fields[app.getString(R.string.info_contains)])
        assertEquals(app.getString(R.string.info_size_value, "8 B", "8"), fields[app.getString(R.string.info_total_size)])
        assertEquals(2, rows.filterIsInstance<PaneViewModel.InfoNode>().map { it.key }.toSet().size)

        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(listOf(info))
        val holder = adapter.createViewHolder(FrameLayout(app), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val tabs = holder.itemView.findViewById<LinearLayout>(R.id.tab_bar)
        val titles = (0 until tabs.childCount).mapNotNull { (tabs.getChildAt(it) as? TextView)?.text?.toString() }
        assertTrue(titles.contains(app.getString(R.string.info_section_basic)))
        assertFalse(titles.contains(app.getString(R.string.info_tab_hash)))
        vm.toggle(info)
        assertEquals(1, vm.state.value.rows.filterIsInstance<PaneViewModel.InfoNode>().size)
    }

    @Test fun `closing or hiding the anchor cancels selection scans and reopening starts fresh`() = runTest(dispatcher) {
        val root = File(ext, "selection").apply { mkdirs() }
        val a = File(root, "a.txt").apply { writeText("abc") }
        val b = File(root, "b.txt").apply { writeText("12345") }
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == root.path })
        advanceUntilIdle()
        val selected = listOf(file(a), file(b))
        vm.toggleSelectionInfo(selected)
        assertEquals(1, vm.activeDirScans())
        vm.toggle(card())
        assertEquals(0, vm.activeDirScans())
        vm.toggleSelectionInfo(selected)
        assertEquals(1, vm.activeDirScans())
        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == root.path })
        assertEquals(0, vm.activeDirScans())
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.InfoNode>().any { it.selection })
        advanceUntilIdle()
    }
}
