package com.twig.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream

/**
 * 只读源:把其他 App 传进来的 content:// URI 包成 [XFile]。两个入口共用:
 *  - 分享("复制到…",[ui.ShareTargetActivity]):交给 [com.twig.core.CopyEngine] 复制到任意目标
 *  - 打开("用 Twig 打开",[ui.ViewIntentActivity]):交给各查看器/压缩包挂载
 *
 * 不新增拷贝/查看逻辑,只新增一个来源(遵循项目"加来源=加 FileSystem"的架构)。
 * 条目永远是叶子文件(不支持目录),写操作不会被调用到,抛异常兜底。
 */
class ShareSourceFileSystem(context: android.content.Context) : FileSystem {

    private val ctx = context.applicationContext
    private val resolver: ContentResolver = ctx.contentResolver
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    override val scheme: String = SCHEME
    override val displayName: String = "Share"

    /** [uri] 存入 path,[name]/[size] 来自调用方查询到的 OpenableColumns 元数据。 */
    fun wrap(uri: Uri, name: String, size: Long): XFile =
        XFile(SCHEME, uri.toString(), isDir = false, size = size, displayName = name)

    /** 自己查 OpenableColumns 包一个条目;查不到时名字退回 URI 末段、大小退回 fd 长度。 */
    fun wrap(uri: Uri): XFile {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }
            .getOrNull()?.takeIf { it >= 0 } ?: 0L
        return wrap(uri, name ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "file" }, size)
    }

    override fun root(): XFile = throw FsException(s(R.string.err_share_no_root))
    override fun resolve(path: String): XFile = XFile(SCHEME, path, isDir = false)
    override fun list(dir: XFile): List<XFile> = emptyList()

    /** 顶层条目,没有上级(默认实现会按 path 切 URI,对 content:// 毫无意义)。 */
    override fun parentOf(file: XFile): XFile? = null

    override fun openInput(file: XFile): InputStream =
        resolver.openInputStream(Uri.parse(file.path)) ?: throw FsException(s(R.string.err_read_failed, file.name))

    /**
     * 定位读:多数 provider(MediaStore/DocumentsProvider/FileProvider)能给出真实 fd,
     * 直接 pread 即可——压缩包流式解析、视频 seek 都靠它,不必整包物化到缓存。
     * 拿不到 fd(纯管道式 provider)时退回基类的"重开跳过"实现。
     */
    override fun openRandom(file: XFile): RandomSource {
        val uri = Uri.parse(file.path)
        val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return super.openRandom(file)
        val fis = java.io.FileInputStream(pfd.fileDescriptor)
        return object : RandomSource {
            private val ch = fis.channel
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                ch.read(java.nio.ByteBuffer.wrap(buffer, offset, length), position)
            override fun length(): Long = pfd.statSize.takeIf { it >= 0 } ?: file.size
            override fun close() {
                runCatching { fis.close() }
                runCatching { pfd.close() }
            }
        }
    }

    /** fd 可用时是真随机访问(本地磁盘);拿不到 fd 的走基类重开跳过,由调用方自行承担。 */
    override fun randomAccessEfficient(): Boolean = true

    override fun writable(): Boolean = false

    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        throw FsException(s(R.string.err_share_read_only))

    override fun mkdir(parent: XFile, name: String): XFile = throw FsException(s(R.string.err_share_read_only))
    override fun delete(file: XFile) = throw FsException(s(R.string.err_share_read_only))
    override fun rename(file: XFile, newName: String): XFile = throw FsException(s(R.string.err_share_read_only))
    override fun exists(file: XFile): Boolean = true

    companion object {
        const val SCHEME = "share"
    }
}
