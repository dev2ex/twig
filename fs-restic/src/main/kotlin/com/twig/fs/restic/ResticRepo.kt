package com.twig.fs.restic

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 一个已解锁的 restic 仓库(只读)。通过底层 [FileSystem] 读取仓库文件,因此
 * 本地 / SMB / SFTP 上的 restic repo 都能读——又一次复用统一抽象。
 */
class ResticRepo private constructor(
    private val fs: FileSystem,
    private val repoDir: XFile,
    private val masterKey: ByteArray,
    private val zstd: Zstd,
) {
    data class Snapshot(
        val id: String,
        val shortId: String,
        val treeId: String,
        val timeLabel: String,
        val timeMillis: Long,
        val hostname: String,
        val paths: List<String>,
    )

    data class Node(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long,
        val subtree: String?,
        val content: List<String>,
    )

    /** blob id -> 位置(pack + 偏移 + 长度 + 是否压缩)。 */
    private data class Loc(val pack: String, val offset: Long, val length: Int, val uncompressed: Int)

    /**
     * blob id -> 位置。**延迟到第一次真正读 blob 时才加载**(★ 2026-08-04):
     * 列快照只读 `snapshots/` 下的独立加密文件,根本用不到索引;而 `index/` 下常有
     * 几十上百个文件、加起来几十 MB,整读一遍在 SMB/SFTP 上要几十秒。以前放在 [open]
     * 里同步做,于是"输完密码到看见快照列表"要等这一整段,UI 上还没有任何指示。
     * 挪到 [readBlob](= 展开某个快照的目录/读文件)之后,那条路径有目录展开的转圈。
     *
     * `by lazy` 默认是 SYNCHRONIZED:并发 readBlob 只会加载一次;初始化抛异常不会被
     * 缓存,下次重试——正合"index 解析失败要明确报错"的原意(见 [loadIndex])。
     * 填满之后只读,不必加锁。
     */
    private val index: Map<String, Loc> by lazy { loadIndex() }

    /**
     * tree 解析缓存。**并发读写**:目录展开与缩略图生成会同时命中同一个仓库
     * (下面 [readRange] 整体加锁正是为了同一个场景,只是当初漏了这张表)。
     */
    private val treeCache = java.util.concurrent.ConcurrentHashMap<String, List<Node>>()

    val snapshots: List<Snapshot> by lazy { loadSnapshots() }

    // ---- 目录/文件访问 ----

    /** "latest" 是虚拟别名,始终指向当前最新快照(snapshots 已按时间倒序)。 */
    fun snapshotByShort(short: String): Snapshot? =
        if (short == "latest") snapshots.firstOrNull() else snapshots.firstOrNull { it.shortId == short }

    fun childrenOfTree(treeId: String): List<Node> = loadTree(treeId)

    /** 解析路径段到"其子项所在的 tree";段为空返回快照根 tree。 */
    fun resolveTree(snapshot: Snapshot, segments: List<String>): String? {
        var tree = snapshot.treeId
        for (seg in segments) {
            val node = loadTree(tree).firstOrNull { it.name == seg && it.isDir } ?: return null
            tree = node.subtree ?: return null
        }
        return tree
    }

    fun resolveNode(snapshot: Snapshot, segments: List<String>): Node? {
        if (segments.isEmpty()) return null
        val parent = resolveTree(snapshot, segments.dropLast(1)) ?: return null
        return loadTree(parent).firstOrNull { it.name == segments.last() }
    }

    /** 文件内容流:按 content blob 顺序惰性读取、解密、解压。 */
    fun openFile(node: Node): InputStream = object : InputStream() {
        private val ids = node.content.iterator()
        private var cur: InputStream = nextChunk()
        private fun nextChunk(): InputStream =
            if (ids.hasNext()) readBlob(ids.next()).inputStream() else EMPTY
        override fun read(): Int {
            while (true) {
                val c = cur.read()
                if (c >= 0) return c
                if (!ids.hasNext()) return -1
                cur = nextChunk()
            }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val n = cur.read(b, off, len)
                if (n >= 0) return n
                if (!ids.hasNext()) return -1
                cur = nextChunk()
            }
        }
    }

    // ---- 内部:加载 ----

    private fun loadTree(treeId: String): List<Node> = treeCache.getOrPut(treeId) {
        val json = JSONObject(String(readBlob(treeId), Charsets.UTF_8))
        val arr = json.getJSONArray("nodes")
        (0 until arr.length()).map { i ->
            val n = arr.getJSONObject(i)
            val content = n.optJSONArray("content")
            Node(
                name = n.getString("name"),
                isDir = n.getString("type") == "dir",
                size = n.optLong("size", 0L),
                mtime = parseTime(n.optString("mtime", "")),
                subtree = if (n.isNull("subtree")) null else n.optString("subtree", null),
                content = if (content == null) emptyList()
                else (0 until content.length()).map { content.getString(it) },
            )
        }
    }

    private fun loadSnapshots(): List<Snapshot> =
        fs.list(sub("snapshots")).filter { !it.isDir }.mapNotNull { f ->
            runCatching {
                val json = JSONObject(String(decryptFile(readWhole(f)), Charsets.UTF_8))
                val time = json.getString("time")
                val pathsArr = json.optJSONArray("paths")
                Snapshot(
                    id = f.name,
                    shortId = f.name.take(8),
                    treeId = json.getString("tree"),
                    timeLabel = time.take(19).replace('T', ' '),
                    timeMillis = parseTime(time),
                    hostname = json.optString("hostname", ""),
                    paths = if (pathsArr == null) emptyList()
                    else (0 until pathsArr.length()).map { pathsArr.getString(it) },
                )
            }.getOrNull()
        }.sortedByDescending { it.timeMillis }

    private fun loadIndex(): Map<String, Loc> {
        val index = HashMap<String, Loc>()
        // 不吞异常:index 是 zstd 压缩的,若解压/解析失败(如平台 zstd 不可用)应明确报错。
        for (f in fs.list(sub("index")).filter { !it.isDir }) {
            val json = JSONObject(String(decryptFile(readWhole(f)), Charsets.UTF_8))
            val packs = json.getJSONArray("packs")
            for (i in 0 until packs.length()) {
                val pack = packs.getJSONObject(i)
                val packId = pack.getString("id")
                val blobs = pack.getJSONArray("blobs")
                for (j in 0 until blobs.length()) {
                    val bl = blobs.getJSONObject(j)
                    index[bl.getString("id")] = Loc(
                        pack = packId,
                        offset = bl.getLong("offset"),
                        length = bl.getInt("length"),
                        uncompressed = bl.optInt("uncompressed_length", -1),
                    )
                }
            }
        }
        return index
    }

    /** 读一个 blob:定位 pack、读区间、解密、按需解压。 */
    private fun readBlob(id: String): ByteArray {
        val loc = index[id] ?: throw FsException("restic: index is missing blob $id")
        val packFile = XFile(
            repoDir.scheme,
            "${repoDir.path}/data/${loc.pack.take(2)}/${loc.pack}",
            isDir = false,
        )
        val raw = readRange(packFile, loc.offset, loc.length)
        val plain = ResticCrypto.decrypt(masterKey, raw)
        return if (loc.uncompressed >= 0) zstd.decompress(plain, loc.uncompressed) else plain
    }

    /**
     * 解密"非打包"文件(config / snapshot / index)。
     * restic v2 里这类文件明文带 1 字节压缩头(0=未压缩,2=zstd),config 例外为原始 JSON。
     */
    private fun decryptFile(raw: ByteArray): ByteArray {
        val plain = ResticCrypto.decrypt(masterKey, raw)
        if (plain.isNotEmpty() && plain[0] == '{'.code.toByte()) return plain // config:无前缀
        val body = if (plain.isEmpty()) plain else plain.copyOfRange(1, plain.size)
        return if (ResticCrypto.isZstdFrame(body)) zstd.decompress(body, -1) else body
    }

    // ---- 底层文件读取 ----

    private fun sub(name: String) = XFile(repoDir.scheme, "${repoDir.path}/$name", isDir = true)

    /**
     * 整读一个"非打包"文件(config / keyfile / snapshot / index)。
     *
     * ★ 别用 `InputStream.readBytes()`:它固定按 8KB 一次调 `read`,而 SMB/SFTP/WebDAV
     * 的每次 read 都是一个网络往返——几 MB 的 index 文件就是几百次往返,几十个 index
     * 文件叠起来正是"解锁 restic 仓库要等很久"的大头。给 1MB 缓冲,底层能一次拉满协商
     * 出来的最大读长度(libsmb2 自己会 clamp 到 max_read_size 并返回短读,循环兜住)。
     */
    private fun readWhole(f: XFile): ByteArray = fs.openInput(f).use { ins ->
        val buf = ByteArray(1 shl 20)
        // 预分配到文件实际大小,省掉 ByteArrayOutputStream 反复扩容时的整段拷贝
        val out = java.io.ByteArrayOutputStream(f.size.coerceIn(1L, MAX_PREALLOC).toInt())
        while (true) {
            val n = ins.read(buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    /**
     * pack 定位读句柄缓存(LRU 2 个)。一个文件的 content blob 通常在同一 pack 内
     * 连续排列,复用同一个 [RandomSource] 能让 SMB(pread)/WebDAV(Range)/FTP/SFTP
     * 的定位读发挥效果;超出上限关闭最久未用的,避免打开的远程句柄无限增长。
     */
    private val packRandoms = object : LinkedHashMap<String, RandomSource>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RandomSource>): Boolean {
            if (size <= 2) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    /**
     * 按区间读取 pack 数据。★ 别再改回"openInput + skip 循环":那样每次都会把
     * pack 开头到目标偏移之间的字节整段拉下来再丢弃,大 pack 靠后的 blob 会拖垮吞吐
     * (2026-07-28 定案,见 CLAUDE.md)。改走 [FileSystem.openRandom] 定位读。
     *
     * ★ 必须整体加锁(2026-07-28):`packRandoms` 缓存的 [RandomSource] 会被 LRU
     * 淘汰时 close(SMB 是销毁整个专用连接)。目录展开常和缩略图生成等并发命中
     * 同一个仓库,若不加锁,一个线程正读到一半、另一个线程的插入把它淘汰关闭,
     * `readAt` 就会返回 <=0,表现为"pack 读取不足"。加锁牺牲的只是 blob 间的并行,
     * 不影响"避免整段下载丢弃"这个核心优化。
     */
    private fun readRange(f: XFile, offset: Long, length: Int): ByteArray = synchronized(packRandoms) {
        val src = packRandoms.getOrPut(f.path) { fs.openRandom(f) }
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = src.readAt(offset + read, buf, read, length - read)
            if (n <= 0) throw FsException("restic: short read from pack")
            read += n
        }
        buf
    }

    companion object {
        private val EMPTY = ByteArray(0).inputStream()

        /** [readWhole] 预分配上限:`XFile.size` 拿不到/不靠谱时别按它开一个巨大的数组。 */
        private const val MAX_PREALLOC = 64L shl 20

        /** 目录子项是否构成一个 restic 仓库(有 config 文件与 data/index/snapshots/keys 目录)。 */
        fun looksLikeRepo(children: List<XFile>): Boolean {
            val names = children.associateBy { it.name }
            return names["config"]?.isDir == false &&
                names["data"]?.isDir == true &&
                names["index"]?.isDir == true &&
                names["snapshots"]?.isDir == true &&
                names["keys"]?.isDir == true
        }

        /** 用密码打开仓库;密码错误(解出的 JSON 无法解析)抛 [FsException]。 */
        fun open(fs: FileSystem, repoDir: XFile, password: String, zstd: Zstd): ResticRepo {
            val master = deriveMasterKey(fs, repoDir, password)
            val repo = ResticRepo(fs, repoDir, master, zstd)
            // 校验:能解出 config 即密码正确
            val cfg = repo.decryptFile(repo.readWhole(XFile(repoDir.scheme, "${repoDir.path}/config", false)))
            runCatching { JSONObject(String(cfg, Charsets.UTF_8)).getInt("version") }
                .getOrElse { throw FsException("restic: wrong password or corrupt repository") }
            repo.loadIndex()
            return repo
        }

        private fun deriveMasterKey(fs: FileSystem, repoDir: XFile, password: String): ByteArray {
            val keysDir = XFile(repoDir.scheme, "${repoDir.path}/keys", true)
            val keyList = try {
                fs.list(keysDir)
            } catch (e: Exception) {
                throw FsException("restic: cannot read keys directory @ ${keysDir.toUri()}: ${e.message}", e)
            }
            val keyFile = keyList.firstOrNull { !it.isDir }
                ?: throw FsException("restic: no key file in keys directory @ ${keysDir.toUri()}")
            val kj = JSONObject(String(fs.openInput(keyFile).use { it.readBytes() }, Charsets.UTF_8))
            val salt = ResticCrypto.base64(kj.getString("salt"))
            val derived = ResticCrypto.deriveKey(
                password.toByteArray(Charsets.UTF_8), salt,
                kj.getInt("N"), kj.getInt("r"), kj.getInt("p"),
            )
            val userKey = derived.copyOfRange(0, 32)
            val data = ResticCrypto.base64(kj.getString("data"))
            val masterJson = runCatching {
                JSONObject(String(ResticCrypto.decrypt(userKey, data), Charsets.UTF_8))
            }.getOrElse { throw FsException("restic: wrong password") }
            return ResticCrypto.base64(masterJson.getString("encrypt"))
        }

        /**
         * 解析 restic 的 RFC3339 时间戳。
         *
         * 两处修正:
         * - **不再共享一个 [SimpleDateFormat] 实例**——它不是线程安全的,而这里被
         *   `loadTree`(并发)和 `loadSnapshots` 同时调用。原来外面套着 runCatching,
         *   所以症状不是崩溃而是时间戳静默变成 0(快照/文件时间显示错乱),更难发现。
         * - **认时区偏移**。restic 写的是带偏移的 RFC3339(`…T12:34:56.789+08:00`),
         *   原来只截前 19 位、按设备本地时区解释,UTC 存的快照会整体偏几小时。
         */
        private fun parseTime(s: String): Long {
            if (s.length < 19) return 0L
            return runCatching {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(s.substring(0, 19))!!.time - offsetMillis(s.substring(19))
            }.getOrDefault(0L)
        }

        /** 秒之后那一截里的时区偏移(`Z` / `+08:00` / `-0500`);认不出按 UTC。 */
        private fun offsetMillis(tail: String): Long {
            val i = tail.indexOfFirst { it == '+' || it == '-' }
            if (i < 0) return 0L // 空、或只有小数秒、或 Z 结尾
            val sign = if (tail[i] == '-') -1L else 1L
            val digits = tail.substring(i + 1).filter { it.isDigit() }
            if (digits.length < 4) return 0L
            return sign * (digits.substring(0, 2).toLong() * 3_600_000L +
                digits.substring(2, 4).toLong() * 60_000L)
        }
    }
}
