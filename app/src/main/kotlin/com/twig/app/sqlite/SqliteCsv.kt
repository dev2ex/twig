package com.twig.app.sqlite

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import java.io.BufferedOutputStream
import java.io.OutputStream

/** Bounded cell reads keep exports independent of grid previews and CursorWindow limits. */
internal object SqliteCsv {
    fun write(db: SQLiteDatabase, table: SqliteTable, columns: List<SqliteColumn>, suffix: String,
        arguments: Array<String>?, signal: CancellationSignal, output: OutputStream, progress: (Long) -> Unit, failure: String) {
        val chunk = (65536 / columns.size).coerceIn(64, 16384) / 2 * 2
        val encoding = db.rawQuery("PRAGMA encoding", null, signal).use { it.moveToFirst(); it.getString(0) }
        var written = 0L
        val buffered = BufferedOutputStream(object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                signal.throwIfCanceled()
                output.write(bytes, offset, count)
                written += count
                progress(written)
            }
        }, 65536)
        fun text(bytes: ByteArray) {
            var start = 0
            bytes.forEachIndexed { index, byte ->
                if (byte == 34.toByte()) {
                    buffered.write(bytes, start, index - start + 1)
                    buffered.write(34)
                    start = index + 1
                }
            }
            buffered.write(bytes, start, bytes.size - start)
        }
        fun quoted(value: String) {
            buffered.write(34); text(value.toByteArray(Charsets.UTF_8)); buffered.write(34)
        }
        val source = " FROM ${SqliteSession.quote(table.name)}$suffix"
        val projection = columns.joinToString(",") {
            val name = SqliteSession.quote(it.name)
            "CASE WHEN $name IS NULL THEN 'n0:' ELSE substr(typeof($name),1,1)||" +
                "length(CAST($name AS BLOB))||':'||hex(substr(CAST($name AS BLOB),1,$chunk)) END"
        }
        buffered.write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        columns.forEachIndexed { index, column ->
            if (index > 0) buffered.write(44)
            quoted(column.name)
        }
        buffered.write(byteArrayOf(13, 10))
        db.rawQuery("SELECT $projection$source", arguments, signal).use { rows ->
            var row = 0L
            while (rows.moveToNext()) {
                signal.throwIfCanceled()
                columns.forEachIndexed { index, column ->
                    if (index > 0) buffered.write(44)
                    val encoded = rows.getString(index)
                    val kind = encoded.first()
                    val separator = encoded.indexOf(':')
                    val length = encoded.substring(1, separator).toLong()
                    if (kind == 't') buffered.write(34)
                    if (kind == 'b') buffered.write("0x".toByteArray(Charsets.US_ASCII))
                    var offset = 0L
                    var hex = encoded.substring(separator + 1)
                    var pending = ""
                    while (offset < length) {
                        signal.throwIfCanceled()
                        if (offset > 0) {
                            val name = SqliteSession.quote(column.name)
                            hex = db.rawQuery("SELECT hex(substr(CAST($name AS BLOB),${offset + 1},$chunk))" +
                                "$source LIMIT 1 OFFSET $row", arguments, signal).use {
                                check(it.moveToFirst()) { failure }
                                it.getString(0)
                            }
                        }
                        val count = minOf(chunk.toLong(), length - offset).toInt()
                        check(hex.length == count * 2) { failure }
                        if (kind == 'b') buffered.write(hex.toByteArray(Charsets.US_ASCII))
                        else {
                            var bytes = ByteArray(count) { i ->
                                ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
                            }
                            if (encoding != "UTF-8") {
                                check(count % 2 == 0) { failure }
                                val chars = CharArray(count / 2) { i ->
                                    val a = bytes[i * 2].toInt() and 255
                                    val b = bytes[i * 2 + 1].toInt() and 255
                                    (if (encoding == "UTF-16le") a or (b shl 8) else (a shl 8) or b).toChar()
                                }
                                var value = pending + String(chars)
                                pending = if (offset + count < length && value.lastOrNull()?.isHighSurrogate() == true)
                                    value.takeLast(1) else ""
                                if (pending.isNotEmpty()) value = value.dropLast(1)
                                bytes = value.toByteArray(Charsets.UTF_8)
                            }
                            if (kind == 't') text(bytes) else buffered.write(bytes)
                        }
                        offset += count
                    }
                    if (kind == 't') buffered.write(34)
                }
                buffered.write(byteArrayOf(13, 10))
                row++
            }
        }
        signal.throwIfCanceled()
        buffered.flush()
    }
}
