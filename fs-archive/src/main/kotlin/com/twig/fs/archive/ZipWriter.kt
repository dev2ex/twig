package com.twig.fs.archive

import java.io.Closeable
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * 手写的 zip 写出器:**纯顺序写**(本地头 → 数据 → 数据描述符,最后中央目录 + EOCD),
 * 不需要回写,所以和 `java.util.zip.ZipOutputStream` 一样能直接串到远程目标的
 * `openOutput()` 上。存在的唯一理由是**加密**——JDK 那个不支持,而引入 zip4j 是几百 KB
 * 的 APK 增量。给了 [password] 就按 WinZip AES-256 加密每个文件条目(见 [ZipAes]),
 * 不给就是普通 zip。
 *
 * 几个约定:
 *  - 条目名一律 UTF-8(通用位标记 bit 11),中文名在任何现代解压工具里都不会乱码;
 *  - 大小/CRC 走**数据描述符**(bit 3),因此写之前不需要知道文件多大,流式即可;
 *  - 加密条目按 AE-2 写:压缩方法字段填 99、真实方法藏进 0x9901 extra、CRC 填 0;
 *  - 单条目 ≥4GB 或整包 ≥4GB / 条目数 >65535 时自动上 zip64。**只在
 *    [putNextEntry] 给出的 sizeHint ≥4GB 时才给该条目开 zip64**(本地头里要预留
 *    extra 字段,写之前就得定);hint 没给准而实际写超了 4GB 会当场抛错,不会写出坏包。
 *
 * 给了 [base] 就是**追加模式**:接着一个已有 zip 往后写,旧条目一个字节都不碰
 * (见 [Base] 与 `ZipFileSystem.appendEntry`)。
 */
class ZipWriter(
    private val raw: OutputStream,
    private val password: String? = null,
    private val base: Base? = null,
) : OutputStream(), Closeable {

    /**
     * 追加模式的底座:把新条目接到一个已有 zip 的后面。
     *
     * [offset] 是写入位置在包里的字节偏移(新条目的本地头就落在这儿),
     * [centralDirectory] 是旧包中央目录的**原始字节**——旧条目的数据没挪窝,
     * 记录里的偏移全都还成立,原样接上即可,不用重新解析每一条;
     * [entryCount] 是旧条目数(EOCD 里的总数要加上它)。
     */
    class Base(val offset: Long, val centralDirectory: ByteArray, val entryCount: Int)

    private class Item(
        val name: String,
        val isDir: Boolean,
        val time: Long,
        val offset: Long,
        val zip64: Boolean,
        val encrypted: Boolean,
    ) {
        var crc = 0L
        var csize = 0L
        var usize = 0L
    }

    private val items = ArrayList<Item>()

    /** 已写到包里的字节偏移;追加模式下从底座给的位置起算,新条目的 offset 才对得上。 */
    private var written = base?.offset ?: 0L
    private var current: Item? = null

    // 当前条目的写入管线(加密时:用户 → deflate → AES → 归档流)
    private var crc: CRC32? = null
    private var deflater: Deflater? = null
    private var pipe: OutputStream? = null
    private var aes: ZipAes.EncryptStream? = null
    private var dataStart = 0L

    /** 目录条目:空数据、STORED、不加密(没有内容可加密)。 */
    fun putDir(name: String, time: Long) {
        val n = if (name.endsWith("/")) name else "$name/"
        val item = Item(n, isDir = true, time = time, offset = written, zip64 = false, encrypted = false)
        writeLocalHeader(item, method = METHOD_STORED, useDescriptor = false)
        items.add(item)
    }

    /**
     * 开一个文件条目;随后往 [ZipWriter] 自身 write 数据,写完调 [closeEntry]。
     * [sizeHint] 是源文件大小(不知道传 -1),只用来决定要不要给这条开 zip64。
     */
    fun putNextEntry(name: String, time: Long, sizeHint: Long = -1L) {
        check(current == null) { "previous entry not closed" }
        val enc = password != null
        val item = Item(
            name = name,
            isDir = false,
            time = time,
            offset = written,
            zip64 = sizeHint >= ZIP64_LIMIT,
            encrypted = enc,
        )
        writeLocalHeader(item, method = if (enc) ZipAes.METHOD else METHOD_DEFLATE, useDescriptor = true)
        dataStart = written
        current = item
        crc = CRC32()
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap:zip 里存裸 deflate 流
        deflater = def
        // 归档流本身不能被管线关掉(后面还要写别的条目),隔一层不传递 close
        val sink: OutputStream = object : OutputStream() {
            override fun write(b: Int) = out(b)
            override fun write(b: ByteArray, off: Int, len: Int) = out(b, off, len)
        }
        val encStream = if (enc) ZipAes.EncryptStream(sink, password!!, ZipAes.STRENGTH_256) else null
        aes = encStream
        pipe = DeflaterOutputStream(encStream ?: sink, def, 8192)
    }

    override fun write(b: Int) {
        val one = ByteArray(1)
        one[0] = b.toByte()
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val item = current ?: throw IllegalStateException("no entry open")
        crc!!.update(b, off, len)
        item.usize += len
        pipe!!.write(b, off, len)
    }

    /** 收尾当前条目:冲干净管线、补认证码,再写数据描述符。 */
    fun closeEntry() {
        val item = current ?: return
        (pipe as DeflaterOutputStream).finish()
        aes?.close() // 写出认证码;不关下游
        deflater!!.end()
        item.crc = if (item.encrypted) 0L else crc!!.value // AE-2 的 CRC 字段填 0
        item.csize = written - dataStart
        if (!item.zip64 && (item.usize >= ZIP64_LIMIT || item.csize >= ZIP64_LIMIT)) {
            // 本地头里没给 zip64 留位置,再写下去就是个坏包 —— 当场停,别交出去
            throw com.twig.core.FsException("Entry exceeds 4 GB but its size was not known in advance: ${item.name}")
        }
        writeDescriptor(item)
        items.add(item)
        current = null
        crc = null
        deflater = null
        pipe = null
        aes = null
    }

    /** 写中央目录与 EOCD。**不关闭 [raw]** —— 归档流的归属在调用方。 */
    fun finish() {
        val cdOffset = written
        // 追加模式:旧记录原样接上(偏移仍然成立),再跟这次新写的
        base?.let { out(it.centralDirectory, 0, it.centralDirectory.size) }
        for (item in items) writeCentralEntry(item)
        val cdSize = written - cdOffset
        writeEnd(cdOffset, cdSize)
        raw.flush()
    }

    override fun close() {
        finish()
    }

    // ---- 记录写出 ----

    private fun writeLocalHeader(item: Item, method: Int, useDescriptor: Boolean) {
        val name = item.name.toByteArray(Charsets.UTF_8)
        val extra = localExtra(item)
        u32(0x04034b50)
        u16(if (item.zip64) 45 else 20) // 需要的版本:zip64 要 4.5
        u16(gpBits(item, useDescriptor))
        u16(method)
        u32(dosTime(item.time).toLong())
        u32(0) // crc:数据描述符里补
        u32(0) // 压缩大小
        u32(0) // 原始大小
        u16(name.size)
        u16(extra.size)
        out(name, 0, name.size)
        out(extra, 0, extra.size)
    }

    private fun writeDescriptor(item: Item) {
        u32(0x08074b50)
        u32(item.crc)
        if (item.zip64) {
            u64(item.csize)
            u64(item.usize)
        } else {
            u32(item.csize)
            u32(item.usize)
        }
    }

    private fun writeCentralEntry(item: Item) {
        val name = item.name.toByteArray(Charsets.UTF_8)
        val big = item.zip64 || item.usize >= ZIP64_LIMIT || item.csize >= ZIP64_LIMIT ||
            item.offset >= ZIP64_LIMIT
        val extra = centralExtra(item, big)
        u32(0x02014b50)
        u16(if (big) 45 else 20) // 创建版本
        u16(if (big) 45 else 20) // 需要的版本
        u16(gpBits(item, useDescriptor = !item.isDir))
        u16(if (item.encrypted) ZipAes.METHOD else if (item.isDir) METHOD_STORED else METHOD_DEFLATE)
        u32(dosTime(item.time).toLong())
        u32(item.crc)
        u32(if (big) 0xFFFFFFFFL else item.csize)
        u32(if (big) 0xFFFFFFFFL else item.usize)
        u16(name.size)
        u16(extra.size)
        u16(0) // 注释
        u16(0) // 磁盘号
        u16(0) // 内部属性
        u32(if (item.isDir) 0x10L else 0L) // 外部属性:目录位
        u32(if (big) 0xFFFFFFFFL else item.offset)
        out(name, 0, name.size)
        out(extra, 0, extra.size)
    }

    private fun writeEnd(cdOffset: Long, cdSize: Long) {
        val count = items.size + (base?.entryCount ?: 0)
        val need64 = count > 0xFFFF || cdOffset >= ZIP64_LIMIT || cdSize >= ZIP64_LIMIT
        if (need64) {
            val rec = written
            u32(0x06064b50)
            u64(44) // 本记录剩余长度
            u16(45)
            u16(45)
            u32(0)
            u32(0)
            u64(count.toLong())
            u64(count.toLong())
            u64(cdSize)
            u64(cdOffset)
            u32(0x07064b50) // locator
            u32(0)
            u64(rec)
            u32(1)
        }
        u32(0x06054b50)
        u16(0)
        u16(0)
        u16(if (need64) 0xFFFF else count)
        u16(if (need64) 0xFFFF else count)
        u32(if (need64) 0xFFFFFFFFL else cdSize)
        u32(if (need64) 0xFFFFFFFFL else cdOffset)
        u16(0)
    }

    /** 通用位标记:bit0 加密、bit3 数据描述符、bit11 名字是 UTF-8。 */
    private fun gpBits(item: Item, useDescriptor: Boolean): Int {
        var bits = 1 shl 11
        if (item.encrypted) bits = bits or 1
        if (useDescriptor) bits = bits or (1 shl 3)
        return bits
    }

    private fun localExtra(item: Item): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (item.zip64) {
            // 大小走数据描述符,这里只是把位置占住(规范要求本地头的 zip64 块含这两个字段)
            out.write(shortLe(0x0001)); out.write(shortLe(16))
            out.write(ByteArray(16))
        }
        if (item.encrypted) {
            val data = ZipAes.buildExtra(ZipAes.STRENGTH_256, METHOD_DEFLATE)
            out.write(shortLe(ZipAes.EXTRA_ID)); out.write(shortLe(data.size))
            out.write(data)
        }
        return out.toByteArray()
    }

    private fun centralExtra(item: Item, big: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (big) {
            // 顺序固定:原始大小 → 压缩大小 → 本地头偏移(只写在中央目录里被置成 0xFFFFFFFF 的那些)
            val body = java.io.ByteArrayOutputStream()
            body.write(longLe(item.usize))
            body.write(longLe(item.csize))
            body.write(longLe(item.offset))
            val data = body.toByteArray()
            out.write(shortLe(0x0001)); out.write(shortLe(data.size))
            out.write(data)
        }
        if (item.encrypted) {
            val data = ZipAes.buildExtra(ZipAes.STRENGTH_256, METHOD_DEFLATE)
            out.write(shortLe(ZipAes.EXTRA_ID)); out.write(shortLe(data.size))
            out.write(data)
        }
        return out.toByteArray()
    }

    // ---- 字节输出 ----

    private fun out(b: Int) {
        raw.write(b)
        written++
    }

    private fun out(b: ByteArray, off: Int, len: Int) {
        raw.write(b, off, len)
        written += len
    }

    private fun u16(v: Int) {
        out(v and 0xff)
        out((v shr 8) and 0xff)
    }

    private fun u32(v: Long) {
        out((v and 0xff).toInt())
        out(((v shr 8) and 0xff).toInt())
        out(((v shr 16) and 0xff).toInt())
        out(((v shr 24) and 0xff).toInt())
    }

    private fun u64(v: Long) {
        u32(v and 0xFFFFFFFFL)
        u32((v ushr 32) and 0xFFFFFFFFL)
    }

    private fun shortLe(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun longLe(v: Long) = ByteArray(8) { ((v ushr (it * 8)) and 0xff).toByte() }

    private companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8
        const val ZIP64_LIMIT = 0xFFFFFFFFL

        /** 毫秒时间戳 → DOS 日期时间;超出 DOS 能表示的范围(1980 前)按 1980-01-01。 */
        fun dosTime(millis: Long): Int {
            if (millis <= 0L) return DOS_EPOCH
            val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
            val year = c.get(java.util.Calendar.YEAR)
            if (year < 1980) return DOS_EPOCH
            return ((year - 1980) shl 25) or
                ((c.get(java.util.Calendar.MONTH) + 1) shl 21) or
                (c.get(java.util.Calendar.DAY_OF_MONTH) shl 16) or
                (c.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
                (c.get(java.util.Calendar.MINUTE) shl 5) or
                (c.get(java.util.Calendar.SECOND) shr 1)
        }

        const val DOS_EPOCH = (1 shl 21) or (1 shl 16)
    }
}
