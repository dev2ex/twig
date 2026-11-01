package com.twig.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalCommandsTest {
    private fun command(text: String, dir: String = "", target: String = RemoteCmd.TARGET_LOCAL) =
        RemoteCmd("", dir, text, false, label = "Test", target = target)

    @Test
    fun `execution identity survives both persistence paths and old records remain SSH`() {
        for (target in listOf(RemoteCmd.TARGET_LOCAL, RemoteCmd.TARGET_ROOT, RemoteCmd.TARGET_SSH,
            RemoteCmd.TARGET_SHIZUKU, RemoteCmd.TARGET_SHIZUKU_ROOT)) {
            val cmd = command("pwd", target = target)
            assertEquals(cmd, RemoteCmd.fromJson(cmd.toJson()))
            assertEquals(cmd, RemoteCmd.from(cmd.putInto(Intent())))
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            assertEquals(cmd, RemoteCmd.fromShortcut(ctx, cmd.launchIntent(ctx)))
        }
        val old = JSONObject().put("conn", "server").put("command", "pwd")
        assertEquals(RemoteCmd.TARGET_SSH, RemoteCmd.fromJson(old).target)
    }

    @Test
    fun `local command runs in quoted directory and captures status and both streams`() {
        val dir = Files.createTempDirectory("twig command's ").toFile()
        try {
            val result = LocalCommands.execute(command("pwd; printf problem >&2; exit 7", dir.path), shell = "/bin/sh")
            assertEquals(7, result.code)
            assertEquals(dir.canonicalPath, result.stdout.trim())
            assertEquals("problem", result.stderr)
        } finally { dir.deleteRecursively() }
    }

    @Test(timeout = 10000)
    fun `blank terminal command opens interactive shell in the saved directory`() {
        val dir = Files.createTempDirectory("twig terminal's ").toFile()
        try {
            for (text in listOf("", "  ")) {
                val script = LocalCommands.line(command(text, dir.path), terminal = true)
                    .replace("/system/bin/sh", "/bin/sh")
                val process = ProcessBuilder("/bin/sh", "-c", script).start()
                process.outputStream.bufferedWriter().use { it.write("pwd\nexit\n") }
                val output = process.inputStream.bufferedReader().readText()
                val errors = process.errorStream.bufferedReader().readText()
                assertEquals(errors, 0, process.waitFor())
                assertEquals(dir.canonicalPath, output.trim())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `failed working directory prevents user command`() {
        val result = LocalCommands.execute(command("printf SHOULD_NOT_RUN", "/missing/twig/path"), shell = "/bin/sh")
        assertFalse(result.ok)
        assertFalse(result.stdout.contains("SHOULD_NOT_RUN"))
    }

    @Test(timeout = 10000)
    fun `large stdout and stderr are drained without deadlock and bounded`() {
        val cmd = command("i=0; while [ \$i -lt 12000 ]; do printf 'stdout line\\n'; printf 'stderr line\\n' >&2; i=\$((i+1)); done")
        val result = LocalCommands.execute(cmd, shell = "/bin/sh")
        assertTrue(result.ok)
        assertEquals(16000, result.stdout.length)
        assertEquals(16000, result.stderr.length)
    }

    @Test
    fun `denied root command never falls back to local execution`() {
        val result = LocalCommands.execute(command("printf SHOULD_NOT_RUN", target = RemoteCmd.TARGET_ROOT),
            shell = "/bin/sh", su = "/bin/false")
        assertFalse(result.ok)
        assertEquals("", result.stdout)
    }

    @Test
    fun `root uid guard rejects non-root even if launcher succeeds`() {
        val script = LocalCommands.line(command("printf SHOULD_NOT_RUN", target = RemoteCmd.TARGET_ROOT))
            .replace("\$(id -u)", "2000")
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        assertEquals("", process.inputStream.bufferedReader().readText())
        assertEquals(1, process.waitFor())
    }
}
