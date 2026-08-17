package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 可缩放图片视图(参照 SambaGallery 交互,自绘不引第三方库):
 * - 双击在 居中(FIT,无裁切) / 填充(CROP,一方向裁切) / 实际尺寸(1:1,可拖看) 间循环
 * - 拖动平移(图片超出视图时);捏合自由缩放
 * - 单击回调([onTap]);未放大时的横向快滑回调翻页([onPage])
 * - 放大到基础位图被插值时([scale] > 1)向外要一块高清区域([onNeedHiRes]),
 *   拿到后叠画在基础位图之上——基础位图为省内存是降采样解的,放大看细节靠这层。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    var onTap: (() -> Unit)? = null

    /** 长按回调(弹操作菜单)。 */
    var onLongPress: (() -> Unit)? = null

    /** 翻页回调:dir=-1 上一张,+1 下一张。 */
    var onPage: ((Int) -> Unit)? = null

    /**
     * 需要高清区块:参数是可视区域(已外扩余量)在**当前显示位图**坐标系里的矩形,
     * 以及当前缩放倍数(供决定区块自身的降采样)。异步解好后回调 [setHiRes]。
     */
    var onNeedHiRes: ((RectF, Float) -> Unit)? = null

    /**
     * 视口变了(缩放/平移/换模式)。图片对比页拿它把另一侧同步过去;
     * [applyViewport] 引起的变化**不会**回调,否则两侧会互相喂招停不下来。
     */
    var onViewport: (() -> Unit)? = null
    private var syncing = false

    private val m = Matrix()
    private var imgW = 0f
    private var imgH = 0f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var mode = 0 // 0 FIT, 1 CROP, 2 ACTUAL
    /** 位图→原图像素的倍数(解码降采样倍数);实际尺寸模式按此放大,呈现原图真实大小。 */
    private var actualScale = 1f

    private var hiRes: Bitmap? = null
    private var hiResRect: RectF? = null // 在显示位图坐标系中,与 hiRes 一一对应
    private var hiResScale = 0f          // 请求这块时的 scale,用于判断是否该按新倍数重解
    private val hiResPaint = Paint().apply { isFilterBitmap = true }
    private val hiResReq = Runnable { requestHiRes() }

    private val fitScale get() = if (imgW == 0f) 1f else minOf(width / imgW, height / imgH)
    private val cropScale get() = if (imgW == 0f) 1f else maxOf(width / imgW, height / imgH)
    private val maxScale get() = maxOf(cropScale, actualScale) * 2f

    /**
     * 缩放下限。★ 不能一律取 [fitScale]:[MODE_FIT_ACTUAL] 下比容器小的图就是要按 1:1 显示,
     * 而那个 scale 小于 fitScale——下限卡在 fitScale 的话,它一被同步/捏合就弹回铺满。
     */
    private val minScale get() =
        if (initialMode == MODE_FIT_ACTUAL) minOf(fitScale, actualScale) else fitScale

    /** 换图后用哪种模式,以及双击循环从哪儿起步。默认 [MODE_FIT]。 */
    var initialMode = MODE_FIT

    /** 双击循环的档位。对比场景里 CROP(裁切铺满)没意义,换成在"实际/适应"之间来回。 */
    private val modeCycle get() =
        if (initialMode == MODE_FIT_ACTUAL) intArrayOf(MODE_FIT_ACTUAL, MODE_FIT, MODE_ACTUAL)
        else intArrayOf(MODE_FIT, MODE_CROP, MODE_ACTUAL)

    init {
        scaleType = ScaleType.MATRIX
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cycle = modeCycle
            val i = cycle.indexOf(mode)
            mode = cycle[(if (i < 0) 0 else i + 1) % cycle.size]
            applyMode()
            notifyViewport()
            return true
        }

        // ★ 同名成员:不写 this@ZoomableImageView 会解析到监听器自己的 onLongPress
        override fun onLongPress(e: MotionEvent) {
            this@ZoomableImageView.onLongPress?.invoke()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx; ty -= dy; clampAndApply(); notifyViewport()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            // 未放大(横向无溢出)时,横向快滑翻页
            if (imgW * scale <= width + 1f && kotlin.math.abs(vx) > kotlin.math.abs(vy) &&
                kotlin.math.abs(vx) > 500f
            ) {
                onPage?.invoke(if (vx > 0) -1 else 1)
                return true
            }
            return false
        }
    })

    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val ns = (scale * f).coerceIn(minScale, maxScale)
            // 以捏合焦点为中心缩放
            tx = d.focusX - (d.focusX - tx) * (ns / scale)
            ty = d.focusY - (d.focusY - ty) * (ns / scale)
            scale = ns
            clampAndApply()
            notifyViewport()
            return true
        }
    })

    /**
     * 设置新图片:重置为 [initialMode]。
     * @param actual 位图→原图像素的倍数(解码降采样倍数),用于"实际尺寸"模式按原图真实大小显示。
     */
    fun setImage(bmp: Bitmap, actual: Float = 1f) {
        clearHiRes()
        setImageBitmap(bmp)
        imgW = bmp.width.toFloat(); imgH = bmp.height.toFloat()
        actualScale = actual
        mode = initialMode
        if (width > 0) applyMode()
    }

    /** 交付一块高清区域([rect] 必须是发起 [onNeedHiRes] 时给出的那个矩形)。 */
    fun setHiRes(bmp: Bitmap, rect: RectF) {
        hiRes = bmp
        hiResRect = rect
        invalidate()
    }

    private fun clearHiRes() {
        removeCallbacks(hiResReq)
        // 不 recycle:这张可能正被上一帧的 canvas 引用,交给 GC 更稳
        hiRes = null
        hiResRect = null
        hiResScale = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = hiRes ?: return
        val r = hiResRect ?: return
        canvas.save()
        canvas.concat(m) // 与基础位图同一变换,高清块按位图坐标落位
        canvas.drawBitmap(h, null, r, hiResPaint)
        canvas.restore()
    }

    /** 缩放/平移停下来后按需要一块高清区域;缩回到不插值就丢掉,省内存。 */
    private fun scheduleHiRes() {
        removeCallbacks(hiResReq)
        if (actualScale <= 1f || scale <= 1.01f) { // 原图没有更多像素,或位图还没被放大
            if (hiRes != null) { clearHiRes(); invalidate() }
            return
        }
        postDelayed(hiResReq, HIRES_DELAY_MS)
    }

    private fun requestHiRes() {
        if (imgW == 0f) return
        // 可视区域反变换回位图坐标,再外扩一圈——小幅平移就不必重解
        val pad = HIRES_PAD
        val vw = width / scale; val vh = height / scale
        val rect = RectF(
            ((-tx / scale) - vw * pad).coerceAtLeast(0f),
            ((-ty / scale) - vh * pad).coerceAtLeast(0f),
            ((-tx + width) / scale + vw * pad).coerceAtMost(imgW),
            ((-ty + height) / scale + vh * pad).coerceAtMost(imgH),
        )
        if (rect.width() < 1f || rect.height() < 1f) return
        val have = hiResRect
        // 已有的块盖得住当前视野、且倍数没大变,就别重解
        if (have != null && have.contains(rect) && scale / hiResScale in 0.7f..1.4f) return
        hiResScale = scale
        onNeedHiRes?.invoke(rect, scale)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (imgW > 0) applyMode()
    }

    private fun applyMode() {
        scale = when (mode) {
            MODE_FIT -> fitScale
            MODE_CROP -> cropScale
            // 按原图真实像素显示,但不超过容器——大图缩到刚好放下,小图保持 1:1 不被拉大
            MODE_FIT_ACTUAL -> minOf(actualScale, fitScale)
            else -> actualScale // 实际尺寸:按原图真实像素大小(可拖看局部)
        }
        val sw = imgW * scale; val sh = imgH * scale
        tx = (width - sw) / 2f
        ty = (height - sh) / 2f
        clampAndApply()
    }

    private fun clampAndApply() {
        val sw = imgW * scale; val sh = imgH * scale
        tx = if (sw <= width) (width - sw) / 2f else tx.coerceIn(width - sw, 0f)
        ty = if (sh <= height) (height - sh) / 2f else ty.coerceIn(height - sh, 0f)
        m.reset(); m.postScale(scale, scale); m.postTranslate(tx, ty)
        imageMatrix = m
        scheduleHiRes()
    }

    /**
     * 把视口变化推给外面。**只在用户手势之后调**,换图与布局(首次拿到尺寸时的 applyMode)
     * 一律不推——两张图各按自己的实际大小落位是初始状态的一部分,谁后加载完谁就把对侧
     * 顶成自己的视口,那两边就都不是实际大小了。
     */
    private fun notifyViewport() {
        if (!syncing) onViewport?.invoke()
    }

    /**
     * 当前视口在图片里的位置,**归一化到图片自身尺寸**:[左上角 u, 左上角 v, 视口覆盖的宽度比]。
     * 归一化是为了让两张**尺寸不同**的图也能对齐同一块内容——同一张照片改过分辨率时,
     * 按视图像素位移同步会立刻错位。
     */
    fun viewport(): FloatArray? {
        if (imgW <= 0f || imgH <= 0f || scale <= 0f || width == 0) return null
        return floatArrayOf(-tx / scale / imgW, -ty / scale / imgH, width / (scale * imgW))
    }

    /** 把视口挪到 [viewport] 描述的位置。对侧图更小时 scale 会被 fit 下限挡住,那是物理限制。 */
    fun applyViewport(v: FloatArray) {
        if (imgW <= 0f || imgH <= 0f || width == 0 || v[2] <= 0f) return
        syncing = true
        scale = (width / (v[2] * imgW)).coerceIn(minScale, maxScale)
        tx = -v[0] * imgW * scale
        ty = -v[1] * imgH * scale
        clampAndApply()
        syncing = false
    }

    /** 左右边缘留给系统边缘返回手势,这个宽度内按下的触摸整个不处理,避免翻页手势和它抢。 */
    private val edgeGuardPx get() = 24f * resources.displayMetrics.density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (event.x < edgeGuardPx || event.x > width - edgeGuardPx)
        ) {
            return false
        }
        scaleGesture.onTouchEvent(event)
        gesture.onTouchEvent(event)
        // 放大后允许平移,阻止父级拦截
        if (imgW * scale > width + 1f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    companion object {
        private const val HIRES_DELAY_MS = 150L // 手势停下来才解,别在捏合过程中反复触发
        private const val HIRES_PAD = 0.15f     // 可视区域外扩比例(外扩是平方级涨内存,别贪)

        /** 适应容器(可能把小图放大铺满)。 */
        const val MODE_FIT = 0
        /** 裁切铺满。 */
        const val MODE_CROP = 1
        /** 原图真实像素大小。 */
        const val MODE_ACTUAL = 2
        /** 真实像素大小,但超过容器就缩到刚好放下。 */
        const val MODE_FIT_ACTUAL = 3
    }
}
