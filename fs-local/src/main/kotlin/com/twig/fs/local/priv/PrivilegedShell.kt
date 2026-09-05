package com.twig.fs.local.priv

import java.io.BufferedReader
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A long-lived privileged shell plus the framing needed to talk to it.
 *
 * ★ Why a *session* and not one `su -c` per operation: every exec goes through the
 * authorization check, and some Magisk builds re-prompt on it. Listing a directory
 * would then cost a fork, a policy lookup and occasionally a dialog. So short
 * commands (stat, mkdir, rm) all ride one shell that stays open.
 *
 * ★ Why binary payloads do *not* ride it: a persistent shell never sends EOF, so
 * the only way to know a command finished is an end marker echoed after it. That
 * works for line-oriented output and is hopeless for file contents, which may
 * contain the marker, may contain no newlines at all, and must not be decoded as
 * text. [openInput]/[openOutput] therefore spawn a one-shot process whose EOF *is*
 * the end of the data.
 */
class PrivilegedShell(
    private val launcher: PrivilegedLauncher,
    /** Timeout for one ordinary command. Kills the session on expiry — see [exec]. */
    private val timeoutMs: Long = 20_000,
) : Closeable {

    /** Exit status plus stdout lines of one command. stderr is in [Result.err]. */
    data class Result(val code: Int, val lines: List<String>, val err: String = "") {
        val ok: Boolean get() = code == 0
        val text: String get() = lines.joinToString("\n")
    }

    private val lock = Any()

    /**
     * ★ The stderr buffer **cannot** be guarded by [lock]. The drain thread takes
     * the lock for every line it reads, while [exec] is **holding [lock]
     * waiting for the command to finish**. If they shared the same lock: drain
     * thread stuck on the lock -> nobody reads stderr -> the pipe (64 KB) fills
     * -> command blocks writing to stderr -> never ends -> the end marker on
     * stdout is never seen, so we just wait for the timeout. Any command whose
     * error output exceeds 64 KB (`find /` full of Permission denied is enough)
     * is guaranteed to hit this.
     */
    private val errLock = Any()

    private val reads = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-priv-read").apply { isDaemon = true }
    }

    private var proc: PrivilegedProcess? = null
    private var sink: OutputStream? = null
    private var source: BufferedReader? = null
    private var errTail = ArrayDeque<String>()

    /** uid the session runs as once [connect] succeeded: 0 for root, 2000 for Shizuku. */
    @Volatile
    var uid: Int = -1
        private set

    val isRoot: Boolean get() = uid == 0

    /**
     * Why the last [connect] failed, in a form worth showing a user and logging.
     *
     * ★ This exists because the first version returned a bare `false` and dropped the
     * exception. "Could not start a privileged process" is unactionable: not rooted,
     * denied at the prompt, Shizuku service stopped, and a reflection call that no
     * longer resolves all look identical, and only one of them is the user's to fix.
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Opens the session and proves it is actually privileged.
     *
     * [grantTimeoutMs] is generous because the first command is what blocks while
     * the user stares at Magisk's prompt. ★ That prompt cannot be shown from the
     * background on Android 10+ (background activity starts are blocked, so Magisk
     * degrades to a notification the user may never see) — callers must only reach
     * here from a foreground, user-initiated action.
     */
    fun connect(grantTimeoutMs: Long = 60_000): Boolean = synchronized(lock) {
        if (uid >= 0 && alive()) return true
        shutdown()
        lastError = null
        val started = runCatching { launcher.start(null) }.getOrElse {
            lastError = "spawn failed: " + describe(it)
            log?.invoke("connect: $lastError")
            return false
        }
        proc = started
        sink = started.stdin
        source = BufferedReader(InputStreamReader(started.stdout), 1 shl 16)
        drainStderr(started)
        // Reading `id` back is the only honest proof: a decoy `su` can exit 0 and a
        // Shizuku service can be reachable yet unauthorized. Both fail this.
        val r = runCatching { run("id", grantTimeoutMs) }.getOrElse {
            lastError = "id failed: " + describe(it)
            log?.invoke("connect: $lastError")
            shutdown()
            return false
        }
        val got = UID.find(r.text)?.groupValues?.get(1)?.toIntOrNull()
        if (got == null) {
            // The process started but is not a usable shell. Its own words are the
            // only clue left, so carry them out rather than reducing this to `false`.
            lastError = "no uid in `id` output (exit=${r.code}, out=${r.text.take(200)}, err=${r.err.take(200)})"
            log?.invoke("connect: $lastError")
            shutdown()
            return false
        }
        uid = got
        log?.invoke("connect: ok, uid=$got")
        return true
    }

    /**
     * ★ Unwraps [java.lang.reflect.InvocationTargetException]: its own message is
     * always null, so a reflected call that throws would otherwise be reported as
     * literally "null" — the failure mode this whole field exists to prevent.
     */
    private fun describe(t: Throwable): String {
        val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        val chain = generateSequence(real) { it.cause }.take(3)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
        return chain
    }

    /** True while the session process is still usable. */
    fun alive(): Boolean = proc != null && sink != null && source != null

    /**
     * Runs one command, reconnecting once if the session died in the meantime.
     *
     * A dead session is routine, not exceptional: Magisk lets the user grant access
     * "once" or "for 10 minutes", and Shizuku goes away whenever its service is
     * stopped or the device reboots. Anything holding a [PrivilegedShell] must
     * survive that, so recovery lives here rather than at every call site.
     *
     * ★ [quiet] marks the calls where a non-zero exit is an *answer*, not a fault:
     * `[ -e path ]` returns 1 for "no", and `stat` returns 1 when one entry of a
     * batch is a broken symlink (`/` on Android always has a few). Logging those
     * would put a line in logcat for every negative existence check — one per file
     * during a bulk copy — and bury the failures that actually mean something.
     */
    fun exec(cmd: String, timeout: Long = timeoutMs, quiet: Boolean = false): Result = synchronized(lock) {
        if (!alive() && !connect()) return Result(-1, emptyList(), "no privileged shell")
        return runCatching { run(cmd, timeout).also { r -> if (!r.ok && !quiet) logFail(cmd, r) } }.getOrElse {
            shutdown()
            if (!connect()) return Result(-1, emptyList(), it.message ?: "shell lost")
            runCatching { run(cmd, timeout) }
                .getOrElse { e -> Result(-1, emptyList(), e.message ?: "shell lost") }
        }
    }

    /**
     * Writes the command followed by a marker echo, then reads until that marker.
     *
     * The marker carries `$?` so the exit status arrives in the same stream as the
     * output — asking for it separately would race with anything else the caller
     * later writes into the shell.
     */
    private fun run(cmd: String, timeout: Long): Result {
        val out = sink ?: throw IllegalStateException("shell closed")
        val src = source ?: throw IllegalStateException("shell closed")
        val mark = "__twig_${MARKER_SEQ++}_${System.nanoTime()}__"
        synchronized(errLock) { errTail.clear() }
        out.write("$cmd\necho $mark $?\n".toByteArray())
        out.flush()
        val task = reads.submit<Result> {
            val lines = ArrayList<String>()
            while (true) {
                val line = src.readLine() ?: throw IllegalStateException("shell closed")
                if (line.startsWith(mark)) {
                    val code = line.substring(mark.length).trim().toIntOrNull() ?: -1
                    return@submit Result(code, lines, errText())
                }
                lines += line
            }
            @Suppress("UNREACHABLE_CODE") Result(-1, lines, errText())
        }
        return try {
            task.get(timeout, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // ★ The reader is parked mid-stream, so the session's framing is now
            // unrecoverable — whatever the stuck command eventually prints would be
            // read as the *next* command's output. Killing it is the only safe exit.
            task.cancel(true)
            shutdown()
            throw IllegalStateException("timed out: $cmd")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception ?: IllegalStateException(e.message ?: "shell failed"))
        }
    }

    /**
     * Reads what a command writes to stdout, as raw bytes.
     *
     * Runs outside the session (see the class doc). Closing the stream kills the
     * process, so abandoning a half-read file does not leave a `cat` running.
     */
    fun openInput(cmd: String): InputStream {
        val p = launcher.start(cmd)
        drainStderr(p, collect = false)
        return object : FilterInputStream(p.stdout) {
            override fun close() {
                runCatching { super.close() }
                p.destroy()
            }
        }
    }

    /**
     * Feeds a command's stdin. Closing the stream closes stdin (the command sees
     * EOF and finishes) and then waits for it, so the write is durable by the time
     * close() returns — callers treat close() as "the file is written".
     */
    fun openOutput(cmd: String): OutputStream {
        val p = launcher.start(cmd)
        drainStderr(p, collect = false)
        return object : FilterOutputStream(p.stdin) {
            // FilterOutputStream forwards this byte-by-byte by default; copying a
            // large file through that is unusably slow.
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                runCatching { super.close() }
                runCatching { p.waitFor() }
                p.destroy()
            }
        }
    }

    /**
     * stderr must be drained continuously: it is a pipe with a small kernel buffer,
     * and a command that fills it while nobody reads blocks forever — which for the
     * session would look exactly like a hung shell.
     *
     * [collect] is false for one-shot processes: their stderr still has to be read
     * for the reason above, but folding it into the session's tail would attribute
     * a `cat`'s complaint to whatever command runs next.
     */
    private fun drainStderr(p: PrivilegedProcess, collect: Boolean = true) {
        val t = Thread {
            runCatching {
                BufferedReader(InputStreamReader(p.stderr)).forEachLine { line ->
                    if (!collect) return@forEachLine
                    synchronized(errLock) {
                        errTail.addLast(line)
                        while (errTail.size > ERR_KEEP) errTail.removeFirst()
                    }
                }
            }
        }
        t.isDaemon = true
        t.name = "twig-priv-err"
        t.start()
    }

    private fun errText(): String = synchronized(errLock) { errTail.joinToString("\n") }

    private fun logFail(cmd: String, r: Result) {
        val hook = log ?: return
        hook("exec failed (exit=${r.code}): ${cmd.take(160)} | ${r.err.take(200)}")
    }

    private fun shutdown() {
        runCatching { sink?.close() }
        runCatching { source?.close() }
        runCatching { proc?.destroy() }
        proc = null; sink = null; source = null
        uid = -1
    }

    override fun close() = synchronized(lock) {
        shutdown()
        reads.shutdownNow()
        Unit
    }

    companion object {
        /**
         * Diagnostic output hook. The pure JVM module has no `android.util.Log`,
         * so `:app` installs it (same pattern as
         * [com.twig.fs.local.LocalFileSystem.changed]).
         *
         * Only failures are reported, not every command — on the privileged
         * path any one command may be invoked thousands of times (list
         * directory, check existence); logging all of them would flood logcat
         * and bury the failures that actually mean something.
         */
        @Volatile
        @JvmStatic
        var log: ((String) -> Unit)? = null

        private const val ERR_KEEP = 20
        private val UID = Regex("uid=(\\d+)")

        @Volatile
        private var MARKER_SEQ = 0

        /**
         * Quotes a path for the shell.
         *
         * Everything reaching the shell is a user-supplied filename, so this is the
         * only thing standing between a file called `; rm -rf /` and disaster.
         * Single quotes suppress every expansion the shell has; the only character
         * they cannot contain is `'` itself, hence the close-escape-reopen dance.
         */
        fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
