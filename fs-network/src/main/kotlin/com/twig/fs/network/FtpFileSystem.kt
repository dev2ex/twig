package com.twig.fs.network

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Browses an FTP server as a filesystem (Phase 3).
 *
 * Connection model: discrete operations (list/mkdir/delete/rename) use short
 * connections (connect, do the work, disconnect); streaming reads/writes
 * (openInput/openOutput) are torn down on stream close (completePendingCommand +
 * disconnect) to ensure the FTP data connection is properly terminated.
 * A connection pool can be added later.
 *
 * All upload/download/extraction to FTP is done by [CopyEngine] through
 * openInput/openOutput, so this class needs no extra code for that.
 */
class FtpFileSystem(
    private val config: FtpConfig,
    override val scheme: String = SCHEME,
) : FileSystem {

    /**
     * [FtpConfig.path] without the surrounding slashes; "" = rooted at the server root.
     */
    private val base = config.path.trim('/')

    override val displayName: String =
        "FTP (${config.host}" + (if (base.isEmpty()) "" else "/$base") + ")"

    /**
     * Visible path → the path actually sent to the server. **Every command argument goes
     * through here** ([listRaw] covers the listing ones); everything that builds an
     * [XFile] keeps using the visible path, which is what makes the root a hard floor.
     */
    private fun srv(p: String): String {
        if (base.isEmpty()) return p
        val rel = p.trim('/')
        return if (rel.isEmpty()) "/$base" else "/$base/$rel"
    }

    override fun root(): XFile = dir("/")

    override fun resolve(path: String): XFile = dir(path) // navigation: directories are constructed optimistically

    override fun list(dir: XFile): List<XFile> = withClient { c ->
        listRaw(c, dir.path).asSequence()
            .map { it to baseName(it.name) }
            .filter { (_, n) -> n.isNotEmpty() && n != "." && n != ".." }
            .map { (f, n) -> toXFile(dir.path, f, n) }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
            .toList()
    }

    override fun openInput(file: XFile): InputStream {
        val c = connect()
        val stream = c.retrieveFileStream(srv(file.path))
        if (stream == null) {
            quietClose(c)
            throw FsException("Cannot read: ${file.path} (${c.replyString})")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { c.completePendingCommand() }
                    quietClose(c)
                }
            }
        }
    }

    // FTP does positioned reads via REST (restart offset) + RETR: the server sends
    // directly from the given offset and does not retransmit the preceding bytes
    // (byte-level efficient, only the ~2MB needed is transferred). The cost is
    // that "jumping" requires opening a new data transfer — FTP streams cannot
    // seek in-stream. So thumbnails for MKV/mp4 can take the precise path
    // (otherwise they degrade to a black frame at time 0); it is just that every
    // jump opens a new connection, slightly slower than SMB/SFTP.
    override fun randomAccessEfficient(): Boolean = true

    override fun openRandom(file: XFile): RandomSource = object : RandomSource {
        private var c: FTPClient? = null
        private var stream: InputStream? = null
        private var pos = -1L

        /** Jumps to [position]: tears down the old connection (to avoid the pitfall where
         * completePendingCommand stalls when a transfer is half-finished), then opens
         * a new connection and issues REST + RETR from that offset. Sequential reads
         * (matching pos) never reach this method. */
        private fun openAt(position: Long) {
            close()
            val cc = connect()
            cc.restartOffset = position
            val s = cc.retrieveFileStream(srv(file.path))
            if (s == null) { quietClose(cc); throw FsException("FTP random read failed: ${cc.replyString}") }
            c = cc
            stream = s
            pos = position
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position != pos || stream == null) openAt(position)
            val n = stream!!.read(buffer, offset, length)
            if (n > 0) pos += n
            return n
        }

        override fun length(): Long = file.size

        override fun close() {
            runCatching { stream?.close() }
            stream = null
            c?.let { quietClose(it) } // disconnect directly without completePendingCommand (it stalls when a transfer is half-finished)
            c = null
        }
    }

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        val c = connect()
        val stream = if (append) c.appendFileStream(srv(file.path)) else c.storeFileStream(srv(file.path))
        if (stream == null) {
            quietClose(c)
            throw FsException("Cannot write: ${file.path} (${c.replyString})")
        }
        return object : FilterOutputStream(stream) {
            // FilterOutputStream defaults to byte-by-byte writes; we forward directly to preserve throughput
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { c.completePendingCommand() }
                    quietClose(c)
                }
            }
        }
    }

    override fun mkdir(parent: XFile, name: String): XFile = withClient { c ->
        val path = join(parent.path, name)
        if (!c.makeDirectory(srv(path))) throw FsException("Could not create directory: $path (${c.replyString})")
        dir(path)
    }

    override fun delete(file: XFile) = withClient { c ->
        deleteRecursive(c, file)
    }

    override fun rename(file: XFile, newName: String): XFile = withClient { c ->
        val to = join(file.parentPath, newName)
        if (!c.rename(srv(file.path), srv(to))) throw FsException("Rename failed (${c.replyString})")
        file.copy(path = to)
    }

    // The old implementation was `listFiles(path) is non-empty`: empty directories
    // would be judged as nonexistent, and servers that ignore the LIST argument
    // would always be judged as existent. Switched to CWD (for directories) /
    // SIZE (for files); only fall back to listing the parent directory and
    // matching the name when neither is supported.
    override fun exists(file: XFile): Boolean = withClient { c ->
        if (c.changeWorkingDirectory(srv(file.path))) return@withClient true
        if (runCatching { c.sendCommand("SIZE", srv(file.path)) }.getOrDefault(-1) == 213) {
            return@withClient true
        }
        val name = file.name
        runCatching { listRaw(c, file.parentPath) }.getOrDefault(emptyArray())
            .any { baseName(it.name) == name }
    }

    /** MFMT (RFC 3659), reports time in UTC; older servers do not recognize this command and the failure is treated as "not supported" without raising an error. */
    override fun setModifiedTime(file: XFile, time: Long): Boolean = runCatching {
        // SimpleDateFormat is not thread-safe, so a new one is built each time — this path is not hot enough to justify pooling
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        withClient { c -> c.setModificationTime(srv(file.path), fmt.format(java.util.Date(time))) }
    }.getOrDefault(false)

    // ---- internals ----

    /**
     * Compatibility implementation of directory listing: **CWD into the directory
     * first, then send an unparameterized LIST**.
     *
     * Previously the code did `LIST <absolute path>` directly. The root worked,
     * but deeper expansion always returned empty — because many FTP servers
     * (embedded devices, router/NAS firmware, FTP-share apps on Android) handle
     * parameterized LIST poorly: they either reply 550 or simply ignore the
     * argument; non-ASCII directory names also fail to resolve when encodings
     * disagree. In all these cases commons-net just returns an **empty array**
     * (no exception), and the UI symptom is "the first level expands, the next
     * one is empty". CWD lets the server parse the path itself, which has the
     * best compatibility, and it also turns "directory missing / no permission"
     * into an explicit failure.
     *
     * Fallback order: CWD succeeds → unparameterized LIST; CWD fails → fall back
     * to the old parameterized LIST; if that is also empty, **throw** (with
     * replyString) — we no longer silently treat it as an empty directory, since
     * empty directories and failures must be distinguishable.
     */
    private fun listRaw(c: FTPClient, visible: String): Array<FTPFile> {
        val path = srv(visible)
        if (c.changeWorkingDirectory(path)) {
            val files = c.listFiles()
            // A successful LIST finishes with 226/250; if the reply is not a positive
            // completion, the data connection never came up (e.g. PASV blocked by
            // NAT/firewall). commons-net just returns an empty array there — we
            // must report it instead of pretending the directory is empty.
            if (files.isNullOrEmpty() && !FTPReply.isPositiveCompletion(c.replyCode)) {
                throw FsException("List failed: $path (${c.replyString})")
            }
            return files ?: emptyArray()
        }
        val cwdFail = c.replyString
        val byArg = c.listFiles(path)
        if (byArg.isNullOrEmpty()) throw FsException("Cannot enter directory: $path ($cwdFail)")
        return byArg
    }

    /** Some servers return the full path in LIST results; take the last segment as the name. */
    private fun baseName(name: String): String = name.trimEnd('/').substringAfterLast('/')

    private fun deleteRecursive(c: FTPClient, file: XFile) {
        if (file.isDir) {
            val children = listRaw(c, file.path)
            for (f in children) {
                val n = baseName(f.name)
                if (n.isEmpty() || n == "." || n == "..") continue
                deleteRecursive(c, toXFile(file.path, f, n))
            }
            // When listing children we CWD'd in; some servers refuse to delete the current working directory, so leave it first
            runCatching { c.changeWorkingDirectory("/") }
            if (!c.removeDirectory(srv(file.path))) {
                throw FsException("Could not delete directory: ${file.path} (${c.replyString})")
            }
        } else if (!c.deleteFile(srv(file.path))) {
            throw FsException("Could not delete file: ${file.path} (${c.replyString})")
        }
    }

    private fun <T> withClient(block: (FTPClient) -> T): T {
        val c = connect()
        try {
            return block(c)
        } finally {
            quietClose(c)
        }
    }

    private fun connect(): FTPClient {
        val c = FTPClient()
        c.controlEncoding = config.encoding
        try {
            c.connect(config.host, config.port)
        } catch (e: Exception) {
            throw FsException("Cannot connect to ${config.host}:${config.port}", e)
        }
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            runCatching { c.disconnect() }
            throw FsException("FTP refused the connection: ${c.replyString}")
        }
        if (!c.login(config.user, config.password)) {
            runCatching { c.disconnect() }
            throw FsException("FTP login failed")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        return c
    }

    private fun quietClose(c: FTPClient) {
        runCatching { if (c.isConnected) c.logout() }
        runCatching { if (c.isConnected) c.disconnect() }
    }

    private fun toXFile(parentPath: String, f: FTPFile, name: String = baseName(f.name)) = XFile(
        scheme = scheme,
        path = join(parentPath, name),
        isDir = f.isDirectory,
        size = if (f.isDirectory) 0L else f.size,
        lastModified = f.timestamp?.timeInMillis ?: 0L,
    )

    private fun dir(path: String) = XFile(scheme = scheme, path = path, isDir = true)

    private fun join(dir: String, name: String): String =
        when {
            dir.endsWith("/") -> "$dir$name"
            else -> "$dir/$name"
        }

    companion object {
        const val SCHEME = "ftp"
    }
}
