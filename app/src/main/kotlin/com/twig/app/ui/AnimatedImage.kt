package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Build
import androidx.annotation.RequiresApi
import com.twig.core.XFile
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun animationCandidate(file: XFile): Boolean =
    Build.VERSION.SDK_INT >= 28 && file.extension.lowercase() in setOf("gif", "webp")

internal class AnimatedImage(val drawable: Drawable, val actual: Float)

private class StaticImage : RuntimeException()

/** Only the visible image owns a decoder; animated images never enter the bitmap cache. */
@RequiresApi(28)
internal fun decodeAnimation(context: Context, local: File): AnimatedImage? {
    var actual = 1f
    val drawable = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(local)) { decoder, info, _ ->
            if (!info.isAnimated) throw StaticImage()
            val w = info.size.width
            val h = info.size.height
            val dm = context.resources.displayMetrics
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            val factor = minOf(1f, maxOf(minOf(sw / w, sh / h), minOf(sw / h, sh / w)),
                sqrt(12_000_000.0 / (w.toDouble() * h)).toFloat())
            val targetW = (w * factor).roundToInt().coerceAtLeast(1)
            decoder.setTargetSize(targetW, (h * factor).roundToInt().coerceAtLeast(1))
            actual = w.toFloat() / targetW
        }
    } catch (_: StaticImage) { return null }
    return if (drawable is AnimatedImageDrawable) AnimatedImage(drawable, actual) else null
}

/** Rotate the live drawable without turning its frames into static bitmaps. */
internal class RotatedImageDrawable(drawable: Drawable) : DrawableWrapper(drawable) {
    override fun getIntrinsicWidth() = drawable!!.intrinsicHeight
    override fun getIntrinsicHeight() = drawable!!.intrinsicWidth

    override fun onBoundsChange(bounds: Rect) {
        drawable!!.setBounds(0, 0, bounds.height(), bounds.width())
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.right.toFloat(), bounds.top.toFloat())
        canvas.rotate(90f)
        drawable!!.draw(canvas)
        canvas.restoreToCount(save)
    }
}
