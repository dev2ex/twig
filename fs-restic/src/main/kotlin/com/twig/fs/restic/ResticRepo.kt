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
 * An unlocked restic repository (read-only). Reads repository files through the underlying
 * [FileSystem], so restic repos on local / SMB / SFTP all work — yet another reuse of the
 * unified abstraction.
 */
class ResticRepo private constructor(
    private val fs: FileSystem,
    private val repoDir: XFile,
    private val masterKey: ByteArray,
    private val zstd: Zstd,
    private val cache: ObjectCache = ObjectCache.NONE,
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

    /** blob id -> location (pack + offset + length + whether compressed). */
    private data class Loc(val pack: String, val offset: Long, val length: Int, val uncompressed: Int)

    /**
     * blob id -> location. **Loaded lazily on the first real blob read** (★ 2026-08-04):
     * listing snapshots only reads the individual encrypted files under `snapshots/`, which
     * do not need the index at all; meanwhile `index/` often contains dozens to hundreds of
     * files, totaling tens of MB, and reading the whole thing on SMB/SFTP takes tens of seconds.
     * Previously it was done synchronously inside [open], so "from entering the password to
     * seeing the snapshot list" had to wait through all of that, with no UI indicator.
     * After moving it to [readBlob] (= expanding a snapshot's directory / reading a file),
     * that path has the directory-expanding spinner.
     *
     * `by lazy` defaults to SYNCHRONIZED: concurrent readBlob will only load once;
     * initialization exceptions are not cached, so the next attempt retries — which matches
     * the intent of "index parse failure must be reported explicitly" (see [loadIndex]).
     * Once filled, it is read-only, so no extra locking is needed.
     */
    private val index: Map<String, Loc> by lazy { loadIndex() }

    /**
     * Tree-parse cache. **Concurrent reads and writes**: directory expansion and thumbnail
     * generation hit the same repository simultaneously ([readRange] below is locked as a
     * whole for exactly this scenario, but this table was originally missed).
     */
    private val treeCache = java.util.concurrent.ConcurrentHashMap<String, List<Node>>()

    val snapshots: List<Snapshot> by lazy { loadSnapshots() }

    // ---- directory/file access ----

    /** "latest" is a virtual alias, always pointing to the current newest snapshot (snapshots is already sorted newest-first). */
    fun snapshotByShort(short: String): Snapshot? =
        if (short == "latest") snapshots.firstOrNull() else snapshots.firstOrNull { it.shortId == short }

    fun childrenOfTree(treeId: String): List<Node> = loadTree(treeId)

    /** Resolve path segments to "the tree containing their children"; empty segments return the snapshot root tree. */
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

    /** File content stream: lazy-read by content blob order, decrypt, decompress. */
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

    // ---- internals: loading ----

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
        // Do not swallow exceptions: the index is zstd-compressed, so a decompression/parse
        // failure (e.g. zstd unavailable on the platform) must be reported explicitly.
        for (f in fs.list(sub("index")).filter { !it.isDir }) {
            val json = JSONObject(String(decryptFile(readCached(f)), Charsets.UTF_8))
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

    /** Read a blob: locate the pack, read the range, decrypt, decompress if needed. */
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
     * Decrypt a "non-pack" file (config / snapshot / index).
     * In restic v2 these plaintexts carry a 1-byte compression header (0=uncompressed, 2=zstd);
     * config is the exception and is raw JSON.
     */
    private fun decryptFile(raw: ByteArray): ByteArray {
        val plain = ResticCrypto.decrypt(masterKey, raw)
        if (plain.isNotEmpty() && plain[0] == '{'.code.toByte()) return plain // config: no prefix
        val body = if (plain.isEmpty()) plain else plain.copyOfRange(1, plain.size)
        return if (ResticCrypto.isZstdFrame(body)) zstd.decompress(body, -1) else body
    }

    // ---- low-level file reads ----

    private fun sub(name: String) = XFile(repoDir.scheme, "${repoDir.path}/$name", isDir = true)

    /**
     * Read an entire "non-pack" file (config / keyfile / snapshot / index) at once.
     *
     * ★ Do not use `InputStream.readBytes()`: it always calls `read` in 8KB chunks,
     * and every SMB/SFTP/WebDAV `read` is a network round trip — a few-MB index file
     * becomes hundreds of round trips, and dozens of index files stacked together is
     * exactly the bulk of "unlocking a restic repo takes forever". Giving a 1MB buffer
     * lets the underlying layer fill at the negotiated max read length (libsmb2 itself
     * clamps to max_read_size and returns short reads, which the loop covers).
     */
    private fun readWhole(f: XFile): ByteArray = fs.openInput(f).use { ins ->
        val buf = ByteArray(1 shl 20)
        // Pre-allocate to the actual file size to avoid whole-array copies as ByteArrayOutputStream grows
        val out = java.io.ByteArrayOutputStream(f.size.coerceIn(1L, MAX_PREALLOC).toInt())
        while (true) {
            val n = ins.read(buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    /**
     * Whole-file read with local cache, used only for files under `index/`. Those files are
     * content-addressed (the filename is the content hash), so their content never changes
     * and the cache never needs invalidating; and they are the most expensive network cost
     * for this reader — dozens of files, tens of MB, redone on every repo open over a slow link.
     * The cache holds **the raw ciphertext** — the reason is in [ObjectCache].
     */
    private fun readCached(f: XFile): ByteArray {
        cache.read(f.name)?.let { return it }
        val bytes = readWhole(f)
        // Failing to write to the cache (no space / no permission) should not affect this read
        runCatching { cache.write(f.name, bytes) }
        return bytes
    }

    /**
     * Pack random-read handle cache (LRU 2). A file's content blobs usually live sequentially
     * inside the same pack, so reusing the same [RandomSource] lets SMB (pread) / WebDAV (Range)
     * / FTP / SFTP do their random reads efficiently; past the cap, close the least-recently-used
     * one to keep open remote handles from growing without bound.
     */
    private val packRandoms = object : LinkedHashMap<String, RandomSource>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RandomSource>): Boolean {
            if (size <= 2) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    /**
     * Read pack data by range. ★ Do not change back to "openInput + skip loop": that pulls
     * every byte from the start of the pack to the target offset and throws them away, so
     * blobs late in large packs tank throughput (decision locked in 2026-07-28, see CLAUDE.md).
     * Switched to [FileSystem.openRandom] for range reads.
     *
     * ★ Must be locked as a whole (2026-07-28): the [RandomSource]s cached in `packRandoms`
     * are closed when LRU evicts them (SMB tears down the entire dedicated connection).
     * Directory expansion and thumbnail generation routinely hit the same repo concurrently,
     * so without locking, one thread mid-read while another's insertion evicts and closes it,
     * `readAt` returns <= 0, manifesting as "pack short read". The lock only costs blob-level
     * parallelism — it does not undermine the core optimization of "avoid whole-packet download".
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

        /** [readWhole] preallocation cap: when `XFile.size` is unavailable / unreliable, do not size the array from it. */
        private const val MAX_PREALLOC = 64L shl 20

        /** Whether the directory's children look like a restic repo (a config file plus data/index/snapshots/keys directories). */
        fun looksLikeRepo(children: List<XFile>): Boolean {
            val names = children.associateBy { it.name }
            return names["config"]?.isDir == false &&
                names["data"]?.isDir == true &&
                names["index"]?.isDir == true &&
                names["snapshots"]?.isDir == true &&
                names["keys"]?.isDir == true
        }

        /** Open a repository with the password; wrong password (the decrypted JSON cannot be parsed) throws [FsException]. */
        fun open(
            fs: FileSystem,
            repoDir: XFile,
            password: String,
            zstd: Zstd,
            cache: ObjectCache = ObjectCache.NONE,
        ): ResticRepo {
            val master = deriveMasterKey(fs, repoDir, password)
            val repo = ResticRepo(fs, repoDir, master, zstd, cache)
            // Validate: decrypting config means the password is correct
            val cfg = repo.decryptFile(repo.readWhole(XFile(repoDir.scheme, "${repoDir.path}/config", false)))
            runCatching { JSONObject(String(cfg, Charsets.UTF_8)).getInt("version") }
                .getOrElse { throw FsException("restic: wrong password or corrupt repository") }
            // ★ Do **not** touch the index here: that is the entire reason [index]'s `by lazy`
            //   exists (see its comment). When 7f73d1d changed the index to lazy loading it forgot
            //   to delete the original synchronous call here, so lazy loading never took effect,
            //   and because it called a private method whose result was thrown away, the index was
            //   in fact read **twice in full** — once here, once when the first readBlob triggered
            //   the lazy load. Password correctness was already verified above by decrypting config,
            //   so reading the index is not needed to confirm it.
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
         * Parse a restic RFC3339 timestamp.
         *
         * Two corrections:
         * - **No longer share a single [SimpleDateFormat] instance** — it is not thread-safe,
         *   and here it is called from `loadTree` (concurrent) and `loadSnapshots` at the same time.
         *   Previously the call was wrapped in runCatching, so the symptom was not a crash but the
         *   timestamp silently becoming 0 (snapshot/file times scrambled) — much harder to notice.
         * - **Honor the timezone offset**. restic writes RFC3339 with an offset
         *   (`…T12:34:56.789+08:00`), but the old code only took the first 19 chars and parsed
         *   them in the device's local time zone, so UTC-stored snapshots ended up off by hours.
         */
        private fun parseTime(s: String): Long {
            if (s.length < 19) return 0L
            return runCatching {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(s.substring(0, 19))!!.time - offsetMillis(s.substring(19))
            }.getOrDefault(0L)
        }

        /** The timezone offset from the part after seconds (`Z` / `+08:00` / `-0500`); unrecognized → UTC. */
        private fun offsetMillis(tail: String): Long {
            val i = tail.indexOfFirst { it == '+' || it == '-' }
            if (i < 0) return 0L // empty, or only fractional seconds, or ends with Z
            val sign = if (tail[i] == '-') -1L else 1L
            val digits = tail.substring(i + 1).filter { it.isDigit() }
            if (digits.length < 4) return 0L
            return sign * (digits.substring(0, 2).toLong() * 3_600_000L +
                digits.substring(2, 4).toLong() * 60_000L)
        }
    }
}
