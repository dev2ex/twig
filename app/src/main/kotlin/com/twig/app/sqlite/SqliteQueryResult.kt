package com.twig.app.sqlite

import android.content.Context
import android.database.AbstractWindowedCursor
import android.database.CursorWindow
import android.os.Build
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.twig.app.R
import com.twig.core.XFile
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Cache only rows returned by a read-only cursor; later viewing/export never reruns user SQL. */
internal class SqliteQueryResult private constructor(val session: SqliteSession, val table: SqliteTable,
    val count: Long, private val directory: File) : Closeable {
    override fun close() {
        session.close()
        synchronized(active) { directory.deleteRecursively(); active.remove(directory) }
    }

    companion object {
        private val active = HashSet<File>()
        fun create(context: Context, source: SQLiteDatabase, input: String, signal: CancellationSignal): SqliteQueryResult {
            val statement = SqliteSql.statement(context, input)
            signal.throwIfCanceled()
            source.rawQuery("PRAGMA query_only=ON", null, signal).use { it.moveToFirst() }
            val directory = synchronized(active) {
                val root = File(context.cacheDir, "sqlite-query").apply { mkdirs() }
                root.listFiles()?.filter { it !in active }?.forEach { it.deleteRecursively() }
                File(root, UUID.randomUUID().toString()).apply { check(mkdirs()); active.add(this) }
            }
            val file = File(directory, "result.db")
            var reader: SqliteSession? = null
            try {
                var count = 0L
                source.rawQuery(statement.sql, null, signal).use { cursor ->
                    if (Build.VERSION.SDK_INT >= 28 && cursor is AbstractWindowedCursor) {
                        cursor.window = CursorWindow("SQLite query", 8L * 1024 * 1024)
                    }
                    val seen = HashSet<String>()
                    val columns = cursor.columnNames.map { original ->
                        var name = original
                        var suffix = 2
                        while (!seen.add(name.lowercase(java.util.Locale.ROOT))) name = "$original (${suffix++})"
                        name
                    }
                    check(columns.isNotEmpty()) { context.getString(R.string.sqlite_sql_no_columns) }
                    SQLiteDatabase.openDatabase(file.path, null,
                        SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { result ->
                        // No affinities: preserve SQLite's runtime types, including mixed-type expression results.
                        result.execSQL("CREATE TABLE query_result (" + columns.joinToString(",", transform = SqliteSession::quote) + ")")
                        result.beginTransaction()
                        try {
                            result.compileStatement("INSERT INTO query_result VALUES (" + columns.joinToString(",") { "?" } + ")").use { insert ->
                                while (cursor.moveToNext()) {
                                    signal.throwIfCanceled()
                                    columns.indices.forEach { column ->
                                        when (cursor.getType(column)) {
                                            Cursor.FIELD_TYPE_NULL -> insert.bindNull(column + 1)
                                            Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(column + 1, cursor.getLong(column))
                                            Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(column + 1, cursor.getDouble(column))
                                            Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(column + 1, cursor.getBlob(column))
                                            else -> insert.bindString(column + 1, cursor.getString(column))
                                        }
                                    }
                                    insert.executeInsert(); count++
                                }
                            }
                            result.setTransactionSuccessful()
                        } finally { result.endTransaction() }
                    }
                }
                signal.throwIfCanceled()
                val opened = SqliteSession.open(context, XFile("file", file.path, false), signal)
                reader = opened
                val table = opened.tables(signal).single { it.name == "query_result" }
                return SqliteQueryResult(opened, table, count, directory)
            } catch (error: Throwable) {
                reader?.close()
                synchronized(active) { directory.deleteRecursively(); active.remove(directory) }
                throw error
            }
        }
    }
}
