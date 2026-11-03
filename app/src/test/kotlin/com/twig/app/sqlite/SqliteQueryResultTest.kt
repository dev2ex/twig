package com.twig.app.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteQueryResultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var file: File
    private fun signal() = CancellationSignal()
    private fun writer() = SQLiteDatabase.openOrCreateDatabase(file, null)
    private fun session() = SqliteSession.open(context, XFile("file", file.path, false), signal())

    @Before fun setup() {
        com.twig.core.FsRegistry.register(com.twig.fs.local.LocalFileSystem())
        directory = File(context.cacheDir, "sql-test-${UUID.randomUUID()}").apply { mkdirs() }
        file = File(directory, "source.db")
        writer().use { db ->
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, label TEXT, payload BLOB)")
            db.execSQL("INSERT INTO entries VALUES (1, 'first', X'010203'), (2, 'second', X'040506')")
        }
    }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `local SQL reads the original without using filesystem snapshot copies`() {
        com.twig.core.FsRegistry.register(object : com.twig.core.FileSystem by com.twig.fs.local.LocalFileSystem() {
            override fun list(dir: XFile): List<XFile> = error("Local queries must not enumerate the source directory for copying")
            override fun openInput(file: XFile): java.io.InputStream = error("Local queries must not stream a source database copy")
        })
        try {
            session().use { source ->
                source.executeQuery(context, "SELECT label FROM entries WHERE id=2", signal()).use { result ->
                    assertEquals(1L, result.count)
                    assertEquals("second", result.session.cell(result.table, 0, 0, signal()).text)
                }
            }
        } finally { com.twig.core.FsRegistry.register(com.twig.fs.local.LocalFileSystem()) }
    }

    @Test fun `oversized returned rows fail explicitly and discard partial result files`() {
        session().use { source ->
            source.executeQuery(context, "SELECT 42 AS value", signal()).use { active ->
                val root = File(context.cacheDir, "sqlite-query")
                val before = root.list()!!.toSet()
                val failure = runCatching {
                    source.executeQuery(context, "SELECT zeroblob(16000000) AS payload", signal()).close()
                }.exceptionOrNull()
                assertTrue(failure.toString(), failure is android.database.sqlite.SQLiteBlobTooBigException)
                assertEquals(before, root.list()!!.toSet())
                assertEquals("42", active.session.cell(active.table, 0, 0, signal()).text)
            }
        }
    }

    @Test fun `one execution stays stable through pages details BLOBs CSV and later source changes`() {
        session().use { source ->
            source.executeQuery(context, "WITH selected AS (SELECT * FROM entries) SELECT random() AS nonce, * FROM selected ORDER BY id DESC; -- tail", signal()).use { result ->
                assertEquals(2L, result.count)
                val reader = result.session
                val table = result.table
                val rows = reader.page(table, 0, signal()).rows
                assertEquals(listOf("2", "1"), rows.map { it[1].text })
                val nonce = rows[0][0].text
                assertEquals(nonce, reader.cell(table, 0, 0, signal()).text)
                assertArrayEquals(byteArrayOf(4, 5, 6), reader.blob(table, 0, 3, 0, 3, 3, signal()))
                writer().use { db -> db.execSQL("DELETE FROM entries") }
                assertEquals(nonce, reader.page(table, 0, signal()).rows[0][0].text)
                assertEquals("second", reader.cell(table, 0, 2, signal()).text)
                val csv = ByteArrayOutputStream()
                reader.exportCsv(table, setOf("nonce"), signal(), csv)
                assertEquals("\uFEFF\"nonce\"\r\n" + rows.joinToString("") { it[0].text + "\r\n" }, csv.toString("UTF-8"))
            }
        }
    }

    @Test fun `large returned cells preserve complete values within the bounded query window`() {
        session().use { source ->
            source.executeQuery(context, "SELECT printf('%03000000d', 1) AS text, zeroblob(3000001) AS payload", signal()).use { result ->
                val cells = result.session.page(result.table, 0, signal()).rows.single()
                assertEquals(3000000L, cells[0].length)
                assertTrue(cells[0].truncated)
                assertEquals(3000001L, cells[1].length)
                assertArrayEquals(ByteArray(65536), result.session.blob(result.table, 0, 1, 2900000, 65536, 3000001, signal()))
                val csv = ByteArrayOutputStream()
                result.session.exportCsv(result.table, setOf("text"), signal(), csv)
                val value = csv.toString("UTF-8")
                assertTrue(value.endsWith("1\"\r\n"))
                assertEquals(3000015, value.toByteArray(Charsets.UTF_8).size)
            }
        }
    }

    @Test fun `read-only PRAGMAs EXPLAIN VALUES and duplicate aliases preserve results`() {
        session().use { source ->
            source.executeQuery(context, "PRAGMA main.table_info('entries')", signal()).use { result ->
                assertEquals(3L, result.count)
                assertEquals(listOf("id", "label", "payload"), result.session.page(result.table, 0, signal()).rows.map { it[1].text })
            }
            source.executeQuery(context, "EXPLAIN QUERY PLAN SELECT * FROM entries", signal()).use { assertTrue(it.count > 0) }
            source.executeQuery(context, "PRAGMA query_only", signal()).use {
                assertEquals("1", it.session.cell(it.table, 0, 0, signal()).text)
            }
            source.executeQuery(context, "VALUES (1, 'text', NULL), (2, 3.5, X'00FF')", signal()).use { result ->
                assertEquals(listOf('t', 'r'), result.session.page(result.table, 0, signal()).rows.map { it[1].kind })
            }
            source.executeQuery(context, "SELECT 1 AS x, 2 AS X, 3 AS 'x (2)'", signal()).use { result ->
                assertEquals(3, result.table.columns.map { it.name.lowercase() }.toSet().size)
                assertEquals(listOf("1", "2", "3"), result.session.page(result.table, 0, signal()).rows.single().map { it.text })
            }
            source.executeQuery(context, "SELECT * FROM entries WHERE 0", signal()).use { result ->
                assertEquals(0L, result.count)
                assertEquals(3, result.table.columns.size)
                assertTrue(result.session.page(result.table, 0, signal()).rows.isEmpty())
            }
        }
    }

    @Test fun `UTF16 sources cache complete values and export UTF8`() {
        val utf16 = File(directory, "utf16.db")
        SQLiteDatabase.openDatabase(utf16.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.execSQL("PRAGMA encoding='UTF-16le'")
            db.execSQL("CREATE TABLE entries(value TEXT)")
            db.execSQL("INSERT INTO entries VALUES (?)", arrayOf("🙂尾"))
        }
        SqliteSession.open(context, XFile("file", utf16.path, false), signal()).use { source ->
            source.executeQuery(context, "SELECT * FROM entries", signal()).use { result ->
                assertEquals("🙂尾", result.session.cell(result.table, 0, 0, signal()).text)
                val csv = ByteArrayOutputStream()
                result.session.exportCsv(result.table, setOf("value"), signal(), csv)
                assertEquals("\uFEFF\"value\"\r\n\"🙂尾\"\r\n", csv.toString("UTF-8"))
            }
        }
    }

    @Test fun `writes multiple statements and mutating PRAGMAs cannot change the source`() {
        val before = file.readBytes()
        session().use { source ->
            for (sql in listOf("DELETE FROM entries", "UPDATE entries SET label='bad'", "DROP TABLE entries",
                "WITH c AS (SELECT 1) DELETE FROM entries", "WITH c AS (SELECT 1) UPDATE entries SET label='bad' RETURNING label", "PRAGMA user_version=99", "PRAGMA journal_mode(WAL)",
                "PRAGMA wal_checkpoint", "ATTACH DATABASE ':memory:' AS outside", "SELECT 1; DELETE FROM entries",
                "SELECT 1; SELECT 2", "/* SELECT */ DELETE FROM entries")) {
                assertTrue(sql, runCatching { source.executeQuery(context, sql, signal()).close() }.isFailure)
            }
            source.executeQuery(context, "SELECT '; -- /*' AS literal; /* trailing ; */", signal()).use { result ->
                assertEquals("; -- /*", result.session.page(result.table, 0, signal()).rows.single().single().text)
            }
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `cancelling cursor reading cleans partial results without closing another result`() {
        session().use { source ->
            source.executeQuery(context, "SELECT 42 AS value", signal()).use { active ->
                val root = File(context.cacheDir, "sqlite-query")
                val before = root.list()!!.toSet()
                val snapshots = File(context.cacheDir, "sqlite").list().orEmpty().toSet()
                val cancellation = signal()
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val started = java.util.concurrent.CountDownLatch(1)
                    val future = executor.submit<SqliteQueryResult> {
                        started.countDown()
                        source.executeQuery(context, "WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL " +
                            "SELECT n+1 FROM seq WHERE n<1000000000) SELECT n FROM seq", cancellation)
                    }
                    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    Thread.sleep(100)
                    cancellation.cancel()
                    val error = runCatching { future.get(10, java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()
                    assertTrue(error.toString(), error is java.util.concurrent.ExecutionException && error.cause is android.os.OperationCanceledException)
                    assertEquals(before, root.list()!!.toSet())
                    assertEquals(snapshots, File(context.cacheDir, "sqlite").list().orEmpty().toSet())
                    assertEquals("42", active.session.cell(active.table, 0, 0, signal()).text)
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Config(sdk = [25])
    @Test fun `querying an old Android WAL source recovers only a private copy`() {
        writer().use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            writer.execSQL("INSERT INTO entries VALUES (3, 'wal row', X'FF')")
            val before = file.readBytes()
            val wal = File(file.path + "-wal")
            val walBefore = wal.readBytes()
            session().use { source ->
                assertTrue(runCatching {
                    source.executeQuery(context, "WITH c AS (SELECT 1) UPDATE entries SET label='bad' RETURNING label", signal()).close()
                }.isFailure)
                source.executeQuery(context, "SELECT * FROM entries ORDER BY id", signal()).use { result ->
                    assertEquals(3L, result.count)
                    assertEquals("wal row", result.session.cell(result.table, 2, 1, signal()).text)
                    assertEquals("first", result.session.cell(result.table, 0, 1, signal()).text)
                }
            }
            assertArrayEquals(before, file.readBytes())
            assertArrayEquals(walBefore, wal.readBytes())
        }
    }

    @Test fun `failed and cancelled queries discard private files without affecting another result`() {
        session().use { source ->
            source.executeQuery(context, "SELECT 42 AS value", signal()).use { active ->
                val root = File(context.cacheDir, "sqlite-query")
                val initial = root.list()!!.toSet()
                assertTrue(runCatching { source.executeQuery(context, "SELECT * FROM missing", signal()) }.isFailure)
                assertTrue(runCatching { source.executeQuery(context, "SELECT 1", signal().apply { cancel() }) }.isFailure)
                assertEquals(initial, root.list()!!.toSet())
                assertEquals("42", active.session.cell(active.table, 0, 0, signal()).text)
            }
        }
    }
}
