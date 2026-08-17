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
 * ZIP 文件系统。读:commons-compress ZipFile 走定位读通道——本地与远程(SMB/WebDAV)
 * 通用,远程只拉中央目录与被访问的数据段,无需整包下载;带 EFS 标志的条目名用 UTF-8,
 * 老包退 GBK。写:增/删/改通过整包重写实现(java.util.zip,仅本地归档)。
 */
class ZipFileSystem : ArchiveFileSystem() {

    override val scheme: String = SCHEME
    override val displayName: String = "Archive"

    // 写入靠整包重写(见下),只对本地宿主的归档有效;远程宿主(SMB/WebDAV 直接挂载,未整包下载)不支持。
    // 加密包一律只读:整包重写要"全解密再全加密",一次失手就是整包数据损坏,收益远不抵风险。
    override fun writable(archivePath: String): Boolean =
        hostOf(archivePath).scheme == HOST_SCHEME && !needsPassword(archivePath)

    private fun openZip(archivePath: String): org.apache.commons.compress.archivers.zip.ZipFile =
        org.apache.commons.compress.archivers.zip.ZipFile.builder()
            .setSeekableByteChannel(openChannel(archivePath))
            .setCharset(GBK) // 仅用于无 EFS 标志的老包条目名
            // 只读中央目录,不逐条 seek 本地文件头(远程包省成百上千次小读);
            // 数据偏移在 getInputStream 时按需懒解析
            .setIgnoreLocalFileHeader(true)
            .get()

    override fun readEntries(archivePath: String): List<ArchiveEntry> {
        openZip(archivePath).use { zf ->
            return zf.entries.asSequence().map {
                ArchiveEntry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.size, it.time)
            }.toList()
        }
    }

    /** (归档!/条目) → STORED 切片,null 表示条目被压缩;探测与挂载共用,免重复解析目录。 */
    private val sliceCache = HashMap<String, Pair<Long, Long>?>()

    /** STORED(未压缩)条目返回其在归档内的 (数据偏移, 长度);压缩条目返回 null。 */
    private fun storedSlice(archivePath: String, inner: String): Pair<Long, Long>? {
        val key = "$archivePath$SEP$inner"
        synchronized(sliceCache) { if (sliceCache.containsKey(key)) return sliceCache[key] }
        val slice = runCatching {
            openZip(archivePath).use { zf ->
                val e = zf.entries.asSequence()
                    .firstOrNull { !it.isDirectory && it.name.replace('\\', '/').trimEnd('/') == inner }
                // 加密条目排除在外:它在包里存的是密文,直接切片读出来的是乱码
                if (e == null || e.generalPurposeBit.usesEncryption() ||
                    e.method != java.util.zip.ZipEntry.STORED || e.size < 0
                ) {
                    null
                } else {
                    // ignoreLocalFileHeader 下数据偏移未解析,开一次流触发懒解析
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
     * STORED 条目的高效定位读:数据在归档内连续原样存放,按 dataOffset+position
     * 直接切片读取——嵌套压缩包(包中包)因此可免物化秒开。
     * 压缩(DEFLATE)条目退回默认实现(顺序解压 + 跳过)。
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
        // 加密条目 commons-compress 读不了(getInputStream 直接抛),自己解密
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

    // ---- 加密条目 ----

    /** 归档 → 第一个加密的**文件**条目名(没有则 null);判「要不要密码」与校验密码都用它。 */
    private val encCache = HashMap<String, String?>()

    private fun firstEncrypted(archivePath: String): String? {
        val key = stampOf(archivePath) // 同名文件被换掉时自动失效
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
     * 校验密码:拿第一个加密条目解一下头(AES 是 2 字节校验值,ZipCrypto 是加密头的末字节)。
     * 只读十几个字节,远程包上也几乎不花钱。
     */
    override fun checkPassword(archivePath: String, password: String): Boolean {
        val inner = firstEncrypted(archivePath) ?: return true
        // 只捕获「密码错」;包坏了、读不动是另一回事,照抛别说成密码错
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
     * 解开一个加密条目。数据段自己按本地头定位读(不走 commons-compress 的 getInputStream,
     * 它对加密条目直接抛 UnsupportedZipFeature),解密后按**真实**压缩方法解压:
     *  - WinZip AES(method 99):真实方法藏在 0x9901 extra 里;
     *  - 传统 ZipCrypto:method 字段就是真实方法,数据前 12 字节是加密头。
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
                // 有数据描述符时 CRC 尚未落进本地头,校验字节改用修改时间的高字节
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
                    java.util.zip.Inflater(true), // nowrap:zip 里是裸 deflate 流
                    8192,
                )
                else -> throw FsException("Unsupported compression method $method in ${e.name}")
            }
        } catch (t: Throwable) {
            runCatching { raw.stream.close() }
            throw t
        }
    }

    /** 一个条目的原始(未解密)数据流 + 本地头里那几样解密要用的信息。 */
    private class RawEntry(
        val stream: InputStream,
        private val extra: ByteArray,
        val dosTime: Int,
        val hasDescriptor: Boolean,
    ) {
        /** 从本地头的 extra 区里挑出某个 ID 的数据段。 */
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
     * 按中央目录记的本地头偏移解析本地头,把通道定位到数据首字节,返回限长到
     * compressedSize 的流。**extra 取本地头里的那一份**(不是中央目录的)——
     * 0x9901 两边都有,但自己解析本地头不依赖 commons-compress 对未知 extra 的处理方式。
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

    // ---- 写入(新增条目走追加,其余整包重写) ----

    override fun createFile(parent: XFile, name: String): XFile {
        val archive = archiveOf(parent.path)
        val ip = innerOf(parent.path)
        val inner = if (ip.isEmpty()) name else "$ip/$name"
        return XFile(scheme, "$archive$SEP$inner", isDir = false)
    }

    /**
     * 条目**覆写** = 整包 [rewrite] 到 `.twigtmp` 再替换原包,天然原子。
     * 新增条目走的 [appendEntry] 不在这个承诺里——它压根不动原有内容,
     * 而这个标志问的就是"覆盖自身会不会写出残片"。
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
                    // 新条目直接接到包尾(旧数据零拷贝);同名覆盖等情况退回整包重写
                    if (appendEntry(archive, inner, tmp)) return
                    rewrite(archive, addFiles = mapOf(inner to tmp)) { raw ->
                        if (cmp(raw) == inner) null else raw // 覆盖同名
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    /**
     * 往已有包里**追加**一个新条目:新条目的本地头+数据接在文件末尾,后面跟一份完整的
     * 中央目录(旧记录原样字节复制 + 新记录)和新 EOCD。**旧条目一个字节都不动**——
     * 它们压没压缩、是什么方法都无所谓,因为根本不去读。
     *
     * 这是对老实现的一次纠正:原来每加一个文件都要把整包**解压再重新压缩**一遍
     * (`rewrite` 里的 `getInputStream().copyTo(zos)`),而 `CopyEngine` 是一个文件调一次
     * [openOutput] —— 往 1GB 的包里拖 10 个小文件,得嚼 10GB。追加之后单次成本只剩
     * "写新条目 + 重写中央目录",与包体积无关。
     *
     * **为什么写在文件末尾而不是覆盖旧中央目录的位置**(那样不留垃圾):覆盖是不可回滚的
     * ——写到一半进程被杀,旧中央目录已经没了,整个包报废。接在末尾则原有字节全程不变,
     * 出错 [setLength] 截回原长度即可完好如初;真被强杀留下半截,解析器从尾部往前扫
     * EOCD 签名通常还能找到旧的那份。代价是每次追加把旧中央目录留成中间的垃圾字节
     * (典型几 KB),删除/改名那些整包重写的操作会顺手清干净。
     *
     * @return false 表示这条路走不通(包不存在 / 同名条目要覆盖 / 尾部结构读不懂),
     *   调用方退回整包重写。
     */
    private fun appendEntry(archivePath: String, inner: String, data: File): Boolean {
        if (hostOf(archivePath).scheme != HOST_SCHEME) return false // 远程包不可定位写
        val f = File(archivePath)
        if (!f.isFile || f.length() == 0L) return false
        // 覆盖同名条目要把旧的摘掉,只能整包重写
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
                raf.setLength(raf.filePointer) // 万一原包尾部后面还有别的东西
            } catch (t: Throwable) {
                raf.setLength(original) // 原有字节没动过,截回去就是原包
                throw t
            }
        }
        // 条目位置变了(中央目录挪到了后面),STORED 切片缓存作废
        synchronized(sliceCache) { sliceCache.keys.removeAll { it.startsWith(archivePath + SEP) } }
        return true
    }

    /** zip 尾部的 EOCD(必要时经 zip64 EOCD)解出来的几个数。 */
    private class EndRecord(val cdOffset: Long, val cdSize: Long, val count: Int)

    /**
     * 从文件尾往前扫 EOCD 签名(注释最长 64KB,所以扫这么多够了)。
     * 三个字段任一是 0xFFFF/0xFFFFFFFF 就说明真值在 zip64 EOCD 里,顺着 locator 去取。
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
     * 整包重写:把旧归档逐条目经 [transform](返回 null 丢弃 / 新名重命名)写入临时包,
     * 再追加 [addDirs]/[addFiles],最后替换原文件。
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
                        if (addFiles.containsKey(cmp(newName))) continue // 由新文件覆盖
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
            // 替换顺序:原包先改名成备份,新包顶上,成功了才删备份。
            // ★ 别改回 `src.delete() && tmp.renameTo(src)`:那样 delete 成功、rename 失败
            //   (存储写满、目录被改成只读、进程正好在这两行之间被杀)原包就没了,
            //   而 finally 又把新包删掉——两头空,整个压缩包彻底消失。
            val bak = File(src.parentFile, "${src.name}.twigbak")
            runCatching { bak.delete() } // 上次失败的残留
            if (src.exists() && !src.renameTo(bak)) throw FsException("Could not replace archive: $archivePath")
            if (!tmp.renameTo(src)) {
                runCatching { bak.renameTo(src) } // 顶不上就把原包还回去
                throw FsException("Could not replace archive: $archivePath")
            }
            bak.delete()
            // 归档已重写,条目偏移全部失效
            synchronized(sliceCache) { sliceCache.keys.removeAll { it.startsWith(archivePath + SEP) } }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 归一化用于比较:统一 '/',去尾部 '/'。 */
    private fun cmp(raw: String): String = raw.replace('\\', '/').trimEnd('/')

    /**
     * 选择读取条目名所用编码:优先 UTF-8;若用 UTF-8 解析抛异常(非法序列),
     * 或名字里出现替换字符,则判定为 GBK 老包。
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
        private val REPLACEMENT_CHAR = Char(0xFFFD) // 名字按 UTF-8 解码失败的占位符
    }
}
