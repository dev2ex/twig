package com.twig.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 手写毛玻璃:把封面缩到 ~64px、盒式模糊几遍再交给 View 放大平滑,依主题叠遮罩。
 * 零依赖(不用已弃用的 RenderScript),CPU 上跑一次几毫秒。
 */
object Blur {

    /**
     * 动态范围压缩系数:每个像素往整图平均色靠(0 = 全铺平均色的纯色,1 = 原样)。
     * 模糊只是把边界抹掉,亮暗块本身还在——0.5 把明暗差砍掉一半,亮处压暗、暗处提亮,
     * 背景更"平",前景文字压在上面各处观感一致(对比度是按平均色算的,背景越平越准)。
     */
    private const val FLATTEN = 0.5f

    /**
     * 遮罩浓度(0 = 不叠)。深色主题叠黑压暗;**浅色主题不叠**——叠白等于往封面上倒一层
     * 牛奶,配上压平之后整块背景又白又平、封面颜色基本没了,很难看。文字可读性不靠它:
     * [MusicTint] 按实际背景色反推前景,浅底自动解出深色字。
     */
    private const val SCRIM_DARK = 140
    private const val SCRIM_LIGHT = 0

    /** 生成一张模糊背景位图([out] 尺寸),压平动态范围后按主题叠遮罩(浅色主题不叠)。 */
    fun background(src: Bitmap, out: Int, dark: Boolean): Bitmap {
        val small = scaleDown(src, 64)
        val blurred = flatten(boxBlur(boxBlur(boxBlur(small, 2), 3), 4), FLATTEN)
        val result = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
        val c = Canvas(result)
        // 放大填满(center-crop 到方形)
        val s = out.toFloat() / minOf(blurred.width, blurred.height)
        val dw = (blurred.width * s); val dh = (blurred.height * s)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        c.save()
        c.translate((out - dw) / 2f, (out - dh) / 2f)
        c.scale(s, s)
        c.drawBitmap(blurred, 0f, 0f, paint)
        c.restore()
        val scrim = if (dark) SCRIM_DARK else SCRIM_LIGHT
        if (scrim > 0) {
            c.drawColor(if (dark) Color.argb(scrim, 0, 0, 0) else Color.argb(scrim, 255, 255, 255))
        }
        return result
    }

    /**
     * 把每个像素按 [k] 往整图平均色插值(逐通道各自取均值,所以整体色相/色调不变,
     * 只是明暗与饱和的起伏被压缩)。就地改 [src](调用方传进来的是刚生成的临时位图)。
     */
    private fun flatten(src: Bitmap, k: Float): Bitmap {
        if (k >= 1f) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var sr = 0L; var sg = 0L; var sb = 0L
        for (c in px) { sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
        val n = px.size
        val mr = (sr / n).toFloat(); val mg = (sg / n).toFloat(); val mb = (sb / n).toFloat()
        for (i in px.indices) {
            val c = px[i]
            val r = (mr + (((c shr 16) and 0xFF) - mr) * k).toInt().coerceIn(0, 255)
            val g = (mg + (((c shr 8) and 0xFF) - mg) * k).toInt().coerceIn(0, 255)
            val b = (mb + ((c and 0xFF) - mb) * k).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        src.setPixels(px, 0, w, 0, 0, w, h)
        return src
    }

    private fun scaleDown(src: Bitmap, target: Int): Bitmap {
        val s = target.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 半径 r 的水平+垂直盒式模糊(简单均值),就地生成新位图。 */
    private fun boxBlur(src: Bitmap, r: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        blurPass(px, tmp, w, h, r, true)
        blurPass(tmp, px, w, h, r, false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = r * 2 + 1
        for (line in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val idx = idx(line, i.coerceIn(0, len - 1), w, horizontal)
                val c = input[idx]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                output[idx(line, i, w, horizontal)] =
                    (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[idx(line, (i + r + 1).coerceIn(0, len - 1), w, horizontal)]
                val sub = input[idx(line, (i - r).coerceIn(0, len - 1), w, horizontal)]
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun idx(line: Int, i: Int, w: Int, horizontal: Boolean) =
        if (horizontal) line * w + i else i * w + line
}
