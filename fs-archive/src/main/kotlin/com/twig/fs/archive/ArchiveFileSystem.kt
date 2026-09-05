package com.twig.fs.archive

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Common base class for archive filesystems: pulls together path parsing for "archive!/inner-path",
 * tree synthesis, reads, falling back to the host directory, and similar logic. Subclasses
 * (zip/7z/rar) only need to provide two things:
 *  - [readEntries]: enumerate every entry inside the archive
 *  - [openEntry]: open an input stream for one archive-internal file
 *
 * Read-only by default; formats that can write (e.g. zip) override the write operations.
 * The archive itself is assumed to live locally ("file").
 */
abstract class ArchiveFileSystem : FileSystem {

    /** One entry inside an archive. `name` is '/'-separated; directory names end with '/'. */
    protected data class ArchiveEntry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val time: Long,
    )

    protected abstract fun readEntries(archivePath: String): List<ArchiveEntry>

    protected abstract fun openEntry(archivePath: String, inner: String): InputStream

    /** Non-local hosts registered at mount time (path -> host XFile), used by the random-access channel and entry cache. */
    private val hosts = HashMap<String, XFile>()
    /** Entry cache for remote archives (remote directory parsing has network cost; local reads fresh each time). */
    private val entryCache = HashMap<String, List<ArchiveEntry>>()
    /** Unlocked archive passwords (archive path -> password); valid for the process lifetime, persistence is the app layer's choice. */
    private val passwords = HashMap<String, String>()

    /**
     * Mount an archive file as the archive root. The host can be any source: local is read directly;
     * SMB/WebDAV and others go through openRandom's positional read for streaming parse (zip/7z), without downloading the whole archive.
     */
    fun rootOf(archive: XFile): XFile {
        synchronized(hosts) {
            if (archive.scheme == HOST_SCHEME) hosts.remove(archive.path)
            else hosts[archive.path] = archive
        }
        return dirXFile(archive.path, "")
    }

    // ---- password (encrypted archives) ----

    /**
     * Record an archive's password; pass null to clear. **Does not verify** — verification is in
     * [checkPassword], so the caller can distinguish "wrong password" from "read failed".
     */
    fun setPassword(archivePath: String, password: String?) {
        synchronized(passwords) {
            if (password == null) passwords.remove(archivePath) else passwords[archivePath] = password
        }
        // For header-encrypted formats (7z/rar) the entry listing itself is a decryption result,
        // so changing the password means re-parsing
        synchronized(entryCache) { entryCache.remove(archivePath) }
    }

    protected fun passwordOf(archivePath: String): String? =
        synchronized(passwords) { passwords[archivePath] }

    fun hasPassword(archivePath: String): Boolean = passwordOf(archivePath) != null

    /**
     * Whether this archive requires a password to read all its content. Each subclass decides
     * (zip looks at entry encryption bits, 7z/rar at the header). Read-only — does not change
     * any state, so it can be called before mounting.
     */
    open fun needsPassword(archivePath: String): Boolean = false

    /** Verify whether the password is correct (does not change state); formats that do not support encryption always return true. */
    open fun checkPassword(archivePath: String, password: String): Boolean = true

    /** Fetch the password, or throw [ArchivePasswordException] for the UI to prompt. */
    protected fun requirePassword(archivePath: String): String =
        passwordOf(archivePath) ?: throw ArchivePasswordException(archivePath)

    /**
     * Identity for "which archive is on this path now" (path + size + modification time); used as
     * the cache key for **probe results** like "needs a password". When a same-named file is
     * swapped for a different archive the cache automatically invalidates — the probe itself reads
     * the archive header, so without this key the probe would have to run every time.
     */
    protected fun stampOf(archivePath: String): String {
        val host = hostOf(archivePath)
        if (host.scheme != HOST_SCHEME) return "$archivePath:${host.size}:${host.lastModified}"
        val f = File(archivePath)
        return "$archivePath:${f.length()}:${f.lastModified()}"
    }

    /** Archive host: the remote XFile registered at mount time, or treated as a local file. */
    protected fun hostOf(archivePath: String): XFile =
        synchronized(hosts) { hosts[archivePath] } ?: XFile(HOST_SCHEME, archivePath, isDir = false)

    /** Open the archive's random-access channel (local FileChannel / remote RandomSource adapted). */
    protected fun openChannel(archivePath: String): java.nio.channels.SeekableByteChannel {
        val host = hostOf(archivePath)
        if (host.scheme == HOST_SCHEME) {
            return java.io.RandomAccessFile(archivePath, "r").channel
        }
        val src = FsRegistry.of(host).openRandom(host)
        val size = if (host.size > 0) host.size else src.length()
        return RandomSourceChannel(src, size)
    }

    /** Get the entry list: remote archives are cached (re-expanding does not re-parse), local reads fresh each time. */
    protected fun entries(archivePath: String): List<ArchiveEntry> {
        if (hostOf(archivePath).scheme == HOST_SCHEME) return readEntries(archivePath)
        synchronized(entryCache) { entryCache[archivePath]?.let { return it } }
        val list = readEntries(archivePath)
        synchronized(entryCache) { entryCache[archivePath] = list }
        return list
    }

    override fun root(): XFile = throw FsException("Archive must be mounted via rootOf(archive)")

    override fun resolve(path: String): XFile {
        val archive = archiveOf(path)
        val inner = innerOf(path)
        if (inner.isEmpty()) return dirXFile(archive, "")
        val match = entries(archive).firstOrNull { normalize(it).trimEnd('/') == inner }
        return if (match != null && !match.isDir) {
            fileXFile(archive, inner, match)
        } else {
            dirXFile(archive, inner)
        }
    }

    override fun list(dir: XFile): List<XFile> {
        val archive = archiveOf(dir.path)
        val inner = innerOf(dir.path)
        val prefix = if (inner.isEmpty()) "" else "$inner/"

        val dirNames = LinkedHashSet<String>()
        val files = LinkedHashMap<String, ArchiveEntry>()

        for (e in entries(archive)) {
            val name = normalize(e)
            if (!name.startsWith(prefix) || name == prefix) continue
            val remainder = name.substring(prefix.length)
            if (remainder.isEmpty()) continue
            val slash = remainder.indexOf('/')
            if (slash >= 0) {
                dirNames.add(remainder.substring(0, slash))
            } else {
                files[remainder] = e
            }
        }

        val result = ArrayList<XFile>(dirNames.size + files.size)
        for (d in dirNames) result.add(dirXFile(archive, prefix + d))
        for ((seg, e) in files) if (seg !in dirNames) result.add(fileXFile(archive, prefix + seg, e))

        result.sortWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
        return result
    }

    override fun openInput(file: XFile): InputStream =
        openEntry(archiveOf(file.path), innerOf(file.path))

    override fun exists(file: XFile): Boolean {
        val archive = archiveOf(file.path)
        val inner = innerOf(file.path)
        if (inner.isEmpty()) {
            val host = hostOf(archive)
            return if (host.scheme == HOST_SCHEME) File(archive).exists()
            else runCatching { FsRegistry.of(host).exists(host) }.getOrDefault(false)
        }
        return entries(archive).any {
            val n = normalize(it).trimEnd('/')
            n == inner || n.startsWith("$inner/")
        }
    }

    override fun parentOf(file: XFile): XFile? {
        val archive = archiveOf(file.path)
        val inner = innerOf(file.path)
        return when {
            inner.isEmpty() -> {
                val host = hostOf(archive)
                if (host.scheme != HOST_SCHEME) return FsRegistry.of(host).parentOf(host)
                val parent = File(archive).parent ?: return null
                FsRegistry.of(HOST_SCHEME).resolve(parent)
            }
            inner.contains('/') -> dirXFile(archive, inner.substringBeforeLast('/'))
            else -> dirXFile(archive, "")
        }
    }

    // ---- read-only by default; writable formats override ----

    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        throw FsException("$displayName is read-only (write not supported)")

    override fun mkdir(parent: XFile, name: String): XFile =
        throw FsException("$displayName is read-only (create not supported)")

    override fun delete(file: XFile): Unit =
        throw FsException("$displayName is read-only (delete not supported)")

    override fun rename(file: XFile, newName: String): XFile =
        throw FsException("$displayName is read-only (rename not supported)")

    // ---- utilities (subclasses may use) ----

    protected fun dirXFile(archive: String, inner: String) = XFile(
        scheme = scheme,
        path = "$archive$SEP$inner",
        isDir = true,
        canWrite = writable(archive),
    )

    protected fun fileXFile(archive: String, inner: String, e: ArchiveEntry) = XFile(
        scheme = scheme,
        path = "$archive$SEP$inner",
        isDir = false,
        size = if (e.size >= 0) e.size else 0L,
        lastModified = e.time,
        canWrite = writable(archive),
    )

    /** Normalize entry names: unify '/'; drop leading '/'; directories end with '/'. */
    private fun normalize(e: ArchiveEntry): String {
        var n = e.name.replace('\\', '/').removePrefix("/")
        if (e.isDir && !n.endsWith("/")) n += "/"
        return n
    }

    /**
     * Whether this archive instance supports writing; determines canWrite on [dirXFile]/[fileXFile]
     * (used by the UI to grey things out). Read-only by default; zip overrides to "host is a local
     * file" (the whole-archive rewrite implementation does not support remote hosts).
     */
    protected open fun writable(archivePath: String): Boolean = false

    /** Whether the host can do efficient random access on this entry (e.g. zip STORED entries can be sliced directly); default no. */
    open fun fastRandom(file: XFile): Boolean = false

    // Split on the last "!/" so nested archives work (outer.zip!/inner.zip!/a.txt)
    protected fun archiveOf(path: String): String = path.substringBeforeLast(SEP)

    protected fun innerOf(path: String): String = path.substringAfterLast(SEP, "").trim('/')

    companion object {
        /** Separator between archive path and inner path, borrowed from the JDK jar URL syntax. */
        const val SEP = "!/"
        const val HOST_SCHEME = "file"
    }
}
