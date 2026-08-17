package com.twig.fs.local

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import com.twig.fs.local.priv.PrivilegedFs
import com.twig.fs.local.priv.PrivilegedShell
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 本地存储文件系统,基于 java.io.File。
 *
 * Phase 1 直接用 File API(需运行时存储权限)。
 * Phase 1.5 计划:Android 10+ 在无 MANAGE_EXTERNAL_STORAGE 时回退到 SAF/DocumentFile,
 * 届时只需新增一个 SafFileSystem 注册到另一 scheme,或在此内部分流,UI 无感知。
 *
 * @param rootDir 该文件系统暴露的根目录(默认外部存储根)。
 */
class LocalFileSystem(
    private val rootDir: File = File("/"),
    override val displayName: String = "Local storage",
) : FileSystem {

    override val scheme: String = SCHEME

    override fun root(): XFile = toXFile(rootDir)

    override fun resolve(path: String): XFile {
        val f = File(path)
        if (!f.exists()) {
            if (maybeHidden(f)) elevation?.stat(path)?.let { return it }
            throw FsException("No such path: $path")
        }
        return toXFile(f)
    }

    override fun list(dir: XFile): List<XFile> {
        val f = File(dir.path)
        // isDirectory needs a successful stat(2); on a path we may traverse but not
        // inspect it comes back false, which is indistinguishable from "not a
        // directory". Ask the elevated shell before believing it.
        if (!f.isDirectory) {
            elevated(dir.path)?.let { return sorted(it) }
            throw FsException("Not a directory: ${dir.path}")
        }
        val children = f.listFiles()
            ?: return elevated(dir.path)?.let { sorted(it) }
                ?: throw FsException("Cannot read directory (permission?): ${dir.path}")
        return sorted(children.map { toXFile(it) })
    }

    /** 目录在前、再按名称不区分大小写排序——文件管理器的常规排序 */
    private fun sorted(items: List<XFile>): List<XFile> = items
        .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })

    private fun elevated(path: String): List<XFile>? = elevation?.list(path)

    override fun openInput(file: XFile): InputStream =
        runCatching { File(file.path).inputStream() }.getOrElse { e ->
            elevation?.openInput(file.path) ?: throw e
        }

    /**
     * 写入的通知时机是**流关闭**,不是打开——媒体库要的是写完之后的那个文件
     * (打开时长度还是 0,扫出来是一条无效记录)。
     * `FilterOutputStream` 的 `write(ByteArray,Int,Int)` 默认逐字节转发,必须覆盖掉,
     * 否则每次拷贝都退化成一字节一次系统调用。
     */
    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        val f = File(file.path)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val out = runCatching { java.io.FileOutputStream(f, append) as OutputStream }.getOrElse { e ->
            val priv = elevation ?: throw e
            // The unprivileged mkdirs above may also have been the thing that failed;
            // redo it with privileges before opening, or the write hits a missing parent.
            f.parent?.let { priv.shell.exec("mkdir -p ${PrivilegedShell.quote(it)}") }
            priv.openOutput(file.path, append)
        }
        val hook = changed ?: return out
        return object : java.io.FilterOutputStream(out) {
            private var done = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                super.close()
                if (!done) { done = true; hook(f.absolutePath) }
            }
        }
    }

    override fun mkdir(parent: XFile, name: String): XFile {
        val dir = File(parent.path, name)
        if (!dir.exists() && !dir.mkdirs()) {
            if (elevation?.mkdir(dir.path) != true) {
                throw FsException("Could not create directory: ${dir.path}")
            }
            return XFile(SCHEME, dir.path, isDir = true)
        }
        return toXFile(dir)
    }

    override fun delete(file: XFile) {
        val f = File(file.path)
        if (!deleteRecursively(f) && elevation?.delete(file.path) != true) {
            throw FsException("Delete failed: ${file.path}")
        }
        changed?.invoke(f.absolutePath)
    }

    override fun rename(file: XFile, newName: String): XFile {
        val src = File(file.path)
        val dst = File(src.parentFile, newName)
        // ★ POSIX rename(2) 会**原子地替换**已存在的目标,File.renameTo 直接映射到它——
        // 不先判一次,把 a.txt 改名成同目录已有的 b.txt 就会无声吃掉 b.txt。
        if (dst.exists()) throw FsException("Target already exists: $newName")
        if (!src.renameTo(dst)) {
            val priv = elevation ?: throw FsException("Rename failed: ${file.path}")
            // dst.exists() above can be a false negative on a path we cannot stat,
            // so the privileged path re-checks before moving — never silently replace.
            if (priv.exists(dst.path)) throw FsException("Target already exists: $newName")
            if (!priv.rename(src.path, dst.path)) throw FsException("Rename failed: ${file.path}")
            changed?.invoke(src.absolutePath)
            changed?.invoke(dst.absolutePath)
            return XFile(SCHEME, dst.path, isDir = file.isDir)
        }
        changed?.invoke(src.absolutePath) // 旧路径也报一次:媒体库那边要把它撤下来
        changed?.invoke(dst.absolutePath)
        return toXFile(dst)
    }

    override fun exists(file: XFile): Boolean {
        val f = File(file.path)
        if (f.exists()) return true
        if (!maybeHidden(f)) return false
        return elevation?.exists(file.path) == true
    }

    /**
     * "不存在"这个答案**可能只是看不见**吗?
     *
     * `File.exists()` 分不出 ENOENT 和 EACCES,而 [exists] 是批量复制里的热路径
     * (每个文件都要问一次目标在不在)。不加这道判断的话,往 `/sdcard` 拷一千个文件
     * 就会白白多一千次 shell 往返 —— 而那些路径的父目录本来就读得动,答案是可信的。
     * 父目录读不了才有必要提权再问一次。`canRead` 是一次 access(2),不起进程。
     */
    private fun maybeHidden(f: File): Boolean {
        if (elevation == null) return false
        val parent = f.parentFile ?: return true
        return !parent.canRead()
    }

    override fun setModifiedTime(file: XFile, time: Long): Boolean =
        File(file.path).setLastModified(time) || elevation?.setModifiedTime(file.path, time) == true

    override fun moveWithin(src: XFile, destDir: XFile, newName: String): Boolean {
        // 同盘 rename 是 O(1) 的最优移动;跨盘 renameTo 会失败,返回 false 交给拷贝引擎
        val from = File(src.path)
        val to = File(destDir.path, newName)
        // 同 rename:renameTo 会静默替换已存在的目标。CopyEngine 只在无同名冲突时才调
        // 这里,但它那份判断基于列目录的快照,期间目标目录可能已被外部改动——兜一次底,
        // 返回 false 退回"拷贝 + 删源",那条路上有完整的冲突询问。
        if (to.exists()) return false
        val ok = runCatching { from.renameTo(to) }.getOrDefault(false)
        if (ok) {
            changed?.invoke(from.absolutePath)
            changed?.invoke(to.absolutePath)
        }
        return ok
    }

    /**
     * 递归删除。**符号链接只删链接本身,绝不跟进去删目标里的东西**——
     * `File.isDirectory` 与 `listFiles()` 都跟随符号链接,不判一下的话,删一个内含
     * "指向别处的目录符号链接"的目录,会先把**链接目标里的文件全删光**再删链接。
     * 这在 Android 上不是理论问题:本地 shell 就能建链接,系统自己也到处是链接。
     */
    private fun deleteRecursively(f: File): Boolean {
        if (f.isDirectory && !isSymlink(f)) {
            f.listFiles()?.forEach { if (!deleteRecursively(it)) return false }
        }
        return f.delete()
    }

    /**
     * 是不是符号链接。**先把父目录 canonical 化再比**,不能直接拿
     * `f.canonicalFile != f.absoluteFile` —— 那样只要路径里**任何一级**是链接就会判真,
     * 而 Android 上 `/sdcard` 本身就是指向 `/storage/emulated/0` 的链接,
     * 于是 `/sdcard/任意目录` 全被误判成链接、递归删除直接失效。
     * (与 Apache Commons IO `FileUtils.isSymlink` 同一套路;这里是纯 JVM 模块,
     * 用不了 `android.system.Os.lstat`,`java.nio.file` 又要 API 26 而 minSdk 是 24。)
     */
    private fun isSymlink(f: File): Boolean = runCatching {
        val parent = f.parentFile ?: return false
        val inCanonicalDir = File(parent.canonicalFile, f.name)
        inCanonicalDir.canonicalFile != inCanonicalDir.absoluteFile
    }.getOrDefault(false)

    private fun toXFile(f: File): XFile = XFile(
        scheme = SCHEME,
        path = f.absolutePath,
        isDir = f.isDirectory,
        size = if (f.isDirectory) 0L else f.length(),
        lastModified = f.lastModified(),
        canRead = f.canRead(),
        canWrite = f.canWrite(),
    )

    companion object {
        const val SCHEME = "file"

        /**
         * 本地文件写入/删除/改名的通知钩子(路径为绝对路径,可能已不存在)。
         *
         * 存在的理由只有一个:**告诉系统媒体库有东西变了**——不通知的话,复制进
         * `DCIM/` 的图片在相册里根本不出现(MediaStore 只认自己扫过的东西,而
         * `java.io` 写文件不会触发扫描)。所有本地写入都经过这里的 `openOutput`,
         * 挂在这一层就等于一次覆盖复制/解压/编辑器保存/WiFi 共享上传所有入口。
         *
         * 这是纯 JVM 模块,不认识 Context 也不认识 MediaStore;由 `:app` 在启动时装上
         * 具体实现(见 `com.twig.app.MediaScan`)。回调可能来自任意线程,实现方自理。
         */
        @Volatile
        @JvmStatic
        var changed: ((path: String) -> Unit)? = null

        /**
         * 提权回落(root / Shizuku)。装上之后,**普通 API 失败的那一步**才改走特权 shell:
         * 列不动的目录、读不了的文件、删不掉的条目。装不上就是 null,行为与从前一字不差。
         *
         * ★ 为什么是"回落"而不是独立的 scheme:用户要的是**「根目录」那棵树点得进去**,
         * 而不是旁边多出一棵长得一模一样的特权树。走同一个 scheme,收藏夹里存的
         * `file:` 路径、跨来源复制(`CopyEngine` 只认 openInput/openOutput)、
         * 缩略图、搜索全部零改动就直接受益。
         *
         * ★ 也正因为是回落,**能自己读的一律不走特权**:每条命令都要 fork 一个进程,
         * 拿它列 `/sdcard` 是白白慢几十倍;而且普通路径下权限位是真实的,
         * 特权路径只能一律报 canWrite=true。
         *
         * 与 [changed] 同一套路:纯 JVM 模块给挂载点,`:app` 按用户开关装/卸
         * (见 `com.twig.app.Privileged`)。回调可能来自任意线程。
         */
        @Volatile
        @JvmStatic
        var elevation: PrivilegedFs? = null
    }
}
