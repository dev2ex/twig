package com.twig.core

import java.io.InputStream
import java.io.OutputStream

/**
 * 一个"文件系统提供者"。本地、压缩包、FTP、SMB、各云盘各实现一份。
 *
 * 这是整个项目的地基:UI 只跟 [FileSystem] + [XFile] 打交道,
 * 跨来源的复制/移动由 [CopyEngine] 通过 openInput/openOutput 统一完成,
 * 因此新增一种来源 = 新增一个 FileSystem 实现,UI 与拷贝逻辑零改动。
 *
 * 实现约定:
 *  - 所有方法都是阻塞 IO,调用方负责放到工作线程。
 *  - 失败抛 [FsException],不要返回 null 吞错误。
 */
interface FileSystem {

    /** 该文件系统的 scheme,需与 [XFile.scheme] 对应且全局唯一。 */
    val scheme: String

    /** 人类可读名称,用于侧栏展示,如 "内部存储" / "FTP"。 */
    val displayName: String

    /** 该文件系统的根条目。 */
    fun root(): XFile

    /** 取某个路径对应的条目(用于导航/校验);不存在抛 [FsException]。 */
    fun resolve(path: String): XFile

    /** 列出目录下的条目;[dir] 必须 isDir。 */
    fun list(dir: XFile): List<XFile>

    /** 打开读取流;调用方负责关闭。 */
    fun openInput(file: XFile): InputStream

    /**
     * 打开写入流;若文件不存在则创建,存在则按 [append] 决定追加或覆盖。
     * 调用方负责关闭。
     */
    fun openOutput(file: XFile, append: Boolean = false): OutputStream

    /** 在 [parent] 下创建子目录并返回其 [XFile]。 */
    fun mkdir(parent: XFile, name: String): XFile

    /**
     * 为"将要写入"的新文件在 [parent] 下确定目标 [XFile],供 [CopyEngine] 随后 openOutput。
     * 默认实现走路径拼接(file/ftp/zip 等真正写入时按需创建即可);
     * 像 SAF 这类必须先 createDocument 才能得到 URI 的实现需覆盖此方法。
     */
    fun createFile(parent: XFile, name: String): XFile {
        val sep = if (parent.path.endsWith("/")) "" else "/"
        return XFile(scheme = scheme, path = "${parent.path}$sep$name", isDir = false)
    }

    /** 删除文件或目录(目录递归删除)。 */
    fun delete(file: XFile): Unit

    /**
     * 重命名(同目录改名);返回新条目。
     *
     * **同名目标已存在时必须抛 [FsException],不得静默覆盖。**
     * 这条要显式写下来,是因为几个底层 API 的默认行为恰恰相反:POSIX `rename(2)`
     * (`File.renameTo`)会原子替换已有目标,WebDAV 的 `MOVE` 也可以带 `Overwrite: T`——
     * 照抄默认值就会让"改个名字"无声吃掉另一个文件。要覆盖的话由调用方先删再改名,
     * 那样至少经过了一次明确的用户确认。
     */
    fun rename(file: XFile, newName: String): XFile

    /** 该条目是否存在。 */
    fun exists(file: XFile): Boolean

    /**
     * 这整个文件系统是否支持写入(与具体条目的 [XFile.canWrite] 是两回事——
     * 后者由 resolve()/list() 现算,一些调用点(如收藏夹)会绕过它直接拼 XFile,
     * 此时仍需靠这个"按 scheme 固定"的判断兜底)。
     * restic/7z/RAR/git 视图这类整个来源都只读的覆写为 false;默认 true。
     */
    fun writable(): Boolean = true

    /**
     * [openOutput] 覆盖写自身是否已经原子(要么完整换成新内容,要么原文件分毫不动)。
     * 默认 false——绝大多数实现是"截断原文件再往里写",写到一半断网/断电就只剩残片。
     * 原地保存(如文本编辑器写回)因此要走"写临时文件 → 删原 → rename"。
     * zip 覆写条目本来就是整包重写到临时文件再替换,已经原子,覆写为 true 免掉
     * 额外两次整包重写。
     */
    fun atomicOverwrite(): Boolean = false

    /**
     * [openRandom] 的 readAt 是否为"真随机访问"(定位读代价与位置无关)。
     * SMB(pread)/WebDAV(HTTP Range)= true;FTP/SFTP 及默认实现只能"重开跳过"
     * (代价 O(位置))= false。缩略图对非 MP4 容器(MKV/AVI 等,没有可离线解析的
     * 采样表)靠把一个真随机访问的数据源交给 MediaMetadataRetriever、让它自己
     * 解封装 + seek——只在此为 true 时才这么做,否则乱 seek 会把整个文件拖下来。
     */
    fun randomAccessEfficient(): Boolean = false

    /**
     * 打开一个支持"定位读"的源(用于媒体播放器随机 seek)。
     * 默认实现基于 [openInput] + 重开跳过(对不支持定位的来源可用但较慢);
     * 像 SMB 这种支持定位读(pread)的实现应覆盖此方法以获得高效 seek。
     */
    fun openRandom(file: XFile): RandomSource = object : RandomSource {
        private var input: java.io.InputStream? = null
        private var pos = -1L
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position != pos || input == null) {
                runCatching { input?.close() }
                val ins = openInput(file)
                var skipped = 0L
                while (skipped < position) {
                    val k = ins.skip(position - skipped)
                    if (k <= 0) { if (ins.read() < 0) break else skipped++ } else skipped += k
                }
                input = ins; pos = position
            }
            val n = input!!.read(buffer, offset, length)
            if (n > 0) pos += n
            return n
        }
        override fun length(): Long = file.size
        override fun close() { runCatching { input?.close() } }
    }

    /**
     * 求父目录;已在该文件系统顶层时返回 null。
     * 默认实现走 [XFile.parentPath];像 zip 这种带 "!/" 边界、
     * 或需要在顶层"跳回宿主文件系统"的实现可覆盖此方法。
     */
    fun parentOf(file: XFile): XFile? {
        if (file.path == "/" || file.path.isEmpty()) return null
        return resolve(file.parentPath)
    }

    /**
     * 同一文件系统内部的高效移动(可选优化)。
     * 返回 true 表示已就地完成;返回 false 表示不支持,交由 [CopyEngine] 走"拷贝+删除"。
     * 默认不支持。
     */
    fun moveWithin(src: XFile, destDir: XFile, newName: String = src.name): Boolean = false

    /**
     * 复制后把源的修改时间写回目标(可选优化)。返回 true = 已设置;false = 不支持或失败,
     * [CopyEngine] 按尽力而为处理,不视为复制失败——目标就留着"刚写入"那一刻的时间。
     *
     * 默认不支持。已实现:本地(直接 setLastModified)、SFTP(setattr 写 mtime)、
     * FTP(MFMT 命令,RFC 3659,老服务器可能不认,失败不报错)。**未实现**:
     * SMB(libsmb2 的 JNI 封装目前没有暴露 utime,需要改 native 代码)、WebDAV(没有
     * 通用标准,各服务器扩展不一致)、压缩包写入(zip/7z 的条目时间要在写入 entry
     * 那一刻就定好,不适合"写完再补"这种事后调用的接口)。
     */
    fun setModifiedTime(file: XFile, time: Long): Boolean = false
}

/** 文件系统操作异常的统一类型。 */
class FsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 支持按位置读取的源(媒体播放器随机 seek 用)。 */
interface RandomSource : java.io.Closeable {
    /** 从 [position] 读最多 [length] 字节到 [buffer];返回读取字节数,末尾返回 -1。 */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    /** 总长度;未知返回 <=0。 */
    fun length(): Long
}
