package com.twig.app.ui

import com.twig.app.AppsFileSystem
import com.twig.core.XFile

/**
 * 树的 **key 编码**与**"上次位置"描述符编解码**。
 *
 * 从 [PaneViewModel] 里抽出来单放,是为了能用纯 JVM 单测覆盖:这些全是字符串/数据
 * 变换,不碰 Android、不碰 IO,而它们错了都**不会崩溃**,只表现为"位置恢复不到原处"
 * "折叠折错一层"——真机上很难系统性走查出来。
 *
 * 描述符尤其值得测:它存在 SharedPreferences 里,**读到的是上一个版本写下的数据**,
 * 编码与解码不对称就是"升级之后位置恢复不了"。所以这里的两个方向做成互逆的一对
 * ([descriptorOf] / [keyOfDescriptor]),测试直接压 round-trip。
 *
 * 会话相关的东西一律由调用方以 lambda 传入(动态 scheme ↔ 连接标签的映射只在
 * [PaneViewModel] 手里),这样本对象保持零依赖。
 */
object TreeKeys {

    /** 树上一行的唯一 id;VM 里十几张表都按它索引。 */
    fun fileKey(file: XFile): String = "f:${file.scheme}:${file.path}"

    // ---- 描述符 ----
    //
    // 格式:`kind \t 参数…`,整份列表再用 '\n' 连起来存(见 Prefs.saveLocation)。
    //   file\t<绝对路径>            本地目录
    //   apps\t<路径>               「应用」树里的目录
    //   conn\t<连接标签>\t<路径>     某台服务器上的目录
    //   group\t<分组 id>           LAN/FTP/… 分组标题
    //   server\t<连接标签>          服务器节点本身
    //   fav\t<收藏 id>             收藏节点

    /**
     * 展开节点的 key → 可持久化描述符;不可持久化的来源(zip/restic/saf/git 内部)返回 null。
     *
     * @param connLabelOf 动态 scheme → 已保存连接的标签;该 scheme 不是服务器时返回 null。
     */
    fun descriptorOf(key: String, connLabelOf: (scheme: String) -> String?): String? {
        val parts: List<String> = when {
            key.startsWith("f:file:") -> listOf("file", key.removePrefix("f:file:"))
            key.startsWith("f:${AppsFileSystem.SCHEME}:") ->
                listOf("apps", key.removePrefix("f:${AppsFileSystem.SCHEME}:"))
            key.startsWith("f:") -> {
                val body = key.removePrefix("f:")
                val scheme = body.substringBefore(':')
                val path = body.substringAfter(':')
                connLabelOf(scheme)?.let { listOf("conn", it, path) }
            }
            key.startsWith("g:") -> listOf("group", key.removePrefix("g:"))
            key.startsWith("s:") -> listOf("server", key.removePrefix("s:"))
            key.startsWith("fav:") -> listOf("fav", key.removePrefix("fav:"))
            else -> null
        } ?: return null
        // 路径/标签里出现分隔符会把这条(甚至整份列表)撕坏。Linux 上 '\t' 和 '\n' 都是
        // 合法文件名字符,真遇上了宁可这一个节点不恢复,也不能让它带坏别的条目。
        // ★ 检查**逐个字段**,不能查拼好的整串——那里面的 '\t' 正是我们自己加的分隔符。
        if (parts.any { field -> SEPARATORS.any { it in field } }) return null
        return parts.joinToString("\t")
    }

    /**
     * 描述符 → 树节点 key,与 [descriptorOf] 互逆。解不出来(格式不认、
     * 对应连接已删除/本次会话还没连上)返回 null。
     *
     * @param schemeOfConn 连接标签 → 本次会话已注册的 scheme;没有则 null。
     */
    fun keyOfDescriptor(d: String, schemeOfConn: (label: String) -> String?): String? {
        val p = d.split('\t')
        return when (p.getOrNull(0)) {
            "file" -> p.getOrNull(1)?.let { "f:file:$it" }
            "apps" -> p.getOrNull(1)?.let { "f:${AppsFileSystem.SCHEME}:$it" }
            "conn" -> {
                val label = p.getOrNull(1) ?: return null
                val path = p.getOrNull(2) ?: return null
                schemeOfConn(label)?.let { "f:$it:$path" }
            }
            "group" -> p.getOrNull(1)?.let { "g:$it" }
            "server" -> p.getOrNull(1)?.let { "s:$it" }
            "fav" -> p.getOrNull(1)?.let { "fav:$it" }
            else -> null
        }
    }

    /**
     * 描述符 → 它指向的目录 [XFile];只有"能指向真实目录"的三种 kind
     * (file/apps/conn)有意义,分组/服务器/收藏节点返回 null。
     */
    fun dirOfDescriptor(d: String, schemeOfConn: (label: String) -> String?): XFile? {
        val p = d.split('\t')
        return when (p.getOrNull(0)) {
            "file" -> p.getOrNull(1)?.let { XFile("file", it, isDir = true) }
            "apps" -> p.getOrNull(1)?.let {
                XFile(AppsFileSystem.SCHEME, it, isDir = true, canWrite = false)
            }
            "conn" -> {
                val label = p.getOrNull(1) ?: return null
                val path = p.getOrNull(2) ?: return null
                schemeOfConn(label)?.let { XFile(it, path, isDir = true) }
            }
            else -> null
        }
    }

    // ---- 祖先链 ----

    /** [ancestorKeys] 只需要每行的这两样。 */
    data class Row(val key: String, val depth: Int)

    /**
     * 靠缩进层级反推某一行的祖先链(手风琴折叠用:除了这条链,其余全折起来)。
     * 从该行往上走,每遇到一个比"当前所需层级"更浅的行就收进来并把所需层级降到它,
     * 走到 0 层为止。[key] 不在 [rows] 里时返回空集。
     */
    fun ancestorKeys(rows: List<Row>, key: String): Set<String> {
        val idx = rows.indexOfFirst { it.key == key }
        if (idx < 0) return emptySet()
        val res = HashSet<String>()
        var need = rows[idx].depth
        for (i in idx - 1 downTo 0) {
            if (rows[i].depth < need) {
                res.add(rows[i].key)
                need = rows[i].depth
                if (need == 0) break
            }
        }
        return res
    }

    private val SEPARATORS = charArrayOf('\t', '\n')
}
