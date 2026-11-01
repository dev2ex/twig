package com.twig.app

import com.twig.fs.network.SftpFileSystem

/** Local command execution uses its own process, never the file-access root shell. */
object LocalCommands {
    /** Verify the requested uid before executing user text; root must never degrade. */
    fun line(cmd: RemoteCmd, terminal: Boolean = false): String {
        require(cmd.isLocal)
        val expectedUid = when (cmd.target) {
            RemoteCmd.TARGET_ROOT, RemoteCmd.TARGET_SHIZUKU_ROOT -> 0
            RemoteCmd.TARGET_SHIZUKU -> 2000
            else -> null
        }
        val guard = if (expectedUid != null) "[ \"\$(id -u)\" = $expectedUid ] || exit 1\n" else ""
        // A terminal-only shortcut still needs a nonempty shell group after cd.
        val command = cmd.command.ifBlank { ":" }
        val body = if (cmd.workdir.isEmpty()) command
            else "cd " + RemoteCmd.sq(cmd.workdir) + " && {\n" + command + "\n}"
        return guard + body + if (terminal) "\nexec /system/bin/sh -i" else "\n"
    }

    /** Drains both pipes concurrently, retaining only a bounded output tail. */
    fun execute(cmd: RemoteCmd, shell: String = PrivShell.SHELL, su: String? = PrivShell.suPath()): SftpFileSystem.ExecResult {
        val script = line(cmd)
        val argv = if (cmd.target == RemoteCmd.TARGET_ROOT) {
            requireNotNull(su)
            listOf(su, "-c", script)
        } else listOf(shell, "-c", script)
        val process = if (cmd.isShizuku) Privileged.startShizukuCommand(cmd)
            else com.twig.fs.local.priv.JvmPrivilegedProcess(ProcessBuilder(argv).start())
        var out: Result<String>? = null
        var err: Result<String>? = null
        fun drain(stream: java.io.InputStream, done: (Result<String>) -> Unit) = Thread {
            done(runCatching {
                val tail = StringBuilder()
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        tail.append(buf, 0, n)
                        if (tail.length > 16_000) tail.delete(0, tail.length - 16_000)
                    }
                }
                tail.toString()
            })
        }.apply { start() }
        try {
            process.stdin.close()
            val stdout = drain(process.stdout) { out = it }
            val stderr = drain(process.stderr) { err = it }
            val code = process.waitFor()
            stdout.join()
            stderr.join()
            return SftpFileSystem.ExecResult(code, out!!.getOrThrow(), err!!.getOrThrow())
        } finally {
            process.destroy()
        }
    }
}
