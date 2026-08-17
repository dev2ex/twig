package com.twig.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.twig.app.Format
import com.twig.app.OpenFiles
import com.twig.app.R
import com.twig.core.XFile
import java.util.concurrent.Executors

/**
 * 文件列表图标:
 * - 内置可打开的类型(视频/音频/图片/文档/文本)用彩色类型图标;
 * - apk 用包内自带的应用图标(仅本地文件,异步解析);
 * - 其余类型若系统已有默认打开应用("始终"选过的),用该应用图标;
 * - 都没有则用通用文件图标。
 *
 * 应用图标按 key(apk 路径 / "ext:扩展名")缓存;解析在单后台线程,
 * 回填前校验 view.tag 防止 RecyclerView 复用错位。
 */
object FileIcons {

    private val cache = HashMap<String, Drawable?>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-icons").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun baseIconRes(file: XFile): Int = when {
        // 「应用」树的条目(.apk / 分包应用的 .xapk)统一用 apk 图标打底,
        // 真正的应用图标下面按 "pkg:" 现问 PackageManager 要(异步回填,不走缩略图管线)
        file.scheme == com.twig.app.AppsFileSystem.SCHEME -> R.drawable.ic_file_apk
        OpenFiles.isVideo(file) || file.extension == "wmv" -> R.drawable.ic_file_video
        OpenFiles.isAudio(file) -> R.drawable.ic_file_audio
        OpenFiles.isImage(file) -> R.drawable.ic_file_image
        file.extension == "pdf" -> R.drawable.ic_file_pdf
        OpenFiles.isDoc(file) || OpenFiles.isText(file) -> R.drawable.ic_file_doc
        OpenFiles.isArchive(file) -> R.drawable.ic_file_archive
        OpenFiles.isApk(file) -> R.drawable.ic_file_apk
        else -> R.drawable.ic_file
    }

    /**
     * 来源类型图标(不是文件类型图标):路径栏、最近位置、复制/压缩的目标行共用这一套,
     * 免得同一个目录在三个界面画出三种图标。
     *
     * 按 scheme 判定即可——每台服务器的 scheme 是「类型 + hash」(见
     * `PaneViewModel.schemeForConn`),[Format.schemeLabel] 剥掉 hash 就是类型,
     * 不需要再去 ConnectionStore 里查连接(连接被删了也还认得出来)。
     */
    fun sourceIconRes(scheme: String): Int = sourceIconOfType(Format.schemeLabel(scheme))

    /** 同 [sourceIconRes],但直接给连接类型——最近位置的条目只存了类型,没有 scheme。 */
    fun sourceIconOfType(type: String): Int = when (type) {
        "smb" -> R.drawable.ic_lan
        "sftp" -> R.drawable.ic_server
        "ftp", "webdav", "dav", "s3" -> R.drawable.ic_cloud
        "git" -> R.drawable.ic_git
        "restic" -> R.drawable.ic_restic
        com.twig.app.AppsFileSystem.SCHEME -> R.drawable.ic_file_apk
        "zip", "7z", "rar" -> R.drawable.ic_file_archive
        else -> R.drawable.ic_storage // 本地 / SAF / 类型未知
    }

    fun bind(view: ImageView, file: XFile) {
        val base = baseIconRes(file)
        view.setImageResource(base)
        val key = when {
            // 「应用」条目:图标现问 PackageManager 要,不必解析 apk
            file.scheme == com.twig.app.AppsFileSystem.SCHEME ->
                appPackageOf(file)?.let { "pkg:$it" } ?: run { view.tag = null; return }
            OpenFiles.isApk(file) && file.scheme == "file" -> file.path
            base == R.drawable.ic_file && file.extension.isNotEmpty() -> "ext:${file.extension}"
            else -> { view.tag = null; return }
        }
        view.tag = key
        synchronized(cache) {
            if (cache.containsKey(key)) {
                cache[key]?.let { view.setImageDrawable(it) }
                return
            }
        }
        val ctx = view.context.applicationContext
        executor.execute {
            val d = runCatching { load(ctx, key) }.getOrNull()
            synchronized(cache) { cache[key] = d }
            if (d != null) main.post { if (view.tag == key) view.setImageDrawable(d) }
        }
    }

    /** 系统默认应用可能变(用户选了"始终"),回到前台时清掉关联图标缓存。 */
    fun clearAppDefaults() {
        synchronized(cache) { cache.keys.removeAll { it.startsWith("ext:") } }
    }

    private fun appPackageOf(file: XFile): String? =
        (runCatching { com.twig.core.FsRegistry.of(file) }.getOrNull() as? com.twig.app.AppsFileSystem)
            ?.packageOf(file)

    private fun load(ctx: Context, key: String): Drawable? {
        val pm = ctx.packageManager
        if (key.startsWith("pkg:")) { // 已安装应用的图标
            val ai = runCatching { pm.getApplicationInfo(key.removePrefix("pkg:"), 0) }.getOrNull()
            return ai?.loadIcon(pm)
        }
        if (!key.startsWith("ext:")) { // apk 自带图标
            val pi = pm.getPackageArchiveInfo(key, 0) ?: return null
            val ai = pi.applicationInfo ?: return null
            ai.sourceDir = key
            ai.publicSourceDir = key
            return ai.loadIcon(pm)
        }
        // 该扩展名的系统默认打开应用("始终"选过才有)
        val ext = key.removePrefix("ext:")
        val mime = OpenFiles.mimeOf("x.$ext")
        if (mime == "*/*") return null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://${ctx.packageName}.stream/probe/probe.$ext"), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val ai = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo ?: return null
        // 没有默认时系统返回解析器自身,不算关联应用
        if (ai.packageName == "android") return null
        return ai.loadIcon(pm)
    }
}
