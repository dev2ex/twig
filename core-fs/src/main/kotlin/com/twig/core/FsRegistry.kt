package com.twig.core

/**
 * 全局文件系统注册表:scheme -> FileSystem。
 *
 * 各模块(fs-local / fs-archive / fs-network ...)在初始化时把自己的实现注册进来,
 * UI 拿到一个 [XFile] 后通过 [of] 找到对应的 FileSystem 来操作它。
 * 这样 :app 不需要直接依赖具体实现,实现了"按需挂载、按需打包"。
 */
object FsRegistry {

    /**
     * ★ 必须线程安全:注册发生在 IO 线程(展开服务器节点、挂载 restic、识别 git 仓库
     * 都在 `Dispatchers.IO` 上跑,两个面板还能同时展开不同服务器),而 [of] 的读取
     * 发生在主线程(建树时逐行取 FileSystem)。裸 HashMap 在这里是真实的数据竞争。
     * 用 synchronizedMap 而不是 ConcurrentHashMap:要保住 LinkedHashMap 的插入顺序,
     * [all] 的语义是"按注册先后列出来源"。
     */
    private val systems: MutableMap<String, FileSystem> =
        java.util.Collections.synchronizedMap(LinkedHashMap())

    fun register(fs: FileSystem) {
        systems[fs.scheme] = fs
    }

    /**
     * 注销一个 scheme 并返回被移除的实现(没有则 null);调用方负责断开连接。
     *
     * 用在"改了服务器配置,下次展开要用新配置重连"这条路上:scheme 是按连接标签
     * 确定性生成的,只改密码时标签不变、scheme 也不变,不注销的话
     * [Connections.ensure] 那句"已注册就复用"会一直把**旧配置建的**实例还回去,
     * 表现为改了密码/主机密钥却不生效,直到重启应用。
     */
    fun unregister(scheme: String): FileSystem? = systems.remove(scheme)

    fun of(scheme: String): FileSystem =
        systems[scheme] ?: throw FsException("No file system registered for scheme: $scheme")

    fun of(file: XFile): FileSystem = of(file.scheme)

    /** 所有已注册的文件系统,用于侧栏列出可访问的来源。 */
    fun all(): List<FileSystem> = synchronized(systems) { systems.values.toList() }
}
