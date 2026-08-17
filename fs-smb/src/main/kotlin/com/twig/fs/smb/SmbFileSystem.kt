package com.twig.fs.smb

import com.twig.core.FileSystem
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream

/**
 * SMB/CIFS 文件系统,基于 libsmb2(原生)。一个实例对应一个已连接的共享。
 *
 * 路径:XFile.path 以 '/' 开头(我们的统一约定),传给 libsmb2 时去掉前导 '/'(其共享根为 "")。
 * 上传/下载/解压到 SMB 全部经 [CopyEngine] 的 openInput/openOutput,本类无需额外代码。
 */
class SmbFileSystem(
    private val config: SmbConfig,
    /** 支持同时挂载多台服务器:每个实例一个唯一 scheme(如 "smb1a2b")。 */
    override val scheme: String = SCHEME,
) : FileSystem {

    private val client = NativeSmbClient()

    override val displayName: String = "SMB (${config.host}/${config.share})"

    /** 建立连接;应在工作线程调用(挂载前)。 */
    fun connect() = client.connect(config)

    /** 协商的 SMB 版本名(如 "SMB3.1.1");未知/未连接返回 null。 */
    fun dialectName(): String? = when (client.dialect()) {
        0x0202 -> "SMB2.0"
        0x0210 -> "SMB2.1"
        0x0300 -> "SMB3.0"
        0x0302 -> "SMB3.0.2"
        0x0311 -> "SMB3.1.1"
        else -> null
    }

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> =
        client.list(smbPath(dir.path))
            .map { e ->
                XFile(
                    scheme = scheme,
                    path = join(dir.path, e.name),
                    isDir = e.isDir,
                    size = if (e.isDir) 0L else e.size,
                    lastModified = e.mtimeSec * 1000L,
                )
            }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })

    override fun openInput(file: XFile): InputStream = client.openInput(smbPath(file.path))

    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        client.openOutput(smbPath(file.path)) // append 不支持,按覆盖处理

    override fun mkdir(parent: XFile, name: String): XFile {
        val path = join(parent.path, name)
        client.mkdir(smbPath(path))
        return XFile(scheme, path, isDir = true)
    }

    override fun delete(file: XFile) {
        if (file.isDir) {
            for (child in list(file)) delete(child)
            client.delete(smbPath(file.path), isDir = true)
        } else {
            client.delete(smbPath(file.path), isDir = false)
        }
    }

    override fun rename(file: XFile, newName: String): XFile {
        val to = join(file.parentPath, newName)
        client.rename(smbPath(file.path), smbPath(to))
        return file.copy(path = to)
    }

    override fun randomAccessEfficient(): Boolean = true // pread 定位读,与位置无关

    override fun openRandom(file: XFile): com.twig.core.RandomSource {
        // 媒体随机读用一条“专用连接”:与浏览连接隔离,收尾时只 destroy_context(不走会崩溃的 close/logoff 路径)。
        val dedicated = NativeSmbClient()
        dedicated.connect(config)
        val fh = dedicated.openReadHandle(smbPath(file.path))
        if (fh == 0L) {
            runCatching { dedicated.closeHard() }
            throw com.twig.core.FsException("Open failed: ${file.name}")
        }
        return object : com.twig.core.RandomSource {
            // 只把"真读到 0 字节"翻译成 EOF(-1);读失败由 pread 抛异常,不再伪装成 EOF
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                dedicated.pread(fh, position, buffer, offset, length).let { if (it == 0) -1 else it }
            override fun length(): Long = file.size
            override fun close() = dedicated.closeHard() // 只销毁 context,连 fh 一起释放
        }
    }

    override fun exists(file: XFile): Boolean =
        runCatching {
            val parent = file.parentPath
            client.list(smbPath(parent)).any { join(parent, it.name) == file.path }
        }.getOrDefault(false)

    fun disconnect() = client.close()

    private fun smbPath(p: String): String = p.removePrefix("/")

    private fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    companion object {
        const val SCHEME = "smb"
    }
}
