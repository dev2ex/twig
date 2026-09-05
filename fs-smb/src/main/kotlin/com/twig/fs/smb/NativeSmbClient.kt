package com.twig.fs.smb

import com.twig.core.FsException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * JNI bindings for libsmb2. One instance = one smb2_context (one connection to a share).
 *
 * smb2_context is not thread-safe and requires single-threaded access — **every native
 * call runs on a dedicated single-threaded executor**, fully preventing multi-threaded
 * access (browsing / media pre-read / FUSE callbacks) into libsmb2 and the resulting
 * heap corruption. When POLLHUP on screen-off drops the connection, a failed operation
 * auto-reconnects with the remembered config and retries.
 *
 * **★ The most fundamental real-world pitfall (2026-07-21, the real culprit behind the
 * "frequent crashes" that appeared once thumbnails shipped)**: the member function that
 * dispatched to the dedicated thread was originally named `run`, identical in name and
 * shape to Kotlin stdlib's `run { }` — inside the anonymous InputStream/OutputStream
 * inner classes of openInput/openOutput, `run { }` would resolve to the stdlib version
 * (running synchronously on the calling thread), not this member function (verified by
 * a dedicated unit test: in a same-shape anonymous inner class, a member with the same
 * name was simply never invoked). As a result, read()/write() had never been truly
 * serialized to the smb-io thread since this class was first written — when thumbnails
 * spun up two background threads reading different network files concurrently, or
 * thumbnail reading raced with media random-read/copy, that was genuine multi-threaded
 * concurrent access into libsmb2, directly violating the "single-threaded access"
 * precondition above, and led to heap-corruption crashes. **The member function has
 * now been renamed [exec] to completely remove the name ambiguity** — when naming
 * future functions, avoid run/let/also/apply/with/use, the stdlib scope-function names,
 * to prevent a repeat of this trap.
 *
 * The stream objects returned by openInput/openOutput snapshot the handle at open time
 * (the "owner"): during a long-running read such as thumbnail loading, if a concurrent
 * call triggers reconnect() due to a dropped connection (destroying the old context,
 * swapping in a new handle), the fh held inside the stream is only valid in the old
 * context — using the new handle with the old fh to call into native is a dangling
 * pointer, and is one of the root causes of the sporadic native crashes seen in
 * thumbnail loading and large file transfers. When the owner differs from the current
 * handle, we simply declare the operation failed / skip the native close, and never
 * reuse the old fh across contexts.
 *
 * **Real-world pitfall (2026-07-21)**: libsmb2 does not protect against handle=0
 * (disconnected / never connected) — passing 0 directly into the native layer will
 * null-pointer-dereference inside functions like nativeOpenFile (the tombstone shows
 * `Java_com_twig_fs_smb_NativeSmbClient_nativeOpenFile`, fault addr 0x14, i.e. taking a
 * field offset from a NULL context). The actual trigger path: one [reconnect] fails for
 * some reason (weak network / server briefly unreachable), leaving handle stuck at 0 —
 * but the corresponding scheme is still "registered" in `FsRegistry`, so every later
 * operation from the UI / thumbnail loading lands on this same client again, and the
 * original code did not check handle before the call but handed it straight to native,
 * crashing deterministically; after the crash and a relaunch the same crash repeats
 * immediately (handle is still 0). Now every native call entry point first goes
 * through [ensureConnected] — when handle=0, attempt a [reconnect] with the remembered
 * config first; only if that fails do we throw [FsException]. We never pass 0 into
 * the native layer.
 */
class NativeSmbClient : AutoCloseable {

    @Volatile private var handle: Long = 0L
    @Volatile private var config: SmbConfig? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "smb-io").apply { isDaemon = true }
    }

    val isConnected: Boolean get() = handle != 0L

    data class Entry(val name: String, val isDir: Boolean, val size: Long, val mtimeSec: Long)

    /** One entry of the server's share list; [type] is the raw srvsvc SHARE_TYPE bitmask. */
    data class Share(val name: String, val type: Int) {
        /** A regular disk share (the low 2 bits say disktree / printq / device / IPC). */
        val isDisk: Boolean get() = (type and 3) == 0
        /** Administrative shares (C$, ADMIN$, IPC$) carry the hidden bit. */
        val isHidden: Boolean get() = (type.toLong() and 0x80000000L) != 0L || name.endsWith('$')
    }

    /** Runs a native call on the dedicated thread and waits for the result (exceptions are rethrown).
     * **The name deliberately avoids `run`**: this function was originally named `run`,
     * and `run { }` inside the anonymous InputStream/OutputStream inner classes of
     * openInput/openOutput was resolved by Kotlin to stdlib's `kotlin.run` (running
     * synchronously on the calling thread), not to this member function — verified by a
     * unit test (in a same-shape anonymous inner class, the same-named member was simply
     * never invoked, always landing on the calling thread rather than the io thread).
     * The consequence was that read/write completely bypassed the single-thread
     * serialization, causing genuine concurrent access to the non-thread-safe
     * smb2_context — this is the **real** root cause of the sporadic native crashes in
     * SMB thumbnails and large file transfers, more fundamental than the handle=0 pitfall.
     * The rename removes the ambiguity entirely. */
    private fun <T> exec(block: () -> T): T =
        try {
            io.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: FsException("SMB operation failed", e)
        }

    /** When disconnected (handle=0), try reconnecting once with the remembered config first;
     * only throw if that still fails — never pass 0 into the native layer (see "real-world
     * pitfall" in the class comment). When handle is already valid, zero overhead and just
     * passes through. */
    private fun ensureConnected() {
        if (handle == 0L && !reconnect()) throw FsException("SMB connection is down")
    }

    private fun errText(): String = if (handle == 0L) "SMB connection is down" else nativeGetLastError(handle)

    /**
     * Whether the last native error was "the server replied but rejected us" (permission
     * / sharing-violation and the like), as opposed to a connection drop. libsmb2 only
     * embeds NT status in the error string — there is no independent error code to
     * query, so we can only match strings. If we don't recognize it, we treat it as a
     * drop (falling back to the original reconnect-and-retry behavior); one extra
     * reconnect is harmless.
     */
    private fun deniedByServer(): Boolean {
        if (handle == 0L) return false
        val e = errText()
        return e.contains("ACCESS_DENIED") || e.contains("SHARING_VIOLATION") ||
            e.contains("MEDIA_WRITE_PROTECTED")
    }

    /** Drops the dead connection and reconnects with the config (call only on the io thread); returns success. */
    private fun reconnect(): Boolean {
        val c = config ?: return false
        val old = handle; handle = 0L
        if (old != 0L) runCatching { nativeDisconnect(old) }
        val h = nativeConnect(c.host, c.share, c.user, c.password, c.domain)
        handle = h
        return h != 0L
    }

    fun connect(c: SmbConfig) = exec {
        config = c
        val h = nativeConnect(c.host, c.share, c.user, c.password, c.domain)
        if (h == 0L) throw FsException("SMB connection failed: ${c.host}/${c.share}")
        handle = h
    }

    /** Negotiated SMB dialect code (e.g. 0x0311 = 3.1.1); returns 0 if not connected. */
    fun dialect(): Int = exec { if (handle == 0L) 0 else nativeDialect(handle) }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { io.submit { nativeDisconnect(h) }.get() }
        io.shutdownNow()
    }

    /**
     * Hard close: only destroys the context (no logoff, no per-fh smb2_close).
     * smb2_close / smb2_disconnect_share run wait_for_reply on the socket during teardown,
     * and have previously crashed the heap here by freeing a stale reply pdu; the dedicated
     * media-playback connection uses this teardown to route around that entirely.
     */
    fun closeHard() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { io.submit { nativeDestroy(h) }.get() }
        io.shutdownNow()
    }

    fun list(path: String): List<Entry> = exec {
        ensureConnected()
        var raw = nativeListDirectory(handle, path)
        if (raw == null && reconnect()) raw = nativeListDirectory(handle, path)
        val lines = raw ?: throw FsException("List failed: ${errText()}")
        lines.map { line ->
            val p = line.split('\t')
            Entry(
                name = p[0],
                isDir = p.getOrNull(1) == "1",
                size = p.getOrNull(2)?.toLongOrNull() ?: 0L,
                mtimeSec = p.getOrNull(3)?.toLongOrNull() ?: 0L,
            )
        }
    }

    /**
     * Enumerates the server's shares over srvsvc (NetrShareEnum).
     *
     * ★ Only works on a context connected to **IPC$** — that is why the "no share name"
     * mode of [SmbFileSystem] keeps a client bound to IPC$ purely for this call, and
     * opens a separate client per share for the actual file operations (one libsmb2
     * context = one share, there is no way around that).
     */
    fun listShares(): List<Share> = exec {
        ensureConnected()
        var raw = nativeListShares(handle)
        if (raw == null && reconnect()) raw = nativeListShares(handle)
        val lines = raw ?: throw FsException("Share enumeration failed: ${errText()}")
        lines.mapNotNull { line ->
            val p = line.split('\t')
            val name = p.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // The type is an unsigned 32-bit mask whose top bit (hidden) is set on admin
            // shares, so it must be parsed as Long before being narrowed.
            Share(name, (p.getOrNull(1)?.toLongOrNull() ?: 0L).toInt())
        }
    }

    fun openInput(path: String): InputStream {
        val fh = openReadHandle(path)
        if (fh == 0L) throw FsException("Open failed ($path): ${errText()}")
        // fh is only valid in the context it was opened in (the "owner"). A long-running
        // read such as thumbnail loading spans a long time, during which another
        // concurrent call triggered by a drop (POLLHUP) may have invoked reconnect(),
        // destroying the native context owned by `owner` — using the new handle with
        // the old fh to call into native touches a dangling pointer, and is one of the
        // root causes of the sporadic native crashes in SMB thumbnail loading / large
        // file reads. Here we use the owner snapshot to detect mismatch and directly
        // declare the read failed (handing the whole operation back to the caller to
        // retry), without overstepping into native calls.
        val owner = handle
        return object : InputStream() {
            private var closed = false
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int = exec {
                if (handle != owner) throw FsException("Connection was re-established; read aborted")
                val n = nativeReadFile(owner, fh, b, off, len)
                if (n < 0) throw FsException("Read failed: ${errText()}")
                if (n == 0) -1 else n
            }
            override fun close() {
                if (!closed) {
                    closed = true
                    if (handle == owner) closeHandle(fh) // The context has already been swapped; the fh was invalidated along with the old context, so don't call native close
                }
            }
        }
    }

    fun openOutput(path: String): OutputStream {
        val fh = exec {
            ensureConnected()
            var f = nativeOpenWrite(handle, path)
            // Reconnecting won't help when the server explicitly rejects us (e.g.
            // insufficient permissions); it would just disconnect once for nothing and
            // force every subsequent operation to redo the handshake. Only a suspected
            // drop is worth a reconnect-and-retry.
            if (f == 0L && !deniedByServer() && reconnect()) f = nativeOpenWrite(handle, path)
            f
        }
        if (fh == 0L) throw FsException("Open for write failed ($path): ${errText()}")
        val owner = handle // Same as openInput: prevent using an old fh with the new context across a reconnect
        return object : OutputStream() {
            private var closed = false
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var w = 0
                while (w < len) {
                    if (handle != owner) throw FsException("Connection was re-established; write aborted")
                    val n = exec { nativeWriteFile(owner, fh, b, off + w, len - w) }
                    if (n <= 0) throw FsException("Write failed: ${errText()}")
                    w += n
                }
            }
            override fun close() {
                if (!closed) {
                    closed = true
                    if (handle == owner) closeHandle(fh)
                }
            }
        }
    }

    // Positioned read (for media-player random seek):
    fun openReadHandle(path: String): Long = exec {
        ensureConnected()
        var fh = nativeOpenFile(handle, path)
        if (fh == 0L && reconnect()) fh = nativeOpenFile(handle, path)
        fh
    }

    /**
     * Positioned read. Native-layer contract: n<0 = error, n==0 = true EOF, n>0 = bytes read.
     *
     * **Failures MUST throw an exception, never return -1**: in the caller's
     * ([com.twig.core.RandomSource]) contract, -1 means EOF, so the original behavior of
     * "also return -1 on drop" made the player / thumbnail loader / archive parser
     * interpret "connection lost" as "file ended" — videos ended early, thumbnails went
     * black, archives reported "corrupt entry" — none pointing at the real cause. This
     * matches the `handle != owner` failure path used in [openInput].
     *
     * ★ This entry point **deliberately does not call [ensureConnected]** (other entry
     * points do): [fh] was opened on the current context, so reconnect would destroy
     * the old context and yield a new handle, invalidating the old fh — passing it
     * back into native would be a dangling pointer (`nativePread` only checks
     * `!handle || !fhHandle`, not whether the two belong together), which is exactly
     * the situation [openInput]/[openOutput] guard against with the owner snapshot. On
     * drop we just error out and let the caller re-open the entire [openRandom].
     */
    fun pread(fh: Long, offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int = exec {
        if (handle == 0L) throw FsException("SMB connection is down")
        val n = nativePread(handle, fh, offset, buf, bufOffset, length)
        if (n < 0) throw FsException("SMB random read failed: ${errText()}")
        n
    }

    fun closeHandle(fh: Long) = exec { if (fh != 0L && handle != 0L) nativeCloseFile(handle, fh) }

    fun mkdir(path: String) = call { nativeMkdir(handle, path) }
    fun delete(path: String, isDir: Boolean) = call { nativeDelete(handle, path, isDir) }
    fun rename(from: String, to: String) = call { nativeRename(handle, from, to) }

    private inline fun call(crossinline op: () -> Int) = exec {
        ensureConnected()
        if (op() < 0) {
            if (!reconnect() || op() < 0) throw FsException(errText())
        }
    }

    // ── JNI ──
    private external fun nativeConnect(host: String, share: String, user: String, password: String, domain: String): Long
    private external fun nativeDialect(handle: Long): Int
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeListDirectory(handle: Long, path: String): Array<String>?
    private external fun nativeListShares(handle: Long): Array<String>?
    private external fun nativeOpenFile(handle: Long, path: String): Long
    private external fun nativeGetFileSize(handle: Long, fh: Long): Long
    private external fun nativeReadFile(handle: Long, fh: Long, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeOpenWrite(handle: Long, path: String): Long
    private external fun nativeWriteFile(handle: Long, fh: Long, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeCloseFile(handle: Long, fh: Long)
    private external fun nativePread(handle: Long, fh: Long, offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int
    private external fun nativeMkdir(handle: Long, path: String): Int
    private external fun nativeDelete(handle: Long, path: String, isDir: Boolean): Int
    private external fun nativeRename(handle: Long, from: String, to: String): Int
    private external fun nativeGetLastError(handle: Long): String

    companion object {
        init { System.loadLibrary("samba_jni") }
    }
}
