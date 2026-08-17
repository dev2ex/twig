package com.twig.fs.local.priv

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * One privileged OS process — a root shell spawned through `su`, or a shell-uid
 * process handed over by Shizuku.
 *
 * The two sources look nothing alike from the outside (one is a setuid binary the
 * ROM provides, the other a binder call into a service the user started over adb),
 * but what they hand back is identical: a process with stdin/stdout/stderr running
 * a command line. Making that the *only* contract is what lets a single
 * [PrivilegedShell] drive both without ever knowing which one is behind it.
 */
interface PrivilegedProcess : Closeable {
    val stdout: InputStream
    val stderr: InputStream
    val stdin: OutputStream

    /** Blocks until the process exits and returns its status. */
    fun waitFor(): Int

    /** Kills the process; must be safe to call more than once. */
    fun destroy()

    override fun close() = destroy()
}

/**
 * Starts a privileged process.
 *
 * [cmd] null means "interactive shell reading commands from stdin" — that is the
 * long-lived session [PrivilegedShell] keeps around. A non-null [cmd] means a
 * one-shot process, used whenever the payload is binary (file contents) and must
 * not be interleaved with the session's command framing.
 */
fun interface PrivilegedLauncher {
    fun start(cmd: String?): PrivilegedProcess
}

/** Wraps a plain [java.lang.Process]; used by [SuLauncher] and by tests. */
class JvmPrivilegedProcess(private val proc: Process) : PrivilegedProcess {
    override val stdout: InputStream get() = proc.inputStream
    override val stderr: InputStream get() = proc.errorStream
    override val stdin: OutputStream get() = proc.outputStream
    override fun waitFor(): Int = proc.waitFor()
    override fun destroy() {
        runCatching { proc.destroy() }
    }
}

/**
 * Root through `su`. Zero dependencies — the whole mechanism is that Magisk's
 * daemon intercepts the exec, prompts the user, and hands back a uid 0 shell.
 *
 * There is deliberately no "is su present" check here beyond trying to run it:
 * `which su` is unreliable (PATH often lacks it) and some ROMs ship a decoy `su`
 * that exits cleanly without granting anything. The only trustworthy signal is
 * reading `uid=0` back out of the shell, which [PrivilegedShell.connect] does.
 */
class SuLauncher(private val suPath: String = "su") : PrivilegedLauncher {
    override fun start(cmd: String?): PrivilegedProcess {
        val argv = if (cmd == null) arrayOf(suPath) else arrayOf(suPath, "-c", cmd)
        return JvmPrivilegedProcess(ProcessBuilder(*argv).start())
    }
}
