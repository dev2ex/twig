package com.twig.app.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import kotlinx.coroutines.*
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
class SqliteTreeTest {
    @Test fun `SQL results share the accordion and use a stable cancellable execution lease`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        com.twig.core.FsRegistry.register(com.twig.fs.local.LocalFileSystem())
        val file = File(context.cacheDir, "query-${UUID.randomUUID()}.db")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tree = SqliteTree(context, scope, { dispatcher }, {})
        val source = XFile("file", file.path, false)
        fun header() = tree.rows("db", 2).filterIsInstance<SqliteQueryNode>().single()
        fun data() = tree.rows("db", 2).filterIsInstance<SqliteDataNode>().single()
        fun table(name: String) = tree.rows("db", 2).filterIsInstance<SqliteTableNode>().single { it.state.table.name == name }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, label TEXT, payload BLOB)")
                db.execSQL("CREATE TABLE query_result(label TEXT)")
                db.execSQL("INSERT INTO query_result VALUES ('ordinary table')")
                db.beginTransaction()
                try {
                    repeat(205) { db.execSQL("INSERT INTO entries VALUES (?,?,?)", arrayOf(it, "row$it", byteArrayOf(it.toByte(), 7))) }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            tree.open("db", source)
            advanceUntilIdle()
            tree.command(table("entries"), SqliteCommand.TOGGLE)
            advanceUntilIdle()
            val sql = "SELECT id, label, payload, random() AS nonce FROM entries ORDER BY id DESC"
            tree.execute("db", sql)
            assertTrue(header().running)
            advanceUntilIdle()
            val first = header()
            assertFalse(first.running)
            assertNull(first.error)
            assertEquals(205L, first.state!!.count)
            assertEquals(2, first.depth)
            assertEquals(3, data().depth)
            assertFalse(table("entries").state.expanded)
            assertTrue(tree.rows("db", 2).filterIsInstance<SqliteTableNode>().all { it.state.execution == null })
            val original = data()
            assertEquals(first.id, original.state.execution)
            assertEquals("204", tree.cell(original, 0, 0)!!.text)
            val nonce = tree.cell(original, 0, 3)!!.text
            val blob = tree.blobReader(original, 0, 2, 2)!!
            assertArrayEquals(byteArrayOf(204.toByte(), 7), blob(0, 2))
            val csv = tree.csvWriter(original, setOf("id", "nonce"))!!
            val output = java.io.ByteArrayOutputStream()
            csv(output) {}
            assertTrue(output.toString("UTF-8").startsWith("\uFEFF\"id\",\"nonce\"\r\n204,$nonce\r\n"))
            tree.sort(original, 0)
            assertNull(data().state.sort)
            tree.page("db", original.state.table.name, 100, execution = first.id)
            advanceUntilIdle()
            assertEquals(setOf(0, 100), data().state.pages.keys)
            assertEquals("4", tree.cell(data(), 200, 0)!!.text)
            tree.command(table("query_result"), SqliteCommand.TOGGLE)
            advanceUntilIdle()
            assertFalse(header().expanded)
            assertEquals("ordinary table", tree.cell(data(), 0, 0)!!.text)
            tree.toggleQuery(header())
            advanceUntilIdle()
            assertFalse(table("query_result").state.expanded)
            assertEquals(nonce, tree.cell(data(), 0, 3)!!.text)
            tree.execute("db", sql)
            advanceUntilIdle()
            assertNotEquals(first.id, header().id)
            assertNull(tree.cell(original, 0, 0))
            assertTrue(runCatching { blob(0, 2) }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { csv(java.io.ByteArrayOutputStream()) {} }.exceptionOrNull() is CancellationException)
            tree.execute("db", "SELECT FROM")
            advanceUntilIdle()
            assertNotNull(header().error)
            assertFalse(header().running)
            assertNull(header().state)
            assertTrue(tree.canSearch("db"))
            tree.execute("db", "SELECT 42 AS value")
            val pending = header()
            tree.closeQuery("db", pending.id)
            advanceUntilIdle()
            assertTrue(tree.rows("db", 2).none { it is SqliteQueryNode })
            tree.execute("db", "SELECT 42 AS value")
            advanceUntilIdle()
            tree.refresh("db", source)
            advanceUntilIdle()
            assertTrue(tree.rows("db", 2).none { it is SqliteQueryNode })
        } finally {
            scope.cancel()
            advanceUntilIdle()
            file.delete()
        }
        assertTrue(File(context.cacheDir, "sqlite-query").listFiles().orEmpty().isEmpty())
    }

    @Test fun `sorting clears pages resets vertical scroll and keeps search ordering independent`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.cacheDir, "sort-${UUID.randomUUID()}.db")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tree = SqliteTree(context, scope, { dispatcher }, {})
        fun table() = tree.rows("db", 0).filterIsInstance<SqliteTableNode>().single { it.state.table.name == "entries" }
        fun data() = tree.rows("db", 0).filterIsInstance<SqliteDataNode>().single()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, value INTEGER)")
                db.beginTransaction()
                try {
                    repeat(251) { db.execSQL("INSERT INTO entries VALUES (?,?)", arrayOf(it, 250 - it)) }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val source = XFile("file", file.path, false)
            tree.open("db", source)
            advanceUntilIdle()
            tree.command(table(), SqliteCommand.TOGGLE)
            advanceUntilIdle()
            tree.page("db", "entries", 100)
            advanceUntilIdle()
            data().state.viewport.x = 20f
            data().state.viewport.y = 300f
            data().state.viewport.selectedRow = 10
            tree.page("db", "entries", 200) // Pending old-order reads must not repopulate the new cache.
            tree.sort(data(), 1)
            advanceUntilIdle()
            assertEquals(setOf(0), data().state.pages.keys)
            assertEquals(20f, data().state.viewport.x, 0f)
            assertEquals(0f, data().state.viewport.y, 0f)
            assertEquals(-1, data().state.viewport.selectedRow)
            assertEquals("250", data().state.pages[0]!!.rows.first()[0].text)
            tree.sort(data(), 1)
            advanceUntilIdle()
            assertTrue(data().state.sort!!.descending)
            assertEquals("0", tree.cell(data(), 0, 0)!!.text)
            tree.search("db", "2%", "entries")
            advanceUntilIdle()
            assertNull(data().state.sort)
            tree.sort(data(), 0)
            tree.sort(data(), 0)
            advanceUntilIdle()
            assertTrue(data().state.sort!!.descending)
            assertEquals("250", tree.cell(data(), 0, 0)!!.text)
            assertEquals("value", table().state.sort!!.column)
            tree.closeSearch("db")
            tree.refresh("db", source)
            advanceUntilIdle()
            assertEquals("value", table().state.sort!!.column)
            assertTrue(table().state.sort!!.descending)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("DROP TABLE entries")
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY)")
            }
            tree.refresh("db", source)
            advanceUntilIdle()
            assertNull(table().state.sort)
        } finally {
            scope.cancel()
            advanceUntilIdle()
            file.delete()
        }
    }

    @Test fun `CSV writer captures the filtered lease and cannot read a replaced search`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.cacheDir, "csv-${UUID.randomUUID()}.db")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tree = SqliteTree(context, scope, { dispatcher }, {})
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, value TEXT)")
                db.execSQL("INSERT INTO entries VALUES (1, 'needle'), (2, 'other')")
            }
            tree.open("db", XFile("file", file.path, false))
            advanceUntilIdle()
            val table = tree.rows("db", 0).filterIsInstance<SqliteTableNode>().single { it.state.table.name == "entries" }
            tree.search("db", "needle", "entries")
            advanceUntilIdle()
            val result = tree.rows("db", 0).filterIsInstance<SqliteDataNode>().single()
            val writer = tree.csvWriter(result, setOf("id"))!!
            val output = java.io.ByteArrayOutputStream()
            writer(output) {}
            assertEquals("\uFEFF\"id\"\r\n1\r\n", output.toString("UTF-8"))
            assertTrue(runCatching {
                writer(object : java.io.OutputStream() {
                    override fun write(value: Int) { throw java.io.IOException("Destination unavailable") }
                }) {}
            }.exceptionOrNull() is java.io.IOException)
            val retry = java.io.ByteArrayOutputStream()
            writer(retry) {}
            assertEquals(output.toString("UTF-8"), retry.toString("UTF-8"))
            tree.search("db", "missing", "entries")
            advanceUntilIdle()
            assertTrue(runCatching { writer(java.io.ByteArrayOutputStream()) {} }.exceptionOrNull() is CancellationException)
            assertNull(tree.csvWriter(result, setOf("id")))
            val empty = table.copy(state = table.state.copy(query = "missing"))
            val emptyWriter = tree.csvWriter(empty, setOf("id"))!!
            val header = java.io.ByteArrayOutputStream()
            emptyWriter(header) {}
            assertEquals("\uFEFF\"id\"\r\n", header.toString("UTF-8"))
            tree.close("db")
            advanceUntilIdle()
            assertTrue(runCatching { emptyWriter(java.io.ByteArrayOutputStream()) {} }.exceptionOrNull() is CancellationException)
        } finally {
            scope.cancel()
            advanceUntilIdle()
            file.delete()
        }
    }

    @Test fun `search results isolate counts pages and details while sharing the table accordion`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.cacheDir, "search-${UUID.randomUUID()}.db")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tree = SqliteTree(context, scope, { dispatcher }, {})
        fun rows() = tree.rows("db", 2)
        fun summary() = rows().filterIsInstance<SqliteSearchNode>().single()
        fun result(name: String) = rows().filterIsInstance<SqliteTableNode>()
            .single { it.state.table.name == name && it.state.query != null }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE notes(value TEXT)")
                db.execSQL("INSERT INTO notes VALUES ('needle note')")
                db.beginTransaction()
                try {
                    repeat(305) { db.execSQL("INSERT INTO users VALUES (?,?)",
                        arrayOf(it, if (it % 2 == 0) "needle$it" else "other$it")) }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            tree.open("db", XFile("file", file.path, false))
            tree.search("db", "needle") // Searching before the database finishes opening is queued.
            advanceUntilIdle()
            assertEquals(154L, summary().matches)
            assertFalse(summary().scanning)
            assertEquals(0, summary().failures)
            assertEquals(153L, result("users").state.count)
            assertEquals(2, result("users").state.table.visibleColumns.size)
            assertEquals(305L, rows().filterIsInstance<SqliteTableNode>()
                .single { it.state.table.name == "users" && it.state.query == null }.state.count)
            assertEquals(rows().size, rows().map { it.key }.toSet().size)
            tree.command(result("users"), SqliteCommand.TOGGLE)
            advanceUntilIdle()
            tree.page("db", "users", 100, "needle")
            advanceUntilIdle()
            assertEquals(53, result("users").state.pages[100]!!.rows.size)
            val data = rows().filterIsInstance<SqliteDataNode>().single()
            assertEquals("needle200", tree.cell(data, 100, 1)!!.text)
            tree.command(result("notes"), SqliteCommand.TOGGLE)
            advanceUntilIdle()
            assertFalse(result("users").state.expanded)
            assertEquals(listOf("notes"), rows().filterIsInstance<SqliteDataNode>().map { it.state.table.name })

            tree.page("db", "notes", 100, "needle")
            tree.search("db", "other")
            advanceUntilIdle()
            assertEquals(152L, summary().matches)
            assertEquals(listOf("users"), rows().filterIsInstance<SqliteTableNode>()
                .filter { it.state.query != null }.map { it.state.table.name })
            assertNull(tree.cell(data, 0, 1)) // Old result nodes cannot read a replacement query.
            tree.closeSearch("db")
            advanceUntilIdle()
            assertTrue(rows().none { it is SqliteSearchNode || it is SqliteNode && it.state.query != null })

            tree.search("db", "needle", "notes")
            advanceUntilIdle()
            assertEquals("notes", summary().table)
            assertEquals(1L, summary().matches)
            assertTrue(rows().filterIsInstance<SqliteTableNode>().none { it.state.query != null })
            val notes = rows().filterIsInstance<SqliteTableNode>().single { it.state.table.name == "notes" }
            assertTrue(notes.state.expanded)
            assertEquals(rows().indexOf(notes) + 1, rows().indexOf(summary()))
            assertEquals(notes.depth + 1, summary().depth)
            val note = rows().filterIsInstance<SqliteDataNode>().single()
            assertEquals(rows().indexOf(summary()) + 1, rows().indexOf(note))
            assertEquals(summary().depth + 1, note.depth)
            assertEquals("needle note", tree.cell(note, 0, 0)!!.text)
            assertTrue(tree.cell(note, 0, 0)!!.matched)
            tree.refresh("db", XFile("file", file.path, false))
            advanceUntilIdle()
            assertEquals("notes", summary().table)
            assertEquals(1L, summary().matches)
            tree.search("db", "needle", "users")
            advanceUntilIdle()
            assertEquals(153L, summary().matches)
            assertTrue(rows().filterIsInstance<SqliteTableNode>().none { it.state.query != null })
            assertEquals("users", rows().filterIsInstance<SqliteDataNode>().single().state.table.name)
            tree.command(rows().filterIsInstance<SqliteTableNode>().single { it.state.table.name == "users" }, SqliteCommand.TOGGLE)
            assertTrue(rows().none { it is SqliteSearchNode || it is SqliteDataNode })

            val users = rows().filterIsInstance<SqliteTableNode>().single { it.state.table.name == "users" }
            tree.command(users, SqliteCommand.TOGGLE)
            advanceUntilIdle()
            tree.search("db", "no matches", "users")
            advanceUntilIdle()
            assertEquals(0L, summary().matches)
            assertTrue(rows().none { it is SqliteDataNode }) // Never show unfiltered data under an empty result.
            tree.command(rows().filterIsInstance<SqliteTableNode>().single { it.state.table.name == "users" }, SqliteCommand.TOGGLE)
            assertTrue(rows().none { it is SqliteSearchNode || it is SqliteDataNode })

            SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("DROP TABLE notes") }
            tree.search("db", "needle")
            advanceUntilIdle()
            assertEquals(153L, summary().matches)
            assertEquals(1, summary().failures)
            assertTrue(rows().filterIsInstance<PaneViewModel.ActionNode>().any { it.id.startsWith("sqlite_search_error:") })
            tree.search("db", "cancelled")
            tree.closeSearch("db")
            advanceUntilIdle()
            assertTrue(rows().none { it is SqliteSearchNode })
        } finally {
            scope.cancel()
            advanceUntilIdle()
            file.delete()
        }
    }

    @Test fun `refresh replaces schema while preserving expanded tables and bounded paging`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.cacheDir, "tree-${UUID.randomUUID()}.db")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tree = SqliteTree(context, scope, { dispatcher }, {})
        fun table() = tree.rows("db", 2).filterIsInstance<SqliteTableNode>().single { it.state.table.name == "entries" }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE other(value TEXT)")
                db.beginTransaction()
                try {
                    repeat(1500) { db.execSQL("INSERT INTO entries VALUES (?, 'value')", arrayOf(it)) }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val source = XFile("file", file.path, false)
            tree.open("db", source)
            advanceUntilIdle()
            assertEquals(1500L, table().state.count)
            tree.command(table(), SqliteCommand.TOGGLE)
            tree.command(table(), SqliteCommand.PROPERTIES)
            advanceUntilIdle()
            repeat(13) { index -> tree.page("db", "entries", index * 100); advanceUntilIdle() }
            assertEquals(8, table().state.pages.size)
            assertEquals(1500, table().state.rowCount)
            // Evicting tail pages while scrolling back cannot shrink an unknown-count table.
            val state = table().state.copy(count = null, end = null)
            assertEquals(1400, state.rowCount)
            tree.page("db", "entries", 0)
            advanceUntilIdle()
            assertEquals(1300, table().state.loadedThrough)
            assertTrue(table().state.pages.containsKey(0))

            SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("ALTER TABLE entries ADD COLUMN added TEXT") }
            tree.command(table(), SqliteCommand.REFRESH)
            advanceUntilIdle()
            assertEquals(3, table().state.table.columns.size)
            assertTrue(table().state.expanded)
            assertTrue(table().state.properties)
            assertTrue(table().state.pages.isEmpty())
            // Switching tables cancels queued reads and cannot resurrect the previous data.
            tree.page("db", "entries", 1400)
            val other = tree.rows("db", 2).filterIsInstance<SqliteTableNode>().single { it.state.table.name == "other" }
            tree.command(other, SqliteCommand.TOGGLE)
            advanceUntilIdle()
            assertFalse(table().state.expanded)
            assertTrue(table().state.pages.isEmpty())
            assertTrue(table().state.pending.isEmpty())
            assertEquals(listOf("other"), tree.rows("db", 2).filterIsInstance<SqliteDataNode>().map { it.state.table.name })
            tree.command(other, SqliteCommand.TOGGLE)
            advanceUntilIdle()
            assertTrue(tree.rows("db", 2).none { it is SqliteDataNode })
            tree.retain(emptySet())
            advanceUntilIdle()
            assertTrue(tree.rows("db", 2).isEmpty())
        } finally {
            scope.cancel()
            advanceUntilIdle()
            file.delete()
        }
    }

    @Test fun `BLOB readers remain bound to the database and search lease that created them`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.cacheDir, "blob-${UUID.randomUUID()}.db")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tree = SqliteTree(context, scope, { dispatcher }, {})
        val source = XFile("file", file.path, false)
        fun data() = tree.rows("db", 1).filterIsInstance<SqliteDataNode>().single()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE entries(name TEXT, payload BLOB)")
                db.execSQL("INSERT INTO entries VALUES ('keep', X'01020304')")
            }
            tree.open("db", source)
            advanceUntilIdle()
            tree.search("db", "keep", "entries")
            advanceUntilIdle()
            val filtered = tree.blobReader(data(), 0, 1, 4)!!
            assertArrayEquals(byteArrayOf(2, 3), filtered(1, 2))
            tree.search("db", "keep", "entries") // Even the same query owns a new lease.
            advanceUntilIdle()
            assertTrue(runCatching { filtered(0, 1) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            val refreshed = tree.blobReader(data(), 0, 1, 4)!!
            tree.refresh("db", source)
            advanceUntilIdle()
            assertTrue(runCatching { refreshed(0, 1) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            val closed = tree.blobReader(data(), 0, 1, 4)!!
            tree.close("db")
            advanceUntilIdle()
            assertTrue(runCatching { closed(0, 1) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } finally { scope.cancel(); advanceUntilIdle(); file.delete() }
    }

    @Test fun `closing a pending database cannot repopulate its rows`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var changed = 0
        val tree = SqliteTree(context, scope, { dispatcher }, { changed++ })
        try {
            tree.open("db", XFile("file", "/not/a/database", false))
            tree.close("db")
            advanceUntilIdle()
            assertEquals(0, changed)
            assertTrue(tree.rows("db", 1).isEmpty())
        } finally { scope.cancel(); advanceUntilIdle() }
    }
}
