package com.twig.app.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var file: File
    private fun signal() = CancellationSignal()
    private fun source() = XFile("file", file.path, false, file.length(), file.lastModified())
    private fun writer() = SQLiteDatabase.openOrCreateDatabase(file, null)
    private fun session() = SqliteSession.open(context, source(), signal())

    @Before fun setup() {
        dir = File(context.cacheDir, "sqlite-test-${UUID.randomUUID()}").apply { mkdirs() }
        file = File(dir, "test.db")
    }
    @After fun cleanup() { dir.deleteRecursively(); FsRegistry.unregister("sqlite-test") }

    @Test fun `LIKE searches count each row once and share filtering with pages and details`() {
        val name = "quoted\" table"
        writer().use { db ->
            db.execSQL("CREATE TABLE ${SqliteSession.quote(name)}(id INTEGER PRIMARY KEY, a TEXT, b TEXT, payload BLOB, mixed)")
            val sql = "INSERT INTO ${SqliteSession.quote(name)} VALUES (?,?,?,?,?)"
            db.execSQL(sql, arrayOf(0, "not a match", null, null, null))
            db.execSQL(sql, arrayOf(1, "needle first", "needle twice", null, null))
            db.execSQL(sql, arrayOf(2, "binary only", null, "needle".toByteArray(), "needle".toByteArray()))
            db.execSQL(sql, arrayOf(3, "prefix NEEDLE suffix", null, null, null))
            db.execSQL(sql, arrayOf(4, "declared BLOB only", null, "needle", null))
            db.execSQL(sql, arrayOf(5, "O'Reilly", null, null, null))
            db.execSQL(sql, arrayOf(6, null, null, null, "needle in untyped column"))
            db.execSQL("CREATE TABLE binary_only(payload BLOB)")
            db.execSQL("INSERT INTO binary_only VALUES (?)", arrayOf("needle".toByteArray()))
        }
        session().use { s ->
            val tables = s.tables(signal())
            val table = tables.single { it.name == name }
            assertEquals(3L, s.count(table, signal(), "needle"))
            assertEquals(listOf(false, true, true, false, false),
                s.page(table, 0, signal(), "needle").rows.first().map { it.matched })
            assertFalse(s.page(table, 0, signal()).rows.any { row -> row.any { it.matched } })
            assertTrue(s.cell(table, 0, 1, signal(), "needle%").matched)
            assertFalse(s.cell(table, 0, 0, signal(), "needle%").matched)
            assertEquals(listOf("1", "3", "6"), s.page(table, 0, signal(), "needle").rows.map { it[0].text })
            assertEquals("3", s.page(table, 1, signal(), "needle").rows.first()[0].text)
            assertEquals("prefix NEEDLE suffix", s.cell(table, 1, 1, signal(), "needle").text)
            assertEquals(1L, s.count(table, signal(), "O'Reilly"))
            assertEquals(0L, s.count(table, signal(), "x' OR 1=1 --"))
            assertEquals(3L, s.count(table, signal(), "need_e"))
            assertEquals(7L, s.count(table, signal(), "%"))
            assertEquals(listOf("1", "6"), s.page(table, 0, signal(), "needle%").rows.map { it[0].text })
            assertEquals(1L, s.count(table, signal(), "%suffix"))
            assertEquals(0L, s.count(table, signal(), "prefix%NEEDLE"))
            assertEquals("prefix NEEDLE suffix", s.cell(table, 0, 1, signal(), "%suffix").text)
            assertEquals(1L, s.count(table, signal(), "5"))
            assertEquals(0L, s.count(tables.single { it.name == "binary_only" }, signal(), "needle"))
            assertEquals(7L, s.count(table, signal()))
        }
    }

    @Test fun `search marks full values beyond previews using SQLite LIKE semantics`() {
        writer().use { db ->
            db.execSQL("CREATE TABLE entries(a TEXT, b TEXT, payload BLOB, mixed)")
            db.execSQL("INSERT INTO entries VALUES (?,?,?,?)",
                arrayOf("x".repeat(300) + "needle", "NEEDLE tail", "needle", "needle".toByteArray()))
        }
        session().use { s ->
            val table = s.tables(signal()).single { it.name == "entries" }
            val row = s.page(table, 0, signal(), "need_e%").rows.single()
            assertEquals(listOf(false, true, false, false), row.map { it.matched })
            val contains = s.page(table, 0, signal(), "needle").rows.single()
            assertTrue(contains[0].truncated)
            assertFalse(contains[0].text.contains("needle"))
            assertEquals(listOf(true, true, false, false), contains.map { it.matched })
        }
    }

    @Test fun `wide table searches reuse one parameter without exceeding expression depth`() {
        writer().use { db ->
            db.execSQL("CREATE TABLE wide (" + (0 until 1200).joinToString(",") { "c$it TEXT" } + ")")
            db.execSQL("INSERT INTO wide(c1199) VALUES ('needle')")
        }
        session().use { s ->
            val table = s.tables(signal()).single { it.name == "wide" }
            assertEquals(1L, s.count(table, signal(), "needle"))
            assertEquals("needle", s.page(table, 0, signal(), "needle").rows.single().last().text)
        }
    }

    @Test fun `emoji previews and bounded details detect truncation by code points`() {
        val emoji = "\uD83D\uDE00"
        assertFalse(SqliteCell.text(emoji).truncated)
        writer().use { db ->
            db.execSQL("CREATE TABLE unicode_values(value TEXT)")
            db.execSQL("INSERT INTO unicode_values VALUES (?), (?)", arrayOf(emoji.repeat(300), emoji.repeat(65537)))
        }
        session().use { s ->
            val table = s.tables(signal()).single { it.name == "unicode_values" }
            val cells = s.page(table, 0, signal()).rows.map { it.single() }
            cells.forEach { cell ->
                assertEquals(emoji.repeat(256), cell.text)
                assertTrue(cell.truncated)
            }
            val complete = s.cell(table, 0, 0, signal())
            assertEquals(300L, complete.length)
            assertFalse(complete.truncated)
            val bounded = s.cell(table, 1, 0, signal())
            assertEquals(emoji.repeat(65536), bounded.text)
            assertEquals(65537L, bounded.length)
            assertTrue(bounded.truncated)
        }
    }

    @Test fun `quoted names null empty strings numbers and large values retain their types`() {
        val name = "odd\"; DROP TABLE safe;--"
        val column = "value\"quoted"
        writer().use { db ->
            db.execSQL("CREATE TABLE ${SqliteSession.quote(name)} (id INTEGER PRIMARY KEY, ${SqliteSession.quote(column)} TEXT, empty TEXT, absent TEXT, bytes BLOB, huge TEXT)")
            db.execSQL("INSERT INTO ${SqliteSession.quote(name)} VALUES (?,?,?,?,?,?)",
                arrayOf(Long.MAX_VALUE, "hello", "", null, ByteArray(2_000_000) { 42 }, "x".repeat(2_000_000)))
        }
        val before = file.readBytes()
        session().use { s ->
            val table = s.tables(signal()).single { it.name != "android_metadata" }
            assertEquals(name, table.name)
            assertEquals(column, table.columns[1].name)
            assertEquals(1L, s.count(table, signal()))
            val row = s.page(table, 0, signal()).rows.single()
            assertEquals(Long.MAX_VALUE.toString(), row[0].text)
            assertEquals('i', row[0].kind)
            assertEquals("", row[2].text)
            assertEquals('t', row[2].kind)
            assertEquals('n', row[3].kind)
            assertEquals(2_000_000L, row[4].length)
            assertEquals(64, row[4].text.length)
            assertTrue(row[4].truncated)
            assertEquals(256, row[5].text.length)
            assertTrue(row[5].truncated)
            assertEquals(65536, s.cell(table, 0, 5, signal()).text.length)
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `without rowid and shadowed rowid names use primary keys for ordering`() {
        writer().use { db ->
            db.execSQL("CREATE TABLE entries (rowid TEXT, _rowid_ TEXT, oid TEXT, key TEXT PRIMARY KEY) WITHOUT ROWID")
            db.execSQL("INSERT INTO entries VALUES ('r','r','r','z'),('r','r','r','a')")
        }
        session().use { s ->
            val table = s.tables(signal()).single { it.name != "android_metadata" }
            assertNull(table.identity.rowId)
            assertEquals(listOf("key"), table.identity.primaryKey)
            assertEquals(listOf("a", "z"), s.page(table, 0, signal()).rows.map { it[3].text })
        }
    }

    @Test fun `paging reaches the exact tail and empty tables keep their columns`() {
        writer().use { db ->
            db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, name TEXT NOT NULL DEFAULT 'default')")
            db.execSQL("CREATE TABLE empty (value TEXT)")
            db.beginTransaction()
            try {
                repeat(205) { db.execSQL("INSERT INTO entries(id) VALUES (?)", arrayOf(it + 1)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        session().use { s ->
            val tables = s.tables(signal())
            val entries = tables.first { it.name == "entries" }
            assertEquals(205L, s.count(entries, signal()))
            assertEquals(100, s.page(entries, 0, signal()).rows.size)
            assertEquals("101", s.page(entries, 100, signal()).rows.first()[0].text)
            assertEquals(5, s.page(entries, 200, signal()).rows.size)
            assertTrue(entries.columns[1].notNull)
            assertEquals("'default'", entries.columns[1].defaultValue)
            val empty = tables.first { it.name == "empty" }
            assertEquals(1, empty.columns.size)
            assertTrue(s.page(empty, 0, signal()).rows.isEmpty())
        }
    }

    @Test fun `read only browsing sees committed uncheckpointed WAL rows`() {
        writer().use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE entries (value TEXT)")
            db.execSQL("INSERT INTO entries VALUES ('in WAL')")
            assertTrue(File(file.path + "-wal").length() > 0)
            session().use { s ->
                val table = s.tables(signal()).single { it.name != "android_metadata" }
                assertEquals("in WAL", s.page(table, 0, signal()).rows.single().single().text)
            }
            assertTrue(db.isOpen)
        }
    }

    @Test fun `corruption handler never deletes the source`() {
        file.writeText("This is not SQLite")
        val bytes = file.readBytes()
        assertTrue(runCatching { session().use { it.tables(signal()) } }.isFailure)
        assertTrue(file.exists())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun `cancelled reads do not turn into empty results`() {
        writer().use { it.execSQL("CREATE TABLE entries(value TEXT)") }
        session().use { s ->
            val cancelled = signal().apply { cancel() }
            try { s.tables(cancelled); fail("Expected cancellation") }
            catch (_: OperationCanceledException) { }
        }
    }

    @Test fun `remote snapshot includes WAL and releases only its own lease`() {
        val local = LocalFileSystem()
        val fs = object : com.twig.core.FileSystem by local {
            override val scheme = "sqlite-test"
            override fun openInput(file: XFile) = local.openInput(file.copy(scheme = "file"))
            override fun list(dir: XFile) = local.list(dir.copy(scheme = "file")).map { it.copy(scheme = scheme) }
        }
        FsRegistry.register(fs)
        writer().use { db ->
            db.enableWriteAheadLogging()
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE entries(value TEXT)")
            db.execSQL("INSERT INTO entries VALUES ('snapshot WAL')")
            val source = source().copy(scheme = fs.scheme)
            val first = SqliteSession.open(context, source, signal())
            val second = SqliteSession.open(context, source, signal())
            assertEquals(2, File(context.cacheDir, "sqlite").listFiles()!!.size)
            first.close()
            second.use { s ->
                assertEquals(1, File(context.cacheDir, "sqlite").listFiles()!!.size)
                assertEquals("snapshot WAL", s.page(s.tables(signal()).single { it.name != "android_metadata" }, 0, signal()).rows.single().single().text)
            }
            assertTrue(File(context.cacheDir, "sqlite").listFiles()!!.isEmpty())
        }
    }

    @Test fun `opaque parents are explicit and a changed snapshot is rejected`() {
        writer().use { it.execSQL("CREATE TABLE entries(value TEXT)") }
        val source = XFile("sqlite-test", "opaque:document", false, file.length(), file.lastModified(), displayName = "test.db")
        val parent = XFile("sqlite-test", "opaque:directory", true)
        var listings = 0
        var mutate = false
        var failListing = false
        val fs = object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = "sqlite-test"
            override fun parentOf(file: XFile): XFile? = null
            override fun openInput(file: XFile) = this@SqliteSessionTest.file.inputStream()
            override fun list(dir: XFile): List<XFile> {
                check(dir == parent)
                check(!failListing) { "Listing unavailable" }
                listings++
                return listOf(if (mutate && listings % 2 == 0) source.copy(lastModified = source.lastModified + 1) else source)
            }
        }
        FsRegistry.register(fs)
        SqliteSession.open(context, source, signal(), parent).use { assertTrue(it.tables(signal()).any { it.name == "entries" }) }
        mutate = true
        assertTrue(runCatching { SqliteSession.open(context, source, signal(), parent) }.isFailure)
        mutate = false
        failListing = true
        assertTrue(runCatching { SqliteSession.open(context, source, signal(), parent) }.isFailure)
        assertTrue(File(context.cacheDir, "sqlite").listFiles()!!.isEmpty())
    }

    @Test fun `wide tables limit cells per page rather than multiplying the row limit`() {
        writer().use { db ->
            db.execSQL("CREATE TABLE wide (" + (0 until 120).joinToString(",") { "c$it TEXT" } + ")")
            repeat(100) { db.execSQL("INSERT INTO wide DEFAULT VALUES") }
        }
        session().use { s ->
            val table = s.tables(signal()).single { it.name == "wide" }
            val rows = s.page(table, 0, signal()).rows
            assertEquals(34, rows.size)
            assertTrue(rows.size * rows.first().size <= 4096)
        }
    }
}
