package com.twig.app.share

import android.content.Context
import android.os.Environment
import com.twig.app.Connections
import com.twig.app.ConnectionStore
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile

/**
 * URL 路径 ↔ [XFile] 的映射,是 HTTP/WebDAV 层唯一认识"文件"的地方。
 *
 * 两种范围([ShareScope])在这里被抹平成同一棵树:
 *  - 单目录:`/a/b` 就是那个目录下的 `a/b`;
 *  - 所有源:`/` 是个**虚拟根**(没有对应的 XFile),子项是 [sources] 给出的各个来源,
 *    `/<来源段>/a/b` 再往下走。
 *
 * ## 为什么是"逐级按名字下钻"而不是"路径拼接"(★ 2026-08-10 重写)
 *
 * 第一版假设"URL 路径段拼起来 = `XFile.path`"。这个假设对本地/SMB/FTP 成立,对
 * **路径不透明**的来源完全不成立:
 *  - 「应用」(`AppsFileSystem`)的 path 是包名 `/user/com.tencent.mm`,而 [XFile.name]
 *    给的是 `微信 8.0.x.apk`——URL 里只可能出现后者,拼回去 resolve 直接抛"找不到该应用";
 *  - SAF 的 path 是一整条 document URI,更没有"父路径 + 名字"这回事。
 *
 * 现在改成:从来源根出发,每一级列目录、按 [XFile.name] 找子项,拿到的就是它**真正的**
 * XFile(不透明 path 原样带着)。代价是深链要逐级列一遍,由 [DirCache] 兜住——浏览本来
 * 就是一级级点下去的,祖先目录全在缓存里。
 */
class ShareRoot(private val ctx: Context, private val scope: ShareScope) {

    /**
     * "所有来源"虚拟根下的一项。
     *
     * [segment] 是 URL 里那一段,**必须稳定**——WebDAV 客户端会把它当挂载点存下来。
     * 用固定字面量(`storage`/`root`/`apps`)或 scheme(服务器,由连接标签确定性算出),
     * 都不会因为用户新展开了一台服务器就整体挪位。[label] 只用来显示。
     */
    class Source(val segment: String, val label: String, val scheme: String, val basePath: String)

    /**
     * 服务起来时把该连的连上:scope 指向某台服务器上的目录、而进程刚冷启动(FsRegistry
     * 里还没有它)时,靠存下来的连接标签自己重连一次。阻塞 IO,必须在工作线程调用。
     *
     * 失败不抛异常——失败会在第一次请求时以"打不开 + 错误原因"表现出来(见
     * [WebUi.renderDir] 的错误条),比服务直接起不来更好排查。
     */
    fun ensureReady() {
        val s = scope as? ShareScope.Dir ?: return
        if (s.connLabel.isEmpty()) return
        if (FsRegistry.all().any { it.scheme == s.scheme }) return
        runCatching {
            Connections.find(ctx, s.connLabel)?.let { Connections.ensure(ctx, it) }
        }
    }

    /** "所有来源"模式的虚拟根:除它以外每个 URL 路径都对应一个真实 [XFile]。 */
    fun isVirtualRoot(path: String): Boolean =
        scope is ShareScope.AllSources && segments(path)?.isEmpty() == true

    /**
     * 虚拟根的子项;单目录模式下为空。
     *
     * **只列真能浏览的来源**。`FsRegistry.all()` 里混着一批不是"根"的东西:
     * zip/7z/rar 是挂在某个宿主文件上的容器(`root()` 直接抛
     * "Archive must be mounted via rootOf(archive)"),SAF 要先选目录树才有根,
     * `share` 是接住别的应用 content:// 的中转。第一版把它们原样摊出来,于是页面上
     * 多了 `Archive`/`7z archive`/`RAR archive`/`Share` 四个点进去必然报错的条目。
     * 判据就一条:[FileSystem.root] 能不能正常返回。
     *
     * 本地存储另拆成「内部存储」和「根目录」两项——`LocalFileSystem` 的根是 `/`,
     * 直接摊出来用户看到的是 `acct`/`apex`/`vendor` 这堆系统目录,而九成场景要的是
     * `/sdcard`。这也跟应用内树上的两个顶级节点对上了。
     */
    fun sources(): List<Source> {
        if (scope !is ShareScope.AllSources) return emptyList()
        val out = ArrayList<Source>()
        val conns = ConnectionStore.all(ctx).associateBy { Connections.schemeOf(it) }
        for (fs in FsRegistry.all()) {
            if (fs.scheme == LOCAL) {
                val ext = runCatching { Environment.getExternalStorageDirectory().absolutePath }
                    .getOrNull().orEmpty()
                if (ext.isNotEmpty()) {
                    out += Source("storage", ctx.getString(R.string.group_internal_storage), LOCAL, ext)
                }
                out += Source("root", ctx.getString(R.string.group_root), LOCAL, "/")
                continue
            }
            // root() 抛异常 = 这个来源不是一棵可独立浏览的树,跳过
            val root = runCatching { fs.root() }.getOrNull() ?: continue
            out += Source(
                segment = fs.scheme,
                label = conns[fs.scheme]?.displayLabel() ?: fs.displayName,
                scheme = fs.scheme,
                basePath = root.path,
            )
        }
        return out
    }

    /**
     * 解析 URL 路径成 [XFile]。路径非法(含 `..`)、来源不存在、某一级找不到时返回 null。
     *
     * 逐级下钻,理由见类注释。
     */
    fun resolve(path: String): XFile? {
        val segs = segments(path) ?: return null
        val (fs, base, rest) = entry(segs) ?: return null
        var cur = runCatching { fs.resolve(base) }.getOrNull()?.copy(isDir = true) ?: return null
        for (name in rest) {
            if (!cur.isDir) return null
            val child = runCatching { cache.list(fs, cur) }.getOrNull()
                ?.firstOrNull { it.name == name } ?: return null
            cur = child
        }
        return cur
    }

    /** 列目录;[file] 必须是 [resolve] 出来的目录。异常照抛,由调用方显示原因。 */
    fun list(file: XFile): List<XFile> = cache.list(FsRegistry.of(file), file)

    /** 该来源整体是否支持写(restic/7z/RAR/应用/git 视图这类只读来源靠它拦住)。 */
    fun writable(file: XFile): Boolean =
        runCatching { FsRegistry.of(file).writable() }.getOrDefault(false)

    /** 写操作改动了某个目录后丢掉缓存,免得浏览器刷新看到的还是旧清单。 */
    fun invalidate(dirPath: String) = cache.drop(dirPath)

    /** 整棵缓存作废(改动可能波及多处时用,比如 MOVE 的两端)。 */
    fun invalidateAll() = cache.clear()

    // ---- 内部 ----

    /** 把 URL 路径段拆成 (文件系统, 起点路径, 还要往下走的段)。 */
    private fun entry(segs: List<String>): Triple<FileSystem, String, List<String>>? {
        when (val sc = scope) {
            is ShareScope.AllSources -> {
                if (segs.isEmpty()) return null // 虚拟根没有 XFile,调用方先问 isVirtualRoot
                val src = sources().firstOrNull { it.segment == segs[0] } ?: return null
                val fs = runCatching { FsRegistry.of(src.scheme) }.getOrNull() ?: return null
                return Triple(fs, src.basePath, segs.drop(1))
            }
            is ShareScope.Dir -> {
                val fs = runCatching { FsRegistry.of(sc.scheme) }.getOrNull() ?: return null
                return Triple(fs, sc.path, segs)
            }
        }
    }

    /**
     * 目录清单的小 LRU。
     *
     * 逐级下钻会把祖先目录反复列一遍(一次深链请求 N 级、一个目录页里 N 个文件各走一遍),
     * 网络来源上那就是成倍的往返。缓存 [MAX] 条、[TTL_MS] 过期:一次浏览会话里祖先全命中,
     * 又不至于让别的客户端刚上传完就看到过期清单(写操作还会主动 [drop])。
     */
    private class DirCache {
        private class Entry(val files: List<XFile>, val at: Long)

        private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX
        }

        fun list(fs: FileSystem, dir: XFile): List<XFile> {
            val key = "${fs.scheme} ${dir.path}"
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(map) {
                map[key]?.let { if (now - it.at < TTL_MS) return it.files }
            }
            val fresh = fs.list(dir) // 网络 IO 放在锁外
            synchronized(map) { map[key] = Entry(fresh, now) }
            return fresh
        }

        fun drop(dirPath: String) = synchronized(map) {
            map.keys.removeAll { it.substringAfter(' ') == dirPath }
        }

        fun clear() = synchronized(map) { map.clear() }

        companion object {
            private const val MAX = 32
            private const val TTL_MS = 5000L
        }
    }

    private val cache = DirCache()

    companion object {
        private const val LOCAL = "file"

        /**
         * 拆 URL 路径成段。**`..` 一律拒绝**(返回 null):这是把设备文件摊到网上的服务,
         * 路径穿越就是把共享目录之外的东西也送出去了。`.` 与空段直接丢掉。
         */
        fun segments(path: String): List<String>? {
            val out = ArrayList<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> return null
                    else -> out.add(seg)
                }
            }
            return out
        }
    }
}
