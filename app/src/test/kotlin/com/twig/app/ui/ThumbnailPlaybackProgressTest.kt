package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.PlaybackStore
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailPlaybackProgressTest {
    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(savedInstanceState)
        }
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val video = XFile("file", "/missing/thumbnail-progress.mp4", false, size = 123456L)
    private val host = Robolectric.buildActivity(Host::class.java)
    private lateinit var parent: FrameLayout
    private val progressColor get() = ContextCompat.getColor(app, R.color.accent)

    @Before fun setup() {
        host.setup()
        parent = FrameLayout(host.get())
        host.get().setContentView(parent)
        PlaybackStore.clear(app)
        Prefs.setResumePlayback(app, true)
        Prefs.setThumbsPlaybackProgress(app, true)
    }

    @After fun cleanup() {
        host.pause().stop().destroy()
        PlaybackStore.clear(app)
        Thumbs.clearCache(app)
        Prefs.setResumePlayback(app, true)
        Prefs.setThumbsPlaybackProgress(app, true)
    }

    private fun black(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
    private fun draw(view: View, w: Int, h: Int): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seed(file: XFile) {
        val key = Thumbs::class.java.getDeclaredMethod("keyOf", XFile::class.java)
            .apply { isAccessible = true }.invoke(Thumbs, file) as String
        val mem = Thumbs::class.java.getDeclaredField("mem").apply { isAccessible = true }
            .get(Thumbs) as LruCache<String, Bitmap>
        mem.put(key, black(200, 100))
    }

    private fun adapter(thumbs: Boolean = true, grid: Int = 0) =
        FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = thumbs, gridMode = grid)

    private fun node(file: XFile) = PaneViewModel.FileNode(file, 1, false, false)
    private fun bind(adapter: FileAdapter, index: Int = 0): RecyclerView.ViewHolder {
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(index))
        parent.addView(holder.itemView)
        adapter.bindViewHolder(holder, index)
        draw(holder.itemView, 400, 160)
        shadowOf(Looper.getMainLooper()).idle()
        return holder
    }
    private fun image(holder: RecyclerView.ViewHolder, grid: Int) =
        holder.itemView.findViewById<ThumbnailImageView>(if (grid == 0) R.id.icon else R.id.thumb)

    @Test fun `one and a half dp bar follows the actual fit-center thumbnail instead of the letterbox`() {
        val view = ThumbnailImageView(parent.context).apply {
            setBackgroundColor(Color.MAGENTA)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setThumbnailBitmap(black(200, 100))
            playbackFraction = .25f
        }
        val bitmap = draw(view, 200, 200)
        assertEquals(progressColor, bitmap.getPixel(25, 149))
        assertNotEquals(Color.BLACK, bitmap.getPixel(25, 148))
        assertNotEquals(progressColor, bitmap.getPixel(25, 148))
        assertEquals(Color.BLACK, bitmap.getPixel(25, 147))
        assertEquals(Color.rgb(85, 85, 85), bitmap.getPixel(100, 149))
        assertEquals(Color.MAGENTA, bitmap.getPixel(25, 150))
        assertEquals(Color.MAGENTA, bitmap.getPixel(25, 199))
    }

    @Test fun `cropped thumbnail draws the bar inside its visible bottom edge`() {
        val view = ThumbnailImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setThumbnailBitmap(black(100, 200))
            playbackFraction = .5f
        }
        val bitmap = draw(view, 200, 100)
        assertEquals(progressColor, bitmap.getPixel(50, 99))
        assertNotEquals(Color.BLACK, bitmap.getPixel(50, 98))
        assertNotEquals(progressColor, bitmap.getPixel(50, 98))
        assertEquals(Color.BLACK, bitmap.getPixel(50, 97))
        assertEquals(Color.rgb(85, 85, 85), bitmap.getPixel(150, 99))
    }

    @Test fun `placeholder has no bar and recycled thumbnail loses its progress`() {
        val view = ThumbnailImageView(parent.context).apply {
            setImageBitmap(black(100, 100))
            playbackFraction = .5f
        }
        assertEquals(Color.BLACK, draw(view, 100, 100).getPixel(25, 99))
        view.setThumbnailBitmap(black(100, 100))
        assertEquals(progressColor, draw(view, 100, 100).getPixel(25, 99))
        view.resetThumbnail()
        view.setThumbnailBitmap(black(100, 100))
        assertEquals(0f, view.playbackFraction)
        assertEquals(Color.BLACK, draw(view, 100, 100).getPixel(25, 99))
    }

    @Test fun `list and grid thumbnails display shared progress from the real cache fill path`() {
        PlaybackStore.save(app, video, 30000L, 120000L)
        seed(video)
        for (grid in 0..1) {
            val adapter = adapter(grid = grid)
            // Matching stays path-independent and does not need a network lookup.
            adapter.submitList(listOf(node(video.copy(scheme = "offline", path = "/other/${video.name}"))))
            val image = image(bind(adapter), grid)
            assertEquals(.25f, image.playbackFraction)
            assertTrue("Displayed thumbnails must use bitmap filtering", (image.drawable as BitmapDrawable).paint.isFilterBitmap)
            assertEquals(progressColor, draw(image, 200, 100).getPixel(25, 99))
        }
    }

    @Test fun `resume updates only progress and completion removes the bar without rebinding the thumbnail`() {
        PlaybackStore.save(app, video, 30000L, 120000L)
        seed(video)
        for (grid in 0..1) {
            PlaybackStore.save(app, video, 30000L, 120000L)
            val adapter = adapter(grid = grid)
            adapter.submitList(listOf(node(video)))
            val holder = bind(adapter)
            val image = image(holder, grid)
            val drawable = image.drawable
            val name = holder.itemView.findViewById<TextView>(if (grid == 0) R.id.name else R.id.cell_name)
            name.text = "keep existing row"
            val height = image.layoutParams.height
            val payloads = mutableListOf<Any>()
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { fail("Must not refresh the whole list") }
                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                    assertEquals(0, start)
                    assertEquals(1, count)
                    payloads += requireNotNull(payload)
                }
            })
            PlaybackStore.save(app, video, 60000L, 120000L)
            adapter.refreshPlaybackProgress(app)
            assertEquals(1, payloads.size)
            adapter.onBindViewHolder(holder, 0, payloads)
            assertEquals(.5f, image.playbackFraction)
            assertSame(drawable, image.drawable)
            assertEquals(height, image.layoutParams.height)
            assertEquals("keep existing row", name.text.toString())
            payloads.clear()
            adapter.refreshPlaybackProgress(app)
            assertTrue("Unchanged progress needs no refresh", payloads.isEmpty())
            PlaybackStore.save(app, video, 120000L, 120000L)
            adapter.refreshPlaybackProgress(app)
            assertEquals(1, payloads.size)
            adapter.onBindViewHolder(holder, 0, payloads)
            assertEquals(0f, image.playbackFraction)
            assertSame(drawable, image.drawable)
        }
    }

    @Test fun `disabled thumbnails or progress preference or resume show no progress`() {
        PlaybackStore.save(app, video, 30000L, 120000L)
        seed(video)
        for (grid in 0..1) for (mode in 0..2) {
            Prefs.setThumbsPlaybackProgress(app, mode != 1)
            Prefs.setResumePlayback(app, mode != 2)
            val adapter = adapter(thumbs = mode != 0, grid = grid)
            adapter.submitList(listOf(node(video)))
            assertEquals(0f, image(bind(adapter), grid).playbackFraction)
        }
    }

    @Test fun `rebinding a video holder to another file clears progress in list and grid`() {
        PlaybackStore.save(app, video, 30000L, 120000L)
        seed(video)
        val other = video.copy(path = "/missing/no-progress.mp4")
        seed(other)
        for (grid in 0..1) {
            val adapter = adapter(grid = grid)
            adapter.submitList(listOf(node(video), node(other)))
            val holder = bind(adapter)
            assertEquals(.25f, image(holder, grid).playbackFraction)
            adapter.bindViewHolder(holder, 1)
            assertEquals(0f, image(holder, grid).playbackFraction)
        }
    }

    @Test fun `link badge survives thumbnail delivery and clears when a holder is reused`() {
        val link = video.copy(path = "/missing/link.mp4", isSymbolicLink = true)
        val ordinary = video.copy(path = "/missing/ordinary.mp4")
        seed(link)
        seed(ordinary)
        for (grid in 0..1) {
            val adapter = adapter(grid = grid)
            adapter.submitList(listOf(node(link), node(ordinary)))
            val holder = bind(adapter)
            val image = image(holder, grid)
            assertTrue(image.symbolicLink)
            image.setThumbnailBitmap(black(100, 100))
            val marked = draw(image, 100, 100)
            image.symbolicLink = false
            val plain = draw(image, 100, 100)
            assertFalse(marked.sameAs(plain))
            // The emblem must leave the upper-left area of the original image intact.
            assertEquals(plain.getPixel(20, 20), marked.getPixel(20, 20))
            image.symbolicLink = true
            adapter.bindViewHolder(holder, 1)
            assertFalse(image.symbolicLink)
        }
    }

    @Test fun `hiding history preserves the thumbnail bar and its preference changes the ui signature`() {
        PlaybackStore.save(app, video, 30000L, 120000L)
        PlaybackStore.hideRecent(app)
        seed(video)
        val adapter = adapter()
        adapter.submitList(listOf(node(video)))
        assertEquals(.25f, image(bind(adapter), 0).playbackFraction)
        val signature = Prefs.uiSignature(app)
        Prefs.setThumbsPlaybackProgress(app, false)
        assertNotEquals(signature, Prefs.uiSignature(app))
        assertTrue(Prefs.resumePlayback(app))
    }
}
