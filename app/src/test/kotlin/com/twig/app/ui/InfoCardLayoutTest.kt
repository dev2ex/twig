package com.twig.app.ui

import android.animation.ValueAnimator
import android.app.Application
import android.app.Activity
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InfoCardLayoutTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `opaque property content cannot erase the upper rounded border`() {
        val context = android.view.ContextThemeWrapper(app, com.twig.app.R.style.Theme_Twig)
        val binding = com.twig.app.databinding.ItemInfoCardBinding.inflate(android.view.LayoutInflater.from(context))
        val content = binding.infoContent
        content.removeAllViews()
        val dp = content.resources.displayMetrics.density
        val width = (200 * dp).toInt()
        val height = (80 * dp).toInt()
        fun render(): android.graphics.Bitmap {
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, width, height)
            return android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also {
                content.draw(android.graphics.Canvas(it))
            }
        }
        val empty = render()
        val fill = android.util.TypedValue().also { context.theme.resolveAttribute(com.twig.app.R.attr.twigSurface, it, true) }.data
        content.addView(View(context).apply { setBackgroundColor(fill) },
            LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp.toInt(), dp.toInt(), dp.toInt(), dp.toInt())
            })
        val covered = render()
        var strokePixels = 0
        val corner = (6 * dp).toInt()
        for (y in 0 until corner) for (dx in 0 until corner) for (x in listOf(dx, width - 1 - dx)) {
            val expected = empty.getPixel(x, y)
            if (android.graphics.Color.alpha(expected) > 200 && expected != fill) {
                strokePixels++
                assertEquals("Rounded border at $x,$y", expected, covered.getPixel(x, y))
            }
        }
        assertTrue(strokePixels > 0)
        empty.recycle()
        covered.recycle()
    }

    private class ScrollParent(app: Application) : FrameLayout(app) {
        var disallowed = false
        var verticalIntercepts = 0
        private var downY = 0f
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowed = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) downY = event.y
            if (event.actionMasked == MotionEvent.ACTION_MOVE && kotlin.math.abs(event.y - downY) > 8) {
                verticalIntercepts++
                return true
            }
            return false
        }
        override fun onTouchEvent(event: MotionEvent) = true
    }

    private fun measure(view: View, height: Int = 300) {
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 400, height)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 10, action, x, y, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    @Test fun `horizontal gestures lock vertical scrolling and cancel child clicks`() {
        val parent = ScrollParent(app)
        val steps = mutableListOf<Int>()
        var clicks = 0
        val card = InfoCardLayout(app).apply { swipeEnabled = true; onSwipe = { steps += it } }
        val child = TextView(app).apply { text = "Launch activity"; setOnClickListener { clicks++ } }
        card.addView(child, LinearLayout.LayoutParams(400, 200))
        parent.addView(card, FrameLayout.LayoutParams(400, 200))
        measure(parent)
        touch(parent, MotionEvent.ACTION_DOWN, 300f, 40f)
        touch(parent, MotionEvent.ACTION_MOVE, 250f, 43f)
        assertTrue(parent.disallowed)
        touch(parent, MotionEvent.ACTION_MOVE, 180f, 100f)
        touch(parent, MotionEvent.ACTION_UP, 100f, 110f)
        assertEquals(listOf(1), steps)
        assertEquals(0, parent.verticalIntercepts)
        assertEquals(0, clicks)
        assertFalse(parent.disallowed)
    }

    @Test fun `vertical gestures return to the parent and never switch tabs`() {
        val parent = ScrollParent(app)
        var switches = 0
        val card = InfoCardLayout(app).apply { swipeEnabled = true; onSwipe = { switches++ } }
        card.addView(TextView(app), LinearLayout.LayoutParams(400, 200))
        parent.addView(card, FrameLayout.LayoutParams(400, 200))
        measure(parent)
        touch(parent, MotionEvent.ACTION_DOWN, 200f, 20f)
        touch(parent, MotionEvent.ACTION_MOVE, 202f, 60f)
        assertFalse(parent.disallowed)
        touch(parent, MotionEvent.ACTION_MOVE, 203f, 100f)
        touch(parent, MotionEvent.ACTION_UP, 203f, 120f)
        assertEquals(1, parent.verticalIntercepts)
        assertEquals(0, switches)
    }

    @Test fun `short horizontal motions and cancelled gestures do not switch tabs`() {
        val steps = mutableListOf<Int>()
        val card = InfoCardLayout(app).apply { swipeEnabled = true; onSwipe = { steps += it } }
        measure(card)
        touch(card, MotionEvent.ACTION_DOWN, 200f, 40f)
        touch(card, MotionEvent.ACTION_MOVE, 180f, 40f)
        touch(card, MotionEvent.ACTION_UP, 180f, 40f)
        touch(card, MotionEvent.ACTION_DOWN, 200f, 40f)
        touch(card, MotionEvent.ACTION_MOVE, 100f, 40f)
        touch(card, MotionEvent.ACTION_CANCEL, 100f, 40f)
        assertTrue(steps.isEmpty())
        touch(card, MotionEvent.ACTION_DOWN, 100f, 40f)
        touch(card, MotionEvent.ACTION_MOVE, 200f, 40f)
        touch(card, MotionEvent.ACTION_UP, 250f, 40f)
        assertEquals(listOf(-1), steps)
    }

    @Test fun `normal taps still reach child actions`() {
        var clicks = 0
        val card = InfoCardLayout(app).apply { swipeEnabled = true }
        val child = TextView(app).apply { text = "Copy"; setOnClickListener { clicks++ } }
        card.addView(child, LinearLayout.LayoutParams(400, 100))
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(card)
        measure(card)
        touch(card, MotionEvent.ACTION_DOWN, 40f, 40f)
        touch(card, MotionEvent.ACTION_UP, 40f, 40f)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, clicks)
    }

    @Test fun `release without move switches tabs once and cancels the child click`() {
        for (vertical in listOf(false, true)) {
            var clicks = 0
            val steps = mutableListOf<Int>()
            val card = InfoCardLayout(app).apply { swipeEnabled = true; onSwipe = { steps += it } }
            card.addView(TextView(app).apply { setOnClickListener { clicks++ } },
                LinearLayout.LayoutParams(400, 200))
            Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(card)
            measure(card)
            touch(card, MotionEvent.ACTION_DOWN, 300f, 40f)
            touch(card, MotionEvent.ACTION_UP, if (vertical) 300f else 100f, if (vertical) 150f else 40f)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, clicks)
            assertEquals(if (vertical) emptyList<Int>() else listOf(1), steps)
        }
    }

    @Test fun `new and old content slide next to each other and release the snapshot`() {
        val card = InfoCardLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val content = TextView(app).apply { text = "General" }
        card.addView(content, LinearLayout.LayoutParams(400, 100))
        measure(card)
        card.showPage("file", "General", 0, content) {}
        card.showPage("file", "Media", 1, content) { content.text = "Media" }
        val animation = ReflectionHelpers.getField<ValueAnimator>(card, "transition")
        val image = ReflectionHelpers.getField<BitmapDrawable>(card, "outgoing")
        assertEquals(160L, animation.duration)
        for (time in listOf(0L, 40L, 80L, 120L)) {
            animation.currentPlayTime = time
            assertEquals(image.bounds.right.toFloat(), content.left + content.translationX, 1f)
            assertFalse(image.bitmap.isRecycled)
        }
        animation.end()
        assertEquals(0f, content.translationX, 0f)
        assertTrue(image.bitmap.isRecycled)
        assertNull(ReflectionHelpers.getField<ValueAnimator?>(card, "transition"))
        card.showPage("file", "General", 0, content) { content.text = "General" }
        val reverse = ReflectionHelpers.getField<ValueAnimator>(card, "transition")
        val previous = ReflectionHelpers.getField<BitmapDrawable>(card, "outgoing")
        reverse.currentPlayTime = 80
        assertEquals(content.right + content.translationX, previous.bounds.left.toFloat(), 1f)
        card.resetPage()
        assertTrue(previous.bitmap.isRecycled)
        assertEquals(0f, content.translationX, 0f)
    }

    @Test fun `tall properties snapshot only the visible area and recycling cancels transitions`() {
        val card = InfoCardLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val content = TextView(app).apply { text = "Components" }
        card.addView(content, LinearLayout.LayoutParams(400, 100_000))
        measure(card)
        card.showPage("file", "App", 1, content) {}
        card.showPage("file", "Hash", 2, content) {}
        val image = ReflectionHelpers.getField<BitmapDrawable>(card, "outgoing")
        assertTrue(image.bitmap.height <= card.height)
        assertTrue(image.bitmap.width.toLong() * image.bitmap.height <= 2_097_152)
        card.showPage("another-file", "General", 0, content) {}
        assertTrue(image.bitmap.isRecycled)
        assertNull(ReflectionHelpers.getField<ValueAnimator?>(card, "transition"))
        assertEquals(0f, content.translationX, 0f)
    }
}
