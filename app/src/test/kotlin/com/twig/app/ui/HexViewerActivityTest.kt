package com.twig.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 十六进制查看器能不能真的开起来、开起来有没有行。
 *
 * 为什么值得单测:这个界面是"读文件 → 算版式 → 铺行"三段异步接力,任何一段没接上,
 * 表现都是同一个——**白屏**,而白屏在真机上看不出是哪一段断的。尤其
 * "文件读完时视图还没量到宽"这种顺序,手上的机器不一定复现得出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HexViewerActivityTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `打开后列出行_并且每行字节数落在合理范围`() {
        val data = ByteArray(4096) { it.toByte() }
        val rv = launch(data)
        assertTrue("列表不该是空的", rv.adapter!!.itemCount > 0)
        // 行数 = 总字节 / 每行字节数,每行至少 4 个、至多 64 个
        val bpr = data.size / rv.adapter!!.itemCount
        assertTrue("每行 $bpr 字节,不在 4..64 内", bpr in 4..64)
    }

    @Test
    fun `文件读完时还没量到宽也要铺出行`() {
        // 先把源准备好再布局,复现"读得比第一次布局快"的顺序:布局回调必须接住它
        val data = ByteArray(1024) { it.toByte() }
        val rv = launch(data, layoutFirst = false)
        assertTrue("布局晚到时行数不能停在 0", rv.adapter!!.itemCount > 0)
    }

    @Test
    fun `空文件不崩_只是没有行`() {
        val rv = launch(ByteArray(0))
        assertEquals(0, rv.adapter!!.itemCount)
    }

    /** 起 Activity 并把主线程的消息推完;[layoutFirst] 控制"先布局还是先读完文件"。 */
    private fun launch(data: ByteArray, layoutFirst: Boolean = true): RecyclerView {
        FsRegistry.register(ByteFs(data))
        val intent = Intent(ctx, HexViewerActivity::class.java)
            .putExtra("scheme", "bytes")
            .putExtra("path", "/f.bin")
            .putExtra("name", "f.bin")
            .putExtra("size", data.size.toLong())
        val activity = Robolectric.buildActivity(HexViewerActivity::class.java, intent).setup().get()
        val rv = activity.findViewById<RecyclerView>(R.id.list)
        if (layoutFirst) layout(rv)
        idle()
        if (!layoutFirst) {
            layout(rv)
            idle()
        }
        return rv
    }

    private fun layout(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1080, 1920)
    }

    /** 读文件那一段在 Dispatchers.IO 上,主线程 idle 一轮可能还没轮到,给几次机会。 */
    private fun idle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class ByteFs(val data: ByteArray) : FileSystem {
        override val scheme = "bytes"
        override val displayName = "bytes"
        override fun root() = XFile(scheme, "/", isDir = true)
        override fun resolve(path: String) = XFile(scheme, path, isDir = false, size = data.size.toLong())
        override fun list(dir: XFile): List<XFile> = emptyList()
        override fun openInput(file: XFile): InputStream = ByteArrayInputStream(data)
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("ro")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("ro")
        override fun delete(file: XFile) = throw FsException("ro")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("ro")
        override fun exists(file: XFile) = true
        override fun randomAccessEfficient() = true
        override fun openRandom(file: XFile) = object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= data.size) return -1
                val n = minOf(length.toLong(), data.size - position).toInt()
                System.arraycopy(data, position.toInt(), buffer, offset, n)
                return n
            }

            override fun length() = data.size.toLong()
            override fun close() = Unit
        }
    }
}
