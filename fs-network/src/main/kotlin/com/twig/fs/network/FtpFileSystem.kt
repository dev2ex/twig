package com.twig.fs.network

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 把一个 FTP 服务器当作文件系统来浏览(Phase 3)。
 *
 * 连接模型:离散操作(list/mkdir/delete/rename)用短连接即连即断;
 * 流式读写(openInput/openOutput)在流关闭时收尾(completePendingCommand + 断开),
 * 保证 FTP 数据连接被正确终结。后续可加连接池优化。
 *
 * 上传/下载/解压到 FTP 全部由 [CopyEngine] 经 openInput/openOutput 完成,本类无需额外代码。
 */
class FtpFileSystem(
    private val config: FtpConfig,
    override val scheme: String = SCHEME,
) : FileSystem {

    override val displayName: String = "FTP (${config.host})"

    override fun root(): XFile = dir("/")

    override fun resolve(path: String): XFile = dir(path) // 导航用,目录乐观构造

    override fun list(dir: XFile): List<XFile> = withClient { c ->
        listRaw(c, dir.path).asSequence()
            .map { it to baseName(it.name) }
            .filter { (_, n) -> n.isNotEmpty() && n != "." && n != ".." }
            .map { (f, n) -> toXFile(dir.path, f, n) }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
            .toList()
    }

    override fun openInput(file: XFile): InputStream {
        val c = connect()
        val stream = c.retrieveFileStream(file.path)
        if (stream == null) {
            quietClose(c)
            throw FsException("Cannot read: ${file.path} (${c.replyString})")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { c.completePendingCommand() }
                    quietClose(c)
                }
            }
        }
    }

    // FTP 靠 REST(restart offset)+ RETR 做定位读:服务端直接从指定偏移开始发,不发
    // 前面的字节(字节层面高效,只传所需 ~2MB)。代价是"跳位"要重开一条数据传输——
    // FTP 流不能在流内 seek。所以缩略图对 MKV/mp4 能走精确路径(否则退化成只取时间 0
    // 黑图),只是每次跳位重连一条连接,略慢于 SMB/SFTP。
    override fun randomAccessEfficient(): Boolean = true

    override fun openRandom(file: XFile): RandomSource = object : RandomSource {
        private var c: FTPClient? = null
        private var stream: InputStream? = null
        private var pos = -1L

        /** 跳到 [position]:拆掉旧连接(免去 completePendingCommand 在半传输时卡住的坑),
         * 新连接上 REST + RETR 从该偏移起读。连续读(pos 吻合)不会走到这里。 */
        private fun openAt(position: Long) {
            close()
            val cc = connect()
            cc.restartOffset = position
            val s = cc.retrieveFileStream(file.path)
            if (s == null) { quietClose(cc); throw FsException("FTP random read failed: ${cc.replyString}") }
            c = cc
            stream = s
            pos = position
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position != pos || stream == null) openAt(position)
            val n = stream!!.read(buffer, offset, length)
            if (n > 0) pos += n
            return n
        }

        override fun length(): Long = file.size

        override fun close() {
            runCatching { stream?.close() }
            stream = null
            c?.let { quietClose(it) } // 直接断开,不 completePendingCommand(半传输时会卡)
            c = null
        }
    }

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        val c = connect()
        val stream = if (append) c.appendFileStream(file.path) else c.storeFileStream(file.path)
        if (stream == null) {
            quietClose(c)
            throw FsException("Cannot write: ${file.path} (${c.replyString})")
        }
        return object : FilterOutputStream(stream) {
            // FilterOutputStream 默认逐字节写,这里直接转发以保证吞吐
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { c.completePendingCommand() }
                    quietClose(c)
                }
            }
        }
    }

    override fun mkdir(parent: XFile, name: String): XFile = withClient { c ->
        val path = join(parent.path, name)
        if (!c.makeDirectory(path)) throw FsException("Could not create directory: $path (${c.replyString})")
        dir(path)
    }

    override fun delete(file: XFile) = withClient { c ->
        deleteRecursive(c, file)
    }

    override fun rename(file: XFile, newName: String): XFile = withClient { c ->
        val to = join(file.parentPath, newName)
        if (!c.rename(file.path, to)) throw FsException("Rename failed (${c.replyString})")
        file.copy(path = to)
    }

    // 老实现是 `listFiles(path) 非空`:空目录会被判成不存在,忽略 LIST 参数的服务端更是
    // 一律判成存在。改走 CWD(目录)/ SIZE(文件),都不支持才退回列父目录比名字。
    override fun exists(file: XFile): Boolean = withClient { c ->
        if (c.changeWorkingDirectory(file.path)) return@withClient true
        if (runCatching { c.sendCommand("SIZE", file.path) }.getOrDefault(-1) == 213) {
            return@withClient true
        }
        val name = file.name
        runCatching { listRaw(c, file.parentPath) }.getOrDefault(emptyArray())
            .any { baseName(it.name) == name }
    }

    /** MFMT(RFC 3659),按 UTC 报时间;老服务器不认这个命令,失败当"不支持"处理不报错。 */
    override fun setModifiedTime(file: XFile, time: Long): Boolean = runCatching {
        // SimpleDateFormat 非线程安全,每次现建一个——这条路径不算热路径,没必要池化
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        withClient { c -> c.setModificationTime(file.path, fmt.format(java.util.Date(time))) }
    }.getOrDefault(false)

    // ---- 内部 ----

    /**
     * 列目录的兼容实现:**先 CWD 进目录,再发不带参数的 LIST**。
     *
     * 曾经直接 `LIST <绝对路径>`,根目录正常、再往下展开却总是空的——因为不少 FTP 服务端
     * (嵌入式设备、路由器/NAS 固件、安卓端的 FTP 共享 App)对带路径参数的 LIST 支持很差:
     * 要么答 550、要么干脆忽略参数;非 ASCII 目录名遇上编码不一致时同样定位不到。这些情况
     * commons-net 一律只返回**空数组**(不抛异常),UI 上就表现成"能展开一级,再展开是空的"。
     * CWD 由服务端自己解析路径,兼容性最好,也顺带把"目录不存在/没权限"变成明确的失败。
     *
     * 兜底顺序:CWD 成功 → 无参 LIST;CWD 失败 → 退回老的带参 LIST;再空就**抛错**
     * (带上 replyString),不再静默当成空目录——空目录与失败必须能区分开。
     */
    private fun listRaw(c: FTPClient, path: String): Array<FTPFile> {
        if (c.changeWorkingDirectory(path)) {
            val files = c.listFiles()
            // LIST 传完是 226/250;不是正向完成说明数据连接没建起来(PASV 被 NAT/防火墙挡等),
            // 此时 commons-net 也只给空数组,得报出来而不是假装目录是空的
            if (files.isNullOrEmpty() && !FTPReply.isPositiveCompletion(c.replyCode)) {
                throw FsException("List failed: $path (${c.replyString})")
            }
            return files ?: emptyArray()
        }
        val cwdFail = c.replyString
        val byArg = c.listFiles(path)
        if (byArg.isNullOrEmpty()) throw FsException("Cannot enter directory: $path ($cwdFail)")
        return byArg
    }

    /** 有的服务端在 LIST 结果里给的是全路径,取末段当名字。 */
    private fun baseName(name: String): String = name.trimEnd('/').substringAfterLast('/')

    private fun deleteRecursive(c: FTPClient, file: XFile) {
        if (file.isDir) {
            val children = listRaw(c, file.path)
            for (f in children) {
                val n = baseName(f.name)
                if (n.isEmpty() || n == "." || n == "..") continue
                deleteRecursive(c, toXFile(file.path, f, n))
            }
            // 列子项时 CWD 进去了,有的服务端不许删当前工作目录,先退出来
            runCatching { c.changeWorkingDirectory("/") }
            if (!c.removeDirectory(file.path)) {
                throw FsException("Could not delete directory: ${file.path} (${c.replyString})")
            }
        } else if (!c.deleteFile(file.path)) {
            throw FsException("Could not delete file: ${file.path} (${c.replyString})")
        }
    }

    private fun <T> withClient(block: (FTPClient) -> T): T {
        val c = connect()
        try {
            return block(c)
        } finally {
            quietClose(c)
        }
    }

    private fun connect(): FTPClient {
        val c = FTPClient()
        c.controlEncoding = config.encoding
        try {
            c.connect(config.host, config.port)
        } catch (e: Exception) {
            throw FsException("Cannot connect to ${config.host}:${config.port}", e)
        }
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            runCatching { c.disconnect() }
            throw FsException("FTP refused the connection: ${c.replyString}")
        }
        if (!c.login(config.user, config.password)) {
            runCatching { c.disconnect() }
            throw FsException("FTP login failed")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        return c
    }

    private fun quietClose(c: FTPClient) {
        runCatching { if (c.isConnected) c.logout() }
        runCatching { if (c.isConnected) c.disconnect() }
    }

    private fun toXFile(parentPath: String, f: FTPFile, name: String = baseName(f.name)) = XFile(
        scheme = scheme,
        path = join(parentPath, name),
        isDir = f.isDirectory,
        size = if (f.isDirectory) 0L else f.size,
        lastModified = f.timestamp?.timeInMillis ?: 0L,
    )

    private fun dir(path: String) = XFile(scheme = scheme, path = path, isDir = true)

    private fun join(dir: String, name: String): String =
        when {
            dir.endsWith("/") -> "$dir$name"
            else -> "$dir/$name"
        }

    companion object {
        const val SCHEME = "ftp"
    }
}
