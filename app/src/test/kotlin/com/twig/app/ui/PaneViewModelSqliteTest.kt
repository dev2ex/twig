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
            assertNull(vm.sqliteSearchTarget())
            vm.toggle(database())
            assertNull(vm.sqliteSearchTarget()) // Opening is still pending.
            advanceUntilIdle()
            assertEquals(database().key, vm.sqliteSearchTarget()?.key)
            fun table() = vm.state.value.rows.filterIsInstance<SqliteTableNode>()
                .single { it.state.table.name == "entries" && it.state.query == null }
            assertEquals(1L, table().state.count)
            vm.toggle(table())
            advanceUntilIdle()
            assertNull(vm.currentSelection())
            assertEquals(database().key, vm.sqliteSearchTarget()?.key)
            assertTrue(vm.state.value.rows.any { it is SqliteDataNode })
            assertFalse(vm.expandedDescriptors().any { it.contains(file.name) })
            SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("INSERT INTO entries VALUES (2)") }
            vm.refresh()
            advanceUntilIdle()
            assertEquals(2L, table().state.count)
            assertTrue(table().state.expanded)
            vm.startSqliteSearch(database(), "2", "entries")
            advanceUntilIdle()
            val search = vm.state.value.rows.filterIsInstance<SqliteSearchNode>().single()
            assertEquals(database().key, vm.sqliteSearchTarget()?.key)
            assertEquals(1L, search.matches)
            assertEquals("entries", search.table)
            assertTrue(vm.state.value.rows.filterIsInstance<SqliteTableNode>().none { it.state.query != null })
            val data = vm.state.value.rows.filterIsInstance<SqliteDataNode>().single()
            assertEquals(database().key, vm.sqliteSearchTarget()?.key)
            assertNull(vm.currentSelection())
            assertEquals("2", vm.sqliteCell(data, 0, 0)!!.text)
            assertEquals(1L, data.state.count)
            assertTrue(table().state.expanded)
            vm.toggle(search)
            advanceUntilIdle()
            assertTrue(vm.state.value.rows.none { it is SqliteSearchNode })
            vm.toggle(database())
            advanceUntilIdle()
            assertFalse(vm.state.value.rows.any { it is SqliteNode })
            assertEquals(database().key, vm.sqliteSearchTarget()?.key)
            // A previously valid database must lose confirmation after a failed reopen.
            file.writeText("not a SQLite database")
            vm.toggle(database())
            assertNull(vm.sqliteSearchTarget())
            advanceUntilIdle()
            assertNull(vm.sqliteSearchTarget())
            vm.toggle(database())
            advanceUntilIdle()
            assertNull(vm.sqliteSearchTarget())
            file.delete()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY)")
                db.execSQL("INSERT INTO entries VALUES (1)")
            }
            vm.startSqliteSearch(database(), "1")
            advanceUntilIdle()
            assertEquals(1L, vm.state.value.rows.filterIsInstance<SqliteSearchNode>().single().matches)
            vm.collapseAll()
            advanceUntilIdle()
            assertTrue(vm.state.value.rows.none { it is SqliteSearchNode || it is SqliteNode })
            assertNull(vm.sqliteSearchTarget())
        } finally {
            store.clear()
            advanceUntilIdle()
            file.delete()
            Dispatchers.resetMain()
        }
    }
}
