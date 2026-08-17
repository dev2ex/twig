package com.twig.app.ui

import android.net.Uri
import android.util.Log
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.DtsReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.RandomAccessFile

/** 标准 TS 包大小(字节)。 */
const val TS_PACKET_SIZE = 188

/** 真 BDAV M2TS 的原始包大小(字节)——4 字节时间戳前缀 + 188 字节标准 TS 包。 */
const val RAW_M2TS_PACKET_SIZE = 192

/**
 * 真正的 M2TS(蓝光 BDAV 封装)每 192 字节一个包——4 字节时间戳前缀 + 188 字节标准
 * TS 包,不是 media3 `TsExtractor` 硬编码假设的纯 188 字节流(它的 sniff/read 都按
 * 固定 188 步进找同步字节,对不上这个布局直接判"无法识别容器")。这里边读边剥掉每
 * 个包开头那 4 字节,喂给标准 TsExtractor 一条干净的 188 字节流;本地/网络来源通用
 * (包一层任意 [DataSource])。
 */
@UnstableApi
class M2tsStrippingDataSource(
    private val upstream: DataSource,
    /** 底层原始文件总字节数(192 字节/包那份,未剥前缀前)。 */
    private val totalRawLength: Long,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var strippedLeft = 0L
    private var posInBlock = 0 // 当前在 192 字节包内的位置,PREFIX..RAW_M2TS_PACKET_SIZE
    private var opened = false
    // 每跨一个包边界就要跳 4 字节前缀,一次大读(比如 seek 探测的 ~110KB)要跨几百次
    // 边界——复用这个小缓冲区,不要每次跳前缀都 ByteArray(PREFIX) 现分配一个,不然
    // 分配速率高到把 GC 打爆(实测这是 seek 慢、缩略图超时的真正原因,不是网络慢)。
    private val skipBuf = ByteArray(PREFIX)
    private var openAtMs = 0L
    private var sessionBytesRead = 0L
    private var sessionReadCalls = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val block = dataSpec.position / TS_PACKET_SIZE
        val offsetInBlock = (dataSpec.position % TS_PACKET_SIZE).toInt()
        val rawStart = block * RAW_M2TS_PACKET_SIZE + PREFIX + offsetInBlock
        posInBlock = PREFIX + offsetInBlock
        openAtMs = android.os.SystemClock.elapsedRealtime()
        sessionBytesRead = 0L
        sessionReadCalls = 0
        Log.d("twig", "m2ts: open pos=${dataSpec.position} rawStart=$rawStart len=${dataSpec.length}")
        upstream.open(DataSpec.Builder().setUri(dataSpec.uri).setPosition(rawStart).build())
        Log.d("twig", "m2ts: upstream.open done in ${android.os.SystemClock.elapsedRealtime() - openAtMs}ms")
        val strippedTotal = (totalRawLength / RAW_M2TS_PACKET_SIZE) * TS_PACKET_SIZE
        strippedLeft = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
        else (strippedTotal - dataSpec.position).coerceAtLeast(0)
        opened = true
        transferStarted(dataSpec)
        return strippedLeft
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (strippedLeft <= 0L) return C.RESULT_END_OF_INPUT
        // 单次调用内部循环尽量填满调用方要的 length,不是每次只吐一个包剩下的
        // 那点字节——TsBinarySearchSeeker 的 seek 探测一次请求 ~110KB,192 字节
        // 一包的话每次调用只回 188 字节,逼调用方为一次探测连续调几百次 read()。
        // 减少外层调用次数只是顺手优化;seek 慢真正的根因是 skipBuf 那个字段
        // 没加之前每跨一个包边界就 ByteArray(4) 现分配一个数组,分配速率高到把
        // GC 打爆(实测 logcat 里整整一分钟持续 "Waiting for a blocking GC")。
        var written = 0
        while (written < length && strippedLeft > 0L) {
            if (posInBlock >= RAW_M2TS_PACKET_SIZE) {
                // 跳过下一个包开头的 4 字节时间戳前缀,复用 skipBuf 不现分配
                var skipped = 0
                while (skipped < PREFIX) {
                    val n = upstream.read(skipBuf, skipped, PREFIX - skipped)
                    if (n <= 0) return if (written > 0) written else C.RESULT_END_OF_INPUT
                    skipped += n
                }
                posInBlock = PREFIX
            }
            val remainInBlock = RAW_M2TS_PACKET_SIZE - posInBlock
            val toRead = minOf((length - written).toLong(), remainInBlock.toLong(), strippedLeft).toInt()
            val n = upstream.read(buffer, offset + written, toRead)
            if (n <= 0) return if (written > 0) written else C.RESULT_END_OF_INPUT
            posInBlock += n
            strippedLeft -= n
            written += n
            bytesTransferred(n)
            // 上游一次没给够整包剩余部分(常见于网络流式读,数据还没到齐)就先
            // 返回,避免在这里死等——调用方会再调 read() 继续,语义上仍然安全。
            if (n < toRead) break
        }
        sessionBytesRead += written
        sessionReadCalls++
        return if (written > 0) written else C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val elapsed = android.os.SystemClock.elapsedRealtime() - openAtMs
        Log.d(
            "twig",
            "m2ts: close after ${elapsed}ms, read $sessionBytesRead bytes in $sessionReadCalls calls",
        )
        runCatching { upstream.close() }
        if (opened) { opened = false; transferEnded() }
    }

    companion object {
        private const val PREFIX = RAW_M2TS_PACKET_SIZE - TS_PACKET_SIZE
    }
}

/**
 * 剥完前缀喂给 [TsExtractor] 用的 [ExtractorsFactory]:显式开
 * [DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS]——蓝光
 * M2TS 的音轨常是 HDMV DTS(DTS-HD/DTS-X 那个 stream_type),默认工厂不开
 * 这个 flag 就直接跳过不建轨道。开完这个 flag 还是少一条最高质量的主音轨:
 * 实测一个 DTS-X 7.1 remux,8 声道那条主音轨用的 stream_type 0x86——这个值在
 * ATSC/DVB 标准注册表里是 SCTE-35 插播信令,但蓝光/HDMV 私有命名空间里复用给了
 * DTS-HD Master Audio,两边撞了同一个字节值。media3 只认标准注册表那个含义,
 * 把这条音轨的数据当 SCTE-35 直接吞掉,从没建出轨道。这个 app 只播本地文件,
 * 用不上真 SCTE-35 信令,[BluRayTsPayloadReaderFactory] 把 0x86 按 DTS-HD 处理
 * 更符合实际场景。
 */
@UnstableApi
fun newM2tsExtractorsFactory(): ExtractorsFactory = ExtractorsFactory {
    arrayOf<Extractor>(
        TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            /* extractorFlags= */ 0, // 不带 FLAG_EMIT_RAW_SUBTITLE_DATA——见下方 subtitleParserFactory 的注释
            // 新版 TextRenderer 不再支持"原始字幕样本 + legacy SubtitleDecoder"这条路
            // (`IllegalStateException: Legacy decoding is disabled`),必须在抽取阶段
            // 就用真正的 SubtitleParser.Factory 把样本转成 application/x-media3-cues,
            // 传 UNSUPPORTED 会在 renderer 里直接崩。[TwigSubtitleParserFactory] 认得
            // application/pgs(对应 [PgsTsReader] 产出的样本),转给 [PgsSubtitleParser]
            // ——官方 PgsParser 一组 Display Set 只出得来一个 Cue,一屏多块字幕会漏。
            TwigSubtitleParserFactory(),
            TimestampAdjuster(0),
            BluRayTsPayloadReaderFactory(),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        ),
    )
}

/**
 * 探测 .m2ts 文件的真实包大小([TS_PACKET_SIZE] 还是 [RAW_M2TS_PACKET_SIZE])——
 * 有些工具把普通 188 字节 TS 流也存成 .m2ts 后缀,不能看后缀就假设要剥前缀。
 * [shared] 为 null 表示本地文件,直接按 [file] 的路径读。
 */
fun detectM2tsPacketSize(file: XFile, shared: RandomSource?): Int {
    val head = ByteArray(RAW_M2TS_PACKET_SIZE * 8)
    val headLen = if (shared != null) {
        shared.readAt(0, head, 0, head.size)
    } else {
        RandomAccessFile(file.path, "r").use { it.read(head) }
    }.coerceAtLeast(0)

    // 检查 0x47 同步字节是否连续对齐。真 BDAV M2TS 连第一个包前面也带 4 字节时间戳
    // 前缀(同步字节落在 offset=4,不是 0)——踩过一次坑,别想当然认为流从字节 0
    // 就是同步字节。
    fun aligned(size: Int, offset: Int): Boolean {
        if (headLen < offset + size * 4) return false
        var pos = offset
        var count = 0
        while (pos < headLen && count < 8) {
            if (head[pos] != 0x47.toByte()) return false
            pos += size
            count++
        }
        return count >= 4
    }
    val prefix = RAW_M2TS_PACKET_SIZE - TS_PACKET_SIZE
    return if (aligned(RAW_M2TS_PACKET_SIZE, prefix)) RAW_M2TS_PACKET_SIZE else TS_PACKET_SIZE
}

/**
 * 委托给 [DefaultTsPayloadReaderFactory],但额外处理三种它不认的蓝光私有 stream_type:
 * - 0x86:HDMV 命名空间复用给 DTS-HD Master Audio,和标准注册表的 SCTE-35 撞车——
 *   见 [newM2tsExtractorsFactory] 的注释。
 * - 0x90:HDMV PGS(图形位图字幕),官方 media3 压根没写 TS 层的切样器,见
 *   [PgsTsReader] 的注释。
 * - 0x83:蓝光 TrueHD/Atmos 音轨。media3 整个 extractor.ts 包里**没有任何 TrueHD/MLP
 *   的 ElementaryStreamReader**(不是漏了个 case,是压根没实现过),真要完整支持
 *   得自己写 MLP 帧同步器,工作量和写编解码器差不多。退而求其次:蓝光规范要求每条
 *   TrueHD 流都内嵌一份能向下兼容的 AC-3 core(5.1,给不支持 TrueHD 的设备用)—— 用
 *   [Ac3Reader] 去这条流里扫同步字,能跳过中间穿插的 MLP 帧、只挑出真正的 AC-3 核心
 *   帧解出来。放弃了 Atmos 的沉浸声道和 TrueHD 的无损,换来能出声(总比整条音轨从
 *   PMT 阶段就被丢掉、一点声音都没有强)。
 */
@UnstableApi
private class BluRayTsPayloadReaderFactory(
    private val delegate: TsPayloadReader.Factory =
        DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS),
) : TsPayloadReader.Factory {

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = delegate.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        val reader = when (streamType) {
            STREAM_TYPE_HDMV_DTS_HD_MA ->
                // DtsReader.EXTSS_HEADER_SIZE_MAX 是 media3 包内可见(package-private),
                // 外部拿不到,这里按其源码里的值(4096)直填。
                PesReader(DtsReader(esInfo.language, esInfo.getRoleFlags(), 4096, MimeTypes.VIDEO_MP2T))
            STREAM_TYPE_HDMV_PGS -> PesReader(PgsTsReader(esInfo.language))
            STREAM_TYPE_HDMV_TRUEHD -> PesReader(Ac3Reader(esInfo.language, esInfo.getRoleFlags(), MimeTypes.VIDEO_MP2T))
            else -> delegate.createPayloadReader(streamType, esInfo)
        }
        // media3 内置的部分 reader 对畸形输入没做防御,实测撞过 Ac3Util.parseAc3
        // SyncframeInfo 数组越界(某些remux 的 AC3 帧头 frmsizecod 超出标准范围)——
        // 这种异常发生在提取线程,不是解码失败那种能靠事后换 renderer 补救的范畴,
        // 原样传播会直接把整个播放器拖崩。包一层防御,坏一个包不至于坏一整条播放。
        return reader?.let { SafeTsPayloadReader(it) }
    }

    companion object {
        private const val STREAM_TYPE_HDMV_DTS_HD_MA = 0x86
        private const val STREAM_TYPE_HDMV_PGS = 0x90
        private const val STREAM_TYPE_HDMV_TRUEHD = 0x83
    }
}

/**
 * 包一层防御性 try-catch:[delegate] 抛的任何运行时异常(比如内置 Ac3Reader 解析畸形
 * AC3 帧头数组越界、SectionReader 解析畸形 SCTE-35 越界)都吞掉、丢弃这一整包数据,
 * 再调 [TsPayloadReader.seek] 让被包装的 reader 复位内部状态、接着解后面的包——好过
 * 直接把异常原样往上抛、拖崩整个播放器。
 */
@UnstableApi
private class SafeTsPayloadReader(private val delegate: TsPayloadReader) : TsPayloadReader {
    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) = delegate.init(timestampAdjuster, extractorOutput, idGenerator)

    override fun seek() = delegate.seek()

    override fun consume(data: ParsableByteArray, flags: Int) {
        try {
            delegate.consume(data, flags)
        } catch (e: Exception) {
            Log.w("twig", "player: TsPayloadReader consume failed, dropping packet: $e")
            runCatching { delegate.seek() }
        }
    }
}
