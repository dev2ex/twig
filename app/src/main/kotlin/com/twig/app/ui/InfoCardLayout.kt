package com.twig.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import com.twig.app.R
import kotlin.math.abs
import kotlin.math.sqrt

/** Properties own horizontal gestures; vertical gestures return to the surrounding file list. */
class InfoCardLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    var swipeEnabled = false
    var onSwipe: ((Int) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axis = 0 // 0 undecided, 1 horizontal, 2 vertical
    private var owner: String? = null
    private var page: String? = null
    private var pageIndex = 0
    private var transition: ValueAnimator? = null
    private var outgoing: BitmapDrawable? = null
    private var contentInMotion: View? = null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val tabs = findViewById<View>(R.id.tab_bar) ?: return
        val content = findViewById<View>(R.id.info_content) ?: return
        // The body owns the shared edge; clip the overlapping title stroke and fill.
        tabs.clipBounds = Rect(0, 0, tabs.width, (content.top - tabs.top).coerceIn(0, tabs.height))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; axis = 0
                // Let this card decide the axis before RecyclerView starts a vertical drag.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (axis == 0) {
                axis = SwipeGesture.axis(event.x - downX, event.y - downY, slop.toFloat(), 1.5f)
                if (axis == 2) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                axis = 2
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return try {
            val handled = super.dispatchTouchEvent(event)
            // Intercepting UP cancels a child without calling this view's onTouchEvent.
            if (event.actionMasked == MotionEvent.ACTION_UP && axis == 1) {
                val dx = event.x - downX
                if (abs(dx) >= 48 * resources.displayMetrics.density) onSwipe?.invoke(if (dx < 0) 1 else -1)
            }
            handled
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                axis = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        swipeEnabled && (axis == 1 || (axis == 2 && event.actionMasked == MotionEvent.ACTION_UP))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && axis == 0) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Snapshot only the visible old content; one live page keeps links, buttons and spinners intact. */
    fun showPage(ownerKey: String, pageKey: String?, index: Int, content: View, render: () -> Unit) {
        val changed = owner == ownerKey && page != null && pageKey != null && page != pageKey
        if (owner != ownerKey || changed) finishTransition()
        val image = if (changed) snapshot(content) else null
        val direction = if (index > pageIndex) 1 else -1
        owner = ownerKey; page = pageKey; pageIndex = index
        render()
        if (image == null) return
        outgoing = image
        contentInMotion = content
        overlay.add(image)
        val left = image.bounds.left
        val top = image.bounds.top
        val imageWidth = image.bounds.width()
        val imageHeight = image.bounds.height()
        val distance = content.width
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                content.translationX = direction * distance * (1f - fraction)
                val x = left - (direction * distance * fraction).toInt()
                image.setBounds(x, top, x + imageWidth, top + imageHeight)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finishTransition() }
            })
        }
        transition?.start()
    }

    private fun snapshot(content: View): BitmapDrawable? {
        val visible = Rect()
        if (content.width <= 0 || !content.getLocalVisibleRect(visible) || visible.height() <= 0) return null
        // Very tall App tabs must never allocate a bitmap for their entire component list.
        val scale = minOf(1.0, sqrt(2_097_152.0 / (content.width.toDouble() * visible.height())))
        var bitmap: Bitmap? = null
        return try {
            val image = Bitmap.createBitmap((content.width * scale).toInt().coerceAtLeast(1),
                (visible.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap = image
            val canvas = Canvas(image)
            canvas.scale(image.width / content.width.toFloat(), image.height / visible.height().toFloat())
            canvas.translate(0f, -visible.top.toFloat())
            content.draw(canvas)
            BitmapDrawable(resources, image).apply {
                setFilterBitmap(true)
                setBounds(content.left, content.top + visible.top, content.right, content.top + visible.bottom)
            }
        } catch (_: OutOfMemoryError) {
            bitmap?.recycle()
            null
        }
    }

    fun finishTransition() {
        val animation = transition
        transition = null
        animation?.removeAllListeners()
        animation?.removeAllUpdateListeners()
        animation?.cancel()
        contentInMotion?.translationX = 0f
        contentInMotion = null
        outgoing?.let { overlay.remove(it); it.bitmap.recycle() }
        outgoing = null
    }

    fun resetPage() {
        finishTransition()
        owner = null; page = null
        onSwipe = null
        swipeEnabled = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw) finishTransition()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDetachedFromWindow() {
        finishTransition()
        super.onDetachedFromWindow()
    }
}
