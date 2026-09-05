package com.twig.fs.network

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * Configuration for an SFTP connection; when [keyPath] is non-empty, public-key
 * authentication is used ([password] doubles as the private-key passphrase).
 *
 * [knownHostKey] / [onLearnHostKey] handle host-key TOFU (see
 * [SftpFileSystem.hostKeyVerifier]): empty means "not remembered yet" — the
 * fingerprint is handed to [onLearnHostKey] on first connect so the layer above
 * can persist it; non-empty means it must match, otherwise the connection is
 * refused.
 */
data class SftpConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String = "",
    val keyPath: String = "",
    val knownHostKey: String = "",
    val onLearnHostKey: (String) -> Unit = {},
    /**
     * Optional directory the connection is rooted at ("/srv/media" or "srv/media");
     * empty = the server root. Every path the UI sees is relative to it, and
     * [SftpFileSystem.serverPath] translates back.
     */
    val path: String = "",
)

/**
 * SFTP filesystem, based on SSHJ.
 *
 * Connection model: SSH handshake is expensive, so we keep a single persistent
 * connection and lazy-connect on the first operation; SFTP multiplexes requests
 * by ID over one channel, so streaming reads/writes coexist with discrete
 * operations. Host key verification follows TOFU (trust on first use, then
 * reject on change), see [hostKeyVerifier].
 */
class SftpFileSystem(
    private val config: SftpConfig,
    override val scheme: String = SCHEME,
) : FileSystem {

    /** [SftpConfig.path] without the surrounding slashes; "" = rooted at the server root. */
    private val base = config.path.trim('/')

    override val displayName: String =
        "SFTP (${config.host}" + (if (base.isEmpty()) "" else "/$base") + ")"

    /**
     * Visible path → the absolute path on the server. **Every SFTP request argument goes
     * through here**, while everything that builds an [XFile] keeps the visible path.
     *
     * ★ It is `public` because SFTP paths escape this class: the git viewer runs
     * `git -C <path>`, the terminal `cd`s into the current directory and remote commands
     * get a workdir — all of them shell out over the same SSH connection and need the
     * **real** path, not the one on screen. Anything that hands a path to a command must
     * call this, or it will land in the wrong directory.
     */
    fun serverPath(visible: String): String {
        if (base.isEmpty()) return visible
        val rel = visible.trim('/')
        return if (rel.isEmpty()) "/$base" else "/$base/$rel"
    }

    /**
     * The inverse of [serverPath]: an absolute path on the server → the path the UI sees;
     * null when it falls **outside** the connection root and so cannot be shown at all.
     *
     * Needed because paths also travel the other way: a command's output can name
     * directories on the server (`git worktree list` prints absolute paths), and turning
     * one back into an [XFile] means stripping the root again — without it the git
     * viewer's worktree list has the right count and expands into nothing.
     */
    fun visiblePath(server: String): String? {
        if (base.isEmpty()) return server
        val rel = server.trim('/')
        if (rel == base) return "/"
        return if (rel.startsWith("$base/")) "/" + rel.removePrefix("$base/") else null
    }

    @Volatile private var client: SFTPClient? = null
    @Volatile private var ssh: SSHClient? = null

    /**
     * Host-key verification (TOFU: trust on first use).
     *
     * Previously this used SSHJ's `PromiscuousVerifier` — which accepts any host
     * key. The comment said "in a file manager, availability comes first", but
     * the cost is that a man-in-the-middle on the LAN can silently hijack every
     * SFTP connection, on which the user's password auth, file transfers and
     * remote command execution all run.
     *
     * Now: the fingerprint is remembered on first connect (passed to
     * [SftpConfig.onLearnHostKey] for persistence), and on every subsequent
     * connect it must match; mismatch throws [HostKeyChanged], and the layer
     * above surfaces both fingerprints so the user can decide — this is exactly
     * the moment where a host-key change *should* interrupt the user (either
     * the server was reinstalled, or someone is hijacking the connection;
     * either way the user needs to know).
     *
     * There is no interactive "do you trust this?" dialog: that requires
     * splitting a blocking IO into two halves to wait for a UI answer, and
     * :fs-network is a pure JVM module that cannot reach the UI. When the user
     * confirms the server did legitimately change its key, the "Forget host key"
     * entry in the server's long-press menu resets the state.
     */
    class HostKeyChanged(val expected: String, val actual: String) :
        RuntimeException("host key changed: expected $expected, got $actual")

    // Cannot be written as a SAM lambda: HostKeyVerifier has two methods (verify + findExistingAlgorithms)
    private fun hostKeyVerifier() = object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
        override fun verify(hostname: String?, port: Int, key: java.security.PublicKey): Boolean {
            val fp = fingerprintOf(key)
            val known = config.knownHostKey
            return when {
                known.isEmpty() -> { config.onLearnHostKey(fp); true } // first encounter: remember it
                known == fp -> true
                else -> throw HostKeyChanged(known, fp)
            }
        }

        /** Do not constrain algorithms (returning an empty list = "no known preference"); let SSHJ negotiate. */
        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
    }

    /**
     * SHA-256 fingerprint matching `ssh-keygen -lf` (SSH wire format → SHA-256 →
     * base64 → drop the '=' padding), so users can paste it next to the one
     * their server prints and compare directly.
     * When the SSH wire format cannot be obtained, falls back to hex of the
     * X.509 encoding — it is only compared against our own stored value, so
     * this is good enough.
     */
    private fun fingerprintOf(key: java.security.PublicKey): String = runCatching {
        val wire = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(key).compactData
        "SHA256:" + b64(java.security.MessageDigest.getInstance("SHA-256").digest(wire))
    }.getOrElse {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(key.encoded)
        "X509:" + d.joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * Standard base64 encoding without '=' padding (this is exactly the form
     * ssh-keygen uses for fingerprints).
     * Hand-rolled: this is a pure JVM module, no `android.util.Base64`;
     * `java.util.Base64` needs API 26, minSdk is 24 — the same pitfall that
     * `ResticCrypto.base64` sidesteps.
     */
    private fun b64(data: ByteArray): String {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(alpha[b0 shr 2])
            sb.append(alpha[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) sb.append(alpha[((b1 and 0x0f) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            if (b2 >= 0) sb.append(alpha[b2 and 0x3f])
            i += 3
        }
        return sb.toString()
    }

    /** Creates and authenticates a new SSH connection (not reused); used independently by SFTP and the terminal. */
    private fun newAuthedClient(): SSHClient {
        val s = SSHClient()
        s.addHostKeyVerifier(hostKeyVerifier())
        try {
            s.connect(config.host, config.port)
            if (config.keyPath.isNotEmpty()) {
                val keys = if (config.password.isNotEmpty()) {
                    s.loadKeys(config.keyPath, config.password.toCharArray())
                } else {
                    s.loadKeys(config.keyPath)
                }
                s.authPublickey(config.user, keys)
            } else {
                s.authPassword(config.user, config.password)
            }
        } catch (e: Exception) {
            runCatching { s.disconnect() }
            // A changed host key must be reported separately — this is not a
            // "connection failed", it is a security event, and the user must
            // see both fingerprints
            generateSequence(e as Throwable) { it.cause }.filterIsInstance<HostKeyChanged>()
                .firstOrNull()?.let {
                    throw FsException(
                        "Host key differs from the one remembered; connection refused.\n" +
                            "The server may have been reinstalled, or someone may be " +
                            "intercepting the connection. If you are sure it is fine, " +
                            "choose \"Forget host key\" in the server long-press menu.\n" +
                            "Remembered: ${it.expected}\nActual: ${it.actual}",
                        it,
                    )
                }
            e.printStackTrace() // logcat W/System.err:twig — debug connection failures with empty e.message
            throw FsException("SFTP connection failed: ${e::class.simpleName}: ${e.message}", e)
        }
        // Keepalive: mobile networks / NATs silently kill idle connections after a
        // few minutes, especially when the app goes background; periodic keepalives
        // keep the connection alive and let us detect a real disconnect faster.
        s.connection.keepAlive.keepAliveInterval = 15
        return s
    }

    @Synchronized
    private fun cli(): SFTPClient {
        client?.let { if (ssh?.isConnected == true) return it }
        disconnect()
        val s = newAuthedClient()
        ssh = s
        return s.newSFTPClient().also { client = it }
    }

    /** Auto-reconnect and retry once after a disconnect (screen-off / network sleep can drop the persistent connection). */
    private fun <T> retry(op: (SFTPClient) -> T): T = try {
        op(cli())
    } catch (e: Exception) {
        disconnect()
        op(cli())
    }

    /**
     * Runs a command on the server, returning stdout; returns null on non-zero
     * exit / failure.
     * Reuses the SFTP persistent SSH connection (one exec session per command);
     * auto-reconnects once on disconnect.
     * Used by the layer above to run remote git and similar server-side operations.
     */
    fun exec(cmd: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                synchronized(this) { cli() } // ensure we are connected
                val session = ssh?.startSession() ?: return null
                try {
                    val c = session.exec(cmd)
                    val out = c.inputStream.readBytes()
                    c.join(30, java.util.concurrent.TimeUnit.SECONDS)
                    return if (c.exitStatus == 0) out else null
                } finally {
                    runCatching { session.close() }
                }
            } catch (e: Exception) {
                if (attempt == 1) return null
                synchronized(this) { disconnect() } // reconnect and try again
            }
        }
        return null
    }

    /** Complete result of a remote command (exit code + both output streams). */
    class ExecResult(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
    }

    /**
     * Runs a command and brings back the exit code along with stdout and stderr —
     * [exec] only returns stdout on success, which is enough for capability
     * detection but not for user-facing script execution where the failure
     * reason must be visible.
     *
     * Two differences from [exec]:
     * - **Uses a dedicated connection** (same as [openShell]): user scripts may
     *   run for a long time, so they must not occupy the shared connection used
     *   for browsing files, nor stall each other when large files are being moved.
     * - **Reads stdout and stderr on separate threads**: with a single thread
     *   reading stdout first then stderr, the server can fill its stderr buffer
     *   and both sides deadlock.
     */
    fun execFull(cmd: String, timeoutSec: Long = 900): ExecResult {
        val c = newAuthedClient()
        try {
            val session = c.startSession()
            try {
                val cmdCh = session.exec(cmd)
                val out = java.io.ByteArrayOutputStream()
                val err = java.io.ByteArrayOutputStream()
                val t1 = Thread({ runCatching { cmdCh.inputStream.copyTo(out) } }, "twig-exec-out")
                val t2 = Thread({ runCatching { cmdCh.errorStream.copyTo(err) } }, "twig-exec-err")
                t1.start(); t2.start()
                runCatching { cmdCh.join(timeoutSec, java.util.concurrent.TimeUnit.SECONDS) }
                t1.join(3000); t2.join(3000)
                return ExecResult(
                    cmdCh.exitStatus ?: -1,
                    out.toString(Charsets.UTF_8.name()),
                    err.toString(Charsets.UTF_8.name()),
                )
            } finally {
                runCatching { session.close() }
            }
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /**
     * One interactive shell session (with PTY, dedicated SSH connection), for the
     * SSH terminal.
     * All outbound operations (stdin data / window-size changes) are serialized
     * through the same lock: concurrent writes scramble the cipher stream
     * counters, the server's MAC check fails, and the TCP connection is dropped
     * (raw EOF).
     */
    class ShellSession internal constructor(
        private val ownClient: SSHClient,
        private val session: net.schmizz.sshj.connection.channel.direct.Session,
        private val shell: net.schmizz.sshj.connection.channel.direct.Session.Shell,
    ) : java.io.Closeable {
        private val writeLock = Any()
        private var lastCols = -1
        private var lastRows = -1

        val stdout: InputStream get() = shell.inputStream

        /** Writes to remote stdin (serialized). */
        fun write(buf: ByteArray, off: Int, len: Int) {
            synchronized(writeLock) {
                shell.outputStream.write(buf, off, len)
                shell.outputStream.flush()
            }
        }

        /**
         * Synchronizes the remote PTY size (serialized; no-op when the size
         * is unchanged). Returns whether a window-change was actually sent.
         * ★ Must not be called from the Android main thread: SSHJ advances
         * packet sequence / cipher-stream state before writing to the socket,
         * so once NetworkOnMainThreadException is thrown the connection is
         * "poisoned" and the next packet is guaranteed to fail.
         * Failures are not silenced — historically swallowing this exception
         * sent the root-cause investigation on a wild goose chase twice over.
         */
        fun resize(cols: Int, rows: Int): Boolean {
            synchronized(writeLock) {
                if (cols == lastCols && rows == lastRows) return false
                lastCols = cols; lastRows = rows
                return try {
                    shell.changeWindowDimensions(cols, rows, 0, 0)
                    true
                } catch (e: Exception) {
                    System.err.println("twig: window-change failed: $e") // logcat W/System.err
                    false
                }
            }
        }

        val isOpen: Boolean get() = shell.isOpen

        /**
         * Exit code of the remote shell; null = no exit-status received.
         * `getExitStatus()` is declared on `Session.Command`, not on `Session`,
         * but the implementation class `SessionChannel` implements
         * Session/Command/Shell simultaneously, so a cast reaches it.
         */
        val exitStatus: Int? get() = runCatching {
            (session as? net.schmizz.sshj.connection.channel.direct.Session.Command)?.exitStatus
        }.getOrNull()

        /**
         * Whether the remote shell **exited on its own** (`exit` / Ctrl+D), as
         * opposed to the connection being dropped — both look like EOF on stdout,
         * so we must rely on the SSH protocol layer to tell them apart; otherwise
         * a clean exit would also trigger the disconnect auto-reconnect.
         *
         * Two criteria; either one means "clean":
         * - We received `exit-status` (the server sends this on normal channel close);
         * - The transport is still up — channel gone but TCP/SSH transport alive
         *   can only mean the remote end closed this shell; a real disconnect
         *   would take the transport with it.
         *
         * stdout's EOF may arrive before `exit-status`, so wait for the channel
         * to actually close (up to [waitMs]) before drawing a conclusion.
         */
        fun exitedCleanly(waitMs: Long = 2000): Boolean {
            runCatching { session.join(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            return exitStatus != null || ownClient.isConnected
        }

        override fun close() {
            runCatching { shell.close() }
            runCatching { session.close() }
            runCatching { ownClient.disconnect() }
        }
    }

    /**
     * Opens an interactive shell (xterm-256color PTY). Uses a **dedicated SSH
     * connection**, isolated from SFTP file transfer — sharing one transport
     * makes terminal input trigger "Broken transport EOF" (the server kicks
     * concurrent channel reads/writes) and large file transfers can stall the
     * terminal.
     */
    fun openShell(cols: Int, rows: Int): ShellSession {
        val c = newAuthedClient()
        return try {
            val s = c.startSession()
            s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
            ShellSession(c, s, s.startShell())
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            throw FsException("Could not open terminal: ${e.message}", e)
        }
    }

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> =
        retry { it.ls(serverPath(dir.path)) }
            .map { info ->
                XFile(
                    scheme = scheme,
                    path = join(dir.path, info.name),
                    isDir = info.isDirectory,
                    size = if (info.isDirectory) 0L else info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                )
            }
            .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })

    override fun openInput(file: XFile): InputStream {
        val rf = retry { it.open(serverPath(file.path)) }
        return object : FilterInputStream(rf.RemoteFileInputStream()) {
            override fun close() {
                try { super.close() } finally { runCatching { rf.close() } }
            }
        }
    }

    // SFTP supports positioned reads (RemoteFile.read(offset,...)), not O(position) reopen-and-skip,
    // so thumbnails for MKV/mp4 can take the "parse the container precisely + download
    // only the necessary fragments" path (otherwise degrading to time 0 = a black frame).
    override fun randomAccessEfficient(): Boolean = true

    override fun openRandom(file: XFile): RandomSource {
        val rf = retry { it.open(serverPath(file.path)) }
        return object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                rf.read(position, buffer, offset, length) // SSHJ: returns bytes read, EOF returns -1
            override fun length(): Long = file.size
            override fun close() { runCatching { rf.close() } }
        }
    }

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        val modes = if (append) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        var chunk = FALLBACK_WRITE_CHUNK
        val rf = retry { c -> c.open(serverPath(file.path), modes).also { chunk = writeChunk(c, it) } }
        val limit = chunk
        return object : FilterOutputStream(rf.RemoteFileOutputStream()) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                var pos = off
                var left = len
                while (left > 0) {
                    val n = minOf(left, limit)
                    out.write(b, pos, n)
                    pos += n
                    left -= n
                }
            }
            override fun close() {
                try { super.close() } finally { runCatching { rf.close() } }
            }
        }
    }

    /**
     * How much data one SFTP WRITE can carry.
     *
     * ★ We must chunk ourselves: SSHJ's `RemoteFileOutputStream.write(buf,off,len)`
     * stuffs the whole `len` into **one** SFTP WRITE packet (unlike reads, which
     * naturally short-read), while OpenSSH `sftp-server`'s `SFTP_MAX_MSG_LENGTH`
     * is 256 KB — exceeding it doesn't return an error code, it just does
     * `error("bad message") + exit(11)` — the subsystem process is gone, and
     * the next read on the client reports `EOF while reading packet`, which
     * looks exactly like a disconnect. After `CopyEngine.pipe`'s buffer was
     * raised from 64 KB to 1 MB in 0.21.1 (to speed up SMB), any file larger
     * than 1 MB copied to SFTP would reliably fail; small files were fine.
     *
     * The chosen value mirrors SSHJ's official uploader (`SFTPFileTransfer.Uploader`):
     * channel-negotiated remote max packet size minus the SFTP request header
     * overhead; OpenSSH is usually 32 KB. Upper and lower bounds guard against
     * servers reporting nonsense values.
     */
    private fun writeChunk(c: SFTPClient, rf: net.schmizz.sshj.sftp.RemoteFile): Int = runCatching {
        (c.sftpEngine.subsystem.remoteMaxPacketSize - rf.outgoingPacketOverhead)
            .coerceIn(8 * 1024, 128 * 1024)
    }.getOrDefault(FALLBACK_WRITE_CHUNK)

    override fun mkdir(parent: XFile, name: String): XFile {
        val path = join(parent.path, name)
        retry { it.mkdir(serverPath(path)) }
        return XFile(scheme, path, isDir = true)
    }

    override fun delete(file: XFile) {
        if (file.isDir) {
            for (child in list(file)) delete(child)
            retry { it.rmdir(serverPath(file.path)) }
        } else {
            retry { it.rm(serverPath(file.path)) }
        }
    }

    override fun rename(file: XFile, newName: String): XFile {
        val to = join(file.parentPath, newName)
        retry { it.rename(serverPath(file.path), serverPath(to)) }
        return file.copy(path = to)
    }

    override fun exists(file: XFile): Boolean =
        runCatching { cli().statExistence(serverPath(file.path)) != null }.getOrDefault(false)

    override fun setModifiedTime(file: XFile, time: Long): Boolean = runCatching {
        val sec = time / 1000
        // SFTPv3's atime/mtime come as a pair, and the protocol has no notion of
        // "change mtime only"; with no better source for atime, just move it
        // along with mtime
        retry { it.setattr(serverPath(file.path), net.schmizz.sshj.sftp.FileAttributes.Builder().withAtimeMtime(sec, sec).build()) }
        true
    }.getOrDefault(false)

    fun disconnect() {
        runCatching { client?.close() }
        runCatching { ssh?.disconnect() }
        client = null
        ssh = null
    }

    private fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    companion object {
        const val SCHEME = "sftp"

        /** Conservative chunk size when the negotiated value is unavailable (OpenSSH's channel packet limit is 32 KB). */
        private const val FALLBACK_WRITE_CHUNK = 32 * 1024 - 1024

        init {
            ensureFullBouncyCastle()
        }

        /**
         * The "BC" bundled with Android is a stripped-down build (missing X25519/EdDSA
         * etc.), so once SSHJ detects it, it stops registering the full BouncyCastle
         * shipped in our dependencies, and the curve25519-sha256 handshake fails with
         * "no such algorithm x25519". Here we evict the fake system BC and replace it
         * with the full version (append-registered, not stealing the preferred-provider
         * slot for TLS and other algorithms).
         */
        private fun ensureFullBouncyCastle() {
            runCatching {
                val full = org.bouncycastle.jce.provider.BouncyCastleProvider()
                val cur = java.security.Security.getProvider("BC")
                if (cur == null || cur.javaClass !== full.javaClass) {
                    java.security.Security.removeProvider("BC")
                    java.security.Security.addProvider(full)
                }
            }
        }
    }
}
