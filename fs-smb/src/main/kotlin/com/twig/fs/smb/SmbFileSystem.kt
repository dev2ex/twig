package com.twig.fs.smb

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * SMB/CIFS file system, backed by libsmb2 (native).
 *
 * Paths: XFile.path starts with '/' (our universal convention); the leading '/' is
 * stripped when handed to libsmb2 (whose share root is ""). Upload / download /
 * extract-to-SMB all go through [CopyEngine]'s openInput/openOutput, so this class
 * needs no extra code for those.
 *
 * **Two shapes, decided by [SmbConfig.share]** (which is a share name *or* a path
 * inside one — see there):
 * - filled in → the root is that share, or that directory inside it, exactly as before
 *   (one connection, one share);
 * - empty → the root lists every disk share on the server. **One libsmb2 context can
 *   only ever be attached to one share**, so this mode keeps a client per share
 *   ([clients], created lazily on first use) plus one bound to IPC$, which exists purely
 *   so srvsvc's NetrShareEnum has somewhere to run ([NativeSmbClient.listShares]).
 *
 * Everything the UI sees is relative to that root, so paths stay short and nothing above
 * it is reachable.
 */
class SmbFileSystem(
    private val config: SmbConfig,
    /** Supports mounting multiple servers simultaneously: each instance gets a unique scheme (e.g. "smb1a2b"). */
    override val scheme: String = SCHEME,
) : FileSystem {

    /** The share name alone — the first segment of [SmbConfig.share], which may carry a path. */
    private val share = config.share.trim('/').substringBefore('/')

    /** No share name = "mount the whole server", the root being its share list. */
    private val serverMode = share.isEmpty()

    /** Where the root sits inside the share (the rest of [SmbConfig.share]); "" = the share root. */
    private val base = config.share.trim('/').substringAfter('/', "")

    /** One connected client per share; in single-share mode it only ever holds that one. */
    private val clients = ConcurrentHashMap<String, NativeSmbClient>()

    override val displayName: String =
        "SMB (" + config.host + (if (serverMode) "" else "/$share") +
            (if (base.isEmpty()) "" else "/$base") + ")"

    /**
     * Establishes the connection; should be called on a worker thread (before mounting).
     * In server mode this connects IPC$ — the credentials are verified here just the same,
     * and the share list needs it anyway.
     */
    fun connect() {
        primary()
    }

    /**
     * Negotiated SMB version name, **major only** ("SMB2" / "SMB3") — it sits on the
     * server row in the tree, where the revision (3.0.2 vs 3.1.1) says nothing the user
     * would act on. The high byte of the dialect code is the major version, so future
     * revisions are covered without a new branch.
     * Returns null if unknown / not connected.
     */
    fun dialectName(): String? = when (runCatching { primary().dialect() }.getOrDefault(0) shr 8) {
        0x02 -> "SMB2"
        0x03 -> "SMB3"
        else -> null
    }

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> {
        val loc = locOf(dir.path) ?: return listShares()
        return client(loc.share).list(loc.path)
            .map { e ->
                XFile(
                    scheme = scheme,
                    path = join(dir.path, e.name),
                    isDir = e.isDir,
                    size = if (e.isDir) 0L else e.size,
                    lastModified = e.mtimeSec * 1000L,
                )
            }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /**
     * The share list, shown as directories. Printers / IPC and the administrative `$`
     * shares are dropped: none of them can be browsed as a file tree, and listing them
     * would only produce rows that error out when tapped.
     */
    private fun listShares(): List<XFile> =
        primary().listShares()
            .filter { it.isDisk && !it.isHidden }
            .map { XFile(scheme, "/${it.name}", isDir = true) }
            .sortedBy { it.name.lowercase() }

    override fun openInput(file: XFile): InputStream = at(file) { c, p -> c.openInput(p) }

    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        at(file) { c, p -> c.openOutput(p) } // append is not supported; treat as overwrite

    override fun mkdir(parent: XFile, name: String): XFile {
        val path = join(parent.path, name)
        val loc = locOf(path) ?: throw FsException("Cannot create a share")
        if (loc.path.isEmpty()) throw FsException("Cannot create a share")
        client(loc.share).mkdir(loc.path)
        return XFile(scheme, path, isDir = true)
    }

    override fun delete(file: XFile) {
        val loc = locOf(file.path) ?: throw FsException("Cannot delete the share list")
        if (loc.path.isEmpty()) throw FsException("Cannot delete a share")
        if (file.isDir) {
            for (child in list(file)) delete(child)
            client(loc.share).delete(loc.path, isDir = true)
        } else {
            client(loc.share).delete(loc.path, isDir = false)
        }
    }

    override fun rename(file: XFile, newName: String): XFile {
        val to = join(file.parentPath, newName)
        val from = locOf(file.path) ?: throw FsException("Cannot rename the share list")
        val dest = locOf(to) ?: throw FsException("Cannot rename to the share list")
        if (from.path.isEmpty() || dest.path.isEmpty()) throw FsException("Cannot rename a share")
        client(from.share).rename(from.path, dest.path)
        return file.copy(path = to)
    }

    override fun randomAccessEfficient(): Boolean = true // pread positioned read, independent of position

    override fun openRandom(file: XFile): com.twig.core.RandomSource {
        val loc = locOf(file.path) ?: throw FsException("Not a file: ${file.path}")
        // Media random reads use a "dedicated connection": isolated from the browse connection,
// and on teardown only destroy_context is called (avoiding the crashing close/logoff path).
        val dedicated = NativeSmbClient()
        dedicated.connect(config.copy(share = loc.share))
        val fh = dedicated.openReadHandle(loc.path)
        if (fh == 0L) {
            runCatching { dedicated.closeHard() }
            throw FsException("Open failed: ${file.name}")
        }
        return object : com.twig.core.RandomSource {
            // Only translate a real 0-byte read into EOF (-1); read failures are thrown by pread,
// no longer disguised as EOF
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                dedicated.pread(fh, position, buffer, offset, length).let { if (it == 0) -1 else it }
            override fun length(): Long = file.size
            override fun close() = dedicated.closeHard() // Only destroy the context; the fh is released along with it
        }
    }

    override fun exists(file: XFile): Boolean =
        runCatching {
            val parent = file.parentPath
            list(XFile(scheme, parent, isDir = true)).any { it.path == file.path }
        }.getOrDefault(false)

    fun disconnect() {
        for (c in clients.values) runCatching { c.close() }
        clients.clear()
    }

    // ---- path ↔ (share, path inside the share) ----

    /** [path] is relative to the share root and carries no leading '/'. */
    private data class Loc(val share: String, val path: String)

    /**
     * Splits an [XFile.path] into the share it lives in and the path inside it; returns
     * null for the "share list" layer, which only exists in server mode.
     */
    private fun locOf(p: String): Loc? {
        val abs = listOf(base, p.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
        if (!serverMode) return Loc(share, abs)
        if (abs.isEmpty()) return null
        return Loc(abs.substringBefore('/'), abs.substringAfter('/', ""))
    }

    private inline fun <T> at(file: XFile, body: (NativeSmbClient, String) -> T): T {
        val loc = locOf(file.path) ?: throw FsException("Not a file: ${file.path}")
        if (loc.path.isEmpty()) throw FsException("Not a file: ${file.path}")
        return body(client(loc.share), loc.path)
    }

    /**
     * The connected client for one share, connecting on first use. `computeIfAbsent`
     * keeps two threads (browsing and thumbnail loading, say) from opening two contexts
     * for the same share; a failed connect throws out of the mapping function, so nothing
     * broken is cached.
     */
    private fun client(share: String): NativeSmbClient =
        clients.computeIfAbsent(share) { s ->
            NativeSmbClient().also { it.connect(config.copy(share = s)) }
        }

    /** The client the connection itself is judged by: the share in single-share mode, IPC$ in server mode. */
    private fun primary(): NativeSmbClient = client(if (serverMode) IPC else share)

    private fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    companion object {
        const val SCHEME = "smb"
        /** srvsvc (the share list) only answers on a connection to this share. */
        private const val IPC = "IPC$"
    }
}
