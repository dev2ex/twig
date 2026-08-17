package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 打包器:把任意来源的一批 [XFile] 压缩成一个归档,写到任意来源的目标目录。
 *
 * 与 [CopyEngine] 同一套路——源侧只用 openInput()、目标侧只用 openOutput(),
 * 所以"把 SMB 上的目录压缩到本地"这类组合天然支持,不需要 N×N 专用代码。
 * 阻塞 IO,调用方负责放到工作线程;进度/取消复用 CopyEngine 的接口,UI 那边
 * 复制与压缩因此共用同一个进度框。
 *
 * 两种格式的写入路径不同:
 *  - **zip**(java.util.zip):纯顺序写,直接串到目标的 openOutput 上,远程目标也免落地;
 *  - **7z**(commons-compress + xz):头部要回写,必须可定位写,只能先写本地文件——
 *    目标是本地就直接写目标,否则写 [tmpDir] 再整包搬过去。
 *
 * 给了密码时:zip 换成自写的 [ZipWriter](WinZip AES-256;java.util.zip 压根不支持加密),
 * 7z 用 commons-compress 自带的 AES-256。两种都**不加密文件名**——加密文件名会让别的
 * 工具连清单都列不出来,而这个功能的常见诉求只是"内容别被随便看到"。
 */
object ArchiveWriter {

    enum class Format(val ext: String) { ZIP("zip"), SEVEN_Z("7z") }

    /** 被取消(区别于真失败):调用方据此提示"已取消"而不是报错。 */
    class Cancelled : RuntimeException("Cancelled")

    /**
     * 把 [items] 打包写入 [target](调用方已用 destFs.createFile 定好的归档文件),
     * [destDir] 是 [target] 所在目录——临时包要建在这里,所以得单独给。
     *
     * **写的全程都落在同目录的 `<名字>.twigpart` 上,整包写完才顶替 [target]。**
     * 老实现直接往 [target] 上写、失败时 `delete(target)` 收尾:目标是个**已存在**的
     * 归档时(UI 弹过"覆盖 xxx?"、用户点了确定),压到一半取消或 OOM 就把用户原来那个
     * 包删了——新内容没写成,旧内容也没了。现在失败只删自己的 `.twigpart`,原包分毫不动。
     *
     * @param plannedBytes 调用方 plan() 过的源总字节,用于进度;<0 则现算。
     * @param tmpDir 7z 且目标非本地时的落地目录(传 cacheDir)。
     * @param password 非空则加密整包(AES-256);空串按不加密处理。
     * @throws Cancelled 用户取消(此时半成品已清掉,原有的同名归档仍在)。
     */
    fun compress(
        items: List<XFile>,
        destDir: XFile,
        target: XFile,
        format: Format,
        listener: CopyEngine.ProgressListener? = null,
        cancelled: CopyEngine.Cancelled = CopyEngine.Cancelled { false },
        plannedBytes: Long = -1,
        tmpDir: File? = null,
        password: String? = null,
    ) {
        if (items.isEmpty()) throw FsException("Nothing to compress")
        val pw = password?.takeIf { it.isNotEmpty() }
        val destFs = FsRegistry.of(target)
        val name = target.name
        val part = destFs.createFile(destDir, "$name$PART_SUFFIX")
        val st = State(
            listener, cancelled,
            if (plannedBytes >= 0) plannedBytes else CopyEngine.totalSize(items),
            excluded = listOf(part, target),
        )
        try {
            when (format) {
                Format.ZIP -> destFs.openOutput(part, append = false).use { out ->
                    // 不加密仍走 java.util.zip:它久经考验,没必要为了统一把老路径也换掉
                    if (pw == null) {
                        ZipOutputStream(out).use { zos -> writeAll(items, ZipSink(zos), st) }
                    } else {
                        ZipWriter(out, pw).use { zw -> writeAll(items, EncryptedZipSink(zw), st) }
                    }
                }
                Format.SEVEN_Z -> writeSevenZ(items, part, st, tmpDir, pw)
            }
        } catch (t: Throwable) {
            // 只清自己的半成品;已存在的同名归档是用户的数据,不归这里处置
            runCatching { destFs.delete(part) }
            throw t
        }
        // 内容已完整落盘,这才动目标:先删旧的(覆盖已经过 UI 确认),再改名顶上。
        // ★ 这一段失败**不做清理** —— 数据就在 .twigpart 里,留着让用户手动改名,
        //   总好过"旧的删了、新的也删了"两头空(ZipFileSystem.rewrite 踩过同款)。
        if (runCatching { destFs.exists(target) }.getOrDefault(false)) destFs.delete(target)
        destFs.rename(part, name)
        listener?.onDone()
    }

    /**
     * 7z 要可定位写:目标是本地就直接写,否则写临时文件再整包搬到目标。
     * 这里的 [target] 已经是 [compress] 建好的 `.twigpart`,不是用户看到的最终文件。
     */
    private fun writeSevenZ(items: List<XFile>, target: XFile, st: State, tmpDir: File?, password: String?) {
        val local = target.scheme == LOCAL_SCHEME
        val file = if (local) File(target.path) else File.createTempFile("twig7z", ".7z", tmpDir)
        try {
            if (local) file.delete() // RandomAccessFile 不截断,残留旧内容会写出坏包
            // 不走 SevenZOutputFile(File):它内部用 java.nio.file.Files 开通道(API 26+),
            // 这里自己给 FileChannel,minSdk 24 也能用
            RandomAccessFile(file, "rw").channel.use { ch ->
                SevenZOutputFile(ch, password?.toCharArray()).use { szo ->
                    szo.setContentMethods(listOf(LZMA2))
                    writeAll(items, SevenZSink(szo), st)
                }
            }
            if (!local) {
                val destFs = FsRegistry.of(target)
                file.inputStream().use { input ->
                    destFs.openOutput(target, append = false).use { out ->
                        CopyEngine.pipe(input, out, st.cancelled)
                    }
                }
            }
        } finally {
            if (!local) file.delete()
        }
    }

    private fun writeAll(items: List<XFile>, sink: Sink, st: State) {
        for (item in items) walk(item, "", sink, st)
    }

    private fun walk(item: XFile, prefix: String, sink: Sink, st: State) {
        if (st.cancelled.isCancelled()) throw Cancelled()
        // 压到自己身上(两个面板停在同一目录时可能发生):正在增长的 .twigpart 与它
        // 将要顶替的同名旧包都不能当源
        if (st.excluded.any { it.scheme == item.scheme && it.path == item.path }) return
        val name = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
        if (item.isDir) {
            sink.dir(name, item.lastModified)
            for (child in FsRegistry.of(item).list(item)) walk(child, name, sink, st)
            st.listener?.onItemDone(isDir = true)
        } else {
            st.listener?.onFile(item)
            sink.file(name, item, st)
            st.listener?.onItemDone(isDir = false)
        }
    }

    /** 一个条目写进归档;两种格式各自实现,walk 逻辑共用。 */
    private interface Sink {
        fun dir(name: String, time: Long)
        fun file(name: String, src: XFile, st: State)
    }

    private class ZipSink(private val zos: ZipOutputStream) : Sink {
        override fun dir(name: String, time: Long) {
            zos.putNextEntry(ZipEntry("$name/").also { if (time > 0) it.time = time })
            zos.closeEntry()
        }

        override fun file(name: String, src: XFile, st: State) {
            zos.putNextEntry(ZipEntry(name).also { if (src.lastModified > 0) it.time = src.lastModified })
            st.pump(src, zos)
            zos.closeEntry()
        }
    }

    /** 加密 zip:同一套 walk,只是换到自写的 [ZipWriter] 上。 */
    private class EncryptedZipSink(private val zw: ZipWriter) : Sink {
        override fun dir(name: String, time: Long) = zw.putDir(name, time)

        override fun file(name: String, src: XFile, st: State) {
            // sizeHint 决定这条要不要开 zip64,写之前就得定(见 ZipWriter 注释)
            zw.putNextEntry(name, src.lastModified, src.size)
            st.pump(src, zw)
            zw.closeEntry()
        }
    }

    private class SevenZSink(private val szo: SevenZOutputFile) : Sink {
        /** SevenZOutputFile 不是 OutputStream,包一层给流水线用(它自己按写入量算 size)。 */
        private val out = object : OutputStream() {
            override fun write(b: Int) = szo.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = szo.write(b, off, len)
        }

        override fun dir(name: String, time: Long) {
            szo.putArchiveEntry(entry(name, isDir = true, time = time))
            szo.closeArchiveEntry()
        }

        override fun file(name: String, src: XFile, st: State) {
            szo.putArchiveEntry(entry(name, isDir = false, time = src.lastModified))
            st.pump(src, out)
            szo.closeArchiveEntry()
        }

        private fun entry(name: String, isDir: Boolean, time: Long) = SevenZArchiveEntry().apply {
            this.name = name
            this.isDirectory = isDir
            // 7z 的时间戳走 java.nio.file.attribute.FileTime(API 26+),老设备上没有就不写
            if (time > 0) runCatching { lastModifiedDate = java.util.Date(time) }
        }
    }

    private class State(
        val listener: CopyEngine.ProgressListener?,
        val cancelled: CopyEngine.Cancelled,
        val total: Long,
        /** 不能当压缩源的条目(正在写的 .twigpart + 它将顶替的同名旧包)。 */
        val excluded: List<XFile>,
    ) {
        var bytes = 0L

        /** 源 → 归档流,进度按整任务累计;取消时抛 [Cancelled] 让整棵递归退出。 */
        fun pump(src: XFile, out: OutputStream) {
            var fileBytes = 0L
            val ok = FsRegistry.of(src).openInput(src).use { input ->
                CopyEngine.pipe(input, out, cancelled) { n ->
                    fileBytes += n
                    bytes += n
                    listener?.onFileBytes(fileBytes, src.size)
                    listener?.onBytes(bytes, total)
                }
            }
            if (!ok) throw Cancelled()
        }
    }

    private const val LOCAL_SCHEME = "file"

    /** 写作中的归档后缀;写完才改名成用户要的名字(见 [compress])。 */
    private const val PART_SUFFIX = ".twigpart"

    /**
     * 7z 的压缩方式:LZMA2,字典 4MB。**别用默认的 8MB**——LZMA2 编码器要的内存约是字典的
     * 11 倍,默认档一开就是 ~90MB Java 堆,手机上压大文件容易 OOM;4MB 档 ~46MB,压缩率
     * 只差一点点。
     */
    private val LZMA2 = org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(
        org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2,
        org.tukaani.xz.LZMA2Options().apply { dictSize = 1 shl 22 },
    )
}
