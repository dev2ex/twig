package com.twig.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration
import java.util.zip.CRC32

/**
 * Stitches "base.apk + each split apk + manifest.json" into a single XAPK
 * (APKPure's format, a zip at heart) stream.
 *
 * All entries are written as **STORED** (uncompressed): apk content is
 * already compressed, so recompressing saves almost nothing, and STORED has
 * one crucial benefit — the **total length is computable without reading the
 * files** ([totalSize]), so `XFile.size` is accurate and the copy progress
 * bar, the time-remaining estimate, and the destination's space check all
 * work normally. (DEFLATE, by contrast, would either have to compress the
 * whole bundle up front to know the size, or let the progress bar guess
 * blindly the whole time.)
 *
 * The trade-off is that STORED's local header requires a correct CRC up
 * front, so [open] reads each apk once to compute its CRC before producing
 * any data — local reads from /data/app are fast, and the result is cached
 * by "path:size:mtime" so the same app on a second copy is not recomputed.
 *
 * zip64 is not used: individual apks and the total are well under 4 GB
 * (if they weren't, [totalSize] would overflow into a negative number, and
 * [AppsFileSystem] would fall back to offering only base.apk).
 */
class XapkPack(private val entries: List<Entry>) {

    /** One entry in the zip: name + content (in-memory bytes or a file on disk). */
    class Entry(val name: String, val bytes: ByteArray?, val file: File?) {
        val nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8)
        val size: Long = bytes?.size?.toLong() ?: file?.length() ?: 0L
        val time: Long = file?.lastModified() ?: System.currentTimeMillis()
        fun open(): InputStream = bytes?.let { ByteArrayInputStream(it) } ?: FileInputStream(file!!)
    }

    /** The number of bytes in the packed bundle (computable without reading file contents; see class note). */
    fun totalSize(): Long {
        var n = 0L
        for (e in entries) {
            n += LFH + e.nameBytes.size + e.size // local header + filename + data
            n += CDH + e.nameBytes.size // central directory entry
        }
        return n + EOCD
    }

    /**
     * Opens the read stream for the whole bundle. First computes every entry's
     * CRC (see class note), then lazily stitches them as
     * "local-header₁ + data₁ … central directory + EOCD" — files are opened
     * on demand, so we never hold a pile of fds at once.
     */
    fun open(): InputStream {
        val crcs = entries.map { crcOf(it) }
        val parts = ArrayList<Entry>(entries.size * 2 + 1)
        var offset = 0L
        val central = ArrayList<ByteArray>(entries.size)
        for ((i, e) in entries.withIndex()) {
            parts += Entry("", localHeader(e, crcs[i]), null)
            parts += e
            central += centralHeader(e, crcs[i], offset)
            offset += LFH + e.nameBytes.size + e.size
        }
        val cdSize = central.sumOf { it.size }.toLong()
        central.forEach { parts += Entry("", it, null) }
        parts += Entry("", eocd(entries.size, cdSize, offset), null)

        val it = parts.iterator()
        return SequenceInputStream(object : Enumeration<InputStream> {
            override fun hasMoreElements(): Boolean = it.hasNext()
            override fun nextElement(): InputStream = it.next().open()
        })
    }

    private fun localHeader(e: Entry, crc: Long): ByteArray {
        val b = ByteArray(LFH + e.nameBytes.size)
        var p = 0
        p = put32(b, p, 0x04034b50)
        p = put16(b, p, 20) // version needed
        p = put16(b, p, 0) // flags
        p = put16(b, p, 0) // method: stored
        p = putDosTime(b, p, e.time)
        p = put32(b, p, crc)
        p = put32(b, p, e.size)
        p = put32(b, p, e.size)
        p = put16(b, p, e.nameBytes.size)
        p = put16(b, p, 0) // extra len
        e.nameBytes.copyInto(b, p)
        return b
    }

    private fun centralHeader(e: Entry, crc: Long, offset: Long): ByteArray {
        val b = ByteArray(CDH + e.nameBytes.size)
        var p = 0
        p = put32(b, p, 0x02014b50)
        p = put16(b, p, 20) // version made by
        p = put16(b, p, 20) // version needed
        p = put16(b, p, 0)
        p = put16(b, p, 0) // stored
        p = putDosTime(b, p, e.time)
        p = put32(b, p, crc)
        p = put32(b, p, e.size)
        p = put32(b, p, e.size)
        p = put16(b, p, e.nameBytes.size)
        p = put16(b, p, 0) // extra
        p = put16(b, p, 0) // comment
        p = put16(b, p, 0) // disk
        p = put16(b, p, 0) // internal attrs
        p = put32(b, p, 0) // external attrs
        p = put32(b, p, offset)
        e.nameBytes.copyInto(b, p)
        return b
    }

    private fun eocd(count: Int, cdSize: Long, cdOffset: Long): ByteArray {
        val b = ByteArray(EOCD)
        var p = 0
        p = put32(b, p, 0x06054b50)
        p = put16(b, p, 0) // disk
        p = put16(b, p, 0) // cd start disk
        p = put16(b, p, count)
        p = put16(b, p, count)
        p = put32(b, p, cdSize)
        p = put32(b, p, cdOffset)
        put16(b, p, 0) // comment len
        return b
    }

    private fun crcOf(e: Entry): Long {
        e.bytes?.let { return CRC32().apply { update(it) }.value }
        val f = e.file ?: return 0L
        val key = "${f.path}:${f.length()}:${f.lastModified()}"
        crcCache[key]?.let { return it }
        val crc = CRC32()
        val buf = ByteArray(1 shl 16)
        FileInputStream(f).use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value.also { crcCache[key] = it }
    }

    private fun put16(b: ByteArray, p: Int, v: Int): Int {
        b[p] = (v and 0xff).toByte()
        b[p + 1] = ((v ushr 8) and 0xff).toByte()
        return p + 2
    }

    private fun put32(b: ByteArray, p: Int, v: Long): Int {
        b[p] = (v and 0xff).toByte()
        b[p + 1] = ((v ushr 8) and 0xff).toByte()
        b[p + 2] = ((v ushr 16) and 0xff).toByte()
        b[p + 3] = ((v ushr 24) and 0xff).toByte()
        return p + 4
    }

    private fun put32(b: ByteArray, p: Int, v: Int): Int = put32(b, p, v.toLong() and 0xffffffffL)

    /** The zip DOS time/date (2 bytes each); anything before 1980 is clamped to 1980-01-01. */
    private fun putDosTime(b: ByteArray, p: Int, millis: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val year = c.get(java.util.Calendar.YEAR)
        if (year < 1980) {
            val q = put16(b, p, 0)
            return put16(b, q, 33) // 1980-01-01
        }
        val time = (c.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (c.get(java.util.Calendar.MINUTE) shl 5) or
            (c.get(java.util.Calendar.SECOND) / 2)
        val date = ((year - 1980) shl 9) or
            ((c.get(java.util.Calendar.MONTH) + 1) shl 5) or
            c.get(java.util.Calendar.DAY_OF_MONTH)
        val q = put16(b, p, time)
        return put16(b, q, date)
    }

    companion object {
        private const val LFH = 30 // fixed part of the local file header
        private const val CDH = 46 // fixed part of a central directory entry
        private const val EOCD = 22 // end-of-central-directory record

        /** If an apk's content is unchanged (same path + size + mtime), skip recomputing the CRC. */
        private val crcCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
