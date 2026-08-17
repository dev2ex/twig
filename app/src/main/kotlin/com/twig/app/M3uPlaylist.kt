package com.twig.app

import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.nio.charset.Charset

/**
 * m3u/m3u8 播放列表解析。列表内的相对路径按"m3u 文件所在目录"解析,且与 m3u 同一来源
 * (本地/SMB/…同 scheme);绝对路径按该来源根解析;http(s) 网络流不支持,跳过。
 * 阻塞 IO,须在工作线程调用。
 */
object M3uPlaylist {

    /** 解析出列表内各曲目的 [XFile](与 m3u 同 scheme;不校验是否存在,交给播放引擎解析时跳过缺失)。
     * 顺带按所在目录列一次真实条目,把 size/lastModified 补进去——导入时存一次,配合
     * [Thumbs] 的缓存 key(md5(name:size:mtime))在跨会话间保持稳定,不用每次进播放列表
     * 都靠 MusicEngine.statByListing 现列目录取值(这是"m3u8 导入的曲目封面/大小每次都要
     * 重新拉一遍"的根源)。按目录分组只列一次,一个专辑目录几十首歌不重复列表。 */
    fun parse(file: XFile): List<XFile> = runCatching {
        val fs = FsRegistry.of(file)
        val text = decode(fs.openInput(file).use { OpenFiles.readAllBytes(it, 256 * 1024) })
        val baseDir = file.parentPath
        val paths = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trim().trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#")) continue // 空行 / #EXTM3U/#EXTINF 等指令
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) continue // 网络流不支持
            val rel = line.replace('\\', '/') // 兼容 Windows 反斜杠
            val abs = if (rel.startsWith("/")) rel else "${baseDir.trimEnd('/')}/$rel"
            paths.add(normalize(abs))
        }
        val dirCache = HashMap<String, List<XFile>>()
        paths.map { p ->
            val dir = p.substringBeforeLast('/', "").ifEmpty { "/" }
            val name = p.substringAfterLast('/')
            val listed = dirCache.getOrPut(dir) {
                runCatching { fs.list(XFile(file.scheme, dir, isDir = true)) }.getOrDefault(emptyList())
            }
            listed.firstOrNull { !it.isDir && it.name == name }
                ?: XFile(file.scheme, p, isDir = false) // 列不到(路径不存在/目录不可读):保留原样,交给播放引擎跳过
        }
    }.getOrDefault(emptyList())

    /** 规整路径:折叠 `.`/`..`,去掉多余斜杠。 */
    private fun normalize(path: String): String {
        val stack = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            else -> stack.add(p)
        }
        return "/" + stack.joinToString("/")
    }

    /** UTF-8(带 BOM 优先),失败退 GBK/系统默认。 */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }
}
