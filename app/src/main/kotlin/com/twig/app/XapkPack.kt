package com.twig.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration
import java.util.zip.CRC32

/**
 * 把「base.apk + 各 split apk + manifest.json」现拼成一个 XAPK(APKPure 格式,本质是 zip)流。
 *
 * 全部条目用 **STORED**(不压缩):apk 内容本身已经压缩过,再压几乎不省空间,
 * 而 STORED 换来一个关键好处 —— **总长度不读文件就能精确算出**([totalSize]),
 * `XFile.size` 因此是准的,复制进度条、剩余时间、目标端的空间校验全都照常工作。
 * (DEFLATE 则要么先整包压一遍才知道大小,要么进度条全程瞎猜。)
 *
 * 代价是 STORED 的 local header 必须提前写对 CRC,所以 [open] 时会把每个 apk 读一遍
 * 算 CRC 再开始吐数据 —— 本地 /data/app 读取很快,且结果按「路径:大小:修改时间」缓存,
 * 同一个应用第二次复制不再重算。
 *
 * 不使用 zip64:单个 apk 与总大小都远小于 4GB(超出时 [totalSize] 会溢出成负数,
 * 由 [AppsFileSystem] 侧回落成只给 base.apk)。
 */
class XapkPack(private val entries: List<Entry>) {

    /** zip 内的一个条目:名字 + 内容(内存字节 或 磁盘文件)。 */
    class Entry(val name: String, val bytes: ByteArray?, val file: File?) {
        val nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8)
        val size: Long = bytes?.size?.toLong() ?: file?.length() ?: 0L
        val time: Long = file?.lastModified() ?: System.currentTimeMillis()
        fun open(): InputStream = bytes?.let { ByteArrayInputStream(it) } ?: FileInputStream(file!!)
    }

    /** 打包后的字节数(不读文件内容即可算出;见类注释)。 */
    fun totalSize(): Long {
        var n = 0L
        for (e in entries) {
            n += LFH + e.nameBytes.size + e.size // 本地头 + 文件名 + 数据
            n += CDH + e.nameBytes.size // 中央目录项
        }
        return n + EOCD
    }

    /**
     * 打开整包的读取流。先把各条目 CRC 算好(见类注释),再按
     * 「本地头₁+数据₁ … 中央目录 EOCD」惰性拼接 —— 文件按需打开,不会同时占住一堆 fd。
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

    /** zip 的 DOS 时间/日期(各 2 字节);1980 年以前一律钳到 1980-01-01。 */
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
        private const val LFH = 30 // 本地文件头固定部分
        private const val CDH = 46 // 中央目录项固定部分
        private const val EOCD = 22 // 目录结束记录

        /** apk 内容不变(路径+大小+修改时间相同)就不重算 CRC。 */
        private val crcCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
