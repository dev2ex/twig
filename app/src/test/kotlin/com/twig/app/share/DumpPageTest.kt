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
 * 把共享页渲染成 HTML 文件,供人工/无头浏览器查看。**不设 `TWIG_DUMP_DIR` 时直接跳过**,
 * 所以平时跑测试它什么都不做。
 *
 * 为什么留着:页面是视觉产物,断言测不出"图标没渲染出来""按钮被内边距挤成 1px"这类问题
 * (2026-08-10 两个都真的发生了)。改样式时这么走一遍:
 *
 * ```
 * TWIG_DUMP_DIR=/tmp/page ./gradlew :app:testReleaseUnitTest --tests "*DumpPageTest"
 * # 然后用 Playwright 开 file:///tmp/page/writable.html 截图 / 查计算样式
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class DumpPageTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** 稀疏文件:只设长度不真写内容,不然 700MB 的假视频会把测试 JVM 撑爆。 */
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
