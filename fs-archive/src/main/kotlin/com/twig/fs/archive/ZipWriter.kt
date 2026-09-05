package com.twig.fs.archive

import java.io.Closeable
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Handwritten zip writer: **strictly sequential** (local header -> data -> data descriptor,
 * then central directory + EOCD), with no seeking back, so it can be piped straight onto the
 * remote target's `openOutput()` the same way `java.util.zip.ZipOutputStream` can. Its only reason
 * for existing is **encryption** — the JDK one does not support it, and pulling in zip4j would
 * add hundreds of KB to the APK. Pass [password] to encrypt each file entry with WinZip AES-256
 * (see [ZipAes]); omit it for a plain zip.
 *
 * Conventions:
 *  - Entry names are always UTF-8 (general purpose bit 11), so Chinese names show correctly in
 *    any modern unarchiver;
 *  - Size/CRC go through the **data descriptor** (bit 3), so the writer does not need to know
 *    the file size up front — streaming is fine;
 *  - Encrypted entries follow AE-2: compression method field set to 99, real method hidden in the
 *    0x9901 extra, CRC set to 0;
 *  - Single entry >=4 GB or whole archive >=4 GB / entry count >65535 auto-promotes to zip64.
 *    **zip64 is only enabled for an entry when its sizeHint at [putNextEntry] is >=4 GB**
 *    (the local header has to reserve the extra field, decided before writing); if the hint was
 *    too small but the actual write exceeds 4 GB, an error is thrown immediately — no corrupt archive.
 *
 * Passing [base] switches to **append mode**: write after an existing zip, never touching any of
 * the old entries' bytes (see [Base] and `ZipFileSystem.appendEntry`).
 */
class ZipWriter(
    private val raw: OutputStream,
    private val password: String? = null,
    private val base: Base? = null,
) : OutputStream(), Closeable {

    /**
     * Append-mode base: a new entry to be appended after an existing zip.
     *
     * [offset] is the byte offset in the archive where writing begins (the new entry's local
     * header lands here),
     * [centralDirectory] is the **raw bytes** of the old archive's central directory — the old
     * entries' data has not moved, so the offsets in the records are still valid and we can
     * just splice them in without re-parsing each one;
     * [entryCount] is the old entry count (EOCD's total needs to add it).
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

    /** Bytes written so far into the archive; in append mode starts at the base's offset, so new entries' offsets line up. */
    private var written = base?.offset ?: 0L
    private var current: Item? = null

    // Current entry's write pipeline (encrypted: user -> deflate -> AES -> archive stream)
    private var crc: CRC32? = null
    private var deflater: Deflater? = null
    private var pipe: OutputStream? = null
    private var aes: ZipAes.EncryptStream? = null
    private var dataStart = 0L

    /** Directory entry: empty data, STORED, not encrypted (nothing to encrypt). */
    fun putDir(name: String, time: Long) {
        val n = if (name.endsWith("/")) name else "$name/"
        val item = Item(n, isDir = true, time = time, offset = written, zip64 = false, encrypted = false)
        writeLocalHeader(item, method = METHOD_STORED, useDescriptor = false)
        items.add(item)
    }

    /**
     * Open a file entry; then write the entry's data into [ZipWriter] itself, and call [closeEntry] when done.
     * [sizeHint] is the source file size (pass -1 if unknown); it only decides whether to enable zip64 for this entry.
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
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap: zip stores raw deflate streams
        deflater = def
        // The archive stream itself must not be closed by the pipeline (more entries follow), so wrap
        // it in a layer that does not propagate close
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

    /** Finish the current entry: flush the pipeline, append the auth code, then write the data descriptor. */
    fun closeEntry() {
        val item = current ?: return
        (pipe as DeflaterOutputStream).finish()
        aes?.close() // write the auth code; do not close downstream
        deflater!!.end()
        item.crc = if (item.encrypted) 0L else crc!!.value // AE-2 sets CRC field to 0
        item.csize = written - dataStart
        if (!item.zip64 && (item.usize >= ZIP64_LIMIT || item.csize >= ZIP64_LIMIT)) {
            // The local header did not reserve room for zip64, so writing further would produce a corrupt archive — stop now
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

    /** Write the central directory and EOCD. **Does not close [raw]** — the archive stream belongs to the caller. */
    fun finish() {
        val cdOffset = written
        // Append mode: splice in the old records verbatim (their offsets are still valid), then add the newly written ones
        base?.let { out(it.centralDirectory, 0, it.centralDirectory.size) }
        for (item in items) writeCentralEntry(item)
        val cdSize = written - cdOffset
        writeEnd(cdOffset, cdSize)
        raw.flush()
    }

    override fun close() {
        finish()
    }

    // ---- record writes ----

    private fun writeLocalHeader(item: Item, method: Int, useDescriptor: Boolean) {
        val name = item.name.toByteArray(Charsets.UTF_8)
        val extra = localExtra(item)
        u32(0x04034b50)
        u16(if (item.zip64) 45 else 20) // required version: zip64 needs 4.5
        u16(gpBits(item, useDescriptor))
        u16(method)
        u32(dosTime(item.time).toLong())
        u32(0) // crc: filled in by the data descriptor
        u32(0) // compressed size
        u32(0) // original size
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
        u16(if (big) 45 else 20) // made-by version
        u16(if (big) 45 else 20) // required version
        u16(gpBits(item, useDescriptor = !item.isDir))
        u16(if (item.encrypted) ZipAes.METHOD else if (item.isDir) METHOD_STORED else METHOD_DEFLATE)
        u32(dosTime(item.time).toLong())
        u32(item.crc)
        u32(if (big) 0xFFFFFFFFL else item.csize)
        u32(if (big) 0xFFFFFFFFL else item.usize)
        u16(name.size)
        u16(extra.size)
        u16(0) // comment
        u16(0) // disk number
        u16(0) // internal attributes
        u32(if (item.isDir) 0x10L else 0L) // external attributes: directory bit
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
            u64(44) // remaining length of this record
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

    /** General purpose bit flags: bit 0 encrypted, bit 3 data descriptor, bit 11 name is UTF-8. */
    private fun gpBits(item: Item, useDescriptor: Boolean): Int {
        var bits = 1 shl 11
        if (item.encrypted) bits = bits or 1
        if (useDescriptor) bits = bits or (1 shl 3)
        return bits
    }

    private fun localExtra(item: Item): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (item.zip64) {
            // Sizes go through the data descriptor; here we only reserve the slot (the spec requires the
            // local header's zip64 block to include those two fields)
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
            // Fixed order: original size -> compressed size -> local header offset (only written for the ones the central directory set to 0xFFFFFFFF)
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

    // ---- byte output ----

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

        /** Millisecond timestamp -> DOS date/time; outside the range DOS can represent (pre-1980) -> 1980-01-01. */
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
