package com.twig.core

/**
 * 一个统一的"条目"——可能是本地文件、压缩包内的项、FTP 上的文件,或某个云盘对象。
 *
 * 对 UI 来说,所有来源都是 [XFile];具体由哪个 [FileSystem] 解释由 [scheme] 决定。
 * 设计目标:轻量、不可变、不持有 IO 资源(真正读写时再向 [FileSystem] 申请流)。
 */
data class XFile(
    /** 所属文件系统的 scheme,如 "file" / "zip" / "ftp"。 */
    val scheme: String,
    /**
     * 在该文件系统内的绝对路径。约定以 '/' 为分隔符,根为 "/"。
     * 注意:这里的 path 不含 scheme 前缀,scheme 单独存放,便于各 FileSystem 自己解释。
     */
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    /** 是否可读/可写,UI 可据此置灰操作;未知时给 true。 */
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    /**
     * 显示名。当 [path] 是不透明标识(如 SAF 的 document URI、云盘的对象 id)、
     * 无法从中切出可读名称时,由 FileSystem 从元数据填入;为 null 时回退到 path 末段。
     */
    val displayName: String? = null,
) {
    /** 文件名;优先用 [displayName],否则取 path 末段;根目录返回 "/"。 */
    val name: String
        get() {
            displayName?.let { return it }
            if (path == "/" || path.isEmpty()) return "/"
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx < 0) trimmed else trimmed.substring(idx + 1)
        }

    /** 父目录路径;根的父仍是根。 */
    val parentPath: String
        get() {
            if (path == "/" || path.isEmpty()) return "/"
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx <= 0) "/" else trimmed.substring(0, idx)
        }

    /** 扩展名(小写,不含点);无扩展名返回空串。 */
    val extension: String
        get() {
            val n = name
            val dot = n.lastIndexOf('.')
            return if (dot <= 0) "" else n.substring(dot + 1).lowercase()
        }

    /** 完整 URI 形式,如 "file:///sdcard/a.txt",用于日志/导航历史。 */
    fun toUri(): String = "$scheme://$path"
}

/**
 * 是否是一个能被复制/移动/分享收件当作目标的"真实可写目录":自身 [XFile.canWrite]
 * (resolve()/list() 现算,收藏夹等绕过它直接拼 XFile 的场景不可信)+ 所属
 * [FileSystem.writable]("这整个来源是否支持写"的兜底,restic/7z/RAR/git 视图等
 * 全程只读的来源靠它拦住)。
 */
fun XFile.isWritableDir(): Boolean =
    isDir && canWrite && runCatching { FsRegistry.of(this).writable() }.getOrDefault(false)
