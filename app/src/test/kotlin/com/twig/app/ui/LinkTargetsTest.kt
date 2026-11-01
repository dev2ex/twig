package com.twig.app.ui

import android.app.Activity
import android.os.Looper
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class LinkTargetsTest {
    @Test fun `list directories and files and grid files show subdued target subtitles and clear on reuse`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = ContextThemeWrapper(host.get(), R.style.Theme_Twig)
            for ((directory, grid) in listOf(true to 0, false to 0, false to 2)) {
                val link = XFile("test-links", "/alias.txt", directory, size = 12,
                    isSymbolicLink = true, linkTarget = "../original/target")
                val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, gridMode = grid)
                adapter.submitList(listOf(
                    PaneViewModel.FileNode(link, 1, directory, false),
                    PaneViewModel.FileNode(link.copy(path = "/ordinary.txt", isSymbolicLink = false, linkTarget = null),
                        1, directory, false),
                ))
                val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
                adapter.bindViewHolder(holder, 0)
                val subtitle = holder.itemView.findViewById<TextView>(if (grid == 0) R.id.sub else R.id.link_target)
                assertEquals(View.VISIBLE, subtitle.visibility)
                assertEquals("→ ../original/target", subtitle.text.toString())
                assertEquals(ContextCompat.getColor(context, R.color.text_secondary), subtitle.currentTextColor)
                assertEquals(TextUtils.TruncateAt.MIDDLE, subtitle.ellipsize)
                adapter.bindViewHolder(holder, 1)
                assertEquals(View.GONE, subtitle.visibility)
                assertEquals("", subtitle.text.toString())
            }
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `unknown targets load off main once and never fill a recycled view`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val thread = AtomicReference<Thread>()
        val previous = FsRegistry.all().firstOrNull { it.scheme == "file" }
        FsRegistry.register(LocalFileSystem(readLink = {
            reads.incrementAndGet()
            thread.set(Thread.currentThread())
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "../remote-target"
        }))
        try {
            val first = TextView(host.get())
            val second = TextView(host.get())
            val link = XFile("file", "/privileged-link", false, isSymbolicLink = true)
            LinkTargets.bind(first, link)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            LinkTargets.bind(second, link)
            LinkTargets.bind(first, link.copy(isSymbolicLink = false))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (second.visibility != View.VISIBLE && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertNotSame(Looper.getMainLooper().thread, thread.get())
            assertEquals(1, reads.get())
            assertEquals("→ ../remote-target", second.text.toString())
            assertEquals(View.GONE, first.visibility)
            assertEquals("", first.text.toString())
            LinkTargets.bind(first, link)
            assertEquals("→ ../remote-target", first.text.toString())
            assertEquals(1, reads.get())
            LinkTargets.clear(first)
            assertEquals(View.GONE, first.visibility)
        } finally {
            release.countDown()
            FsRegistry.unregister("file")
            previous?.let(FsRegistry::register)
            host.pause().stop().destroy()
        }
    }
}
