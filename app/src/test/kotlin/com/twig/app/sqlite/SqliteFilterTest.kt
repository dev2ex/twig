package com.twig.app.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.Assert.*
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
class SqliteFilterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun signal() = CancellationSignal()
    private fun database(block: (SQLiteDatabase, SqliteSession) -> Unit) {
        val file = File(context.cacheDir, "filters-${UUID.randomUUID()}.db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { writer ->
                writer.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, category, tag TEXT, payload BLOB)")
                SqliteSession.open(context, XFile("file", file.path, false), signal()).use { block(writer, it) }
            }
        } finally { file.delete() }
    }

    @Test fun `top ten groups ignore their own filter and honor other filters and search`() = database { db, s ->
        repeat(12) { group -> repeat(group + 1) {
            db.execSQL("INSERT INTO entries(category,tag) VALUES (?,?)", arrayOf("group$group", if (group % 2 == 0) "keep" else "skip"))
        } }
        db.execSQL("INSERT INTO entries(category,tag) VALUES (?, 'keep')", arrayOf("binary".toByteArray()))
        val table = s.tables(signal()).single { it.name == "entries" }
        val options = s.filterOptions(table, "category", signal())
        assertEquals(10, options.size)
        assertEquals((11 downTo 2).map { "group$it" }, options.map { it.value.text })
        assertEquals((12L downTo 3L).toList(), options.map { it.count })
        val filters = listOf(SqliteFilter("category", listOf(SqliteCell.text("group0"))),
            SqliteFilter("tag", listOf(SqliteCell.text("keep"))))
        val candidates = s.filterOptions(table, "category", signal(), "group%", filters)
        assertEquals((10 downTo 0 step 2).map { "group$it" }, candidates.map { it.value.text })
        assertEquals(1L, s.count(table, signal(), filters = filters))
        assertTrue(runCatching { s.filterOptions(table, "payload", signal()) }.isFailure)
        assertTrue(runCatching { s.filterOptions(table, "category", signal().apply { cancel() }) }
            .exceptionOrNull() is OperationCanceledException)
    }

    @Test fun `typed values preserve null empty text mixed numbers quotes and numeric precision`() = database { db, s ->
        val values = listOf(null, "", "7", 7L, 1.2345678901234567, "O'Reilly%_", "a\u0000b", Double.POSITIVE_INFINITY)
        values.forEach { db.execSQL("INSERT INTO entries(category) VALUES (?)", arrayOf(it)) }
        val table = s.tables(signal()).single { it.name == "entries" }
        val options = s.filterOptions(table, "category", signal())
        assertEquals(values.size, options.size)
        for (option in options) {
            val filters = listOf(SqliteFilter("category", listOf(option.value)))
            assertEquals("${option.value}", 1L, s.count(table, signal(), filters = filters))
        }
        val selected = listOf(SqliteCell('n', ""), SqliteCell.text(""), SqliteCell.text("7"))
        assertEquals(listOf("1", "2", "3"), s.page(table, 0, signal(),
            filters = listOf(SqliteFilter("category", selected))).rows.map { it[0].text })
    }

    @Test fun `filter paging details blobs and CSV keep identical row identities and sorting`() = database { db, s ->
        repeat(320) { id -> db.execSQL("INSERT INTO entries VALUES (?,?,?,?)", arrayOf(
            id, "group${id % 3}", if (id % 2 == 0) "keep" else "skip", "blob$id".toByteArray())) }
        val table = s.tables(signal()).single { it.name == "entries" }
        val filters = listOf(SqliteFilter("category", listOf(SqliteCell.text("group0"), SqliteCell.text("group1"))))
        val sort = SqliteSort("id", true)
        val expected = (319 downTo 0).filter { it % 3 != 2 && it % 2 == 0 }
        assertEquals(expected.size.toLong(), s.count(table, signal(), "keep", filters))
        val rows = listOf(0, 100).flatMap { s.page(table, it, signal(), "keep", sort, filters).rows }
        assertEquals(expected, rows.map { it[0].text.toInt() })
        assertEquals(expected[103].toString(), s.cell(table, 103, 0, signal(), "keep", sort, filters).text)
        val bytes = "blob${expected[103]}".toByteArray()
        assertArrayEquals(bytes, s.blob(table, 103, 3, 0, bytes.size, bytes.size.toLong(), signal(), "keep", sort, filters))
        val output = java.io.ByteArrayOutputStream()
        s.exportCsv(table, setOf("id"), signal(), output, "keep", sort = sort, filters = filters)
        assertEquals(expected.map(Int::toString), output.toString("UTF-8").trim().lines().drop(1))
    }

    @Config(sdk = [25])
    @Test fun `grouping respects collation quoted identifiers and numeric equivalence`() = database { db, s ->
        val column = "odd\"; --"
        db.execSQL("CREATE TABLE names (${SqliteSession.quote(column)} TEXT COLLATE NOCASE)")
        db.execSQL("INSERT INTO names VALUES ('Alpha'),('ALPHA'),('Beta')")
        db.execSQL("INSERT INTO entries(category) VALUES (7), (7.0), ('7')")
        val names = s.tables(signal()).single { it.name == "names" }
        val options = s.filterOptions(names, column, signal())
        assertEquals(listOf(2L, 1L), options.map { it.count })
        assertEquals(2L, s.count(names, signal(), filters = listOf(SqliteFilter(column, listOf(options[0].value)))))
        val entries = s.tables(signal()).single { it.name == "entries" }
        val numbers = s.filterOptions(entries, "category", signal())
        assertEquals(listOf(2L, 1L), numbers.map { it.count })
        assertEquals(2L, s.count(entries, signal(), filters = listOf(SqliteFilter("category", listOf(numbers[0].value)))))
        assertEquals(1L, s.count(entries, signal(), filters = listOf(SqliteFilter("category", listOf(numbers[1].value)))))
    }

    @Test fun `custom operators compare typed values and keep null empty and blob semantics`() = database { db, s ->
        listOf<Any?>(null, "", 7, 18, 25, "18", "abc", "prefixABCtail", byteArrayOf(1)).forEach {
            db.execSQL("INSERT INTO entries(category,tag) VALUES (?, 'keep')", arrayOf(it))
        }
        val table = s.tables(signal()).single { it.name == "entries" }
        fun ids(expression: String) = s.page(table, 0, signal(), filters = listOf(
            SqliteFilter("category", condition = SqliteCondition.parse(expression)!!))).rows.map { it[0].text.toInt() }
        assertEquals(listOf(5), ids("> 18"))
        assertEquals(listOf(4, 5), ids(">= 18"))
        assertEquals(listOf(3), ids("< 18"))
        assertEquals(listOf(3, 4), ids("<= 18"))
        assertEquals(listOf(4), ids("= 18"))
        assertEquals(listOf(3, 5), ids("!= 18"))
        assertEquals(listOf(6), ids("= '18'"))
        assertEquals(listOf(2), ids("= ''"))
        assertEquals(listOf(1), ids("IS NULL"))
        assertEquals((2..9).toList(), ids("IS NOT NULL"))
        assertEquals(listOf(7), ids("LIKE abc"))
        assertEquals(listOf(7, 8), ids("LIKE '%abc%'"))
        assertEquals(listOf(2, 3, 4, 5, 6), ids("NOT LIKE '%abc%'"))
        val filter = SqliteFilter("category", condition = SqliteCondition.keyword("ABC"))
        assertEquals(2L, s.count(table, signal(), filters = listOf(filter)))
        assertEquals(listOf("prefixABCtail"), s.page(table, 0, signal(), filters = listOf(
            SqliteFilter("category", condition = SqliteCondition.keyword("prefix%")))).rows.map { it[1].text })
    }

    @Test fun `custom conditions share search arguments paging details blobs export and group counts`() = database { db, s ->
        repeat(320) { id -> db.execSQL("INSERT INTO entries VALUES (?,?,?,?)", arrayOf(
            id, id, if (id % 2 == 0) "keep" else "skip", "blob$id".toByteArray())) }
        val table = s.tables(signal()).single { it.name == "entries" }
        val filters = listOf(SqliteFilter("category", condition = SqliteCondition.parse(">= 100")),
            SqliteFilter("tag", condition = SqliteCondition.keyword("keep")))
        val sort = SqliteSort("id", true)
        val expected = (318 downTo 100 step 2).map(Int::toString)
        assertEquals(110L, s.count(table, signal(), "keep", filters))
        val rows = listOf(0, 100).flatMap { s.page(table, it, signal(), "keep", sort, filters).rows }
        assertEquals(expected, rows.map { it[0].text })
        assertEquals(expected[103], s.cell(table, 103, 0, signal(), "keep", sort, filters).text)
        val bytes = "blob${expected[103]}".toByteArray()
        assertArrayEquals(bytes, s.blob(table, 103, 3, 0, bytes.size, bytes.size.toLong(), signal(), "keep", sort, filters))
        val groups = s.filterOptions(table, "tag", signal(), filters = filters)
        assertEquals(listOf(110L, 110L), groups.map { it.count })
        val output = java.io.ByteArrayOutputStream()
        s.exportCsv(table, setOf("id"), signal(), output, "keep", sort = sort, filters = filters)
        assertEquals(expected, output.toString("UTF-8").trim().lines().drop(1))
    }

    @Test fun `handwritten filter operands stay bound even when they contain SQL syntax`() = database { db, s ->
        val text = "x'; DROP TABLE entries; --"
        db.execSQL("INSERT INTO entries(category) VALUES (?)", arrayOf(text))
        val table = s.tables(signal()).single { it.name == "entries" }
        val filter = SqliteFilter("category", condition = SqliteCondition.parse("= " + text))
        assertEquals(1L, s.count(table, signal(), filters = listOf(filter)))
        assertEquals(1L, s.count(table, signal(), filters = listOf(
            SqliteFilter("category", condition = SqliteCondition.keyword(text)))))
        assertTrue(s.tables(signal()).any { it.name == "entries" })
    }

    @Test fun `oversized candidates remain bounded and cannot silently filter a prefix`() = database { db, s ->
        db.execSQL("INSERT INTO entries(category) VALUES (?)", arrayOf("x".repeat(2_000_000)))
        val table = s.tables(signal()).single { it.name == "entries" }
        val value = s.filterOptions(table, "category", signal()).single().value
        assertTrue(value.truncated)
        assertEquals(65536, value.text.length)
        assertTrue(runCatching { s.count(table, signal(), filters = listOf(SqliteFilter("category", listOf(value)))) }.isFailure)
    }
}
