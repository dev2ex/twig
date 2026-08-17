package com.twig.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File

/** 文件打开相关工具:外部应用打开、非本地来源物化、文本判定。 */
object OpenFiles {

    /**
     * 常见可用内置文本查看器直接查看的扩展名。
     * 不含 "ts":与 MPEG-TS 视频扩展名冲突,文件管理器场景 TS 视频远比裸 TypeScript
     * 源码常见,让位给 [VIDEO_EXT]。
     */
    private val TEXT_EXT = setOf(
        "txt", "log", "md", "markdown", "json", "xml", "csv", "ini", "conf", "cfg", "properties",
        "html", "htm", "css", "js", "kt", "kts", "java", "c", "cpp", "h", "py",
        "sh", "gradle", "yml", "yaml", "toml", "rs", "go", "sql",
    )

    private val PREVIEW_EXT = setOf("md", "markdown", "html", "htm")

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "m4v", "mov", "avi", "ts", "m2ts", "flv")
    private val AUDIO_EXT = setOf("mp3", "aac", "m4a", "flac", "ogg", "opus", "wav", "wma", "mid")
    private val DOC_EXT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf", "epub", "mobi",
    )
    private val ARCHIVE_EXT = setOf("zip", "jar", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst")

    fun isText(file: XFile): Boolean = !file.isDir && file.extension in TEXT_EXT

    /** markdown/html:能用 [com.twig.app.ui.TextViewerActivity] 的预览模式(WebView 渲染)打开。 */
    fun isPreviewable(file: XFile): Boolean = !file.isDir && file.extension in PREVIEW_EXT

    fun isImage(file: XFile): Boolean = !file.isDir && file.extension in IMAGE_EXT

    fun isVideo(file: XFile): Boolean = !file.isDir && file.extension in VIDEO_EXT

    fun isAudio(file: XFile): Boolean = !file.isDir && file.extension in AUDIO_EXT

    fun isDoc(file: XFile): Boolean = !file.isDir && file.extension in DOC_EXT

    fun isApk(file: XFile): Boolean = !file.isDir && file.extension == "apk"

    /** m3u/m3u8 播放列表文件(用音乐播放器打开)。 */
    fun isPlaylist(file: XFile): Boolean = !file.isDir && file.extension in setOf("m3u", "m3u8")

    fun isArchive(file: XFile): Boolean = !file.isDir && file.extension in ARCHIVE_EXT

    /**
     * 把非本地来源(zip/ftp 等)的文件物化到缓存目录,返回本地副本;本地文件直接返回原文件。
     * 阻塞 IO,需在工作线程调用。
     */
    fun materialize(context: Context, file: XFile): File {
        if (file.scheme == "file") return File(file.path)
        val dir = CacheDirs.dir(context, CacheDirs.OPEN)
        // 文件名前缀上来源+路径的 hash:光用 file.name 的话,不同目录下的同名文件
        // (到处都有的 cover.jpg)会互相覆盖,打开 A 看到的是 B 的内容。
        val key = Integer.toHexString("${file.scheme}:${file.path}:${file.size}:${file.lastModified}".hashCode())
        val out = File(dir, "${key}_${file.name}")
        // 1MB 缓冲(默认 8KB 会把网络往返延迟放大成大量小读,参照 CopyEngine)
        FsRegistry.of(file).openInput(file).use { input ->
            out.outputStream().use { input.copyTo(it, COPY_BUFFER_SIZE) }
        }
        CacheDirs.trim(dir, keep = out)
        return out
    }

    private const val COPY_BUFFER_SIZE = 1 shl 20

    /**
     * 大缓冲区整读一个流。★ 别用 `InputStream.readBytes(bufferSize)`——那个
     * 已废弃的重载只把参数当 ByteArrayOutputStream 初始容量,实际 read() 调用
     * 仍固定 8KB,对网络往返延迟毫无帮助(2026-07-28 踩过一次)。这里用
     * `copyTo(out, bufferSize)`,bufferSize 才是真正控制单次 read() 大小的参数。
     */
    fun readAllBytes(input: java.io.InputStream, bufferSize: Int = COPY_BUFFER_SIZE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        input.copyTo(out, bufferSize)
        return out.toByteArray()
    }

    /** 按文件名求 MIME 类型;未知返回 *&#47;*。 */
    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        // 部分设备的 MimeTypeMap 数据库不含 apk 映射,显式指定以确保拉起安装器
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** 分享给其他应用(同样经 [StreamProvider] 流式授权,不整文件缓存)。 */
    fun share(context: Context, file: XFile) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, StreamProvider.uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share)))
    }

    /**
     * 用其他应用打开(经 [StreamProvider] 流式授权,任意来源均无需整文件缓存,
     * 外部播放器可直接 seek)。
     *
     * [forceChooser]=false:直接 startActivity,系统弹解析器(带"仅此一次/始终",
     * 已设默认应用时直接打开);true:强制弹完整选择器(忽略默认,无"始终")。
     */
    fun openWith(context: Context, file: XFile, forceChooser: Boolean = false): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(StreamProvider.uriFor(context, file), mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(
                if (forceChooser) {
                    Intent.createChooser(intent, context.getString(R.string.open_with_external))
                } else intent,
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
