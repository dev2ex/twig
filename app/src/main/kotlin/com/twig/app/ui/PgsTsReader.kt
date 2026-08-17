package com.twig.app.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * 蓝光 PGS(Presentation Graphic Stream,图形位图字幕)在 TS 层的切样器——media3 官方
 * 压根没写这个(`DefaultTsPayloadReaderFactory` 的 switch 语句里没有 HDMV PGS 的
 * stream_type 0x90 分支),只支持了 DVB 字幕(`DvbSubtitleReader`,不同的欧洲广播标准)。
 * PGS 的**位图解码**本身 media3 有(`androidx.media3.extractor.text.pgs.PgsParser`,
 * MKV 走它),缺的只是 TS 层"怎么把 PES 包切成它认的样本"这一步,这里补上。
 *
 * 关键前提(用 `ffprobe`/`mediainfo` + 官方 BD-ROM PG 文档核对过):**M2TS 里一个 PES
 * 包正好装一个完整 PGS segment**(1 字节 segment_type + 2 字节 length + payload),
 * 时间戳由 PES 包自己的 PTS 给,不像离线 .sup 文件那样每个 segment 前面还带一份
 * 冗余的 'PG' magic + PTS + DTS(那是 .sup 这种非复用格式自己发明的、为了脱离容器
 * 保留时间信息)。[PgsParser.parse] 期望的输入是**一整个 Display Set**(PCS→WDS→
 * PDS(可选多个)→ODS(可选多个)→END,序列内挨个 [type][len][payload] 拼起来,
 * 不带 magic/PTS/DTS)喂一次——它每次 parse() 调用开头都会 reset 内部状态,所以
 * 单个 segment 单独喂永远拼不出完整的位图。这里按 PES 包累积,直到看到某个 segment
 * 的 type 是 END(0x80)才把攒的这一串通过 `TrackOutput.sampleMetadata` 整体吐出
 * 一个样本(时间戳取这组里第一个 PES 包的 PTS,即 PCS 段的时间)。
 */
@UnstableApi
class PgsTsReader(private val language: String?) : ElementaryStreamReader {

    private lateinit var output: TrackOutput
    private var started = false
    private var pesTimeUs = C.TIME_UNSET
    private var sampleTimeUs = C.TIME_UNSET
    private var sampleBytesWritten = 0

    // 跨 consume() 调用的 segment 边界状态(见 [consume] 的注释:一次 consume 只给一个
    // TS 包的载荷,不是一整个 PES 包,所以边界必须自己攒着算)
    private val header = ByteArray(3)
    private var headerBytes = 0
    private var segmentBytesLeft = 0
    private var sawEnd = false

    override fun seek() {
        started = false
        pesTimeUs = C.TIME_UNSET
        sampleTimeUs = C.TIME_UNSET
        sampleBytesWritten = 0
        headerBytes = 0
        segmentBytesLeft = 0
        sawEnd = false
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        output.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(language)
                .build(),
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        // 只记住"当前这个 PES 包的时间戳";样本从哪一组开始、拿哪个 PTS 由 [consume]
        // 按 segment 边界决定(一组可能跨几个 PES,一个 PES 也可能装着好几组)。
        this.pesTimeUs = pesTimeUs
        started = true
    }

    override fun consume(data: ParsableByteArray) {
        if (!started) return
        // ★ 一次 consume() 拿到的是**一个 TS 包**的载荷(≤184 字节),不是一整个 PES 包
        //   ——`PesReader` 是边收边喂的。所以不能像早期实现那样"每次 consume 都把第一个
        //   字节当 segment_type 记下来":大位图(ODS)要跨几十上百个 TS 包,那些 chunk
        //   的首字节是位图数据,值恰好是 0x80 的概率约 1/256,一个 20KB 的 ODS 撞上
        //   假 END 的概率就上三成——样本被从中间截断吐出去(字幕解不出/花),剩下半截
        //   又自成一个样本(解出空,把整屏字幕擦掉一瞬)。移动字幕 Display Set 发得密,
        //   这两件事都被放大成肉眼可见的"闪 + 时有时无"。
        //   这里改成真正按 [type][len][payload] 走 segment 边界(状态跨 consume/PES 保留),
        //   只有解析到 type==END 的**段头**才算这组结束,并且**当场**就把样本切出去
        //   ——不等 packetFinished:这样"一个样本 = 恰好一个 Display Set"是硬保证,
        //   不管封装是一个 PES 一个 segment、还是整组(甚至连着几组)打在一个 PES 里,
        //   [PgsSubtitleParser] 那边都能按"一次 parse 一组"解。
        val buf = data.data
        while (data.bytesLeft() > 0) {
            // 一组的时间戳 = 它第一个字节所在 PES 包的 PTS(跨 PES 的组沿用第一个,
            // 一个 PES 里的第二组只能沿用同一个 PTS——它没有别的可给)
            if (sampleBytesWritten == 0) sampleTimeUs = pesTimeUs
            val start = data.position
            val end = start + data.bytesLeft()
            var i = start
            var boundary = -1
            while (i < end) {
                if (segmentBytesLeft > 0) {
                    val skip = minOf(segmentBytesLeft, end - i)
                    i += skip
                    segmentBytesLeft -= skip
                } else {
                    header[headerBytes++] = buf[i++]
                    if (headerBytes == 3) {
                        headerBytes = 0
                        val type = header[0].toInt() and 0xFF
                        segmentBytesLeft = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                        if (type == SEGMENT_TYPE_END) sawEnd = true
                    }
                }
                // END 段收完整(段头收全 + payload 收完)= 这一组到此为止
                if (sawEnd && headerBytes == 0 && segmentBytesLeft == 0) { boundary = i; break }
            }
            // 真正写进样本的是完整字节(含段头本身,解析器就是从那个字节开始逐段解析的)
            val n = (if (boundary >= 0) boundary else end) - start
            if (n > 0) {
                output.sampleData(data, n)
                sampleBytesWritten += n
            }
            if (boundary < 0) return // 这组还没完,等后续 TS 包/PES 包接着攒
            output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null)
            sampleBytesWritten = 0
            sawEnd = false
        }
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        // 正常情况在 [consume] 里按 END 段当场切好了;这里只兜最后一包没有 END 的残尾。
        if (isEndOfInput && sampleBytesWritten > 0) {
            output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null)
            sampleBytesWritten = 0
            headerBytes = 0
            segmentBytesLeft = 0
            sawEnd = false
        }
    }

    companion object {
        private const val SEGMENT_TYPE_END = 0x80
    }
}
