package com.twig.app.ui

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 测试用的内存文件系统:注册进 [com.twig.core.FsRegistry] 就能冒充一台服务器。
 *
 * 为什么需要它——`revealPath` / `resolveFavorite` 的网络分支要"连上某台服务器再逐级
 * 展开",真跑的话得有 FTP/SMB 服务端。而这两条链路本身的逻辑(反查连接、展开分组与
 * 服务器节点、按路径逐级下钻)跟对面是什么协议毫无关系,拿假的来跑正好把它们隔离出来。
 *
 * ★ 提前把它注册到目标 scheme 上,[com.twig.app.Connections.ensure] 开头那句
 * "已注册就复用"就会直接返回,不会去 new 真的 FtpFileSystem。
 */
class FakeFileSystem(
    override val scheme: String,
    /** 目录路径 → 子项名字;文件不在这里出现(见 [files])。 */
    private val dirs: Map<String, List<String>>,
    /** 文件路径 → 内容。 */
    private val files: Map<String, String> = emptyMap(),
    /** 文件路径 → mtime(毫秒);没给的按 0。对比功能要按时间判定,需要能精确摆布。 */
    private val times: Map<String, Long> = emptyMap(),
) : FileSystem {

    override val displayName: String = "Fake($scheme)"

    /** 记录 list 被调过哪些路径,便于断言"确实逐级展开过"。 */
    val listed = mutableListOf<String>()

    /** 记录哪些文件被真的读过,便于断言"该省的读取确实省掉了"。 */
    val opened = mutableListOf<String>()

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = when {
        dirs.containsKey(path) -> XFile(scheme, path, isDir = true)
        files.containsKey(path) -> XFile(
            scheme, path, isDir = false,
            size = files.getValue(path).length.toLong(),
            lastModified = times[path] ?: 0L,
        )
        else -> throw FsException("no such path: $path")
    }

    override fun list(dir: XFile): List<XFile> {
        listed += dir.path
        val kids = dirs[dir.path] ?: throw FsException("no such directory: ${dir.path}")
        val prefix = if (dir.path.endsWith("/")) dir.path else "${dir.path}/"
        return kids.map { resolve("$prefix$it") }
    }

    override fun openInput(file: XFile): InputStream {
        opened += file.path
        return ByteArrayInputStream((files[file.path] ?: throw FsException("no such file")).toByteArray())
    }

    override fun exists(file: XFile): Boolean =
        dirs.containsKey(file.path) || files.containsKey(file.path)

    // ---- 只读:这些路径在被测的链路上不会走到 ----
    override fun writable(): Boolean = false
    override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("read-only")
    override fun mkdir(parent: XFile, name: String): XFile = throw FsException("read-only")
    override fun delete(file: XFile) = throw FsException("read-only")
    override fun rename(file: XFile, newName: String): XFile = throw FsException("read-only")
}
