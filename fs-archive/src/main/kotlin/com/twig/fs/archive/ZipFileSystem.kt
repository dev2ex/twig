package com.twig.fs.archive

import com.twig.core.FsException
import com.twig.core.XFile
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ZIP filesystem. Read: commons-compress ZipFile goes through a seekable channel — works for
 * local and remote (SMB/WebDAV) alike; remote only fetches the central directory and the data
 * ranges actually accessed, with no whole-archive download. Entry names use UTF-8 when the EFS
 * flag is set; old archives fall back to GBK. Write: add/delete/modify are done by rewriting the
 * whole archive (java.util.zip, local archives only).
 */
class ZipFileSystem : ArchiveFileSystem() {

    override val scheme: String = SCHEME
    override val displayName: String = "Archive"

    // Writes rely on full-archive rewriting (see below), so they only work for archives hosted
    // locally; remote hosts (SMB/WebDAV mounted without downloading the whole archive) are not
    // supported. Encrypted archives are always read-only: rewriting the whole archive requires
    // "decrypt everything then re-encrypt everything" — a single slip corrupts the whole archive,
    // and the risk is nowhere near the payoff.
    override fun writable(archivePath: String): Boolean =
        hostOf(archivePath).scheme == HOST_SCHEME && !needsPassword(archivePath)

    private fun openZip(archivePath: String): org.apache.commons.compress.archivers.zip.ZipFile =
        org.apache.commons.compress.archivers.zip.ZipFile.builder()
            .setSeekableByteChannel(openChannel(archivePath))
            .setCharset(GBK) // only used for entry names of old archives without the EFS flag
            // Only read the central directory; do not seek each local file header (saves hundreds
            // to thousands of small reads on remote archives). Data offsets are parsed lazily on demand
            // inside getInputStream.
            .setIgnoreLocalFileHeader(true)
            .get()

    override fun readEntries(archivePath: String): List<ArchiveEntry> {
        openZip(archivePath).use { zf ->
            return zf.entries.asSequence().map {
                ArchiveEntry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.size, it.time)
            }.toList()
        }
    }

    /** (archive!/entry) -> STORED slice; null means the entry is compressed. Shared by probing and mounting so the directory is not re-parsed. */
    private val sliceCache = HashMap<String, Pair<Long, Long>?>()

    /** For a STORED (uncompressed) entry returns its (data offset, length) inside the archive; for a compressed entry returns null. */
    private fun storedSlice(archivePath: String, inner: String): Pair<Long, Long>? {
        val key = "$archivePath$SEP$inner"
        synchronized(sliceCache) { if (sliceCache.containsKey(key)) return sliceCache[key] }
        val slice = runCatching {
            openZip(archivePath).use { zf ->
                val e = zf.entries.asSequence()
                    .firstOrNull { !it.isDirectory && it.name.replace('\\', '/').trimEnd('/') == inner }
                // Encrypted entries are excluded: they store ciphertext in the archive, so slicing
                // would read out garbage.
                if (e == null || e.generalPurposeBit.usesEncryption() ||
                    e.method != java.util.zip.ZipEntry.STORED || e.size < 0
                ) {
                    null
                } else {
                    // With ignoreLocalFileHeader the data offset is not yet parsed; open the stream once to trigger lazy parsing
                    if (e.dataOffset < 0) runCatching { zf.getInputStream(e)?.close() }
                    if (e.dataOffset >= 0) e.dataOffset to e.size else null
                }
            }
        }.getOrNull()
        synchronized(sliceCache) { sliceCache[key] = slice }
        return slice
    }

    override fun fastRandom(file: XFile): Boolean =
        storedSlice(archiveOf(file.path), innerOf(file.path)) != null

    /**
     * Efficient random access for STORED entries: the data sits inside the archive unchanged,
     * so we just slice-read at dataOffset + position — that is why nested archives (archives
     * inside archives) open without materialization.
     * Compressed (DEFLATE) entries fall back to the default implementation (sequential decompress + skip).
     */
    override fun openRandom(file: XFile): com.twig.core.RandomSource {
        val archive = archiveOf(file.path)
        val (off, len) = storedSlice(archive, innerOf(file.path)) ?: return super.openRandom(file)
        val ch = openChannel(archive)
        return object : com.twig.core.RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= len) return -1
                val want = minOf(length.toLong(), len - position).toInt()
                val bb = java.nio.ByteBuffer.wrap(buffer, offset, want)
                synchronized(ch) {
                    ch.position(off + position)
                    return ch.read(bb)
                }
            }

            override fun length(): Long = len

            override fun close() {
                runCatching { ch.close() }
            }
        }
    }

    override fun openEntry(archivePath: String, inner: String): InputStream {
        val zf = openZip(archivePath)
        val entry = zf.entries.asSequence()
            .firstOrNull { !it.isDirectory && it.name.replace('\\', '/').trimEnd('/') == inner }
        if (entry == null) {
            zf.close()
            throw FsException("No such file in archive: $inner")
        }
        // commons-compress cannot read encrypted entries (getInputStream throws outright); decrypt ourselves
        if (entry.generalPurposeBit.usesEncryption()) {
            zf.close()
            return openEncrypted(archivePath, entry, requirePassword(archivePath))
        }
        return object : FilterInputStream(zf.getInputStream(entry)) {
            override fun close() {
                try { super.close() } finally { zf.close() }
            }
        }
    }

    // ---- encrypted entries ----

    /** Archive -> first encrypted **file** entry name (null if none); used both for "needs password?" and password verification. */
    private val encCache = HashMap<String, String?>()

    private fun firstEncrypted(archivePath: String): String? {
        val key = stampOf(archivePath) // automatically invalidated when a same-named file is swapped out
        synchronized(encCache) { if (encCache.containsKey(key)) return encCache[key] }
        val name = runCatching {
            openZip(archivePath).use { zf ->
                zf.entries.asSequence()
                    .firstOrNull { !it.isDirectory && it.generalPurposeBit.usesEncryption() }
                    ?.name
            }
        }.getOrNull()
        synchronized(encCache) { encCache[key] = name }
        return name
    }

    override fun needsPassword(archivePath: String): Boolean = firstEncrypted(archivePath) != null

    /**
     * Verify the password: decrypt the first encrypted entry's head (2-byte check for AES, last byte
     * of the encryption header for ZipCrypto). Only a dozen bytes; cheap even on remote archives.
     */
    override fun checkPassword(archivePath: String, password: String): Boolean {
        val inner = firstEncrypted(archivePath) ?: return true
        // Only catch "wrong password"; a broken / unreadable archive is a different matter, so rethrow instead of calling it a wrong password
        return try {
            openZip(archivePath).use { zf ->
                val e = zf.entries.asSequence().first { it.name == inner }
                openEncrypted(archivePath, e, password).close()
            }
            true
        } catch (t: ArchivePasswordException) {
            false
        }
    }

    /**
     * Open an encrypted entry. The data segment is read at a known offset via the local header
     * (does not use commons-compress's getInputStream, which throws UnsupportedZipFeature outright
     * for encrypted entries), then decrypted and decompressed with the **actual** compression method:
     *  - WinZip AES (method 99): the real method is hidden inside the 0x9901 extra;
     *  - traditional ZipCrypto: the method field is the real method; the first 12 bytes are the encryption header.
     */
    private fun openEncrypted(
        archivePath: String,
        e: org.apache.commons.compress.archivers.zip.ZipArchiveEntry,
        password: String,
    ): InputStream {
        val raw = openRawData(archivePath, e)
        try {
            val aes = if (e.method == ZipAes.METHOD) {
                ZipAes.parseExtra(raw.extraOf(ZipAes.EXTRA_ID) ?: ByteArray(0))
                    ?: throw FsException("Malformed AES header in archive entry: ${e.name}")
            } else {
                null
            }
            val plain: InputStream = if (aes != null) {
                ZipAes.DecryptStream(raw.stream, password, aes.first, e.compressedSize, archivePath)
            } else {
                // When a data descriptor is present the CRC is not yet in the local header, so use
                // the high byte of the modification time as the check byte
                val check = if (raw.hasDescriptor) {
                    ZipCrypto.timeCheckByte(raw.dosTime)
                } else {
                    ZipCrypto.crcCheckByte(e.crc)
                }
                ZipCrypto.open(raw.stream, password, e.compressedSize, check, archivePath)
            }
            val method = aes?.second ?: e.method
            return when (method) {
                java.util.zip.ZipEntry.STORED -> plain
                java.util.zip.ZipEntry.DEFLATED -> java.util.zip.InflaterInputStream(
                    plain,
                    java.util.zip.Inflater(true), // nowrap: zip stores raw deflate streams
                    8192,
                )
                else -> throw FsException("Unsupported compression method $method in ${e.name}")
            }
        } catch (t: Throwable) {
            runCatching { raw.stream.close() }
            throw t
        }
    }

    /** Raw (undecrypted) data stream for an entry plus the few local-header fields the decryption needs. */
    private class RawEntry(
        val stream: InputStream,
        private val extra: ByteArray,
        val dosTime: Int,
        val hasDescriptor: Boolean,
    ) {
        /** Pick out the data segment with the given ID from the local header's extra area. */
        fun extraOf(id: Int): ByteArray? {
            var p = 0
            while (p + 4 <= extra.size) {
                val hid = (extra[p].toInt() and 0xff) or ((extra[p + 1].toInt() and 0xff) shl 8)
                val len = (extra[p + 2].toInt() and 0xff) or ((extra[p + 3].toInt() and 0xff) shl 8)
                if (p + 4 + len > extra.size) return null
                if (hid == id) return extra.copyOfRange(p + 4, p + 4 + len)
                p += 4 + len
            }
            return null
        }
    }

    /**
     * Parse the local header by the offset the central directory records, position the channel
     * at the first data byte, and return a stream limited to compressedSize.
     * **extra is taken from the local header** (not the central one) — 0x9901 exists on both sides,
     * but parsing the local header ourselves avoids depending on how commons-compress handles unknown extras.
     */
    private fun openRawData(
        archivePath: String,
        e: org.apache.commons.compress.archivers.zip.ZipArchiveEntry,
    ): RawEntry {
        val ch = openChannel(archivePath)
        try {
            val head = ByteArray(LOCAL_HEADER_LEN)
            readAt(ch, e.localHeaderOffset, head)
            if (le32(head, 0) != 0x04034b50L) throw FsException("Bad local header for ${e.name}")
            val flags = le16(head, 6)
            val dosTime = le32(head, 10).toInt()
            val nameLen = le16(head, 26)
            val extraLen = le16(head, 28)
            val extra = ByteArray(extraLen)
            if (extraLen > 0) readAt(ch, e.localHeaderOffset + LOCAL_HEADER_LEN + nameLen, extra)
            val start = e.localHeaderOffset + LOCAL_HEADER_LEN + nameLen + extraLen
            ch.position(start)
            val limited = object : InputStream() {
                private var left = e.compressedSize
                private val one = ByteArray(1)

                override fun read(): Int {
                    val n = read(one, 0, 1)
                    return if (n < 0) -1 else one[0].toInt() and 0xff
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0L) return -1
                    val want = minOf(len.toLong(), left).toInt()
                    val n = ch.read(java.nio.ByteBuffer.wrap(b, off, want))
                    if (n <= 0) return -1
                    left -= n
                    return n
                }

                override fun close() {
                    ch.close()
                }
            }
            return RawEntry(limited, extra, dosTime, hasDescriptor = flags and (1 shl 3) != 0)
        } catch (t: Throwable) {
            runCatching { ch.close() }
            throw t
        }
    }

    private fun readAt(ch: java.nio.channels.SeekableByteChannel, pos: Long, buf: ByteArray) {
        ch.position(pos)
        val bb = java.nio.ByteBuffer.wrap(buf)
        while (bb.hasRemaining()) {
            if (ch.read(bb) <= 0) throw FsException("Unexpected end of archive")
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)

    // ---- writes (new entries go through append, the rest rewrite the whole archive) ----

    override fun createFile(parent: XFile, name: String): XFile {
        val archive = archiveOf(parent.path)
        val ip = innerOf(parent.path)
        val inner = if (ip.isEmpty()) name else "$ip/$name"
        return XFile(scheme, "$archive$SEP$inner", isDir = false)
    }

    /**
     * Entry **overwrite** = rewrite the whole archive via [rewrite] to `.twigtmp` then replace the
     * original, atomic by construction.
     * New entries go through [appendEntry], which does not have that promise — it does not touch
     * existing bytes at all, while the flag is asking "will an overwrite leave a fragment behind".
     */
    override fun atomicOverwrite(): Boolean = true

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        val archive = archiveOf(file.path)
        val inner = innerOf(file.path)
        val tmp = File.createTempFile("twigzip", null)
        return object : FileOutputStream(tmp) {
            override fun close() {
                super.close()
                try {
                    // New entries are appended at the tail (zero-copy of the old data); same-name overwrites etc. fall back to full rewrite
                    if (appendEntry(archive, inner, tmp)) return
                    rewrite(archive, addFiles = mapOf(inner to tmp)) { raw ->
                        if (cmp(raw) == inner) null else raw // overwrite same name
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    /**
     * **Append** a new entry to an existing archive: the new entry's local header + data are
     * tacked onto the file end, followed by a full central directory (the old records copied
     * byte-for-byte + the new one) and a new EOCD. **Not a single byte of the old entries is
     * touched** — whether they are compressed or what method they use does not matter, because
     * we never read them.
     *
     * This corrects an earlier implementation: the old code would **decompress and recompress
     * the whole archive** for every added file (the `getInputStream().copyTo(zos)` in `rewrite`),
     * and `CopyEngine` calls [openOutput] once per file — drag 10 small files into a 1 GB
     * archive and you chew through 10 GB. After appending, the per-call cost is only
     * "write the new entry + rewrite the central directory", independent of archive size.
     *
     * **Why write at the end of the file instead of overwriting the old central directory's
     * position** (which would not leave garbage): overwriting is not rollbackable — if the
     * process is killed mid-write, the old central directory is already gone and the archive
     * is wasted. Appending leaves the original bytes untouched; on error, [setLength] back to
     * the original length restores the archive intact; even a forced kill that leaves a tail
     * fragment usually still finds the old EOCD by scanning back from the end. The price is that
     * each append leaves the old central directory as garbage bytes in the middle (typically a
     * few KB), which delete/rename operations (whole-archive rewrite) clean up along the way.
     *
     * @return false means this path is not available (archive does not exist / same-name entry
     *   to be overwritten / tail structure unreadable), and the caller falls back to whole-archive rewrite.
     */
    private fun appendEntry(archivePath: String, inner: String, data: File): Boolean {
        if (hostOf(archivePath).scheme != HOST_SCHEME) return false // remote archives do not support positional writes
        val f = File(archivePath)
        if (!f.isFile || f.length() == 0L) return false
        // Overwriting a same-name entry means removing the old one, which only whole-archive rewrite can do
        if (entries(archivePath).any { cmp(it.name) == inner }) return false
        val end = readEnd(f) ?: return false
        val original = f.length()

        java.io.RandomAccessFile(f, "rw").use { raf ->
            val cd = ByteArray(end.cdSize.toInt())
            raf.seek(end.cdOffset)
            raf.readFully(cd)
            try {
                raf.seek(original)
                val sink = object : OutputStream() {
                    override fun write(b: Int) = raf.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
                }
                ZipWriter(sink, base = ZipWriter.Base(original, cd, end.count)).use { zw ->
                    zw.putNextEntry(inner, data.lastModified(), data.length())
                    data.inputStream().use { it.copyTo(zw, 1 shl 16) }
                    zw.closeEntry()
                }
                raf.setLength(raf.filePointer) // in case there is something after the original archive tail
            } catch (t: Throwable) {
                raf.setLength(original) // the original bytes have not been touched, truncating back gives the original archive
                throw t
            }
        }
        // Entry positions have changed (central directory moved to the back), so the STORED slice cache is invalidated
        synchronized(sliceCache) { sliceCache.keys.removeAll { it.startsWith(archivePath + SEP) } }
        return true
    }

    /** A few numbers decoded from the EOCD at the zip tail (going through zip64 EOCD if needed). */
    private class EndRecord(val cdOffset: Long, val cdSize: Long, val count: Int)

    /**
     * Scan back from the file end for the EOCD signature (the comment is at most 64KB, so
     * scanning that much is enough).
     * If any of the three fields is 0xFFFF / 0xFFFFFFFF the real value lives in the zip64 EOCD;
     * follow the locator to fetch it.
     */
    private fun readEnd(f: File): EndRecord? = runCatching {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val scan = minOf(len, 0xFFFFL + EOCD_LEN)
            val buf = ByteArray(scan.toInt())
            raf.seek(len - scan)
            raf.readFully(buf)
            var p = buf.size - EOCD_LEN
            while (p >= 0 && le32(buf, p) != 0x06054b50L) p--
            if (p < 0) return@runCatching null

            var count = le16(buf, p + 10)
            var cdSize = le32(buf, p + 12)
            var cdOffset = le32(buf, p + 16)
            if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                val loc = p - ZIP64_LOCATOR_LEN
                if (loc < 0 || le32(buf, loc) != 0x07064b50L) return@runCatching null
                val recOffset = le64(buf, loc + 8)
                val rec = ByteArray(56)
                raf.seek(recOffset)
                raf.readFully(rec)
                if (le32(rec, 0) != 0x06064b50L) return@runCatching null
                count = le64(rec, 32).toInt()
                cdSize = le64(rec, 40)
                cdOffset = le64(rec, 48)
            }
            if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > len) return@runCatching null
            EndRecord(cdOffset, cdSize, count)
        }
    }.getOrNull()

    private fun le64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    override fun mkdir(parent: XFile, name: String): XFile {
        val archive = archiveOf(parent.path)
        val ip = innerOf(parent.path)
        val dirInner = if (ip.isEmpty()) name else "$ip/$name"
        rewrite(archive, addDirs = setOf(dirInner)) { it }
        return dirXFile(archive, dirInner)
    }

    override fun delete(file: XFile) {
        val archive = archiveOf(file.path)
        val inner = innerOf(file.path)
        rewrite(archive) { raw ->
            val t = cmp(raw)
            if (t == inner || t.startsWith("$inner/")) null else raw
        }
    }

    override fun rename(file: XFile, newName: String): XFile {
        val archive = archiveOf(file.path)
        val inner = innerOf(file.path)
        val parent = if (inner.contains('/')) inner.substringBeforeLast('/') + "/" else ""
        val newInner = parent + newName
        rewrite(archive) { raw ->
            val t = cmp(raw)
            val trailing = if (raw.endsWith("/")) "/" else ""
            when {
                t == inner -> newInner + trailing
                t.startsWith("$inner/") -> newInner + t.substring(inner.length) + trailing
                else -> raw
            }
        }
        return file.copy(path = "$archive$SEP$newInner")
    }

    /**
     * Whole-archive rewrite: stream the old archive entry by entry through [transform] (returning
     * null to drop, or a new name to rename) into a temp archive, append [addDirs]/[addFiles],
     * then replace the original file.
     */
    private fun rewrite(
        archivePath: String,
        addDirs: Set<String> = emptySet(),
        addFiles: Map<String, File> = emptyMap(),
        transform: (String) -> String?,
    ) {
        val cs = detectCharset(archivePath)
        val src = File(archivePath)
        val tmp = File(src.parentFile, "${src.name}.twigtmp")
        try {
            ZipOutputStream(tmp.outputStream(), cs).use { zos ->
                ZipFile(src, cs).use { zf ->
                    val en = zf.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        val newName = transform(e.name) ?: continue
                        if (addFiles.containsKey(cmp(newName))) continue // new file takes over
                        val ne = ZipEntry(newName).apply { time = e.time }
                        zos.putNextEntry(ne)
                        if (!e.isDirectory) zf.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                for (d in addDirs) {
                    zos.putNextEntry(ZipEntry(if (d.endsWith("/")) d else "$d/"))
                    zos.closeEntry()
                }
                for ((name, f) in addFiles) {
                    zos.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            // Replace order: rename the original to a backup first, then put the new one on top,
            // and only delete the backup once that succeeds.
            // ★ Do not change back to `src.delete() && tmp.renameTo(src)`: if delete succeeds and
            //   rename fails (storage full, directory flipped to read-only, process killed between
            //   those two lines), the original is gone, and `finally` then deletes the new one too —
            //   empty on both sides, the archive vanishes.
            val bak = File(src.parentFile, "${src.name}.twigbak")
            runCatching { bak.delete() } // leftover from a previous failed run
            if (src.exists() && !src.renameTo(bak)) throw FsException("Could not replace archive: $archivePath")
            if (!tmp.renameTo(src)) {
                runCatching { bak.renameTo(src) } // put the original back if the swap failed
                throw FsException("Could not replace archive: $archivePath")
            }
            bak.delete()
            // Archive has been rewritten, so all entry offsets are invalid
            synchronized(sliceCache) { sliceCache.keys.removeAll { it.startsWith(archivePath + SEP) } }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Normalize for comparison: unify '/', drop trailing '/'. */
    private fun cmp(raw: String): String = raw.replace('\\', '/').trimEnd('/')

    /**
     * Choose the encoding used to read entry names: prefer UTF-8; if UTF-8 parsing throws
     * (illegal sequence), or the name contains a replacement character, decide it is an old GBK archive.
     */
    private fun detectCharset(archivePath: String): Charset {
        return try {
            ZipFile(File(archivePath), Charsets.UTF_8).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    if (en.nextElement().name.any { it == REPLACEMENT_CHAR }) return GBK
                }
            }
            Charsets.UTF_8
        } catch (e: Exception) {
            GBK
        }
    }

    companion object {
        const val SCHEME = "zip"
        private const val LOCAL_HEADER_LEN = 30
        private const val EOCD_LEN = 22
        private const val ZIP64_LOCATOR_LEN = 20
        private val GBK: Charset = Charset.forName("GBK")
        private val REPLACEMENT_CHAR = Char(0xFFFD) // placeholder used when a name fails to decode as UTF-8
    }
}
