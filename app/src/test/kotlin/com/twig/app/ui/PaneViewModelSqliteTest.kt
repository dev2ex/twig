package com.twig.app.ui

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PaneViewModelSqliteTest {
    @Test fun `database expands in place and table contents never become file selections`() = runTest {
        val app: Application = ApplicationProvider.getApplicationContext()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        val root = Environment.getExternalStorageDirectory()
        val file = File(root, "twig-${UUID.randomUUID()}.db")
        val vm = PaneViewModel(app).apply { io = dispatcher }
        store.put("test", vm)
        try {
            FsRegistry.register(LocalFileSystem())
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY)")
                db.execSQL("INSERT INTO entries VALUES (1)")
            }
            vm.bootstrap(listOf("file\t${root.path}"))
            advanceUntilIdle()
            fun database() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.path == file.path }
            assertTrue(database().expandable)
            vm.toggle(database())
            advanceUntilIdle()
            fun table() = vm.state.value.rows.filterIsInstance<SqliteTableNode>().single { it.state.table.name == "entries" }
            assertEquals(1L, table().state.count)
            vm.toggle(table())
            advanceUntilIdle()
            assertNull(vm.currentSelection())
            assertTrue(vm.state.value.rows.any { it is SqliteDataNode })
            assertFalse(vm.expandedDescriptors().any { it.contains(file.name) })
            SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("INSERT INTO entries VALUES (2)") }
            vm.refresh()
            advanceUntilIdle()
            assertEquals(2L, table().state.count)
            assertTrue(table().state.expanded)
            vm.toggle(database())
            advanceUntilIdle()
            assertFalse(vm.state.value.rows.any { it is SqliteNode })
        } finally {
            store.clear()
            advanceUntilIdle()
            file.delete()
            Dispatchers.resetMain()
        }
    }
}
