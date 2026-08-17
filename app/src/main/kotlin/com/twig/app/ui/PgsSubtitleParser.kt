package com.twig.app.ui

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 自己实现的 PGS(蓝光图形位图字幕)解析器,替换 media3 官方
 * `androidx.media3.extractor.text.pgs.PgsParser`——**官方那个一个 Display Set 只出得来
 * 一个 Cue**,一屏同时有多块字幕(说话人字幕 + 画面注释、上下两块、左右分栏)时只显示其中一块。
 *
 * 官方实现的具体缺口(1.9.0 反编译核对过):
 * - `CueBuilder` 只有一组 `bitmapX/bitmapY/bitmapWidth/bitmapHeight` + 一块 `bitmapData`;
 *   PCS(Presentation Composition Segment)里 `number_of_composition_objects` 之后**只读第一个
 *   composition object 的坐标**(源码是 `skipBytes(11)` 硬跳过 object_id/window_id/cropped_flag),
 *   后面的对象连位置都不解析。
 * - 多个 ODS(Object Definition Segment)会互相覆盖:第二个 ODS 一来就把 `bitmapData` reset 掉、
 *   宽高改写,于是**最后一个对象的位图**配上**第一个对象的坐标**画出来,只剩一块。
 * - 每次 `parse()` 开头 `cueBuilder.reset()`,不保留 epoch 状态,所以"复用上一组对象、只更新调色板"
 *   的 Display Set(淡入淡出常用)会解出空,字幕直接闪没。
 *
 * 这里按 BD-ROM PG 规范完整实现:一个 Display Set → **每个 composition object 一个 Cue**;
 * 对象(ODS)与调色板(PDS)按 epoch 缓存,Epoch Start 才清空,因此纯调色板更新的 Display Set
 * 也能正常出图。坐标/尺寸语义与官方一致(相对 plane 的比例 + 左上角锚点),
 * [BitmapCueView] 那边不用改渲染语义。
 *
 * 输入是一整个 Display Set(PCS→WDS→PDS*→ODS*→END,挨个 `[type][len][payload]` 拼接),
 * 由 [PgsTsReader](M2TS)或 MatroskaExtractor(MKV)按容器切好后喂进来。
 */
@UnstableApi
class PgsSubtitleParser : SubtitleParser {

    /**
     * ODS 累积中的对象:RLE 原始字节(可能跨多个 ODS 分片),解码延后到出 Cue 时按当前调色板做。
     * 解出来的位图连同"用的哪版调色板"一起留着:移动字幕是**同一个对象换坐标**连发几十上百组
     * Display Set,每组重解一次几十万像素的 RLE 纯属白烧 CPU,还让 [BitmapCueView] 的降采样
     * 缓存(按位图对象身份存)次次落空 → 掉帧、看着就是"闪"。
     */
    private class PgsObject(val width: Int, val height: Int) {
        val rle = ByteArrayOutputStream()
        var complete = false
        var bitmap: Bitmap? = null
        var bitmapPaletteId = -1
        var bitmapPaletteRev = -1
        // 裁切窗结果也留一份:滚动/移动字幕的另一种做法就是对象不动、只挪裁切窗
        var cropSrc: Bitmap? = null
        var cropX = -1
        var cropY = -1
        var cropBitmap: Bitmap? = null
    }

    /** PCS 里的一个 composition object:引用某个 ODS,给出它在画面上的落点(可带裁切窗)。 */
    private class Composition(val objectId: Int, val x: Int, val y: Int, val crop: IntArray?)

    private val buffer = ParsableByteArray()
    private val inflatedBuffer = ParsableByteArray()
    private var inflater: Inflater? = null

    // ---- epoch 状态(跨 Display Set 保留,Epoch Start / seek 时清空)----
    private val objects = HashMap<Int, PgsObject>()
    private val palettes = HashMap<Int, IntArray>()
    /** 调色板内容改一次 +1,用来判断对象缓存的位图还能不能接着用(淡入淡出会连改) */
    private val paletteRevs = HashMap<Int, Int>()
    private var planeWidth = 0
    private var planeHeight = 0
    private var pendingObjectId = -1
    private var pendingObject: PgsObject? = null

    // ---- 当前 Display Set 状态 ----
    private val compositions = ArrayList<Composition>()
    private var activePaletteId = 0
    private var sawPresentation = false

    override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun reset() {
        objects.clear()
        palettes.clear()
        paletteRevs.clear()
        compositions.clear()
        pendingObjectId = -1
        pendingObject = null
        planeWidth = 0
        planeHeight = 0
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        buffer.reset(data, offset + length)
        buffer.setPosition(offset)
        // MKV 里的 PGS 轨可能整体 zlib 压缩(官方 PgsParser 也做这一步,保持行为一致)
        val inf = inflater ?: Inflater().also { inflater = it }
        if (Util.maybeInflate(buffer, inflatedBuffer, inf)) {
            buffer.reset(inflatedBuffer.data, inflatedBuffer.limit())
        }
        compositions.clear()
        sawPresentation = false
        while (buffer.bytesLeft() >= 3) {
            val type = buffer.readUnsignedByte()
            val len = buffer.readUnsignedShort()
            if (len > buffer.bytesLeft()) break
            val end = buffer.position + len
            when (type) {
                SEGMENT_PALETTE -> parsePalette(end)
                SEGMENT_OBJECT -> parseObject(end)
                SEGMENT_PRESENTATION -> parsePresentation(end)
            }
            buffer.setPosition(end)
        }
        // 一个样本正好一个 Display Set:解完整体吐一次。compositions 为空(PCS 里
        // number_of_composition_objects == 0)就是"清屏"指令,吐空列表把字幕擦掉。
        val cues = buildCues()
        // ★ 只有**读到了 PCS 的完整一组**才吐。截断/半截的样本(流损坏、seek 落在中间、
        //   容器切样切歪)解不出东西,这时候吐空列表等于把整屏字幕擦掉一瞬 —— 一块出问题
        //   全屏跟着闪。不吐 = 这个样本丢掉,屏幕保持上一组,肉眼无感。
        if (sawPresentation && (compositions.isEmpty() || cues.isNotEmpty())) {
            output.accept(CuesWithTiming(cues, C.TIME_UNSET, C.TIME_UNSET))
        }
    }

    /** PCS:画面尺寸 + epoch 控制 + 本组要显示哪些对象、各自落在哪。 */
    private fun parsePresentation(end: Int) {
        if (end - buffer.position < 11) return
        sawPresentation = true
        planeWidth = buffer.readUnsignedShort()
        planeHeight = buffer.readUnsignedShort()
        buffer.skipBytes(3) // frame_rate(1) + composition_number(2)
        val state = buffer.readUnsignedByte()
        if (state and COMPOSITION_STATE_EPOCH_START != 0) {
            // 新 epoch:之前缓存的对象/调色板全部作废
            objects.clear()
            palettes.clear()
            pendingObject = null
            pendingObjectId = -1
        }
        buffer.skipBytes(1) // palette_update_flag
        activePaletteId = buffer.readUnsignedByte()
        val count = buffer.readUnsignedByte()
        repeat(count) {
            if (end - buffer.position < 8) return
            val objectId = buffer.readUnsignedShort()
            buffer.skipBytes(1) // window_id
            val cropped = buffer.readUnsignedByte() and 0x80 != 0
            val x = buffer.readUnsignedShort()
            val y = buffer.readUnsignedShort()
            var crop: IntArray? = null
            if (cropped) {
                if (end - buffer.position < 8) return
                crop = intArrayOf(
                    buffer.readUnsignedShort(), buffer.readUnsignedShort(),
                    buffer.readUnsignedShort(), buffer.readUnsignedShort(),
                )
            }
            compositions.add(Composition(objectId, x, y, crop))
        }
    }

    /** PDS:调色板按 id 累积更新(没列出的条目沿用旧值,规范如此,淡入淡出靠这个)。 */
    private fun parsePalette(end: Int) {
        if (end - buffer.position < 2) return
        val id = buffer.readUnsignedByte()
        buffer.skipBytes(1) // palette_version
        val colors = palettes.getOrPut(id) { IntArray(256) }
        paletteRevs[id] = (paletteRevs[id] ?: 0) + 1
        while (end - buffer.position >= 5) {
            val index = buffer.readUnsignedByte()
            val y = buffer.readUnsignedByte()
            val cr = buffer.readUnsignedByte()
            val cb = buffer.readUnsignedByte()
            val a = buffer.readUnsignedByte()
            val r = (y + 1.40200 * (cr - 128)).toInt()
            val g = (y - 0.34414 * (cb - 128) - 0.71414 * (cr - 128)).toInt()
            val b = (y + 1.77200 * (cb - 128)).toInt()
            colors[index] = (a shl 24) or
                (Util.constrainValue(r, 0, 255) shl 16) or
                (Util.constrainValue(g, 0, 255) shl 8) or
                Util.constrainValue(b, 0, 255)
        }
    }

    /** ODS:位图对象定义,大对象会拆成多个 ODS 分片(首片带尺寸,末片带 last 标志)。 */
    private fun parseObject(end: Int) {
        if (end - buffer.position < 4) return
        val id = buffer.readUnsignedShort()
        buffer.skipBytes(1) // object_version
        val sequence = buffer.readUnsignedByte()
        if (sequence and SEQUENCE_FIRST != 0) {
            if (end - buffer.position < 7) return
            buffer.skipBytes(3) // object_data_length(含下面 4 字节宽高,用不上)
            val w = buffer.readUnsignedShort()
            val h = buffer.readUnsignedShort()
            if (w <= 0 || h <= 0 || w > MAX_DIMEN || h > MAX_DIMEN) { pendingObject = null; return }
            pendingObjectId = id
            pendingObject = PgsObject(w, h)
        } else if (pendingObjectId != id) {
            // 中途 seek 进来、首片没拿到——这个对象整条丢掉,别把碎片当完整位图
            return
        }
        val obj = pendingObject ?: return
        val n = end - buffer.position
        if (n > 0) {
            obj.rle.write(buffer.data, buffer.position, n)
            buffer.skipBytes(n)
        }
        if (sequence and SEQUENCE_LAST != 0) {
            obj.complete = true
            // 正常一个 epoch 里就一两个对象;真遇到长 epoch 不停定义新对象的流,别让缓存无限涨
            if (objects.size >= MAX_CACHED_OBJECTS && !objects.containsKey(id)) objects.clear()
            objects[id] = obj
            pendingObject = null
            pendingObjectId = -1
        }
    }

    /** 本组每个 composition object 出一个 Cue(官方实现只出得来一个,就差在这)。 */
    private fun buildCues(): List<Cue> {
        if (planeWidth <= 0 || planeHeight <= 0 || compositions.isEmpty()) return emptyList()
        val colors = palettes[activePaletteId] ?: return emptyList()
        val rev = paletteRevs[activePaletteId] ?: 0
        val cues = ArrayList<Cue>(compositions.size)
        for (c in compositions) {
            val obj = objects[c.objectId] ?: continue
            if (!obj.complete) continue
            var bitmap = decoded(obj, colors, rev) ?: continue
            val crop = c.crop
            if (crop != null) {
                // 裁切窗只挑对象里的一块出来;落点仍是 composition 给的 x/y(规范:说的就是裁切后那块的位置)
                val cx = crop[0].coerceIn(0, bitmap.width - 1)
                val cy = crop[1].coerceIn(0, bitmap.height - 1)
                val cw = crop[2].coerceIn(1, bitmap.width - cx)
                val ch = crop[3].coerceIn(1, bitmap.height - cy)
                bitmap = cropped(obj, bitmap, cx, cy, cw, ch)
            }
            cues.add(
                Cue.Builder()
                    .setBitmap(bitmap)
                    .setPosition(c.x.toFloat() / planeWidth)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(c.y.toFloat() / planeHeight, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize(bitmap.width.toFloat() / planeWidth)
                    .setBitmapHeight(bitmap.height.toFloat() / planeHeight)
                    .build(),
            )
        }
        return cues
    }

    /**
     * PGS 的行程编码(RLE)解码,规范里的五种码字:
     * `C`(非 0)= 1 个 C 色像素;`00 00` = 换行;`00 0L`= L 个透明;`00 4L LL` = LL 个透明;
     * `00 8L C` = L 个 C 色;`00 CL LL C` = LL 个 C 色。行内不足宽度的部分补透明。
     */
    /** 裁切结果按"源位图 + 裁切原点"复用,裁切窗没动就返回同一个位图对象(缓存才命中得上)。 */
    private fun cropped(obj: PgsObject, src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val prev = obj.cropBitmap
        if (prev != null && obj.cropSrc === src && obj.cropX == x && obj.cropY == y &&
            prev.width == w && prev.height == h
        ) {
            return prev
        }
        val out = Bitmap.createBitmap(src, x, y, w, h)
        obj.cropSrc = src
        obj.cropX = x
        obj.cropY = y
        obj.cropBitmap = out
        return out
    }

    private fun decoded(obj: PgsObject, colors: IntArray, paletteRev: Int): Bitmap? {
        val cached = obj.bitmap
        if (cached != null && obj.bitmapPaletteId == activePaletteId && obj.bitmapPaletteRev == paletteRev) {
            return cached
        }
        val bmp = decode(obj, colors)
        obj.bitmap = bmp
        obj.bitmapPaletteId = activePaletteId
        obj.bitmapPaletteRev = paletteRev
        return bmp
    }

    private fun decode(obj: PgsObject, colors: IntArray): Bitmap? {
        val w = obj.width
        val h = obj.height
        val data = obj.rle.toByteArray()
        val argb = IntArray(w * h)
        var index = 0
        var pos = 0
        while (pos < data.size && index < argb.size) {
            val first = data[pos++].toInt() and 0xFF
            var color: Int
            var run: Int
            if (first != 0) {
                color = colors[first]
                run = 1
            } else {
                if (pos >= data.size) break
                val second = data[pos++].toInt() and 0xFF
                if (second == 0) {
                    // 换行标记:规范要求每行都编满 w 个像素,所以这里**什么都不做**、像素序
                    // 继续线性往下走(ffmpeg pgssub 与 media3 都是这个行为)。别顺手写成
                    // "跳到下一行行首":编满的行会因此白白空掉一整行,整幅图隔行错位;而
                    // "只在没编满时补齐"也不行——只编了 `00 00` 的空行同样处在行首,两种情况
                    // 在这个位置根本区分不了。
                    continue
                }
                val longRun = second and 0x40 != 0
                run = if (longRun) {
                    if (pos >= data.size) break
                    ((second and 0x3F) shl 8) or (data[pos++].toInt() and 0xFF)
                } else {
                    second and 0x3F
                }
                color = if (second and 0x80 == 0) {
                    0 // 透明
                } else {
                    if (pos >= data.size) break
                    colors[data[pos++].toInt() and 0xFF]
                }
            }
            // 按线性像素序填(与 ffmpeg/media3 一致:码字不跨行是编码器的事,这里不猜、不重排)
            val to = minOf(index + run, argb.size)
            java.util.Arrays.fill(argb, index, to, color)
            index = to
        }
        return runCatching { Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
    }

    companion object {
        private const val SEGMENT_PALETTE = 0x14
        private const val SEGMENT_OBJECT = 0x15
        private const val SEGMENT_PRESENTATION = 0x16
        private const val COMPOSITION_STATE_EPOCH_START = 0x80
        private const val SEQUENCE_FIRST = 0x80
        private const val SEQUENCE_LAST = 0x40
        private const val MAX_DIMEN = 8192
        private const val MAX_CACHED_OBJECTS = 64
    }
}

/**
 * 把 PGS 交给 [PgsSubtitleParser],其余格式(SRT/ASS/DVB/CEA…)照旧走
 * [DefaultSubtitleParserFactory]。抽取阶段就要用它把样本转成 `application/x-media3-cues`
 * (新版 TextRenderer 不再支持 legacy 解码路径),所以每条构建 MediaSource 的路径
 * ——[MediaSources]、[newM2tsExtractorsFactory]、播放器默认 MediaSource 工厂——都要挂上。
 */
@UnstableApi
class TwigSubtitleParserFactory : SubtitleParser.Factory {

    private val delegate = DefaultSubtitleParserFactory()

    private fun isPgs(format: Format) =
        MimeTypes.APPLICATION_PGS.equals(format.sampleMimeType, ignoreCase = true)

    override fun supportsFormat(format: Format) = isPgs(format) || delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format) =
        if (isPgs(format)) Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        else delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        if (isPgs(format)) PgsSubtitleParser() else delegate.create(format)
}
