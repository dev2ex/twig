package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import com.twig.app.OpenFiles
import com.twig.core.XFile
import java.io.File
import kotlin.math.roundToInt

/**
 * 图片解码:降采样 + EXIF 旋转 + 放大后按区域重解高清块。
 *
 * 本来长在 `ImageViewerActivity` 里,提出来是因为**图片对比页要同一套解码质量**——
 * 另写一份精简版的话,放大看细节时就是糊的,而"放大比细节"恰恰是图片对比的主要用途。
 * 这里全是无状态函数,谁都能调。
 */

/** 高清块的内存闸门,见 [decodeRegion]。 */
private const val MAX_HIRES_SCREENS = 2
private const val MAX_HIRES_PX = 12_000_000L

/**
 * @param bmp     已应用 EXIF 旋转的降采样位图
 * @param actual  位图→原图像素倍数(密度缩放后不再等于 inSampleSize,是实际宽度比)
 * @param exifRot 已烧进 [bmp] 的 EXIF 旋转角
 * @param path    本地文件路径,放大时按区域重解高清块用
 * @param origW/origH 文件坐标系(未旋转)的原图尺寸
 */
class Decoded(
    val bmp: Bitmap,
    val actual: Float,
    val exifRot: Int,
    val path: String,
    val origW: Int,
    val origH: Int,
)

internal fun decodeImage(ctx: Context, file: XFile): Decoded? {
    val local = if (file.scheme == "file") File(file.path) else OpenFiles.materialize(ctx, file)
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(local.absolutePath, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    val ow = opts.outWidth
    val oh = opts.outHeight
    val rot = exifDegrees(local.absolutePath)

    // ★ 目标尺寸按"FIT 铺满屏幕真正需要多少像素"算,不是拿屏幕长边一刀切。
    // EXIF 旋转后的朝向才是显示朝向;autoFit 还可能再转 90°,两种朝向取需求大的那个,
    // 否则转过去就得插值放大(见 screenFitRotation)。
    val swapped = rot == 90 || rot == 270
    val dw = if (swapped) oh else ow
    val dh = if (swapped) ow else oh
    val dm = ctx.resources.displayMetrics
    val sw = dm.widthPixels.toFloat()
    val sh = dm.heightPixels.toFloat()
    val need = maxOf(
        minOf(sw / dw, sh / dh), // 按当前朝向铺满
        minOf(sw / dh, sh / dw), // autoFit 转 90° 后铺满
    ).coerceAtMost(1f) // 原图比屏幕还小就按原样解,不放大

    // inSampleSize 只能取 2 的幂,取"不小于目标"的那一档,余下的零头交给密度缩放——
    // 只靠 2 的幂会最坏差一倍(如 4096 超限就退到 2048,够不着屏幕要的 2465),
    // 底图不够用会逼着高清块在 FIT 档就介入,那一块比底图本身还贵。
    var sample = 1
    while (need * (sample * 2) <= 1f) sample *= 2
    val targetW = (ow * need).roundToInt().coerceAtLeast(1)

    // 单张可达数十 MB,内存不够就退一档再来——总比整张显示不出来强
    var bmp: Bitmap? = null
    while (sample <= 64) {
        val s = sample
        val afterW = ow / s
        try {
            bmp = BitmapFactory.decodeFile(
                local.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = s
                    if (targetW < afterW) { // 2 的幂降完仍大于目标,用密度缩放收到精确尺寸
                        inScaled = true
                        inDensity = afterW
                        inTargetDensity = targetW
                    }
                },
            )
            break // 返回 null 是格式问题,重试没用
        } catch (_: OutOfMemoryError) {
            sample *= 2
        }
    }
    if (bmp == null) return null
    // ★ inDensity/inTargetDensity 是拿像素数当"密度"用的,BitmapFactory 会把 inTargetDensity
    // 写进位图的 density 字段;BitmapDrawable 随后按"位图 density : 画布 density"再缩一次,
    // 图就变成左上角一个小缩略图。解完必须把 density 改回屏幕的(Coil 同样有这一步)。
    bmp.density = dm.densityDpi
    // 位图→原图倍数:密度缩放后不再等于 inSampleSize,按实际宽度比算(旋转会交换宽高,得先算)
    val actual = ow.toFloat() / bmp.width
    if (rot != 0) bmp = rotate(bmp, rot)
    return Decoded(bmp, actual, rot, local.absolutePath, ow, oh)
}

/**
 * [rect] 是**显示位图**坐标系(已含 [rot] 旋转、已降采样 d.actual 倍)里的矩形;
 * 要先按 [rot] 反变换回文件坐标系才能喂 BitmapRegionDecoder,解出来的块再正着转回去。
 */
internal fun decodeRegion(d: Decoded, rot: Int, rect: RectF, scale: Float, vw: Int, vh: Int): Bitmap? {
    val s = d.actual
    val l = (rect.left * s).toInt(); val t = (rect.top * s).toInt()
    val r = (rect.right * s).toInt(); val bo = (rect.bottom * s).toInt()
    val ow = d.origW; val oh = d.origH
    // 文件坐标 (X,Y) --顺时针 rot--> 显示坐标 (x,y) 的逆映射
    val src = when (rot) {
        90 -> Rect(t, oh - r, bo, oh - l)
        180 -> Rect(ow - r, oh - bo, ow - l, oh - t)
        270 -> Rect(ow - bo, l, ow - t, r)
        else -> Rect(l, t, r, bo)
    }
    if (!src.intersect(0, 0, ow, oh) || src.width() < 1 || src.height() < 1) return null

    // 区块自身也降采样:解到屏幕上约 1:1 就够。★ 不能拿块尺寸跟视图宽高分别比再取 &&
    // ——块与视图长宽比不同时,任一边先小于视图就停止降采样,scale 刚过 1(可视区域几乎
    // 是整张图)时会把整张原图解进内存,几十 MB 起,再 rotate 一次翻倍,必 OOM。
    // 原图像素密度是屏幕的 s/scale 倍,rs 照这个取 2 的幂即可,与宽高比无关。
    var rs = 1
    while (rs * 2 <= s / scale) rs *= 2
    // 兜底:rs 向下取幂后块最大仍可达 4 倍视野,加上外扩余量会更大,按总像素数硬限。
    // ★ 不能只写成"几屏"——4K 屏(1644×3840)上两屏就是 12.6M 像素 = 50MB,闸门形同虚设
    val maxPx = minOf(vw.toLong() * vh * MAX_HIRES_SCREENS, MAX_HIRES_PX)
    while ((src.width().toLong() / rs) * (src.height() / rs) > maxPx) rs *= 2
    // 被上限推到不比基础位图更密了,这块就是白解——省下这次内存与解码
    if (rs >= s) return null

    @Suppress("DEPRECATION")
    val dec = runCatching { BitmapRegionDecoder.newInstance(d.path, false) }.getOrNull() ?: return null
    val piece = try {
        // OOM 不能只靠外层 runCatching 吞:堆被这一大块占住后,崩的往往是主线程别处的分配
        var out: Bitmap? = null
        while (rs <= 64) {
            val cur = rs
            try {
                out = dec.decodeRegion(src, BitmapFactory.Options().apply { inSampleSize = cur })
                break
            } catch (_: OutOfMemoryError) {
                rs *= 2
            } catch (_: Throwable) {
                break // 区域非法/格式不支持,重试无用
            }
        }
        out
    } finally {
        runCatching { dec.recycle() }
    } ?: return null
    if (rot == 0) return piece
    return try { rotate(piece, rot) } catch (_: OutOfMemoryError) { null } // 旋转要再复制一份
}

internal fun exifDegrees(path: String): Int = runCatching {
    when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}.getOrDefault(0)

internal fun rotate(bmp: Bitmap, deg: Int): Bitmap {
    val mtx = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mtx, true)
}
