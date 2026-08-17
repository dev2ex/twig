package com.twig.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 已安装应用的虚拟只读视图:根下两个目录 —— 「已安装」(用户应用)与「系统」,
 * 每个条目是一个应用。
 *
 * - 单 apk 应用 → 直接就是那个 apk 文件(读 `sourceDir` 真实路径,零成本);
 * - 分包应用(split apk) → 现拼成 **XAPK**(APKPure 格式,zip 容器 + `manifest.json`,
 *   见 [XapkPack])。只复制 base.apk 会漏掉 `split_config.*`,装回去缺资源/缺 ABI;
 *   XAPK 把整套打在一起,SAI / MT管理器 / APKPure 安装器都认,不认的工具把后缀改成
 *   .zip 也能解开。
 *
 * 读一律走 [openInput],所以复制到对侧、查看、打包全部零改动复用现有链路。
 * 写(新建/改名/删除)一律不支持:卸载不是删文件,由 UI 层起系统卸载界面
 * (见 `PaneFragment.performDelete`)。
 *
 * 枚举整机应用需要 manifest 里的 `QUERY_ALL_PACKAGES` 权限(Android 11+);
 * 没有它 [PackageManager.getInstalledApplications] 只返回本应用与 `<queries>` 声明过的。
 */
class AppsFileSystem(context: Context) : FileSystem {

    private val ctx = context.applicationContext
    private val pm = ctx.packageManager
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    override val scheme: String = SCHEME
    override val displayName: String = ctx.getString(R.string.apps_root)

    override fun root(): XFile = XFile(SCHEME, "/", isDir = true, canWrite = false)

    override fun resolve(path: String): XFile {
        val segs = segsOf(path)
        if (segs.isEmpty()) return root()
        val cat = segs[0]
        if (cat != USER && cat != SYSTEM) throw FsException(s(R.string.err_apps_unknown_dir, path))
        if (segs.size == 1) return dirOf(cat)
        val info = infoOf(segs[1]) ?: throw FsException(s(R.string.err_apps_missing, segs[1]))
        return toXFile(info, cat)
    }

    override fun list(dir: XFile): List<XFile> {
        val segs = segsOf(dir.path)
        if (segs.isEmpty()) return listOf(dirOf(USER), dirOf(SYSTEM))
        val cat = segs[0]
        val wantSystem = cat == SYSTEM
        // 一次 getInstalledPackages 拿全,不要每个包再 getPackageInfo 一次 ——
        // 几百个应用时那是几百次 binder 往返,展开要卡好几秒
        return pm.getInstalledPackages(0)
            .filter { it.applicationInfo?.let { ai -> isSystem(ai) } == wantSystem }
            .mapNotNull { runCatching { toXFile(it, cat) }.getOrNull() }
    }

    override fun openInput(file: XFile): InputStream =
        packOf(file)?.open() ?: FileInputStream(baseApkOf(file))

    /**
     * 单 apk 走真实文件的定位读(缩略图/外部播放器等受益);XAPK 是现拼的流,
     * 没有可定位的底层文件,交给默认实现(重开+跳过)。
     */
    override fun openRandom(file: XFile): RandomSource {
        if (packOf(file) != null) return super.openRandom(file)
        val raf = RandomAccessFile(baseApkOf(file), "r")
        return object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                raf.seek(position)
                return raf.read(buffer, offset, length)
            }
            override fun length(): Long = raf.length()
            override fun close() { runCatching { raf.close() } }
        }
    }

    override fun randomAccessEfficient(): Boolean = true

    override fun writable(): Boolean = false

    override fun openOutput(file: XFile, append: Boolean): OutputStream = readOnly()

    override fun mkdir(parent: XFile, name: String): XFile = readOnly()

    override fun createFile(parent: XFile, name: String): XFile = readOnly()

    override fun rename(file: XFile, newName: String): XFile = readOnly()

    /** 卸载不是删文件:UI 层识别本 scheme 后改起系统卸载界面,不会走到这里。 */
    override fun delete(file: XFile): Unit =
        throw FsException(ctx.getString(R.string.apps_use_uninstall))

    override fun exists(file: XFile): Boolean {
        val segs = segsOf(file.path)
        return when (segs.size) {
            0 -> true
            1 -> segs[0] == USER || segs[0] == SYSTEM
            else -> infoOf(segs[1]) != null
        }
    }

    // ---- UI 层要用的钩子 ----

    /** 该条目对应的包名(path 段就是包名);不是应用条目(根/分类目录)返回 null。 */
    fun packageOf(file: XFile): String? = segsOf(file.path).takeIf { it.size >= 2 }?.get(1)

    /** 列表行要分开显示的三段(应用名 / 版本 / 包名)。 */
    data class AppMeta(val label: String, val version: String, val pkg: String)

    /**
     * 应用名与版本号 —— 列表每行都要用,所以列条目([toXFile])时就顺手记下来,
     * 这里优先查内存表:绑定行时不能再去问 PackageManager(每行一次 binder 往返会掉帧)。
     * 缓存由每次列目录刷新(应用升级后版本号跟着变);没列过的条目才现查一次。
     */
    fun metaOf(file: XFile): AppMeta? {
        val pkg = packageOf(file) ?: return null
        metaCache[pkg]?.let { return it }
        val info = infoOf(pkg) ?: return null
        val ai = info.applicationInfo ?: return null
        return refreshMeta(info, ai)
    }

    /** 现算并刷新缓存。 */
    private fun refreshMeta(info: PackageInfo, ai: ApplicationInfo): AppMeta {
        val label = runCatching { pm.getApplicationLabel(ai).toString() }
            .getOrDefault(info.packageName)
        return AppMeta(label, info.versionName.orEmpty(), info.packageName)
            .also { metaCache[info.packageName] = it }
    }

    /** 该条目背后的 base apk 真实路径(缩略图等要真文件时用);取不到返回 null。 */
    fun apkPathOf(file: XFile): String? =
        packageOf(file)?.let { infoOf(it)?.applicationInfo?.sourceDir }

    // ---- 内部 ----

    /** 该条目要打成 XAPK 时返回打包器,单 apk 应用返回 null。 */
    private fun packOf(file: XFile): XapkPack? =
        packageOf(file)?.let { infoOf(it) }?.let { packFor(it) }

    /**
     * 分包应用 → XAPK 打包器;单 apk 返回 null。
     * 总大小溢出 32 位(要 zip64 才装得下,现实中的应用不会有)时也退回单 apk。
     */
    private fun packFor(info: PackageInfo): XapkPack? {
        val ai = info.applicationInfo ?: return null
        val splits = ai.splitSourceDirs?.filter { it.isNotEmpty() } ?: emptyList()
        if (splits.isEmpty()) return null
        val base = File(ai.sourceDir ?: return null)
        val files = ArrayList<Pair<String, File>>()
        files += "${info.packageName}.apk" to base
        // 平台类型,老系统/异常包上确实可能是 null,显式按可空处理
        val names: Array<String>? = info.splitNames
        splits.forEachIndexed { i, path ->
            val f = File(path)
            // 名字优先用 split 自己的文件名(split_config.arm64_v8a.apk 这种,安装器认得),
            // 缺失时按 split 名兜底
            files += (f.name.ifEmpty { "split_${names?.getOrNull(i) ?: i}.apk" }) to f
        }
        val total = files.sumOf { it.second.length() }
        val manifest = manifestJson(info, files, total).toByteArray(Charsets.UTF_8)
        val entries = ArrayList<XapkPack.Entry>(files.size + 1)
        entries += XapkPack.Entry("manifest.json", manifest, null)
        files.forEach { (n, f) -> entries += XapkPack.Entry(n, null, f) }
        val pack = XapkPack(entries)
        return if (pack.totalSize() in 1..MAX_ZIP32) pack else null
    }

    /** APKPure 的 XAPK 清单;字段名沿用它那套,安装器据此还原 base + splits。 */
    private fun manifestJson(info: PackageInfo, files: List<Pair<String, File>>, total: Long): String {
        val ai = info.applicationInfo
        val label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?: info.packageName
        val splits = files.mapIndexed { i, (n, _) ->
            val id = if (i == 0) "base" else n.removeSuffix(".apk").removePrefix("split_")
            """{"file":${jsonStr(n)},"id":${jsonStr(id)}}"""
        }.joinToString(",")
        return buildString {
            append("{\"xapk_version\":2")
            append(",\"package_name\":").append(jsonStr(info.packageName))
            append(",\"name\":").append(jsonStr(label))
            append(",\"version_code\":").append(jsonStr(versionCodeOf(info).toString()))
            append(",\"version_name\":").append(jsonStr(info.versionName ?: ""))
            append(",\"min_sdk_version\":").append(jsonStr(minSdkOf(ai).toString()))
            append(",\"target_sdk_version\":").append(jsonStr((ai?.targetSdkVersion ?: 0).toString()))
            append(",\"total_size\":").append(total)
            append(",\"split_apks\":[").append(splits).append("]}")
        }
    }

    private fun minSdkOf(ai: ApplicationInfo?): Int =
        if (ai != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ai.minSdkVersion
        } else {
            0
        }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private fun baseApkOf(file: XFile): File {
        val src = apkPathOf(file) ?: throw FsException(s(R.string.err_apps_not_entry, file.path))
        return File(src)
    }

    private fun infoOf(pkg: String): PackageInfo? =
        runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()

    private fun isSystem(ai: ApplicationInfo): Boolean =
        (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun segsOf(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    private fun dirOf(cat: String): XFile = XFile(
        SCHEME, "/$cat", isDir = true, canWrite = false,
        displayName = ctx.getString(if (cat == SYSTEM) R.string.apps_system else R.string.apps_user),
    )

    /**
     * path 用包名(唯一、稳定,便于 UI 反查),显示名给「应用名 版本.apk/.xapk」——
     * 复制到对侧时 [com.twig.core.CopyEngine] 取的正是 [XFile.name],落地就是这个文件名。
     */
    private fun toXFile(info: PackageInfo, cat: String): XFile {
        val ai = info.applicationInfo ?: throw FsException(s(R.string.err_apps_no_info, info.packageName))
        val base = File(ai.sourceDir ?: throw FsException(s(R.string.err_apps_no_apk, info.packageName)))
        val pack = runCatching { packFor(info) }.getOrNull()
        // 顺手刷新列表行要用的应用名/版本(见 metaOf);文件名仍用下面拼的完整名,
        // 复制出去落地的就是它
        val meta = refreshMeta(info, ai)
        val name = buildString {
            append(meta.label.replace('/', '_'))
            if (meta.version.isNotEmpty()) append(' ').append(meta.version.replace('/', '_'))
            append(if (pack != null) ".xapk" else ".apk")
        }
        return XFile(
            scheme = SCHEME,
            path = "/$cat/${info.packageName}",
            isDir = false,
            size = pack?.totalSize() ?: base.length(),
            lastModified = base.lastModified(),
            canWrite = false,
            displayName = name,
        )
    }

    private fun readOnly(): Nothing = throw FsException(ctx.getString(R.string.apps_read_only))

    companion object {
        /** 包名 → 列表行要用的应用名/版本;列条目时填,绑定行时只读内存。 */
        private val metaCache = java.util.concurrent.ConcurrentHashMap<String, AppMeta>()

        const val SCHEME = "apps"
        private const val USER = "user"
        private const val SYSTEM = "system"
        private const val MAX_ZIP32 = 0xFFFFFFFFL - 1
    }
}
