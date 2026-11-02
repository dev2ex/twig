package com.twig.app.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.Build
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Schema and bounded results are independent of the tree, for future query/edit screens. */
data class SqliteColumn(val name: String, val type: String, val notNull: Boolean,
    val defaultValue: String?, val primaryKey: Int, val hidden: Int = 0)

/** A future editor must use this identity, never a displayed row offset, for updates. */
data class SqliteIdentity(val rowId: String?, val primaryKey: List<String>)
data class SqliteTable(val name: String, val ddl: String, val columns: List<SqliteColumn>,
    val identity: SqliteIdentity) {
    val visibleColumns = columns.filter { it.hidden != 1 }
}
data class SqliteCell(val kind: Char, val text: String, val length: Long = text.codePointCount(0, text.length).toLong()) {
    val truncated get() = (kind == 't' && length > text.codePointCount(0, text.length)) || (kind == 'b' && length > text.length / 2)
    companion object {
        fun text(value: String) = SqliteCell('t', value)
        fun decode(value: String): SqliteCell {
            val kind = value.first()
            return if (kind == 't' || kind == 'b') {
                val sep = value.indexOf(':')
                SqliteCell(kind, value.substring(sep + 1), value.substring(1, sep).toLong())
            } else SqliteCell(kind, value.substring(1))
        }
    }
}
data class SqlitePage(val offset: Int, val rows: List<List<SqliteCell>>)

/**
 * One browsing lease. Connections/cursors never escape a read; original databases are
 * opened read-only, with a non-destructive corruption handler. Remote/SAF sources use
 * a private, disposable snapshot, including any WAL. No write API is exposed yet.
 */
class SqliteSession private constructor(private val path: File, private val temporary: File?) : Closeable {
    private var closed = false
    private val lock = ReentrantReadWriteLock()

    private fun <T> read(signal: CancellationSignal, block: (SQLiteDatabase) -> T): T = lock.read {
        check(!closed)
        signal.throwIfCanceled()
        val mode = if (temporary == null) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
        SQLiteDatabase.openDatabase(path.path, null, mode or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            { /* Browsing must never delete a corrupt source or its journal. */ }).use { db ->
            // Older SQLite versions need a writable private copy to recover a copied WAL.
            // SQL remains read-only; checkpoint/recovery may only change disposable files.
            if (temporary != null) db.rawQuery("PRAGMA query_only=ON", null, signal).use { it.moveToFirst() }
            block(db)
        }
    }

    fun tables(signal: CancellationSignal): List<SqliteTable> = read(signal) { db ->
        val schemas = db.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' " +
            "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name COLLATE NOCASE", null, signal).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1).orEmpty()) }
        }
        schemas.map { (name, ddl) ->
            signal.throwIfCanceled()
            fun columns(pragma: String) = db.rawQuery("PRAGMA $pragma(${quote(name)})", null, signal).use { c ->
                buildList {
                    while (c.moveToNext()) add(SqliteColumn(c.getString(1), c.getString(2).orEmpty(),
                        c.getInt(3) != 0, if (c.isNull(4)) null else c.getString(4), c.getInt(5),
                        if (c.columnCount > 6) c.getInt(6) else 0))
                }
            }
            val cols = columns("table_xinfo").ifEmpty { columns("table_info") }
            val rowId = listOf("_rowid_", "rowid", "oid").firstOrNull { candidate ->
                cols.none { it.name.equals(candidate, true) } && runCatching {
                    // Unquoted: unknown double-quoted identifiers may become string literals.
                    db.rawQuery("SELECT $candidate FROM ${quote(name)} LIMIT 0", null, signal).use { p -> p.count }
                }.isSuccess
            }
            SqliteTable(name, ddl, cols, SqliteIdentity(rowId,
                cols.filter { it.primaryKey > 0 }.sortedBy { it.primaryKey }.map { it.name }))
        }
    }

    fun count(table: SqliteTable, signal: CancellationSignal): Long = read(signal) { db ->
        db.rawQuery("SELECT count(*) FROM ${quote(table.name)}", null, signal).use { it.moveToFirst(); it.getLong(0) }
    }

    fun page(table: SqliteTable, offset: Int, signal: CancellationSignal): SqlitePage = read(signal) { db ->
        val projection = table.visibleColumns.joinToString(",") { preview(it.name, PREVIEW_CHARS, PREVIEW_BYTES) }
        db.rawQuery("SELECT $projection FROM ${quote(table.name)}${order(table)} LIMIT ${pageSize(table)} OFFSET $offset",
            null, signal).use { c ->
            SqlitePage(offset, buildList {
                while (c.moveToNext()) {
                    signal.throwIfCanceled()
                    add(List(c.columnCount) { SqliteCell.decode(c.getString(it)) })
                }
            })
        }
    }

    /** Bounded detail read; the UI reports truncation explicitly rather than risking CursorWindow/OOM. */
    fun cell(table: SqliteTable, row: Int, column: Int, signal: CancellationSignal): SqliteCell = read(signal) { db ->
        val projection = preview(table.visibleColumns[column].name, 65536, 32768)
        db.rawQuery("SELECT $projection FROM ${quote(table.name)}${order(table)} LIMIT 1 OFFSET $row", null, signal).use {
            check(it.moveToFirst())
            SqliteCell.decode(it.getString(0))
        }
    }

    override fun close(): Unit = lock.write {
        closed = true
        temporary?.let { dir -> synchronized(activeSnapshots) {
            dir.deleteRecursively()
            activeSnapshots.remove(dir)
        } }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val PREVIEW_CHARS = 256
        const val PREVIEW_BYTES = 32
        /** A wide table must not multiply the eight-page cache into hundreds of MB. */
        fun pageSize(table: SqliteTable) = (4096 / table.visibleColumns.size.coerceAtLeast(1)).coerceIn(1, PAGE_SIZE)
        private val activeSnapshots = HashSet<File>()
        fun recognizes(file: XFile) = !file.isDir && file.scheme != "trash" &&
            file.extension in setOf("db", "db3", "sqlite", "sqlite3", "s3db")
        fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
        private fun order(table: SqliteTable): String {
            val keys = table.identity.rowId?.let { listOf(it) } ?: table.identity.primaryKey
            return if (keys.isEmpty()) "" else " ORDER BY " + keys.joinToString(",") { quote(it) }
        }
        private fun preview(name: String, chars: Int, bytes: Int): String {
            val c = quote(name)
            return "CASE typeof($c) WHEN 'null' THEN 'n' WHEN 'blob' THEN 'b'||length($c)||':'||hex(substr($c,1,$bytes)) " +
                "WHEN 'text' THEN 't'||length($c)||':'||substr($c,1,$chars) " +
                "WHEN 'integer' THEN 'i'||$c ELSE 'r'||$c END"
        }

        fun open(context: Context, file: XFile, signal: CancellationSignal, parent: XFile? = null): SqliteSession {
            if (file.scheme == "file" && File(file.path).canRead() &&
                (Build.VERSION.SDK_INT >= 28 || !File(file.path + "-wal").exists())) {
                return SqliteSession(File(file.path), null)
            }
            val fs = FsRegistry.of(file)
            val dir = synchronized(activeSnapshots) {
                val root = File(context.cacheDir, "sqlite").apply { mkdirs() }
                // A previous process may have died without releasing its leases.
                root.listFiles()?.filter { it !in activeSnapshots }?.forEach { it.deleteRecursively() }
                File(root, UUID.randomUUID().toString()).apply { check(mkdirs()); activeSnapshots.add(this) }
            }
            try {
                // Listing failure is unknown, never evidence that a required WAL is absent.
                // The tree supplies the real containing directory for opaque SAF identifiers.
                val directory = parent ?: fs.parentOf(file) ?: error(context.getString(R.string.sqlite_open_failed))
                val siblings = fs.list(directory)
                val wal = siblings.firstOrNull { it.name == file.name + "-wal" }
                val journal = siblings.firstOrNull { it.name == file.name + "-journal" && it.size != 0L }
                check(journal == null) { context.getString(R.string.sqlite_journal_active) }
                val sources = listOfNotNull(siblings.firstOrNull { it.path == file.path } ?: file, wal)
                for ((index, source) in sources.withIndex()) {
                    val target = File(dir, if (index == 0) "database" else "database-wal")
                    fs.openInput(source).use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            signal.throwIfCanceled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    } }
                    check(source.size <= 0 || target.length() == source.size) { context.getString(R.string.sqlite_source_changed) }
                }
                val after = fs.list(directory)
                for (source in sources) {
                    val latest = after.firstOrNull { it.path == source.path }
                    check(latest != null && latest.size == source.size && latest.lastModified == source.lastModified) {
                        context.getString(R.string.sqlite_source_changed)
                    }
                }
                check(after.any { it.name == file.name + "-wal" } == (wal != null)) {
                    context.getString(R.string.sqlite_source_changed)
                }
                check(after.none { it.name == file.name + "-journal" && it.size != 0L }) {
                    context.getString(R.string.sqlite_source_changed)
                }
                return SqliteSession(File(dir, "database"), dir)
            } catch (e: Throwable) {
                synchronized(activeSnapshots) { dir.deleteRecursively(); activeSnapshots.remove(dir) }
                throw e
            }
        }
    }
}
