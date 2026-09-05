package com.twig.app.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Renders the share page into an HTML file for manual/headless-browser inspection.
 * **Skipped entirely when `TWIG_DUMP_DIR` is unset**, so it does nothing during a normal
 * test run.
 *
 * Why it is kept: the page is a visual artifact, and assertions cannot catch things like
 * "the icon did not render" or "the button got squeezed to 1px by padding" (both actually
 * happened on 2026-08-10). Run this when changing styles:
 *
 * ```
 * TWIG_DUMP_DIR=/tmp/page ./gradlew :app:testReleaseUnitTest --tests "*DumpPageTest"
 * # then open file:///tmp/page/writable.html with Playwright to screenshot / inspect computed styles
 * ```
 *
 * Note: the Chinese file/directory names used below (假期照片, 项目备份, 海边日落.jpg,
 * etc.) are deliberate fixture data — the whole point of this dump is to visually confirm
 * that real-world CJK file names render correctly on the page, so they are left as is.
 */
@RunWith(RobolectricTestRunner::class)
class DumpPageTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** A sparse file: only sets the length without actually writing content, otherwise a fake 700MB video would blow up the test JVM. */
    private fun sized(dir: File, name: String, len: Long) {
        java.io.RandomAccessFile(File(dir, name), "rw").use { it.setLength(len) }
    }

    @Test
    fun dump() {
        val out = File(System.getenv("TWIG_DUMP_DIR") ?: return)
        val dir = File(System.getProperty("java.io.tmpdir"), "twig-dump-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "假期照片").mkdirs()
        File(dir, "项目备份").mkdirs()
        File(dir, "Download").mkdirs()
        sized(dir, "海边日落.jpg", 2_840_000)
        sized(dir, "会议纪要 2026-08.pdf", 184_320)
        sized(dir, "Twig_0.97.0_release.apk", 9_292_039)
        sized(dir, "旅行 vlog 剪辑版.mp4", 734_003_200)
        sized(dir, "Bohemian Rhapsody.flac", 41_943_040)
        sized(dir, "素材归档.zip", 157_286_400)
        sized(dir, "ShareHandler.kt", 18_432)
        sized(dir, "readme.txt", 1_204)
        FsRegistry.register(LocalFileSystem())

        for (ro in listOf(true, false)) {
            val port = ServerSocket(0).use { it.localPort }
            val cfg = ShareConfig(
                scope = ShareScope.Dir("file", dir.path, "share"),
                readOnly = ro, port = port, deviceName = "Pixel 8 Pro",
            )
            val server = HttpServer(port, null, ShareHandler(app, cfg, ShareRoot(app, cfg.scope)))
            server.start()
            Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 5000
                sock.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                sock.getOutputStream().flush()
                val input = BufferedInputStream(sock.getInputStream())
                while (true) {
                    val h = HttpServer.readLine(input) ?: break
                    if (h.isEmpty()) break
                }
                val buf = ByteArrayOutputStream()
                input.copyTo(buf)
                File(out, if (ro) "readonly.html" else "writable.html")
                    .writeBytes(buf.toByteArray())
            }
            server.stop()
        }
        dir.deleteRecursively()
    }
}
