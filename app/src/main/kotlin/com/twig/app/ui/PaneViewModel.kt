package com.twig.app.ui

import android.app.Application
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twig.app.AppsFileSystem
import com.twig.app.CompareSession
import com.twig.app.CompareStore
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Favorite
import com.twig.app.FavoritesStore
import com.twig.app.Format
import com.twig.app.GitFileSystem
import com.twig.app.HistoryEntry
import com.twig.app.HistoryStore
import com.twig.app.OpenFiles
import com.twig.app.XFileGitFs
import com.twig.git.GitRepo
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.app.SortSpec
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.ArchivePasswordException
import com.twig.fs.archive.Archives
import com.twig.fs.network.DavConfig
import com.twig.fs.network.FtpConfig
import com.twig.fs.network.FtpFileSystem
import com.twig.fs.network.SftpConfig
import com.twig.fs.network.SftpFileSystem
import com.twig.fs.network.WebDavFileSystem
import com.twig.fs.restic.ResticFileSystem
import com.twig.fs.restic.ResticRepo
import com.twig.fs.zstd.NativeZstd
import com.twig.fs.smb.SmbConfig
import com.twig.fs.smb.SmbFileSystem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次目录递归统计的运行状态(属性卡片所在目录 fileKey → 此对象)。 */
private class DirScanState(
    var stat: DirStat = DirStat(),
    var scanning: Boolean = true,
    var job: kotlinx.coroutines.Job? = null,
)

/** 一次递归通配符搜索的运行状态(owner 目录 fileKey → 此对象)。 */
private class SearchState(
    val pattern: String,
    var scanning: Boolean = true,
    val results: MutableList<XFile> = mutableListOf(),
    var job: kotlinx.coroutines.Job? = null,
)

/**
 * X-plore 式的"整棵树"模型:顶级节点为 内部存储/根目录/LAN/FTP/文档树(SAF),
 * SMB/FTP 服务器是 LAN/FTP 组下的树节点(展开即连接),压缩包是可展开的文件节点。
 * 永远不"进入"目录——一切都在同一棵树里就地展开/折叠。
 *
 * [currentDir] = 最近点击的目录节点,是新建/粘贴(复制/移动)的目标,行上以边框高亮。
 */
class PaneViewModel(app: Application) : AndroidViewModel(app) {

    // ---- 节点类型 ----

    sealed interface Node {
        val key: String
        val depth: Int
    }

    /** 真实文件/目录(也包括存储顶级节点与 SAF 树根、压缩包)。 */
    data class FileNode(
        val file: XFile,
        override val depth: Int,
        val expandable: Boolean,
        val expanded: Boolean,
        /** 顶级节点显示名(如"内部存储");普通节点为 null 用 file.name。 */
        val label: String? = null,
        /** 存储节点的容量文本。 */
        val capacity: String? = null,
        val loading: Boolean = false,
        /**
         * 同一个文件可以在树上出现两处 —— 别的 App「用 Twig 打开」的压缩包挂在树顶
         * ([externalMount]),而它在存储树里**原本那一行也还在**。行的 key 是 DiffUtil
         * 认行的唯一依据,两处同 key 就会认错行(症状:其中一处展开是空的)。
         * 外部挂载的那棵子树整体带上这个前缀,与原位置彻底分开;普通行为空串。
         */
        val keyPrefix: String = "",
    ) : Node {
        override val key: String get() = keyPrefix + fileKey(file)
    }

    /** 虚拟分组:LAN / FTP / SAF。 */
    data class GroupNode(val id: String, val label: String, val expanded: Boolean) : Node {
        override val depth: Int get() = 0
        override val key: String get() = "g:$id"
    }

    /** 已保存的服务器(展开即连接);[info] 为连接后得到的补充信息(如 SMB 版本)。 */
    data class ServerNode(
        val conn: SavedConnection,
        val expanded: Boolean,
        val connecting: Boolean,
        val info: String? = null,
    ) : Node {
        override val depth: Int get() = 1
        override val key: String get() = "s:${conn.label()}"
    }

    /** 组内动作项,如"添加服务器…"。 */
    data class ActionNode(val id: String, val label: String) : Node {
        override val depth: Int get() = 1
        override val key: String get() = "a:$id"
    }

    /**
     * 收藏项(展开即按需连接/解锁并直达该目录)。
     * [conn] 是收藏所在连接(conn/restic 两种 kind 才有)当前的已保存配置——展示时用它取
     * 实时别名/协议类型,而不是收藏创建时冻结的 [Favorite.label](连接改名后收藏跟着更新)。
     */
    data class FavoriteNode(
        val fav: Favorite,
        val conn: SavedConnection?,
        override val depth: Int,
        val expanded: Boolean,
        val connecting: Boolean,
    ) : Node {
        override val key: String get() = "fav:${fav.id}"
    }

    /**
     * 对比收藏项(左右两侧目录的引用)。与 [FavoriteNode] 不同——它不是一个可展开的真实
     * 目录,点击直接跳到 [CompareActivity] 重新扫描,长按菜单也是另一套(见
     * `PaneFragment.compareFavMenu`)。
     */
    data class CompareNode(val session: CompareSession, override val depth: Int) : Node {
        override val key: String get() = "cmp:${session.id}"
    }

    /**
     * 属性卡片(长按菜单"属性"打开):挂在对应文件行正下方,左缘与文件图标对齐;
     * 每个信息分组一个 tab,文件另有"哈希"tab(本地选中即算,网络手动点计算)。
     * [details] 为 null 表示读取中。点 ✕ 或再次选菜单项关闭;所在目录折叠后自动清除。
     * 目录另有 [dirStat]:递归统计出的文件/目录数与总大小,边扫边刷([scanning] 期间转圈)。
     */
    data class InfoNode(
        val file: XFile,
        override val depth: Int,
        val details: com.twig.app.FileInfo.Details?,
        val tab: Int = 0,
        val hashRows: List<Pair<String, String>>? = null,
        val hashing: Boolean = false,
        val dirStat: DirStat? = null,
        val scanning: Boolean = false,
    ) : Node {
        override val key: String get() = "info:${fileKey(file)}"
    }

    /**
     * 递归通配符搜索的虚拟结果目录,紧贴被搜索目录行下方(不依赖该目录本身是否展开)。
     * 名称含实时匹配数"搜索结果(N)",边扫边填充,子项就是普通 [FileNode](挂在其下一级,
     * 复制/删除/属性/再展开等全部复用普通文件行逻辑)。这不是常规目录:点击这一行本身即
     * 收缩并把整个虚拟目录从树上移出(丢弃已扫到的结果),需要时重新发起搜索;
     * 长按显示统计([matchedFiles]/[matchedDirs] 分别计数)。
     */
    data class SearchNode(
        val root: XFile,
        override val depth: Int,
        val pattern: String,
        val scanning: Boolean,
        val matchedFiles: Int,
        val matchedDirs: Int,
    ) : Node {
        override val key: String get() = "search:${fileKey(root)}"
    }

    /** 目录内检测到的 restic 备份仓库(未解锁需输入密码;解锁后子项为快照)。 */
    data class ResticNode(
        val repoDir: XFile,
        override val depth: Int,
        val expanded: Boolean,
        val unlocked: Boolean,
        /** 正在解锁(scrypt + 读快照列表,网络仓库可能几十秒)——行上显示转圈。 */
        val connecting: Boolean = false,
    ) : Node {
        override val key: String get() = "restic:${fileKey(repoDir)}"
    }

    data class State(
        val rows: List<Node> = emptyList(),
        val currentDir: XFile? = null,
        /** 最近选中(点击展开/折叠)的节点 key,用于整树高亮框与恢复定位。 */
        val currentKey: String? = null,
        val error: String? = null,
        /**
         * 展开这个加密压缩包要密码(UI 据此弹密码框);[error] 同时非空表示上一次给的不对。
         * 和 [error] 一样是"一次性"的:下一轮 [rebuild] 就清掉。
         */
        val passwordFor: XFile? = null,
        /**
         * 正在恢复上次位置(逐个展开、行数还会变)。UI 靠它判断"这版行还不是最终版",
         * 恢复期间反复把滚动锚定到目标行,直到它变 false 才收手。
         */
        val restoring: Boolean = false,
        /** 恢复定位的首选目标行(上次打开的文件);解析不出来时 UI 退回 [currentKey]。 */
        val scrollKey: String? = null,
    )

    // ---- 状态 ----
    //
    // ★ **线程规则只有一条:这些集合一律只在主线程写。**
    //
    // `withContext(io)` 块只负责"把数据取回来",产出 [Listing] / [Restored] /
    // [RevealPlan] 这类纯数据,回到主线程再由 applyListing / applyRestored /
    // applyReveal 落表。加新的异步路径时照这个样子写,别在 IO 块里直接改下面任何一张表。
    //
    // 为什么要这条规则:恢复位置、连服务器、识别 git/restic 仓库原来都在 IO 上直接改这些
    // 表,而 refresh()/refreshLocal()/toggleFile() 是**可以同时在跑的独立协程**——
    // 两个 IO 线程同时改同一张表,轻则丢更新、重则读到"改了一半"的中间态
    // (children 有了新列表、keyFile 还没跟上)。改成单线程写之后,rebuild() 每次
    // 看到的都是一致的快照,并发容器也就只是兜底而不是唯一防线了。
    //
    // 下面仍用并发容器,是因为**读**还是跨线程的(IO 里会读 children/gitInfo 判断
    // "要不要重新列",见 planReveal),留着它们比换回裸 HashMap 更省心。
    //
    // 例外:serverScheme / schemeToConn / serverInfo / resticScheme / schemeToRestic
    // 这几张由 schemeForConn / schemeForRestic 在 IO 上写。它们是**确定性函数的缓存**
    // (scheme 由 Connections.schemeOf(conn) 算出),并发写入写的是同一个值,幂等;
    // 而且 connSchemeBlocking 是给 PaneFragment 在 IO 上直接调的公开入口,搬回主线程
    // 要改公开契约,不划算。

    // ---- (1) 跨线程 ----

    /** 顺序有意义([expandedDescriptors] 按它存恢复顺序),所以是 LinkedHashSet 包同步。
     *  单元素 add/remove/contains 直接安全;**遍历/removeAll{} 必须自己 synchronized(expanded)**。 */
    private val expanded: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())
    private val children = ConcurrentHashMap<String, List<XFile>>()
    /** 可展开节点 key -> 重新列举所需的 XFile(服务器为其根)。 */
    private val keyFile = ConcurrentHashMap<String, XFile>()
    /** 服务器 key -> 已注册的 scheme(已连接标记)。 */
    private val serverScheme = ConcurrentHashMap<String, String>()
    /** 连接后得到的服务器补充信息(label → 如 "SMB3.1.1")。 */
    private val serverInfo = ConcurrentHashMap<String, String>()
    /** 检测到 git 仓库的目录(dirKey → 已注册的虚拟 fs scheme + 显示名)。 */
    private val gitInfo = ConcurrentHashMap<String, Pair<String, String>>()
    /** 反查(供"最近位置"从 git scheme 还原宿主目录)。 */
    private val schemeToGitHost = ConcurrentHashMap<String, XFile>()
    /**
     * "廉价" git scheme(本地直读 / SSH 远程执行 git 命令):status()/log() 本身不缓存,
     * 每次调用即读当前状态;这类根节点每次展开都强制重拉,不吃 [children] 缓存。
     * SMB/WebDAV 等无 git 命令时靠 [XFileGitFs] 解析 .git 对象,逐文件网络访问贵,
     * 不在此列——保留缓存,靠手动"刷新"菜单项按需更新。
     */
    private val cheapGitSchemes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 被识别为 restic 仓库的目录 key。 */
    private val resticRepos: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** restic 节点 key -> 已注册的 restic scheme(已解锁)。 */
    private val resticScheme = ConcurrentHashMap<String, String>()
    /** 反查(供"收藏"从动态 scheme 还原来源)。 */
    private val schemeToConn = ConcurrentHashMap<String, SavedConnection>()
    private val schemeToRestic = ConcurrentHashMap<String, XFile>()

    // ---- (2) 只在主线程 ----

    /** 外部 App 传进来、临时挂在树顶的压缩包(见 [mountExternal]);同时只留一个。 */
    private var externalMount: XFile? = null
    private val connecting = HashSet<String>()
    /** 正在异步加载子项的节点 key(用于显示加载转圈)。 */
    private val loadingKeys = HashSet<String>()
    /** apk 默认点击直接安装,不当压缩包展开;用户选"以压缩包方式打开"后记入此集,该节点起才可展开浏览包内容。 */
    private val forcedArchive = HashSet<String>()
    /** 打开了属性卡片的文件 key(fileKey);卡片内容缓存,关闭/折叠即丢弃。 */
    private val infoOpen = LinkedHashSet<String>()
    private val infoCache = HashMap<String, com.twig.app.FileInfo.Details>()
    /** 卡片当前选中的 tab、已算出的哈希、正在计算哈希的 key。 */
    private val infoTab = HashMap<String, Int>()
    private val hashCache = HashMap<String, List<Pair<String, String>>>()
    private val hashing = HashSet<String>()
    /** 目录属性卡片的递归统计:目录 fileKey → 运行状态(卡片关闭/折叠即取消,见 [rebuild])。 */
    private val dirScan = HashMap<String, DirScanState>()
    /** 递归通配符搜索:被搜索目录 fileKey → 运行状态。见 [SearchNode]。 */
    private val searchState = HashMap<String, SearchState>()
    /** 本轮 rebuild 已挂过附属行(属性卡片/搜索结果)的目录 key,防同一目录出现两处时重复挂。 */
    private val attachedKeys = HashSet<String>()

    var currentDir: XFile? = null
    /** 最近选中的节点 key(任意类型:目录/服务器/分组/收藏/restic)。 */
    private var currentKey: String? = null
        private set

    // ---- 恢复上次位置的中间状态 ----
    private var restoring = false
    /** 滚动定位的首选目标行(“跳转到所在目录”要定位到的那个文件);为 null 时按当前目录定位。 */
    private var scrollKey: String? = null
    /** 还没解析成 XFile 的当前目录描述符:网络位置要等对应服务器连上(serverScheme 建好)才解得出。 */
    private var pendingCurrentDesc: String? = null

    /**
     * 阻塞 IO 用的调度器。**只为可测性存在**——生产环境永远是 [Dispatchers.IO]。
     *
     * 单测里换成受控调度器,`advanceUntilIdle()` 才覆盖得到这些 `withContext` 块;
     * 否则它们跑在真实线程池上、虚拟时间管不着,测试会在 IO 还没做完时就往下断言,
     * 变成时好时坏的 flaky 测试。
     *
     * 做成可写字段而不是构造参数:`by viewModels()` 走的是
     * `AndroidViewModelFactory`,它按反射找 `(Application)` 这个构造器,加了参数就得
     * 靠 `@JvmOverloads` 兜——万一没兜住是**运行时**才炸,而单测直接 new 根本发现不了。
     * 这条缝只给测试用,别在生产代码里改它。
     */
    @androidx.annotation.VisibleForTesting
    internal var io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    private var sort = SortSpec.load(getApplication())

    /** 是否显示隐藏文件;每次 [rebuild] 开头刷新一次。 */
    private var showHidden = Prefs.showHidden(getApplication())

    /** 存入子项时统一按当前排序;虚拟节点(服务器/收藏等)不受影响。 */
    private fun putChildren(key: String, list: List<XFile>) {
        children[key] = sortList(list)
    }

    /** 修改排序:重排所有已缓存子项并重建。 */
    fun setSort(spec: SortSpec) {
        sort = spec
        resortAll()
    }

    /** 设置(缩略图/网格模式)变化后按新规则重排已缓存子项。 */
    fun resortAll() {
        // children 可能被 IO 线程并发改动,取出来判空再写回,别 !!
        for (k in children.keys.toList()) children[k]?.let { children[k] = sortList(it) }
        rebuild()
    }

    /**
     * 能出缩略图的文件紧跟文件夹之后聚齐,树式列表与网格一视同仁 —— 缩略图开着时图文混排,
     * 图片挤在一堆文本文件中间很难扫;聚在一起才像相册。网格模式即使缩略图总开关关着也照样
     * 分组(那时格子里是大图标),否则格子与整行混排更乱。都关着(树式列表 + 无缩略图)时
     * 用原排序,与历来行为一致。
     */
    private fun sortList(list: List<XFile>): List<XFile> {
        val app = getApplication<Application>()
        val cmp = sort.comparator()
        val grid = Prefs.thumbsGrid(app)
        // 都关着(树式列表 + 无缩略图):不分组,与历来行为一致
        if (!Prefs.thumbs(app) && grid == 0) return SortRules.sorted(list, cmp, null)
        // 网格「全部文件」:压缩包和目录一样渲染成整行(可展开),夹在格子中间会把网格切断,
        // 所以紧跟目录聚到前面;其余模式压缩包就是普通行,不必单独成组。
        val archivesFirst = grid == 2
        return SortRules.sorted(list, cmp) { f ->
            SortRules.groupOf(f.isDir, expandableArchive(f), Thumbs.canThumb(f), archivesFirst)
        }
    }

    /**
     * 隐藏文件(名字以 '.' 开头)按偏好过滤。只在**渲染**时滤,[children] 缓存始终存全量——
     * 开关切回来不必重新列目录,网络来源尤其省一轮 IO。
     */
    private fun visible(list: List<XFile>?): List<XFile> {
        val l = list ?: return emptyList()
        return if (showHidden) l else l.filter { !it.name.startsWith(".") }
    }

    /**
     * 压缩包是否渲染成可展开的整行(与 [addFile] 同一判定)。
     * apk 默认不展开(点击走安装),除非用户手动选过"以压缩包方式打开"。
     */
    private fun expandableArchive(f: XFile, key: String = fileKey(f)): Boolean =
        !f.isDir && Archives.isArchive(f) && (!OpenFiles.isApk(f) || forcedArchive.contains(key))

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 首次装配。[descriptors] 非空时恢复上次的展开(本地目录 + 网络位置 + 分组/服务器,
     * 网络用已保存连接重连);否则默认展开内部存储。[currentDesc] 恢复当前目录。
     */
    fun bootstrap(descriptors: List<String> = emptyList(), currentDesc: String? = null) {
        if (_state.value.rows.isNotEmpty()) return
        if (descriptors.isEmpty()) {
            rebuild()
            (state.value.rows.firstOrNull { it is FileNode && (it as FileNode).label != null } as? FileNode)
                ?.let { toggleFile(it) }
            return
        }
        val parsed = descriptors.sortedBy { it }.map { it.split('\t') }
        // 分组先展开(建立树结构),纯内存操作、不必等 IO
        parsed.filter { it.getOrNull(0) == "group" }.forEach { expanded.add("g:${it[1]}") }
        // 服务器/本地目录先标成"加载中"再出第一版骨架:所有根节点立刻可见,
        // 还没连上/列出的位置显示转圈,而不是整棵树空白直到全部恢复完成。
        // 网络目录("conn")挂在服务器行之下,行本身要等服务器连上才有位置渲染,
        // 由服务器节点的转圈状态覆盖,不需要单独预标记。
        for (p in parsed) when (p.getOrNull(0)) {
            "server" -> if (connFor(p[1]) != null) connecting.add("s:${p[1]}")
            "file" -> loadingKeys.add(fileKey(XFile("file", p[1], isDir = true)))
            "apps" -> loadingKeys.add(fileKey(XFile(AppsFileSystem.SCHEME, p[1], isDir = true)))
            "fav" -> if (FavoritesStore.contains(getApplication(), p[1])) connecting.add("fav:${p[1]}")
        }
        restoring = true
        pendingCurrentDesc = currentDesc
        resolvePending() // 本地位置立刻解得出;网络的等服务器连上后在循环里再试
        rebuild()
        viewModelScope.launch {
            for (p in parsed) {
                if (p.getOrNull(0) == "group") continue
                // IO 只把数据取回来,落表在下面的主线程段——这个类的线程规则见类注释
                val fetched = withContext(io) {
                    runCatching {
                        when (p[0]) {
                            "server" -> fetchServer(p[1])
                            "file" -> fetchDir(XFile("file", p[1], isDir = true))
                            "apps" -> fetchDir(XFile(AppsFileSystem.SCHEME, p[1], isDir = true, canWrite = false))
                            "conn" -> connFor(p[1])?.let { fetchDir(XFile(schemeForConn(it), p[2], isDir = true)) }
                            "fav" -> fetchFavoriteById(p[1])
                            else -> null
                        }
                    }.getOrNull()
                }
                fetched?.let { applyRestored(it) }
                when (p[0]) {
                    "server" -> connecting.remove("s:${p[1]}")
                    "file" -> loadingKeys.remove(fileKey(XFile("file", p[1], isDir = true)))
                    "apps" -> loadingKeys.remove(fileKey(XFile(AppsFileSystem.SCHEME, p[1], isDir = true)))
                    "fav" -> connecting.remove("fav:${p[1]}")
                    else -> Unit
                }
                resolvePending()
                rebuild()
            }
            upgradeContainerKey()
            restoring = false
            rebuild() // 最后一版行才是最终版,UI 据此收手停止重复锚定
        }
    }

    /**
     * 恢复收尾时校准高亮框的 key。
     *
     * 收藏/服务器的**根目录在树上没有自己的行** —— 子项直接挂在 `fav:`/`s:` 行下面
     * (见 [addFavorite]/[addServer]),所以上次停在收藏根目录时,高亮框框的是 `fav:` 行。
     * 但存盘只存得下"哪个目录"([currentDescriptor] 看的是 [currentDir]),"经由收藏
     * 到达"这件事在那里就丢了;恢复时 [resolvePending] 一律按 `fileKey` 设 key,
     * 于是指向一个**根本不存在的行**,表现为"从收藏展开的位置,重开后没有绿框"。
     *
     * ★ 这一步必须放在恢复循环**之后**:[containerKeyFor] 查的是 [keyFile],而
     * `fav:`/`s:` 的条目要等 [restoreFavorite]/[restoreServer] 跑完才填得进去。
     * 放进 [resolvePending] 是不够的——本地路径在循环开始前那次调用就已经解析成功
     * 并清掉了 pendingCurrentDesc,那时 keyFile 还是空的。
     *
     * [up] 早就用 [containerKeyFor] 解决过同一类问题(见它的注释),这里是漏了同一步。
     */
    private fun upgradeContainerKey() {
        val cur = currentDir ?: return
        // 用户在恢复期间自己点过别的行(currentKey 已不是按 currentDir 推出来的那个)
        // 就不要覆盖他的选择
        if (currentKey != fileKey(cur)) return
        containerKeyFor(cur)?.let { currentKey = it }
    }

    /**
     * 把还没解析的"当前目录"描述符尽量解析成 XFile。网络描述符依赖 [serverScheme]
     * (服务器连上才有),所以要在恢复过程中反复调用,而不是 bootstrap 开头一次。
     */
    private fun resolvePending() {
        val d = pendingCurrentDesc ?: return
        descToXFile(d)?.let {
            currentDir = it
            currentKey = fileKey(it)
            pendingCurrentDesc = null
        }
    }

    /**
     * IO 阶段取回来、待落进树的一份数据。见 [applyRestored]。
     *
     * 这个类型存在的意义就是把"取"和"写"分开:恢复/定位链路原来在 `withContext(io)`
     * 里直接改 [children]/[keyFile]/[expanded],现在 IO 只产出 Restored,回主线程再落表。
     */
    private class Restored(
        val key: String,
        val file: XFile,
        val listing: Listing,
        /** restic 收藏顺带取到的快照列表(挂在 `restic:` 节点上)。 */
        val extra: Pair<String, List<XFile>>? = null,
    )

    /** 把 [Restored] 落进树。**只能在主线程调用。** */
    private fun applyRestored(r: Restored) {
        keyFile[r.key] = r.file
        putListing(r.key, r.listing)
        r.extra?.let { (k, v) -> children[k] = sortList(v) }
        expanded.add(r.key)
    }

    private fun fetchServer(label: String): Restored? {
        val conn = connFor(label) ?: return null
        val root = XFile(schemeForConn(conn), "/", isDir = true)
        return Restored("s:$label", root, listChildren(root)) // 经 listChildren 以识别 git/restic
    }

    private fun fetchDir(dir: XFile): Restored =
        Restored(fileKey(dir), dir, listChildren(dir)) // 经 listChildren 以识别 git/restic

    /** 重连/解锁收藏指向的目录(restic 收藏若没有已存密码会失败,保持折叠——与手动展开时一致)。 */
    private fun fetchFavoriteById(id: String): Restored? {
        val fav = FavoritesStore.all(getApplication()).firstOrNull { it.id == id } ?: return null
        return resolveFavorite(fav, password = null)
    }

    private fun connFor(label: String): SavedConnection? =
        ConnectionStore.all(getApplication()).firstOrNull { it.label() == label }

    private fun descToXFile(d: String): XFile? = TreeKeys.dirOfDescriptor(d, ::sessionSchemeOf)

    /**
     * 连接标签 → 本次会话已注册的 scheme。连接已被删除、或这台服务器本次还没连上时
     * 都返回 null —— 描述符解析据此判断"这条恢复不了"。
     */
    private fun sessionSchemeOf(label: String): String? =
        if (connFor(label) == null) null else serverScheme["s:$label"]

    /** 保存上次位置的描述符(本地/应用/网络/分组/服务器/收藏;restic 仓库节点本身、SAF 跳过)。 */
    fun expandedDescriptors(): List<String> = expandedSnapshot().mapNotNull { key ->
        TreeKeys.descriptorOf(key) { scheme -> schemeToConn[scheme]?.label() }
    }

    /**
     * 当前目录描述符;还没解析出来(网络服务器没连上就退出)时把原样存回去,别丢。
     * 可持久化的来源只有本地、应用树与已连接服务器,其它(zip/restic/saf/git)返回 null。
     */
    fun currentDescriptor(): String? {
        val cur = currentDir ?: return pendingCurrentDesc
        return when {
            cur.scheme == "file" -> "file\t${cur.path}"
            cur.scheme == AppsFileSystem.SCHEME -> "apps\t${cur.path}"
            schemeToConn.containsKey(cur.scheme) -> "conn\t${schemeToConn[cur.scheme]!!.label()}\t${cur.path}"
            else -> pendingCurrentDesc
        }
    }

    /**
     * [file] 所在那一层的子项缓存桶 key。普通目录行就是 [fileKey],但**收藏 / 服务器 /
     * restic 仓库的根目录在树上没有自己的行**——子项直接挂在 `fav:`/`s:`/`restic:` 那一行
     * 下面(见 [addFavorite]/[addServer]),[children] 也是存在那个 key 上的。所以直接拿
     * `f:scheme:parentPath` 去查会落空:从收藏里点开的图片曾因此只有孤零零一张、显示
     * 1/1 且不能翻页。找不到时退回 `f:` 形式(调用方按"取不到就单张"处理)。
     */
    private fun siblingsKey(file: XFile): String {
        val fk = "f:${file.scheme}:${file.parentPath}"
        if (children.containsKey(fk)) return fk
        // 收藏/服务器根的路径可能带尾斜杠(存进来时是什么样就是什么样),parentPath 则一定不带
        val parent = file.parentPath.trimEnd('/').ifEmpty { "/" }
        return keyFile.entries.firstOrNull { (k, v) ->
            k != fk && v.scheme == file.scheme &&
                v.path.trimEnd('/').ifEmpty { "/" } == parent && children.containsKey(k)
        }?.key ?: fk
    }

    /** 与 [file] 同目录的图片列表 + 该文件在其中的下标(用于图片查看器左右切换)。 */
    fun imageSiblings(file: XFile): Pair<List<XFile>, Int> {
        // 与列表所见一致:隐藏文件不显示时也不参与翻页;万一目标本身不在其中(隐藏项从别处打开)退回单张
        val images = visible(children[siblingsKey(file)]).filter { !it.isDir && OpenFiles.isImage(it) }
            .takeIf { l -> l.any { it.path == file.path } } ?: listOf(file)
        val idx = images.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return images to idx
    }

    /** 与 [file] 同目录的音频列表(按面板当前排序)+ 该文件下标(用于音乐播放队列)。 */
    fun audioSiblings(file: XFile): Pair<List<XFile>, Int> {
        val audios = visible(children[siblingsKey(file)]).filter { !it.isDir && OpenFiles.isAudio(it) }
            .takeIf { l -> l.any { it.path == file.path } } ?: listOf(file)
        val idx = audios.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return audios to idx
    }

    /** 把 XFile 转成可持久化的播放列表曲目(本地/已连接服务器);其它来源返回 null。 */
    fun trackFrom(file: XFile): com.twig.app.PlaylistTrack? = when {
        file.scheme == "file" ->
            com.twig.app.PlaylistTrack(kind = "local", path = file.path, size = file.size, lastModified = file.lastModified)
        schemeToConn.containsKey(file.scheme) ->
            com.twig.app.PlaylistTrack(
                kind = "conn", path = file.path, connLabel = schemeToConn[file.scheme]!!.label(),
                size = file.size, lastModified = file.lastModified,
            )
        else -> null
    }

    // ---- 交互 ----

    fun toggle(node: Node) {
        when (node) {
            is FileNode -> toggleFile(node)
            is GroupNode -> {
                currentKey = node.key
                currentDir = null // 分组标题(局域网/FTP/…)不是真实目录,清掉避免复制/新建误用上一次选中的目录
                if (!expanded.remove(node.key)) accordionExpand(node.key)
                rebuild()
            }
            is ServerNode -> toggleServer(node)
            is ResticNode -> if (node.unlocked) toggleRestic(node) // 未解锁由 Fragment 弹密码
            is FavoriteNode -> Unit // 由 Fragment 处理(可能需密码)
            is CompareNode -> Unit // 由 Fragment 处理(跳到对比页)
            is ActionNode -> Unit // 由 Fragment 处理
            is InfoNode -> { // 点 ✕ 关闭
                val key = fileKey(node.file)
                infoOpen.remove(key)
                stopDirScan(key)
                rebuild()
            }
            is SearchNode -> closeSearch(node) // 点击虚拟目录行本身:收缩并移出
        }
    }

    fun expandGroup(id: String) {
        expanded.add("g:$id")
        rebuild()
    }

    /**
     * 当前"绿色框选"的文件/目录(服务器/分组等非文件节点返回 null),
     * 供未勾选任何项时作为复制/删除/重命名的默认操作对象。
     * 排除根目录与内部存储顶级节点,防误删整个存储。
     */
    fun currentSelection(): XFile? = currentKey?.let { k ->
        keyFile[k]?.takeIf {
            fileKey(it) == k && it.path != "/" &&
                !(it.scheme == "file" && it.path == Environment.getExternalStorageDirectory().absolutePath)
        }
    }

    /** 取(必要时建立)连接对应的已注册 scheme;阻塞 IO,失败返回 null。 */
    fun connSchemeBlocking(conn: SavedConnection): String? =
        runCatching { schemeForConn(conn) }.getOrNull()

    /** 仅重建树(组会重读已保存连接/SAF 授权),不重新列目录。 */
    fun refreshTree() = rebuild()

    /**
     * 非空时树上**只保留这一个根**——选择器的「就在这台服务器里挑脚本」用:
     * 别的来源(本地/其他服务器)整棵不出现,自然也就选不到,比选中后再报错好。
     */
    var lockRoot: XFile? = null

    /** [lockRoot] 那一行显示的名字(服务器名)。 */
    var lockLabel: String? = null

    /**
     * 挂载外部 App「用 Twig 打开」传进来的压缩包([ViewIntentActivity]):作为树顶的一行
     * 就地展开,展开逻辑与树里点开压缩包完全一致(经 [listChildren] → `ArchiveFileSystem.rootOf`)。
     * 只留最近一个——外部条目不属于任何真实目录,留一串历史挂载只会让树顶越堆越长。
     */
    fun mountExternal(archive: XFile) {
        val key = EXTERNAL_KEY_PREFIX + fileKey(archive)
        externalMount = archive
        keyFile[key] = archive
        currentKey = key
        children.remove(key) // 同一个包再次打开(内容可能变了)不吃上次的条目缓存
        loadingKeys.add(key)
        expanded.add(key)
        rebuild()
        viewModelScope.launch {
            val r = runCatching { withContext(io) { listChildren(archive) } }
            loadingKeys.remove(key)
            r.fold(
                {
                    putListing(key, it)
                    it.mountRoot?.let { root -> currentDir = root } // 外部打开的包同样可以当目标目录
                    accordionExpand(key); rebuild()
                },
                { expanded.remove(key); rebuild(); emitFailure(it, archive) },
            )
        }
    }

    /** 该 scheme 是否为已连接服务器(矩形树图判断能否"在另一面板显示")。 */
    fun isConnScheme(s: String): Boolean = schemeToConn.containsKey(s)

    /** 该 scheme 对应的连接(路径栏显示服务器名/类型图标用);非服务器来源为 null。 */
    fun connOf(s: String): SavedConnection? = schemeToConn[s]

    /**
     * 在树中逐级展开并定位到 [target](矩形树图"在另一面板显示"):
     * 本地按 内部存储/根目录 归属选根;服务器来源先展开分组与服务器节点再下钻。
     * 其余来源(压缩包内/restic 等)不支持。
     * [focus] 是 [target] 里要滚动定位到的那个文件("跳转到所在目录"从音乐播放页/桌面
     * 快捷方式过来时给出),高亮框仍框住目录本身,只是把列表滚到这一行。
     * [openGit] 时顺带展开该目录下的 Git 虚拟节点并定位到它(“最近位置”里的 git 项)。
     */
    fun revealPath(target: XFile, focus: XFile? = null, openGit: Boolean = false) {
        viewModelScope.launch {
            val r = runCatching {
                val plan = withContext(io) { planReveal(target, openGit) }
                applyReveal(plan)   // 主线程落表
                plan.gitKey
            }
            r.fold(
                { gitKey ->
                    currentDir = target
                    currentKey = fileKey(target)
                    scrollKey = gitKey ?: focus?.let { fileKey(it) }
                    rebuild()
                },
                { rebuild(); emitError(it.message) },
            )
        }
    }

    /**
     * 定位的"计划":哪些 key 该留着展开、哪些目录需要现列、git 虚拟根是哪个。
     * IO 阶段只产出它,不碰任何 VM 状态;落表见 [applyReveal]。
     */
    private class RevealPlan(
        val keep: Set<String>,
        /** 按从根到目标的顺序;已有缓存的只带 XFile,需要现列的带 [Restored]。 */
        val expand: List<Pair<String, XFile>>,
        val fetched: List<Restored>,
        val gitKey: String?,
    )

    /** 主线程:把 [RevealPlan] 落进树,并按手风琴规则把不在链上的分支折起来。 */
    private fun applyReveal(plan: RevealPlan) {
        plan.fetched.forEach { applyRestored(it) }
        plan.expand.forEach { (key, x) ->
            keyFile[key] = x
            expanded.add(key)
        }
        // 其余展开的分支(另一根目录/别的服务器等)全部折叠
        synchronized(expanded) { expanded.removeAll { it !in plan.keep } }
    }

    /** IO:算出定位计划(会按需建立连接、列目录),不写任何 VM 状态。 */
    private fun planReveal(target: XFile, openGit: Boolean): RevealPlan {
        // 手风琴规则:定位后同侧只保留这一条展开链,其余根/分支全折叠(与 accordionExpand 一致)。
        val keep = HashSet<String>()
        val chain = ArrayList<String>()
        val expand = ArrayList<Pair<String, XFile>>()
        val fetched = ArrayList<Restored>()
        if (target.scheme == "file") {
            val ext = Environment.getExternalStorageDirectory().absolutePath
            val root = if (target.path == ext || target.path.startsWith("$ext/")) ext else "/"
            var p = target.path
            while (true) {
                chain.add(0, p)
                if (p == root || p == "/" || p.isEmpty()) break
                p = XFile("file", p, isDir = true).parentPath
            }
            if (chain.firstOrNull() != root) chain.add(0, root)
        } else {
            // 本次会话该服务器还没展开过时 schemeToConn 里没有——scheme 是按连接确定性生成的
            // (Connections.schemeOf),按它反查已保存连接、当场建连接注册,而不是直接报错。
            val conn = schemeToConn[target.scheme]
                ?: ConnectionStore.all(getApplication()).firstOrNull { Connections.schemeOf(it) == target.scheme }
                    ?.also { schemeForConn(it) }
                ?: throw FsException(str(R.string.err_reveal_unsupported))
            val group = when (conn.type) {
                "smb" -> "lan"
                "webdav" -> "dav"
                else -> conn.type
            }
            keep.add("g:$group")
            expand.add("g:$group" to XFile(target.scheme, "/", isDir = true))
            val sKey = "s:${conn.label()}"
            if (!children.containsKey(sKey)) {
                fetchServer(conn.label())?.let { fetched.add(it) }
            }
            keep.add(sKey)
            expand.add(sKey to XFile(target.scheme, "/", isDir = true))
            var p = target.path // 服务器根的子项挂在服务器节点下,链不含 "/"
            while (p != "/" && p.isNotEmpty()) {
                chain.add(0, p)
                p = XFile(target.scheme, p, isDir = true).parentPath
            }
        }
        for (d in chain) {
            val x = XFile(target.scheme, d, isDir = true)
            val key = fileKey(x)
            keep.add(key)
            if (!children.containsKey(key)) fetched.add(fetchDir(x)) else expand.add(key to x)
        }
        // Git 虚拟节点挂在宿主目录行下,上面逐级展开时 listChildren 已顺带
        // ensureGit 注册好,这里只需把它也展开(不然只看到宿主目录、还得再点一次)
        var gitKey: String? = null
        if (openGit) {
            gitInfo[fileKey(target)]?.let { (s, _) ->
                val root = XFile(s, "/", isDir = true)
                val gk = fileKey(root)
                if (children.containsKey(gk)) expand.add(gk to root) else fetched.add(fetchDir(root))
                keep.add(gk)
                gitKey = gk
            }
        }
        return RevealPlan(keep, expand, fetched, gitKey)
    }

    // ---- 最近位置(见 [HistoryStore]:记目录,不记文件) ----

    /** 在某目录里打开了文件:把这个**目录**记进最近位置。 */
    fun noteOpenedIn(file: XFile) {
        noteHistory(XFile(file.scheme, file.parentPath, isDir = true), "dir")
    }

    private fun noteHistory(dir: XFile, kind: String) {
        val e = when {
            dir.scheme == "file" -> HistoryEntry(kind, dir.path)
            schemeToConn.containsKey(dir.scheme) ->
                HistoryEntry(kind, dir.path, schemeToConn[dir.scheme]!!.label())
            else -> return // 压缩包内/restic/saf 等来源存不成"如何到达",不记
        }
        HistoryStore.add(getApplication(), e)
    }

    /**
     * 跳到一条最近位置。网络连接可能本次会话还没展开过,按标签反查已保存连接、用
     * [Connections.schemeOf] 算出确定性 scheme 交给 [revealPath] 现连(它自己会处理)。
     * 连接已被删除时返回 false,由 UI 提示。
     */
    fun revealHistory(e: HistoryEntry): Boolean {
        val scheme = if (e.connLabel.isEmpty()) {
            "file"
        } else {
            val conn = ConnectionStore.all(getApplication()).firstOrNull { it.label() == e.connLabel }
                ?: return false
            Connections.schemeOf(conn)
        }
        revealPath(XFile(scheme, e.path, isDir = true), openGit = e.kind == "git")
        return true
    }

    /**
     * 编辑/删除服务后调用:丢弃该连接的缓存与折叠节点,下次展开用**新配置**重连。
     *
     * ★ 必须把 scheme 从 [FsRegistry] 里注销掉。scheme 是按连接标签确定性生成的,
     * 只改密码/主机密钥时标签不变、scheme 也不变,光清 VM 这几张表没用——
     * [Connections.ensure] 开头那句"已注册就复用"会把**旧配置建的**实例原样还回来,
     * 表现为改了配置却不生效,直到重启应用。顺带断开旧连接,别把 socket 漏在那儿。
     */
    fun forgetServer(label: String) {
        val key = "s:$label"
        val scheme = serverScheme.remove(key)
        if (scheme != null) {
            schemeToConn.remove(scheme)
            when (val fs = FsRegistry.unregister(scheme)) {
                is SmbFileSystem -> runCatching { fs.disconnect() }
                is SftpFileSystem -> runCatching { fs.disconnect() }
                else -> Unit
            }
        }
        serverInfo.remove(label)
        children.remove(key)
        expanded.remove(key)
        keyFile.remove(key)
        rebuild()
    }

    /**
     * 折叠当前目录并把"当前目录"上移一级。服务器/收藏的根目录不是独立的 [FileNode]
     * (子项直接挂在 [ServerNode]/[FavoriteNode] 行下,见 [addServer]/[addFavorite]),
     * 逐级 parentPath 走到那里后改为折叠该节点本身;再上一级折叠其所在分组([GroupNode]),
     * 一路退回树顶层——不再在到达某个来源自己的文件系统根时提前把 currentDir 清空了事。
     */
    fun up() {
        val key = currentKey ?: return
        when {
            key.startsWith("g:") -> {
                expanded.remove(key)
                currentKey = null
                currentDir = null
            }
            key.startsWith("s:") -> {
                expanded.remove(key)
                Thumbs.cancelPending(descendantFilesByKey(key))
                val conn = serverScheme[key]?.let { schemeToConn[it] }
                currentKey = conn?.let { "g:${groupIdFor(it)}" }
                currentDir = null
            }
            key.startsWith("fav:") -> {
                expanded.remove(key)
                currentKey = "g:fav"
                currentDir = null
            }
            else -> {
                val cur = currentDir ?: return
                expanded.remove(key)
                if (cur.path == "/" || cur.path.isEmpty()) {
                    currentDir = null
                    currentKey = null
                } else {
                    val parent = cur.copy(path = cur.parentPath)
                    currentDir = parent
                    currentKey = containerKeyFor(parent) ?: fileKey(parent)
                }
            }
        }
        rebuild()
    }

    /** 服务器所在分组 id(局域网/dav/sftp/ftp),与 [revealPath] 用的映射一致。 */
    private fun groupIdFor(conn: SavedConnection): String = when (conn.type) {
        "smb" -> "lan"
        "webdav" -> "dav"
        else -> conn.type
    }

    /** [x] 是否恰是某个已展开服务器/收藏的根目录——是则返回该节点的 key("s:.."/"fav:.."),
     *  供 [up] 从子目录折回来时改用容器节点本身,而不是一个从未真正展开过的 fileKey。 */
    private fun containerKeyFor(x: XFile): String? = keyFile.entries.firstOrNull { (k, v) ->
        (k.startsWith("s:") || k.startsWith("fav:")) && v.scheme == x.scheme && v.path == x.path
    }?.key

    /**
     * 只重列已展开的本地目录(外部应用增删文件后同步),网络目录不动。
     * 内容没变化时不重建树,避免无谓刷新。
     */
    fun refreshLocal() {
        val targets = expandedSnapshot().mapNotNull { k ->
            keyFile[k]?.takeIf { it.scheme == "file" && it.isDir }?.let { k to it }
        }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            // IO 只取,落表在下面(sortList 还要读 Prefs,本来也该在主线程)
            val fresh = HashMap<String, Listing>()
            withContext(io) {
                for ((k, f) in targets) runCatching { fresh[k] = listChildren(f) }
            }
            var changed = false
            for ((k, l) in fresh) {
                val sorted = sortList(applyListing(l))
                if (children[k] != sorted) changed = true
                children[k] = sorted
            }
            if (changed) rebuild()
        }
    }

    /**
     * 清掉 [dir] 自身的子项缓存(不管当前是否展开)。[refresh] 只重列"当前已展开"的目录——
     * 对着一个折叠的目录直接建子目录(长按菜单,不必先点开)时,若它之前展开过又被折叠,
     * 缓存留着旧列表,refresh 不会碰它,下次展开就还是建之前的样子(重启 app 才会因为全量
     * 重建而"看起来好了")。这里显式清缓存,保证下次展开必重新拉取。
     */
    fun invalidate(dir: XFile) {
        children.remove(fileKey(dir))
    }

    /** 增删改后刷新:重新列举所有已展开目录。 */
    fun refresh() {
        val targets = expandedSnapshot().mapNotNull { k -> keyFile[k]?.let { k to it } }
        viewModelScope.launch {
            val fresh = HashMap<String, Listing>()
            withContext(io) {
                for ((k, f) in targets) runCatching { fresh[k] = listChildren(f) }
            }
            for ((k, l) in fresh) putListing(k, l)
            rebuild()
        }
    }

    // ---- 展开逻辑 ----

    /**
     * 手风琴展开:折叠所有不在该节点祖先链上的节点(目录/服务器/分组/收藏均参与),
     * 全树只保持"当前选中"这一条展开链。
     */
    private fun accordionExpand(key: String) {
        val ancestors = ancestorKeysOf(key)
        // removeAll{} 内部是遍历,synchronizedSet 的遍历必须自己加锁(锁就是集合本身)
        synchronized(expanded) { expanded.removeAll { it != key && it !in ancestors } }
        expanded.add(key)
    }

    /** [expanded] 的快照;遍历它的地方一律先取快照,别直接迭代(见字段注释)。 */
    private fun expandedSnapshot(): List<String> = synchronized(expanded) { expanded.toList() }

    private fun ancestorKeysOf(key: String): Set<String> =
        TreeKeys.ancestorKeys(_state.value.rows.map { TreeKeys.Row(it.key, it.depth) }, key)

    /**
     * 长按菜单"属性":开/关该文件行下方的信息卡片。多张卡片可同时保持打开,
     * 互不影响;首次打开异步读取(EXIF/媒体信息/应用信息可能有 IO)。
     */
    fun toggleInfo(n: FileNode) = toggleInfo(n.file)

    /** 同上,但直接给文件(收藏行没有对应的 [FileNode],菜单里的"属性"走这个入口)。 */
    fun toggleInfo(file: XFile) {
        val key = fileKey(file)
        if (!infoOpen.remove(key)) {
            infoOpen.add(key)
            if (file.isDir) startDirScan(key, file)
            if (!infoCache.containsKey(key)) {
                viewModelScope.launch {
                    val d = withContext(io) {
                        runCatching { com.twig.app.FileInfo.load(getApplication(), file) }
                            .getOrElse {
                                com.twig.app.FileInfo.Details(emptyList(), it.message ?: str(R.string.info_failed))
                            }
                    }
                    if (infoOpen.contains(key)) {
                        infoCache[key] = d
                        rebuild()
                    }
                }
            }
        } else {
            stopDirScan(key)
        }
        rebuild()
    }

    /**
     * 目录属性卡片的递归统计(文件数/目录数/总大小):打开卡片即扫,边扫边刷。
     * 关闭卡片([toggleInfo] 再点一次 / ✕)或所在目录折叠([rebuild] 里的清理)都会取消——
     * 大目录树尤其是网络来源的扫描代价高,卡片看不见就不该继续跑。
     */
    private fun startDirScan(key: String, dir: XFile) {
        dirScan.remove(key)?.job?.cancel()
        val st = DirScanState()
        dirScan[key] = st
        st.job = viewModelScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            scanDirStat(dir, io).collect {
                st.stat = it
                rebuild()
            }
            // 扫得太快就把转圈多留一会儿(见 DIR_SCAN_MIN_SPIN_MS):数字早就是最终值了,
            // 这半秒只影响转圈什么时候停
            val left = DIR_SCAN_MIN_SPIN_MS - (android.os.SystemClock.elapsedRealtime() - t0)
            if (left > 0) kotlinx.coroutines.delay(left)
            st.scanning = false
            rebuild()
        }
    }

    private fun stopDirScan(key: String) {
        dirScan.remove(key)?.job?.cancel()
    }

    /** 仅供单测:仍在跑的目录递归统计条数(卡片关闭/折叠后必须归零)。 */
    internal fun activeDirScans(): Int = dirScan.count { it.value.job?.isActive == true }

    /**
     * 切换属性卡片的 tab。切到"哈希"tab(下标 == 分组数)时,本地文件自动开算;
     * 网络文件等用户点"计算"按钮(整文件读取,流量/耗时由用户决定)。
     */
    fun selectInfoTab(n: InfoNode, idx: Int) {
        val key = fileKey(n.file)
        infoTab[key] = idx
        val isHash = !n.file.isDir && idx == (n.details?.sections?.size ?: 0)
        if (isHash && n.file.scheme == "file" && !hashCache.containsKey(key) && !hashing.contains(key)) {
            startHash(key, n.file)
        }
        rebuild()
    }

    /** "哈希"tab 的手动计算按钮(网络文件)。 */
    fun computeHash(n: InfoNode) {
        val key = fileKey(n.file)
        if (!hashCache.containsKey(key) && !hashing.contains(key)) startHash(key, n.file)
        rebuild()
    }

    private fun startHash(key: String, file: XFile) {
        hashing.add(key)
        viewModelScope.launch {
            val r = runCatching {
                withContext(io) { com.twig.app.FileInfo.hashes(file) }
            }
            hashing.remove(key)
            if (infoOpen.contains(key)) {
                hashCache[key] = r.getOrElse { listOf(str(R.string.err_hash_failed) to (it.message ?: str(R.string.err_hash_read_error))) }
                rebuild()
            }
        }
    }

    /**
     * 发起递归通配符搜索:结果作为虚拟目录挂在 [root] 行下方,边扫边出(节流约 250ms 刷新一次,
     * 避免大量命中逐条触发整树重建)。同一目录重复发起时取消旧任务、清空旧结果重新开始。
     * [root] 当前必须在树中有对应的可见 [FileNode] 行(虚拟目录才有地方挂),否则本次调用
     * 静默丢弃——[rebuild] 的清理逻辑也会在该行不可见时自动取消任务、丢弃状态。
     *
     * 高亮框([CurrentDirFrame] 框住"currentKey 行 + 它的直接子级")随之切到搜索虚拟目录
     * 本身([SearchNode.key]),而不是停留在被搜索的目录——否则框住的是"目录 + 搜索结果
     * 虚拟目录标题行"这一层,框不到再下一层的结果列表(见 [closeSearch] 里的回落)。
     */
    fun startSearch(root: XFile, pattern: String) {
        val key = fileKey(root)
        if (_state.value.rows.none { it.key == key }) return
        searchState.remove(key)?.job?.cancel()
        val st = SearchState(pattern)
        searchState[key] = st
        currentDir = root
        currentKey = "search:$key"
        rebuild()
        var lastUi = 0L
        st.job = viewModelScope.launch {
            scanSearch(root, pattern).collect { f ->
                st.results.add(f)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUi > 250) {
                    lastUi = now
                    rebuild()
                }
            }
            st.scanning = false
            rebuild()
        }
    }

    /**
     * 点击搜索虚拟目录行本身:取消未完成的扫描并把它从树上移出(结果不保留,需要重新发起)。
     * 高亮框若正停在这个搜索节点上,回落到被搜索的目录本身(而不是悬空指向一个已消失的 key)。
     */
    private fun closeSearch(node: SearchNode) {
        val rootKey = fileKey(node.root)
        searchState.remove(rootKey)?.job?.cancel()
        if (currentKey == node.key) currentKey = rootKey
        rebuild()
    }

    /** 长按菜单"以压缩包方式打开":apk 默认不可展开,这里放行并当普通压缩包挂载浏览。 */
    fun openAsArchive(n: FileNode) {
        forcedArchive.add(n.key)
        toggleFile(n.copy(expandable = true))
    }

    /**
     * 长按菜单"刷新":丢弃该节点的子项缓存,重新拉取(git 根节点额外清 status/log/diff 缓存)。
     * 通用于任意可展开节点,不止 git——用户按需手动刷新目录/压缩包内容。
     */
    fun refreshNode(n: FileNode) {
        if (!n.expandable) return
        val key = n.key
        children.remove(key)
        // 折叠状态也要清 git 缓存:SMB/WebDAV 仓库手动刷新是唯一刷新途径,
        // logCache 无 TTL,折叠时"刷新"若不清,下次展开还是旧的
        val isExpanded = expanded.contains(key)
        if (isExpanded) loadingKeys.add(key)
        rebuild()
        viewModelScope.launch {
            val r = runCatching {
                withContext(io) {
                    // invalidate 放到发请求前一刻(而不是上面主线程 rebuild 之前):
                    // 提前清会让 rebuild 用空缓存渲染出"注册时的旧分支名",闪一下才被新值盖掉
                    (FsRegistry.of(n.file) as? GitFileSystem)?.invalidate()
                    if (isExpanded) listChildren(n.file) else null
                }
            }
            loadingKeys.remove(key)
            r.fold(
                { it?.let { l -> putListing(key, l) }; rebuild() }, // 折叠时也 rebuild:标题可能已随 invalidate 更新
                { rebuild(); emitFailure(it, n.file) },
            )
        }
    }

    private fun toggleFile(n: FileNode) {
        if (!n.expandable) return
        if (n.file.isDir) currentDir = n.file
        val key = n.key
        currentKey = key
        // 展开(不是折叠)Git 虚拟根时,把宿主目录记进最近位置——git 视图里的子节点
        // (更改/历史/某次提交)scheme 相同但 path 不是 "/",不重复记
        if (key !in expanded && n.file.path == "/" && n.file.scheme.startsWith("git")) {
            schemeToGitHost[n.file.scheme]?.let { noteHistory(it, "git") }
        }
        // 本地/SSH git(status()/log() 本身不缓存)下的任意节点——根/更改/历史/某次提交——每次
        // 展开都强制重拉,不吃 children 缓存;根节点之外的子节点若不单独判断,展开链路上只有
        // 根节点会重拉,子节点(如"历史")仍会命中自己那份 children 缓存,看起来"没刷新"。
        // (SMB/WebDAV 等靠 XFileGitFs 解析的远程 git 太贵,不在此列,见 cheapGitSchemes 注释)
        val freshGit = n.file.scheme in cheapGitSchemes
        when {
            expanded.remove(key) -> {
                Thumbs.cancelPending(descendantFiles(n.file))
                // 收起该目录时一并移出挂在它下面的搜索结果虚拟目录(不会自然从 rows 里消失——
                // 它渲染时不看 exp,见 addFile),避免折叠后搜索结果孤零零留在一个收起的目录下面
                searchState.remove(key)?.job?.cancel()
                // 压缩包收起来了,粘贴目标不能还留在包里:退回它所在的那个目录
                if (mountRoots.containsKey(key)) {
                    runCatching { FsRegistry.of(n.file).parentOf(n.file) }.getOrNull()
                        ?.let { currentDir = it }
                }
                rebuild()
            }
            searchState.containsKey(key) -> {
                // 该目录本身并未真正展开,箭头只是因为挂了搜索结果而显示成"已展开"(见 addFile)。
                // 点击这一行应该按箭头看起来的样子处理——当作"收起":只关掉搜索,不要落到下面
                // 的分支去反而把它真正展开、拉出这个目录原本没请求过的文件/目录。
                searchState.remove(key)?.job?.cancel()
                rebuild()
            }
            children.containsKey(key) && !freshGit -> {
                mountRoots[key]?.let { currentDir = it } // 压缩包:当前目录落到包根
                accordionExpand(key); rebuild()
            }
            else -> {
                loadingKeys.add(key); rebuild() // 先显示转圈
                viewModelScope.launch {
                    val r = runCatching {
                        withContext(io) {
                            // invalidate 放到发请求前一刻:提前在主线程清掉缓存,上面这次
                            // rebuild 会用空缓存把 Git 节点的标题渲成"注册时的旧分支名",
                            // 等新值填回来之前多闪一次,不如干脆晚点清
                            if (freshGit) (FsRegistry.of(n.file) as? GitFileSystem)?.invalidate()
                            listChildren(n.file)
                        }
                    }
                    loadingKeys.remove(key)
                    r.fold(
                        {
                            putListing(key, it)
                            // 展开压缩包 = 选中包根,和展开目录一样能当复制/新建的目标。
                            // 树上那一行是宿主文件(isDir=false),上面那句 `if (isDir)` 管不着它
                            it.mountRoot?.let { root -> currentDir = root }
                            accordionExpand(key); rebuild()
                        },
                        { rebuild(); emitFailure(it, n.file) },
                    )
                }
            }
        }
    }

    private fun toggleServer(n: ServerNode) {
        val key = n.key
        currentKey = key
        when {
            expanded.remove(key) -> {
                Thumbs.cancelPending(descendantFilesByKey(key))
                currentDir = keyFile[key] ?: currentDir // 折叠后高亮留在服务器行,目标同步成它的根,不留上一次目录
                rebuild()
            }
            children.containsKey(key) -> {
                currentDir = keyFile[key] ?: currentDir
                accordionExpand(key); rebuild()
            }
            connecting.contains(key) -> Unit
            else -> {
                connecting.add(key)
                rebuild()
                viewModelScope.launch {
                    val r = runCatching { withContext(io) { connectAndList(n.conn, key) } }
                    connecting.remove(key)
                    r.fold(
                        {
                            applyRestored(it)
                            currentDir = keyFile[key] ?: currentDir
                            accordionExpand(key); rebuild()
                        },
                        { rebuild(); emitError(it.message) },
                    )
                }
            }
        }
    }

    /** 连接服务器(每台一个唯一 scheme 注册进 FsRegistry)并列出根;IO 线程,不写 VM 状态。 */
    private fun connectAndList(conn: SavedConnection, key: String): Restored {
        val root = XFile(schemeForConn(conn), "/", isDir = true)
        return Restored(key, root, listChildren(root))
    }

    /**
     * 取(或建立)某连接对应的已注册 scheme;服务器节点与收藏共用同一连接。
     * 真正建连接/注册的活儿交给 [Connections.ensure](全项目唯一一份),这里只
     * 维护 VM 自己的反查表。
     */
    private fun schemeForConn(conn: SavedConnection): String {
        val nodeKey = "s:${conn.label()}"
        serverScheme[nodeKey]?.let { return it }
        val s = Connections.ensure(getApplication(), conn) { info -> serverInfo[conn.label()] = info }
        serverScheme[nodeKey] = s
        schemeToConn[s] = conn
        return s
    }

    /** 取(或建立)某 restic 仓库对应的已注册 scheme。 */
    private fun schemeForRestic(repoDir: XFile, password: String): String {
        val nodeKey = "restic:${fileKey(repoDir)}"
        resticScheme[nodeKey]?.let { return it }
        val repo = ResticRepo.open(FsRegistry.of(repoDir), repoDir, password, NativeZstd())
        val s = "restic" + Integer.toHexString(nodeKey.hashCode())
        FsRegistry.register(ResticFileSystem(repo, s))
        resticScheme[nodeKey] = s
        schemeToRestic[s] = repoDir
        return s
    }

    /**
     * 用密码解锁 restic 仓库:打开、注册 ResticFileSystem、把快照作为子项。
     *
     * 解锁期间该行显示转圈(复用 [connecting]),并且**忽略重复点击**——网络仓库解锁要
     * 几十秒,以前既没有转圈也没有去重,用户会以为没反应而反复点,每点一次就多起一次
     * 完整的 `ResticRepo.open`(scrypt + 整读 index),全都挤在 SMB 那把串行锁上排队,
     * 越点越慢,最后表现成"永远打不开"。
     */
    fun unlockRestic(repoDir: XFile, password: String, onResult: (Boolean, String?) -> Unit) {
        val nodeKey = "restic:${fileKey(repoDir)}"
        if (!connecting.add(nodeKey)) return // 已经在解锁了,这一下点击不再叠加一次
        rebuild() // 先把转圈显示出来
        viewModelScope.launch {
            val r = runCatching {
                withContext(io) {
                    val scheme = schemeForRestic(repoDir, password)
                    scheme to FsRegistry.of(scheme).list(XFile(scheme, "/", isDir = true))
                }
            }
            connecting.remove(nodeKey)
            r.fold(
                onSuccess = { (_, snaps) ->
                    putChildren(nodeKey, snaps)
                    accordionExpand(nodeKey)
                    rebuild()
                    onResult(true, null)
                },
                onFailure = {
                    forgetRestic(nodeKey)
                    rebuild()
                    onResult(false, it.message)
                },
            )
        }
    }

    /**
     * 丢弃一次没走完的解锁。★ 必须回滚(2026-08-04 定案):[schemeForRestic] 一打开仓库
     * 就把 scheme 记进 [resticScheme],可紧随其后的"列快照"还可能失败(SMB 断线/短读/
     * index 读到一半超时)。不回滚的话这个节点会渲染成 unlocked 而 `children` 是空的,
     * 之后点它只走 [toggleRestic] 去展开一个空节点,**永远不再重试解锁**——症状正是
     * 用户报的"第一次输密码进去过,之后再点就一直展不开"。
     */
    private fun forgetRestic(nodeKey: String) {
        val s = resticScheme.remove(nodeKey) ?: return
        schemeToRestic.remove(s)
        FsRegistry.unregister(s)
        children.remove(nodeKey)
        expanded.remove(nodeKey)
    }

    /** 折叠/展开已解锁的 restic 节点。 */
    fun toggleRestic(node: ResticNode) {
        currentKey = node.key
        if (expanded.remove(node.key)) {
            Thumbs.cancelPending(descendantFilesByKey(node.key))
            rebuild()
        } else {
            accordionExpand(node.key); rebuild()
        }
    }

    // ---- 收藏 ----

    /** 根据一个目录节点构造收藏(不支持的来源返回 null)。 */
    fun favoriteFrom(file: XFile): Favorite? {
        if (!file.isDir) return null
        return when {
            file.scheme == "file" ->
                Favorite(label = file.name, kind = "local", path = file.path)
            schemeToConn.containsKey(file.scheme) -> {
                val c = schemeToConn[file.scheme]!!
                Favorite(label = "${c.displayLabel()}:${file.name}", kind = "conn", path = file.path, connLabel = c.label())
            }
            schemeToRestic.containsKey(file.scheme) -> {
                val repoDir = schemeToRestic[file.scheme]!! // 底层仓库 XFile(可能在 SMB 等上)
                val repoConn = when {
                    repoDir.scheme == "file" -> ""
                    schemeToConn.containsKey(repoDir.scheme) -> schemeToConn[repoDir.scheme]!!.label()
                    else -> return null // 仓库在不可持久化的来源(如 zip/SAF)上,暂不支持收藏
                }
                Favorite(
                    label = "restic:${file.name}",
                    kind = "restic",
                    path = file.path,
                    repoPath = repoDir.path,
                    repoConnLabel = repoConn,
                )
            }
            else -> null
        }
    }

    fun addFavorite(fav: Favorite) {
        FavoritesStore.add(getApplication(), fav)
        expanded.add("g:fav")
        rebuild()
    }

    fun removeFavorite(fav: Favorite) {
        FavoritesStore.remove(getApplication(), fav)
        rebuild()
    }

    /** 重命名收藏(传空串等于恢复自动生成的完整路径名)。 */
    fun renameFavorite(fav: Favorite, newLabel: String) {
        FavoritesStore.rename(getApplication(), fav, newLabel)
        rebuild()
    }

    // ---- 对比收藏 ----

    fun removeCompare(session: CompareSession) {
        CompareStore.remove(getApplication(), session)
        rebuild()
    }

    fun renameCompare(session: CompareSession, newLabel: String) {
        CompareStore.rename(getApplication(), session, newLabel)
        rebuild()
    }

    /**
     * 收藏指向的真实目录——只有展开(连接/解锁)过一次才解析得出([resolveFavorite] 存进
     * [keyFile]);没解析过返回 null,菜单里那些要拿目录说事的项就不给。
     */
    fun favoriteTarget(node: FavoriteNode): XFile? = keyFile[node.key]

    /** 服务器节点指向的根目录——同 [favoriteTarget],连接(展开)过一次才解析得出。 */
    fun serverTarget(node: ServerNode): XFile? = keyFile[node.key]

    /** 刷新收藏节点:丢掉缓存的子项并重列(连接/解锁已经建好,不用重走 resolveFavorite)。 */
    fun refreshFavorite(node: FavoriteNode) = refreshVirtual(node.key)

    /** 同上,用于服务器节点(连接已建好,不重连)。 */
    fun refreshServer(node: ServerNode) = refreshVirtual(node.key)

    /** 收藏/服务器这类"子项挂在自己 key 下"的虚拟行的刷新,见 [refreshNode] 的普通目录版。 */
    private fun refreshVirtual(key: String) {
        val target = keyFile[key] ?: return
        children.remove(key)
        if (!expanded.contains(key)) { rebuild(); return }
        connecting.add(key) // 收藏行的转圈复用"连接中"指示
        rebuild()
        viewModelScope.launch {
            val r = runCatching { withContext(io) { listChildren(target) } }
            connecting.remove(key)
            r.fold(
                { putListing(key, it); rebuild() },
                { rebuild(); emitError(it.message) },
            )
        }
    }

    /**
     * 展开/折叠收藏节点。展开时按需连接(网络)/解锁(restic)并列出目标目录。
     * restic 需要密码时由 [password] 提供(为空则失败,由 Fragment 弹框重试)。
     */
    fun toggleFavorite(node: FavoriteNode, password: String?, onResult: (Boolean, String?) -> Unit) {
        val key = node.key
        currentKey = key
        when {
            expanded.remove(key) -> {
                currentDir = keyFile[key] ?: currentDir // 收藏本身也是个真实目录,和 toggleServer 一致地同步成当前目标
                rebuild(); onResult(true, null)
            }
            children.containsKey(key) -> {
                currentDir = keyFile[key] ?: currentDir
                accordionExpand(key); rebuild(); onResult(true, null)
            }
            connecting.contains(key) -> Unit
            else -> {
                connecting.add(key); rebuild()
                viewModelScope.launch {
                    val r = runCatching { withContext(io) { resolveFavorite(node.fav, password) } }
                    connecting.remove(key)
                    r.fold(
                        { restored ->
                            applyRestored(restored)
                            currentDir = keyFile[key] ?: currentDir
                            accordionExpand(key); rebuild(); onResult(true, null)
                        },
                        { rebuild(); onResult(false, it.message) },
                    )
                }
            }
        }
    }

    private fun resolveFavorite(fav: Favorite, password: String?): Restored {
        var extra: Pair<String, List<XFile>>? = null
        val scheme = when (fav.kind) {
            "local" -> "file"
            "conn" -> {
                val conn = ConnectionStore.all(getApplication()).firstOrNull { it.label() == fav.connLabel }
                    ?: throw com.twig.core.FsException(str(R.string.err_conn_deleted))
                schemeForConn(conn)
            }
            "restic" -> {
                // 先确保仓库所在的底层来源可用(本地或某网络连接)
                val repoScheme = if (fav.repoConnLabel.isEmpty()) {
                    "file"
                } else {
                    val conn = ConnectionStore.all(getApplication()).firstOrNull { it.label() == fav.repoConnLabel }
                        ?: throw com.twig.core.FsException(str(R.string.err_repo_conn_deleted))
                    schemeForConn(conn)
                }
                val repoDir = XFile(repoScheme, fav.repoPath, isDir = true)
                val pw = password ?: Prefs.resticPassword(getApplication(), fav.repoPath)
                    ?: throw com.twig.core.FsException(str(R.string.err_need_restic_password))
                val s = schemeForRestic(repoDir, pw)
                // 收藏走的是这条独立解锁路径,不经过树里正常展开 restic 节点的 unlockRestic()——
                // 那边的快照列表缓存(nodeKey)不会顺带被填。缺了它,同一仓库稍后在树里展开
                // "所有快照" 会因为 resticScheme 已标记"已解锁"而直接读缓存,读到空的。
                val nodeKey = "restic:${fileKey(repoDir)}"
                // 快照列表也只是取回来,落表交给 applyRestored
                if (!children.containsKey(nodeKey)) {
                    extra = nodeKey to FsRegistry.of(s).list(XFile(s, "/", isDir = true))
                }
                s
            }
            else -> throw com.twig.core.FsException(str(R.string.err_unsupported_favorite))
        }
        val target = XFile(scheme, fav.path, isDir = true)
        return Restored("fav:${fav.id}", target, listChildren(target), extra) // listChildren 顺带识别 git/restic
    }

    /**
     * 一次列举的结果:子项 + 这次**顺带发现**的东西。
     *
     * 发现(restic 仓库 / git 仓库)原来是 [listChildren] 直接写进 VM 那几张表的,
     * 而它跑在 IO 线程上。现在改成带出来,由主线程 [applyListing] 落表——
     * IO 只负责"取回数据",这是这个类里唯一该记住的线程规则。
     */
    private class Listing(
        val children: List<XFile>,
        /** 该目录本身是个 restic 仓库(值为 `fileKey(dir)`)。 */
        val resticRepo: String? = null,
        /** 该目录下有 .git;数据源已建好并注册进 FsRegistry,待登记进 VM 的反查表。 */
        val git: Git? = null,
        /**
         * 这次列的是个压缩包,值为**挂载后的包根**(scheme 是 zip/7z/rar 那套)。
         * 展开它时要拿这个当"当前目录",复制/新建才落得进包里——树上那一行的 XFile
         * 是**宿主文件**(isDir=false),不能当目录用。
         */
        val mountRoot: XFile? = null,
    ) {
        class Git(val dirKey: String, val dir: XFile, val scheme: String, val label: String, val cheap: Boolean)
    }

    /**
     * 把 [listChildren] 的发现落进 VM 状态,返回子项。**只能在主线程调用。**
     * 幂等:同一个目录重复 apply 不会有副作用(scheme 由 key 确定性算出)。
     */
    private fun applyListing(l: Listing): List<XFile> {
        l.resticRepo?.let { resticRepos.add(it) }
        l.git?.let { g ->
            gitInfo[g.dirKey] = g.scheme to g.label
            schemeToGitHost[g.scheme] = g.dir
            if (g.cheap) cheapGitSchemes.add(g.scheme)
        }
        return l.children
    }

    /** [applyListing] + 排序 + 写进 [children];主线程。 */
    private fun putListing(key: String, l: Listing) {
        l.mountRoot?.let { mountRoots[key] = it }
        putChildren(key, applyListing(l))
    }

    /**
     * 已展开的压缩包 key → 包根目录。展开一个包时"当前目录"要落到这儿,
     * 复制/新建才进得了包里(见 [toggleFile])。**不能塞进 [keyFile]** ——
     * 那张表存的是"重新列举需要的 XFile",对压缩包必须是**宿主文件**本身
     * (刷新走 [listChildren] 的归档分支,要物化、要解密码),换成包根就全乱了。
     */
    private val mountRoots = ConcurrentHashMap<String, XFile>()

    /** 列子项:目录直接列(并识别 restic 仓库 / git 仓库);压缩包挂载后列包根。IO 线程。 */
    private fun listChildren(file: XFile): Listing =
        if (file.isDir) {
            val kids = FsRegistry.of(file).list(file)
            Listing(
                children = kids,
                resticRepo = if (ResticRepo.looksLikeRepo(kids)) fileKey(file) else null,
                // .git 也可能是**文件**(worktree / 子模块,内容是 "gitdir: …"),别只认目录
                git = if (kids.any { it.name == ".git" }) buildGit(file) else null,
            )
        } else {
            // zip/7z 经定位读通道流式解析(远程免下载)。两种情况先物化到缓存(见 archiveTarget):
            // - rar:junrar 只认本地文件
            // - 嵌套包(包中包)且内层条目被压缩:隔着外层解压流 seek 会退化成
            //   反复全量解压。STORED(未压缩,zip 套 zip 的常态)可直接切片,免物化秒开
            val (afs, archive) = archiveTarget(file)
            // ★ rootOf 必须排在探测加密**之前**:非本地宿主(SMB/WebDAV/S3…)是挂载
            // 那一刻才登记的,在那之前 needsPassword 会把远程路径当本地文件去开,
            // 读不到字节又被内部 runCatching 吞成"不需要密码"——远程加密包就再也
            // 不弹密码框了(见 RemoteArchiveMountTest)
            val root = afs.rootOf(archive)
            unlockIfEncrypted(afs, archive, file)
            Listing(afs.list(root), mountRoot = root)
        }

    // ---- 加密压缩包 ----

    /**
     * 挂载前解锁加密包。已解锁过的直接放行;有保存的密码就用它;没有(或存的那个已经不对)
     * 就抛 [ArchivePasswordException],由 UI 弹框问。**IO 线程**(要读归档头)。
     *
     * [original] 是用户点的那个文件,[archive] 可能是它物化到缓存后的本地副本——
     * 密码按 [original] 记,这样 SMB 上的加密包换了缓存文件名也还认得。
     */
    private fun unlockIfEncrypted(afs: ArchiveFileSystem, archive: XFile, original: XFile) {
        val path = archive.path
        if (afs.hasPassword(path) || !afs.needsPassword(path)) return
        val key = archivePwKey(original)
        val saved = Prefs.archivePassword(getApplication(), key) ?: throw ArchivePasswordException(path)
        if (!afs.checkPassword(path, saved)) {
            // 存的那个已经不管用了(包被重新加密过),清掉免得每次都白试一遍
            Prefs.setArchivePassword(getApplication(), key, null)
            throw ArchivePasswordException(path, wrong = true)
        }
        afs.setPassword(path, saved)
    }

    /**
     * 用户在密码框里给了密码:校验通过就记下来并重新展开 [file],不通过回 false 让 UI 再问。
     * [save] 决定要不要落盘([Prefs.setArchivePassword])。
     */
    fun unlockArchive(file: XFile, password: String, save: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                withContext(io) {
                    val (afs, archive) = archiveTarget(file)
                    afs.rootOf(archive) // 同上:先挂载登记宿主,远程包才读得到字节
                    if (!afs.checkPassword(archive.path, password)) return@withContext false
                    afs.setPassword(archive.path, password)
                    true
                }
            }.getOrElse { emitFailure(it, null); false }
            onResult(ok)
            if (!ok) return@launch
            if (save) Prefs.setArchivePassword(getApplication(), archivePwKey(file), password)
            // 上一次展开是失败收场的,缓存里没有它的子项,这里重新走一遍完整挂载
            children.remove(fileKey(file))
            expandFileNode(file)
        }
    }

    /** 密码存储的 key:用**用户点的那个文件**(而不是缓存副本路径),换设备缓存也不影响。 */
    private fun archivePwKey(file: XFile): String = "${file.scheme}:${file.path}"

    /**
     * 归档文件 → (对应的归档 FileSystem, 真正要挂载的归档 XFile)。
     * 物化规则与 [listChildren] 完全一致,两处都要用所以抽出来。IO 线程。
     */
    private fun archiveTarget(file: XFile): Pair<ArchiveFileSystem, XFile> {
        val scheme = Archives.schemeFor(file) ?: throw FsException(str(R.string.err_unsupported_type))
        val afs = FsRegistry.of(scheme) as ArchiveFileSystem
        val hostFs = runCatching { FsRegistry.of(file) }.getOrNull()
        val needLocal = scheme == com.twig.fs.archive.RarFileSystem.SCHEME ||
            (hostFs is ArchiveFileSystem && !hostFs.fastRandom(file))
        return afs to if (needLocal) localArchive(file) else file
    }

    /** 解锁成功后重新展开这一行(树里已有的那个节点,或外部挂载的那一行)。 */
    private fun expandFileNode(file: XFile) {
        val key = fileKey(file)
        if (externalMount?.let { fileKey(it) } == key) {
            mountExternal(file) // 它自己会用 EXTERNAL_KEY_PREFIX 那套 key
            return
        }
        val node = _state.value.rows.filterIsInstance<FileNode>().firstOrNull { it.key == key }
        if (node != null) {
            expanded.remove(key) // toggleFile 是"切换",先保证它处在折叠态
            toggleFile(node)
        }
    }

    /**
     * 把归档物化到缓存目录(RAR 与嵌套包需要),按"来源+路径+大小+修改时间"缓存,
     * 重复展开不重复下载/解压;本地文件直接返回。
     */
    private fun localArchive(file: XFile): XFile {
        if (file.scheme == ArchiveFileSystem.HOST_SCHEME) return file
        val dir = com.twig.app.CacheDirs.dir(getApplication(), com.twig.app.CacheDirs.ARCHIVES)
        val key = Integer.toHexString(
            "${file.scheme}:${file.path}:${file.size}:${file.lastModified}".hashCode(),
        )
        val out = java.io.File(dir, "${key}_${file.name}")
        if (!out.exists() || out.length() != file.size) {
            val tmp = java.io.File(dir, "$key.part")
            try {
                // 1MB 缓冲(默认 8KB 会把网络往返延迟放大成大量小读,参照 CopyEngine)
                FsRegistry.of(file).openInput(file).use { ins ->
                    tmp.outputStream().use { ins.copyTo(it, 1 shl 20) }
                }
                out.delete()
                if (!tmp.renameTo(out)) throw FsException(str(R.string.err_cache_archive_failed, file.name))
            } finally {
                tmp.delete()
            }
            com.twig.app.CacheDirs.trim(dir, keep = out)
        }
        out.setLastModified(System.currentTimeMillis()) // LRU 记一次使用
        return XFile(
            scheme = ArchiveFileSystem.HOST_SCHEME, path = out.path,
            isDir = false, size = out.length(), lastModified = file.lastModified,
        )
    }

    // ---- 建树 ----

    private fun rebuild() {
        val rows = ArrayList<Node>()
        attachedKeys.clear()
        showHidden = Prefs.showHidden(getApplication()) // 每轮读一次,不在每个目录里反复查 SharedPreferences
        val lock = lockRoot
        if (lock != null) {
            addFile(rows, lock, 0, lockLabel)
        } else {
            val ext = Environment.getExternalStorageDirectory().absolutePath
            // 外部打开的压缩包放最前面:它不属于任何一棵存储树,夹在中间反而找不到
            externalMount?.let { addFile(rows, it, 0, keyPrefix = EXTERNAL_KEY_PREFIX) }
            addGroup(rows, "fav", str(R.string.group_fav))
            // 对比收藏没有专门的空态提示——一次都没保存过就整个不露这一组,不占地方
            if (CompareStore.all(getApplication()).isNotEmpty()) {
                addGroup(rows, "cmp", str(R.string.compare_saved))
            }
            addFile(rows, XFile("file", ext, isDir = true), 0, str(R.string.group_internal_storage), capacity(ext))
            addFile(rows, XFile("file", "/", isDir = true), 0, str(R.string.group_root), capacity("/"))
            addGroup(rows, "lan", str(R.string.group_lan))
            addGroup(rows, "ftp", "FTP")
            addGroup(rows, "sftp", "SSH (SFTP)")
            addGroup(rows, "dav", "WebDAV")
            addGroup(rows, "s3", "S3")
            addGroup(rows, "saf", str(R.string.group_saf))
            // 应用管理:虚拟只读来源,和存储节点一样是棵可就地展开的树(已安装/系统)
            addFile(
                rows,
                XFile(AppsFileSystem.SCHEME, "/", isDir = true, canWrite = false),
                0,
                str(R.string.apps_root),
            )
        }
        // 所在目录被折叠(卡片行没被建出来)的属性卡片自动关闭并丢缓存
        val liveInfo = rows.mapNotNullTo(HashSet()) { (it as? InfoNode)?.let { n -> fileKey(n.file) } }
        infoOpen.retainAll(liveInfo)
        infoCache.keys.retainAll(infoOpen)
        infoTab.keys.retainAll(infoOpen)
        hashCache.keys.retainAll(infoOpen)
        // 卡片没了(关闭/折叠)就别继续扫目录
        dirScan.keys.toList().forEach { k -> if (k !in infoOpen) dirScan.remove(k)?.job?.cancel() }
        // 所在目录行不可见(祖先被折叠)的搜索任务自动取消并丢弃,与属性卡片一致
        val liveSearch = rows.mapNotNullTo(HashSet()) { (it as? SearchNode)?.let { n -> fileKey(n.root) } }
        searchState.keys.toList().forEach { k ->
            if (k !in liveSearch) {
                searchState.remove(k)?.job?.cancel()
                if (currentKey == "search:$k") currentKey = null // 高亮曾指向它,行没了就不悬空
            }
        }
        _state.value = State(rows, currentDir, currentKey, null, null, restoring, scrollKey)
    }

    private fun addFile(
        rows: MutableList<Node>,
        file: XFile,
        depth: Int,
        label: String? = null,
        capacity: String? = null,
        /** 见 [FileNode.keyPrefix];整棵子树都要带着它,否则包内条目又会和原位置那棵撞上。 */
        keyPrefix: String = "",
    ) {
        val key = keyPrefix + fileKey(file)
        val expandable = file.isDir || expandableArchive(file, key)
        if (expandable) keyFile[key] = file
        val exp = expandable && expanded.contains(key)
        // 挂了搜索结果的目录箭头显示成"已展开"(哪怕本身没真正展开)——视觉上呼应下方冒出的
        // 搜索结果虚拟目录;但这只影响图标,真正的子项列表仍只在 exp 为真时才拉取/渲染,
        // 未展开的目录发起搜索后不会连带显示自己下面原本的文件/目录。
        rows += FileNode(
            file, depth, expandable, exp || searchState.containsKey(key),
            label, capacity, loadingKeys.contains(key), keyPrefix,
        )
        addAttachments(rows, file, depth)
        if (exp) {
            // git 虚拟根节点放最前面,不用在一堆真实文件后面翻找。
            // 显示名不用 gitInfo 里注册时冻结的那份(切分支后会一直显示旧分支名),
            // 改读 GitFileSystem.displayName——它跟着 statusCache 里最新的分支走。
            gitInfo[key]?.let { (s, gl) ->
                val live = (runCatching { FsRegistry.of(s) }.getOrNull() as? GitFileSystem)?.displayName ?: gl
                addFile(rows, XFile(s, "/", isDir = true, displayName = live, canWrite = false), depth + 1)
            }
            visible(children[key]).forEach { addFile(rows, it, depth + 1, keyPrefix = keyPrefix) }
            if (resticRepos.contains(key)) addRestic(rows, file, depth + 1)
        }
    }

    /**
     * 挂在某个目录行下方的附属行:属性卡片 + 搜索结果虚拟目录(都不依赖该目录是否展开,
     * 展开时排在其子项之前)。[depth] 是所属行自己的层级。收藏行没走 [addFile],但菜单里
     * 同样能开属性/发起搜索,所以两处共用这段。
     */
    private fun addAttachments(rows: MutableList<Node>, file: XFile, depth: Int) {
        val key = fileKey(file)
        // 同一个目录可能在树上出现多次(收藏 + 存储树里的原位置、搜索结果里的同一目录……),
        // 附属行的 key 只按目录算,重复挂会在 DiffUtil 里撞 key —— 只认这一轮的第一处。
        if (!attachedKeys.add(key)) return
        if (infoOpen.contains(key)) {
            rows += InfoNode(
                file, depth + 1, infoCache[key],
                tab = infoTab[key] ?: 0,
                hashRows = hashCache[key],
                hashing = hashing.contains(key),
                dirStat = dirScan[key]?.stat,
                scanning = dirScan[key]?.scanning == true,
            )
        }
        searchState[key]?.let { st ->
            rows += SearchNode(
                file, depth + 1, st.pattern, st.scanning,
                matchedFiles = st.results.count { !it.isDir },
                matchedDirs = st.results.count { it.isDir },
            )
            st.results.forEach { addFile(rows, it, depth + 2) }
        }
    }

    /**
     * 目录检测到 .git:建数据源、注册虚拟文件系统,把要登记的东西带回去(**IO 线程**)。
     *
     * 注册进 [FsRegistry] 留在这里而不是搬到主线程——建 GitData 本身要读 .git
     * (SSH 仓库还要跑一次远程 exec),必须在 IO 上;而 FsRegistry 自己是线程安全的。
     * 搬走的只是 VM 那几张反查表的写入,见 [applyListing]。
     */
    private fun buildGit(dir: XFile): Listing.Git? {
        val key = fileKey(dir)
        if (gitInfo.containsKey(key)) return null // 已登记过,不重复建
        val (data, cheap) = runCatching { com.twig.app.gitDataFor(dir) }.getOrNull() ?: return null
        val branch = runCatching { data.branch() }.getOrDefault("?")
        val scheme = "git" + Integer.toHexString(key.hashCode())
        val label = "Git ($branch)"
        // host 传 dir:虚拟树里的「工作区」要靠它把仓库记的绝对路径映射回这边(见 GitFileSystem)
        FsRegistry.register(GitFileSystem(getApplication(), data, scheme, label, host = dir))
        return Listing.Git(key, dir, scheme, label, cheap)
    }

    private fun addRestic(rows: MutableList<Node>, repoDir: XFile, depth: Int) {
        val nodeKey = "restic:${fileKey(repoDir)}"
        // "已解锁" = 仓库打开了**并且**快照列表也取回来了。只看 resticScheme 的话,一次
        // "打开成功、列快照失败" 就会把这行永久钉在 unlocked 上、点开却是空的(见
        // [forgetRestic]);两个条件一起看,这种半成品状态下点击会重新走解锁,自愈。
        val unlocked = resticScheme.containsKey(nodeKey) && children.containsKey(nodeKey)
        val exp = expanded.contains(nodeKey)
        rows += ResticNode(repoDir, depth, exp, unlocked, connecting.contains(nodeKey))
        if (exp && unlocked) {
            // fs-restic 是纯 JVM 模块,没有字符串资源可用,"最新" 虚拟目录(path=="/latest")
            // 只带语言无关的原始名;本地化文案在这唯一的渲染点接管。
            visible(children[nodeKey]).forEach { f ->
                val shown = if (f.path == "/latest") f.copy(displayName = str(R.string.restic_latest)) else f
                addFile(rows, shown, depth + 1)
            }
        }
    }

    private fun addGroup(rows: MutableList<Node>, id: String, label: String) {
        val key = "g:$id"
        val exp = expanded.contains(key)
        rows += GroupNode(id, label, exp)
        if (!exp) return
        val app = getApplication<Application>()
        when (id) {
            "lan", "ftp", "sftp", "dav", "s3" -> {
                val type = when (id) {
                    "lan" -> "smb"
                    "dav" -> "webdav"
                    else -> id
                }
                for (conn in ConnectionStore.all(app).filter { it.type == type }) addServer(rows, conn)
                val label = when (type) {
                    "smb" -> str(R.string.action_add_smb)
                    "ftp" -> str(R.string.action_add_ftp)
                    "sftp" -> str(R.string.action_add_sftp)
                    "s3" -> str(R.string.action_add_s3)
                    else -> str(R.string.action_add_webdav)
                }
                rows += ActionNode("add_$type", label)
                // 另一台 Twig 的 WiFi 共享说的就是 WebDAV,扫出来直接存成一条 WebDAV 连接
                if (type == "webdav") rows += ActionNode("add_scan_twig", str(R.string.share_scan))
            }
            "saf" -> {
                for (perm in app.contentResolver.persistedUriPermissions) {
                    runCatching {
                        val docId = DocumentsContract.getTreeDocumentId(perm.uri)
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(perm.uri, docId)
                        XFile(
                            "saf", docUri.toString(), isDir = true,
                            displayName = docId.substringAfterLast(':').ifEmpty { docId },
                        )
                    }.getOrNull()?.let { addFile(rows, it, 1) }
                }
                rows += ActionNode("add_saf", str(R.string.action_add_saf))
            }
            "fav" -> {
                val favs = FavoritesStore.all(app)
                if (favs.isEmpty()) rows += ActionNode("fav_hint", str(R.string.fav_hint))
                else {
                    val conns = ConnectionStore.all(app).associateBy { it.label() }
                    for (fav in favs) addFavorite(rows, fav, conns)
                }
            }
            "cmp" -> {
                for (s in CompareStore.all(app)) rows += CompareNode(s, depth = 1)
            }
        }
    }

    private fun addFavorite(rows: MutableList<Node>, fav: Favorite, conns: Map<String, SavedConnection>) {
        val key = "fav:${fav.id}"
        val exp = expanded.contains(key)
        val conn = when (fav.kind) {
            "conn" -> conns[fav.connLabel]
            "restic" -> conns[fav.repoConnLabel]
            else -> null
        }
        rows += FavoriteNode(fav, conn, depth = 1, expanded = exp, connecting = connecting.contains(key))
        // 属性卡片/搜索结果照普通目录行的规矩挂在下方(键按收藏所指的真实目录算)
        keyFile[key]?.let { dir -> addAttachments(rows, dir, depth = 1) }
        if (exp) {
            visible(children[key]).forEach { addFile(rows, it, 2) }
            // 收藏的目录本身就是 restic 仓库根(fav.kind 是 local/conn,还没经 resolveFavorite
            // 的 restic 分支解锁):和普通浏览一致,补一行"restic 仓库"提示可展开解锁。
            keyFile[key]?.let { dir -> if (resticRepos.contains(fileKey(dir))) addRestic(rows, dir, 2) }
        }
    }

    private fun addServer(rows: MutableList<Node>, conn: SavedConnection) {
        val key = "s:${conn.label()}"
        val exp = expanded.contains(key)
        rows += ServerNode(conn, exp, connecting.contains(key), serverInfo[conn.label()])
        // 属性卡片/搜索结果照普通目录行挂在下方(键按服务器根目录算),同 [addFavorite]
        keyFile[key]?.let { dir -> addAttachments(rows, dir, depth = 1) }
        if (exp) visible(children[key]).forEach { addFile(rows, it, 2) }
    }

    // ---- 工具 ----

    /** 取字符串资源(多语言:随 AppCompatDelegate 当前 locale)。 */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun capacity(path: String): String? = runCatching {
        val st = StatFs(path)
        val total = st.blockCountLong * st.blockSizeLong
        val avail = st.availableBlocksLong * st.blockSizeLong
        str(R.string.capacity_format, Format.size(avail), Format.size(total))
    }.getOrNull()

    private fun emitError(msg: String?) {
        _state.value = _state.value.copy(error = msg ?: str(R.string.err_operation_failed))
    }

    /**
     * 展开失败的统一出口:加密包缺密码不是"错误",而是要请用户输密码——
     * 那条路走 [State.passwordFor],别把 fs-archive 里那句英文异常 toast 出去。
     */
    private fun emitFailure(t: Throwable, file: XFile?) {
        if (t is ArchivePasswordException && file != null) {
            _state.value = _state.value.copy(
                passwordFor = file,
                error = if (t.wrong) str(R.string.archive_wrong_pw) else null,
            )
        } else {
            emitError(t.message)
        }
    }

    /** 折叠目录节点([FileNode])时收集其下(含仍展开的子目录/压缩包,递归)已缓存的
     * 子项,交给 [Thumbs.cancelPending] 把还没开始跑的缩略图任务从线程池队列摘掉。 */
    private fun descendantFiles(dir: XFile, out: MutableList<XFile> = mutableListOf()): List<XFile> =
        descendantFilesByKey(fileKey(dir), out)

    /** 同 [descendantFiles],但从任意节点 key 开始——服务器根/restic 仓库根的 key
     * 不是 `fileKey()` 那套("s:"/"restic:" 前缀),子项仍缓存在同一个 [children] 表里。 */
    private fun descendantFilesByKey(rootKey: String, out: MutableList<XFile> = mutableListOf()): List<XFile> {
        val kids = children[rootKey] ?: return out
        for (c in kids) {
            out.add(c)
            if (expanded.contains(fileKey(c))) descendantFiles(c, out)
        }
        return out
    }

    companion object {
        /**
         * 外部打开(「用 Twig 打开」)挂在树顶那棵子树的 key 前缀。它和这个文件在存储树里
         * 原本那一行是**两行**,不加以区分就会撞 DiffUtil 的 key。
         */
        const val EXTERNAL_KEY_PREFIX = "x:"

        /** 见 [TreeKeys.fileKey];这里保留同名入口,免得几十个调用点都要改。 */
        fun fileKey(file: XFile): String = TreeKeys.fileKey(file)
    }
}
