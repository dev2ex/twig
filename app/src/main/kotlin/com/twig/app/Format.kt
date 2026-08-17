package com.twig.app

import java.text.DecimalFormat
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/** 轻量格式化工具,避免引入额外库。 */
object Format {
    private val dateFmt = SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault())
    private val sizeFmt = DecimalFormat("#.#")

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return "${sizeFmt.format(v)} ${units[i]}"
    }

    fun time(millis: Long): String =
        if (millis <= 0) "" else dateFmt.format(Date(millis))

    /** 每台服务器/每个仓库一个唯一 scheme = 类型 + hash(见 PaneViewModel.schemeForConn) */
    private val SCHEME_TYPES =
        listOf("webdav", "restic", "git", "sftp", "smb", "ftp", "dav", "s3")

    /**
     * scheme 的展示名:剥掉「类型 + hash」里的 hash 部分。
     * 不能按"取到第一个非字母为止"切——hash 是十六进制,以 a-f 开头时会被当成类型的一部分
     * (`sftp` + `a3f2…` 显示成 `sftpa`)。类型不在表里的(file/zip/7z/apps…)本来就没有
     * hash 后缀,原样返回。
     */
    fun schemeLabel(scheme: String): String =
        SCHEME_TYPES.firstOrNull { scheme.startsWith(it) } ?: scheme

    /**
     * `类型:/服务器/路径`——与面板路径栏同一写法。服务器名取用户起的标签,
     * 非服务器来源(zip/git/restic…)没有服务器名,别多插一道斜杠。
     *
     * `PaneFragment` 另有一份:那边会优先查本面板 VM 的反查表,能认出对侧面板还没
     * 展开过的服务器。不需要那一层的调用方(对比页、文本对比页)用这个就够了。
     */
    fun pathLabel(f: com.twig.core.XFile): String = when {
        f.scheme == "file" -> f.path
        f.scheme == "saf" -> f.name
        else -> {
            val server = Connections.ofScheme(f.scheme)?.shortLabel().orEmpty()
            val path = if (f.path.startsWith("/")) f.path else "/${f.path}"
            val head = schemeLabel(f.scheme)
            if (server.isEmpty()) "$head:$path" else "$head:/$server$path"
        }
    }
}
