package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.media3.common.text.Cue
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 位图字幕(PGS 等)叠加层,铺满屏幕。
 * cue 的 position/line/size/bitmapHeight 是相对视频帧的比例,经 [setVideoRect]
 * 给的"视频显示矩形"映射到屏幕坐标(裁切/拉伸模式下矩形可大于/变形于屏幕)。
 *
 * 绘制规则:
 * - 字幕位图**始终保持原始宽高比**,以纵向缩放系数统一缩放(拉伸填充模式下
 *   画面变形但字幕不变形),位置按帧映射的中心点对齐。
 * - 位图底边靠下的 cue 视为主对白:任何画面模式下都重锚到屏幕底部;
 *   其余(画面注释)保持原样原位,裁切模式下允许被裁。
 * - 整数像素对齐;缩小超过一半时分级降采样(mip)避免锯齿。
 */
class BitmapCueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var cues: List<Cue> = emptyList()
    private val video = RectF()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmapCache = HashMap<Bitmap, Bitmap>() // 原位图 → 处理(黑化/降采样)后

    fun setCues(list: List<Cue>) {
        if (cues.isEmpty() && list.isEmpty()) return
        cues = list
        // 移动字幕是同一块位图连发几十上百组新坐标(解析器对位图做了复用,对象身份不变)——
        // 这里别整表 clear,只把不再出现的位图摘掉,降采样结果才留得住,不然每组都重算一遍。
        if (bitmapCache.isNotEmpty()) {
            val alive = list.mapNotNullTo(HashSet()) { it.bitmap }
            bitmapCache.keys.retainAll(alive)
        }
        invalidate()
    }

    /** 视频画面在屏幕上的显示矩形(居中,裁切/拉伸模式下会超出屏幕或变形)。 */
    fun setVideoRect(left: Float, top: Float, w: Float, h: Float) {
        video.set(left, top, left + w, top + h)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cues.isEmpty() || video.isEmpty) return
        val vw = video.width()
        val vh = video.height()
        val sw = width.toFloat()
        val sh = height.toFloat()
        // 一屏可能同时有多块字幕(对白 + 画面注释、上下两块、左右分栏),先把每块的目标
        // 矩形都算出来,主对白那组**整组一起**重锚到屏幕底部——逐块各自贴底会把本来错开
        // 的几块压到同一行叠在一起。
        val bmps = ArrayList<Bitmap>(cues.size)
        val rects = ArrayList<RectF>(cues.size)
        val scales = ArrayList<Float>(cues.size)
        val dialogue = ArrayList<Int>(cues.size) // 判定为主对白的那几块在上面几个表里的下标
        for (cue in cues) {
            val bmp = cue.bitmap ?: continue
            // 统一缩放系数:优先纵向(bitmapHeight 相对帧高),不受横向拉伸影响
            val s = when {
                cue.bitmapHeight != Cue.DIMEN_UNSET -> cue.bitmapHeight * vh / bmp.height
                cue.size != Cue.DIMEN_UNSET -> cue.size * vw / bmp.width
                else -> 1f
            }
            val cw = bmp.width * s
            val ch = bmp.height * s
            // 帧映射的矩形(可能被画面模式变形),取其中心点来放置保比例的字幕
            val fw = if (cue.size != Cue.DIMEN_UNSET) cue.size * vw else cw
            val fh = if (cue.bitmapHeight != Cue.DIMEN_UNSET) cue.bitmapHeight * vh else ch
            var fx = if (cue.position != Cue.DIMEN_UNSET) video.left + cue.position * vw
            else video.left + (vw - fw) / 2
            var fy = if (cue.line != Cue.DIMEN_UNSET) video.top + cue.line * vh
            else video.top + vh * 0.85f - fh
            when (cue.positionAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> fx -= fw / 2
                Cue.ANCHOR_TYPE_END -> fx -= fw
            }
            when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> fy -= fh / 2
                Cue.ANCHOR_TYPE_END -> fy -= fh
            }
            val cx = fx + fw / 2
            val cy = fy + fh / 2
            val r = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
            // 主对白判定按位图底边(有些片源的 PGS 对象接近整帧大小,中心点判定会失效)
            if ((fy + fh) > video.top + vh * 0.75f) dialogue.add(rects.size)
            bmps.add(bmp)
            rects.add(r)
            scales.add(s)
        }
        if (dialogue.isNotEmpty()) {
            // 主对白整组一起平移:贴屏幕底部(留 2% 边距)、水平方向整组保持在屏幕内,
            // 组内各块的相对位置(上下两行、左右分栏)原样保留。
            var bottom = Float.NEGATIVE_INFINITY
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (i in dialogue) {
                bottom = max(bottom, rects[i].bottom)
                left = minOf(left, rects[i].left)
                right = max(right, rects[i].right)
            }
            val dy = sh * 0.98f - bottom
            var dx = if (left < 0f) -left else 0f
            if (right + dx > sw) dx = sw - right // 组比屏幕宽时右边优先(与原逐块实现的先后顺序一致)
            for (i in dialogue) rects[i].offset(dx, dy)
        }
        for (i in rects.indices) {
            val r = rects[i]
            val bmp = bmps[i]
            val s = scales[i]
            // 整数像素对齐,避免半像素采样造成的边缘发虚
            dst.set(
                r.left.roundToInt().toFloat(), r.top.roundToInt().toFloat(),
                (r.left.roundToInt() + r.width().roundToInt()).toFloat(),
                (r.top.roundToInt() + r.height().roundToInt()).toFloat(),
            )
            canvas.drawBitmap(prepared(bmp, s), null, dst, paint)
        }
    }

    /** 缩小超过一半时分级降采样(mip)避免锯齿;结果按原位图缓存。 */
    private fun prepared(bmp: Bitmap, scale: Float): Bitmap {
        if (scale > 0.5f) return bmp
        bitmapCache[bmp]?.let { return it }
        var cur = bmp
        var s = scale
        while (s <= 0.5f && cur.width > 1 && cur.height > 1) {
            cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            s *= 2
        }
        bitmapCache[bmp] = cur
        return cur
    }
}
