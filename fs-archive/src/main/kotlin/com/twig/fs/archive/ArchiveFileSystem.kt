package com.twig.fs.archive

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 压缩包文件系统的公共基类:把"归档!/包内路径"的路径解析、目录树合成、读取、
 * 退回宿主目录等逻辑收敛于此。子类(zip/7z/rar)只需提供两件事:
 *  - [readEntries]:枚举归档内全部条目
 *  - [openEntry]:打开某个包内文件的输入流
 *
 * 默认只读;可写的格式(如 zip)覆写写操作。归档本身假定位于本地("file")。
 */
abstract class ArchiveFileSystem : FileSystem {

    /** 一个归档内的条目。name 以 '/' 分隔;目录的 name 以 '/' 结尾。 */
    protected data class ArchiveEntry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val time: Long,
    )

    protected abstract fun readEntries(archivePath: String): List<ArchiveEntry>

    protected abstract fun openEntry(archivePath: String, inner: String): InputStream

    /** 挂载时登记的非本地宿主(路径 → 宿主 XFile),供定位读通道与条目缓存使用。 */
    private val hosts = HashMap<String, XFile>()
    /** 远程归档的条目缓存(远程目录解析有网络成本;本地每次现读)。 */
    private val entryCache = HashMap<String, List<ArchiveEntry>>()
    /** 已解锁归档的密码(归档路径 → 密码);进程内有效,是否落盘由 app 层决定。 */
    private val passwords = HashMap<String, String>()

    /**
     * 把一个归档文件挂载为包根。宿主可以是任意来源:本地直接读文件;
     * SMB/WebDAV 等经 openRandom 定位读流式解析(zip/7z),无需整包下载。
     */
    fun rootOf(archive: XFile): XFile {
        synchronized(hosts) {
            if (archive.scheme == HOST_SCHEME) hosts.remove(archive.path)
            else hosts[archive.path] = archive
        }
        return dirXFile(archive.path, "")
    }

    // ---- 密码(加密归档) ----

    /**
     * 记下某个归档的密码;传 null 清除。**不校验**——校验走 [checkPassword],
     * 让调用方能把「密码错」与「读取失败」分开提示。
     */
    fun setPassword(archivePath: String, password: String?) {
        synchronized(passwords) {
            if (password == null) passwords.remove(archivePath) else passwords[archivePath] = password
        }
        // 头加密的格式(7z/rar)条目清单本身就是解密结果,换密码后要重新解析
        synchronized(entryCache) { entryCache.remove(archivePath) }
    }

    protected fun passwordOf(archivePath: String): String? =
        synchronized(passwords) { passwords[archivePath] }

    fun hasPassword(archivePath: String): Boolean = passwordOf(archivePath) != null

    /**
     * 该归档是否要密码才能读全内容。子类各自判断(zip 看条目的加密位,7z/rar 看头)。
     * 只读**不**改状态,可以在挂载前问。
     */
    open fun needsPassword(archivePath: String): Boolean = false

    /** 校验密码对不对(不改状态);不支持加密的格式恒 true。 */
    open fun checkPassword(archivePath: String, password: String): Boolean = true

    /** 取密码,没有就抛 [ArchivePasswordException] 让 UI 去问。 */
    protected fun requirePassword(archivePath: String): String =
        passwordOf(archivePath) ?: throw ArchivePasswordException(archivePath)

    /**
     * "这个路径上现在是哪个包"的标识(路径 + 大小 + 修改时间),给「要不要密码」这类
     * **探测结果**当缓存 key 用。同名文件被换成另一个包时缓存自动失效——探测本身
     * 要读归档头,不这么记就得每次现探。
     */
    protected fun stampOf(archivePath: String): String {
        val host = hostOf(archivePath)
        if (host.scheme != HOST_SCHEME) return "$archivePath:${host.size}:${host.lastModified}"
        val f = File(archivePath)
        return "$archivePath:${f.length()}:${f.lastModified()}"
    }

    /** 归档宿主:挂载时登记的远程 XFile,否则视为本地文件。 */
    protected fun hostOf(archivePath: String): XFile =
        synchronized(hosts) { hosts[archivePath] } ?: XFile(HOST_SCHEME, archivePath, isDir = false)

    /** 打开归档的定位读通道(本地 FileChannel / 远程 RandomSource 适配)。 */
    protected fun openChannel(archivePath: String): java.nio.channels.SeekableByteChannel {
        val host = hostOf(archivePath)
        if (host.scheme == HOST_SCHEME) {
            return java.io.RandomAccessFile(archivePath, "r").channel
        }
        val src = FsRegistry.of(host).openRandom(host)
        val size = if (host.size > 0) host.size else src.length()
        return RandomSourceChannel(src, size)
    }

    /** 取条目列表:远程归档带缓存(重复展开不重复解析),本地每次现读。 */
    protected fun entries(archivePath: String): List<ArchiveEntry> {
        if (hostOf(archivePath).scheme == HOST_SCHEME) return readEntries(archivePath)
        synchronized(entryCache) { entryCache[archivePath]?.let { return it } }
        val list = readEntries(archivePath)
        synchronized(entryCache) { entryCache[archivePath] = list }
        return list
    }

    override fun root(): XFile = throw FsException("Archive must be mounted via rootOf(archive)")

    override fun resolve(path: String): XFile {
        val archive = archiveOf(path)
        val inner = innerOf(path)
        if (inner.isEmpty()) return dirXFile(archive, "")
        val match = entries(archive).firstOrNull { normalize(it).trimEnd('/') == inner }
        return if (match != null && !match.isDir) {
            fileXFile(archive, inner, match)
        } else {
            dirXFile(archive, inner)
        }
    }

    override fun list(dir: XFile): List<XFile> {
        val archive = archiveOf(dir.path)
        val inner = innerOf(dir.path)
        val prefix = if (inner.isEmpty()) "" else "$inner/"

        val dirNames = LinkedHashSet<String>()
        val files = LinkedHashMap<String, ArchiveEntry>()

        for (e in entries(archive)) {
            val name = normalize(e)
            if (!name.startsWith(prefix) || name == prefix) continue
            val remainder = name.substring(prefix.length)
            if (remainder.isEmpty()) continue
            val slash = remainder.indexOf('/')
            if (slash >= 0) {
                dirNames.add(remainder.substring(0, slash))
            } else {
                files[remainder] = e
            }
        }

        val result = ArrayList<XFile>(dirNames.size + files.size)
        for (d in dirNames) result.add(dirXFile(archive, prefix + d))
        for ((seg, e) in files) if (seg !in dirNames) result.add(fileXFile(archive, prefix + seg, e))

        result.sortWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
        return result
    }

    override fun openInput(file: XFile): InputStream =
        openEntry(archiveOf(file.path), innerOf(file.path))

    override fun exists(file: XFile): Boolean {
        val archive = archiveOf(file.path)
        val inner = innerOf(file.path)
        if (inner.isEmpty()) {
            val host = hostOf(archive)
            return if (host.scheme == HOST_SCHEME) File(archive).exists()
            else runCatching { FsRegistry.of(host).exists(host) }.getOrDefault(false)
        }
        return entries(archive).any {
            val n = normalize(it).trimEnd('/')
            n == inner || n.startsWith("$inner/")
        }
    }

    override fun parentOf(file: XFile): XFile? {
        val archive = archiveOf(file.path)
        val inner = innerOf(file.path)
        return when {
            inner.isEmpty() -> {
                val host = hostOf(archive)
                if (host.scheme != HOST_SCHEME) return FsRegistry.of(host).parentOf(host)
                val parent = File(archive).parent ?: return null
                FsRegistry.of(HOST_SCHEME).resolve(parent)
            }
            inner.contains('/') -> dirXFile(archive, inner.substringBeforeLast('/'))
            else -> dirXFile(archive, "")
        }
    }

    // ---- 默认只读;可写格式覆写 ----

    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        throw FsException("$displayName is read-only (write not supported)")

    override fun mkdir(parent: XFile, name: String): XFile =
        throw FsException("$displayName is read-only (create not supported)")

    override fun delete(file: XFile): Unit =
        throw FsException("$displayName is read-only (delete not supported)")

    override fun rename(file: XFile, newName: String): XFile =
        throw FsException("$displayName is read-only (rename not supported)")

    // ---- 工具(子类可用) ----

    protected fun dirXFile(archive: String, inner: String) = XFile(
        scheme = scheme,
        path = "$archive$SEP$inner",
        isDir = true,
        canWrite = writable(archive),
    )

    protected fun fileXFile(archive: String, inner: String, e: ArchiveEntry) = XFile(
        scheme = scheme,
        path = "$archive$SEP$inner",
        isDir = false,
        size = if (e.size >= 0) e.size else 0L,
        lastModified = e.time,
        canWrite = writable(archive),
    )

    /** 规范化条目名:统一 '/';去掉前导 '/';目录确保以 '/' 结尾。 */
    private fun normalize(e: ArchiveEntry): String {
        var n = e.name.replace('\\', '/').removePrefix("/")
        if (e.isDir && !n.endsWith("/")) n += "/"
        return n
    }

    /**
     * 该归档实例是否支持写入,决定 [dirXFile]/[fileXFile] 的 canWrite(供 UI 置灰用)。
     * 默认只读;zip 覆写为"宿主是本地文件"(整包重写实现,远程宿主不支持)。
     */
    protected open fun writable(archivePath: String): Boolean = false

    /** 宿主能否对该条目做高效定位读(如 zip 的 STORED 条目可直接切片);默认否。 */
    open fun fastRandom(file: XFile): Boolean = false

    // 按最后一个 "!/" 切分,以支持嵌套归档(outer.zip!/inner.zip!/a.txt)
    protected fun archiveOf(path: String): String = path.substringBeforeLast(SEP)

    protected fun innerOf(path: String): String = path.substringAfterLast(SEP, "").trim('/')

    companion object {
        /** 分隔归档路径与包内路径,借鉴 JDK jar URL 语法。 */
        const val SEP = "!/"
        const val HOST_SCHEME = "file"
    }
}
