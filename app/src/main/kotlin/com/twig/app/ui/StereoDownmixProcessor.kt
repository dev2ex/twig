package com.twig.app.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 多声道(>2)16-bit PCM 降混为立体声。
 *
 * ffmpeg 软解 5.1/7.1 输出 6/8 声道 PCM 时,部分设备(如 Xperia)的 AudioTrack
 * 会反复 dead IAudioTrack → write failed -6,ExoPlayer 不停按可恢复错误重建,
 * 表现为"播一秒停一下"。手机输出本就是立体声,解码后先降混一劳永逸。
 *
 * 声道顺序按 ffmpeg/ANDROID 默认布局:FL FR FC (LFE) (BC/BL BR) (SL SR)。
 */
@UnstableApi
class StereoDownmixProcessor : BaseAudioProcessor() {

    private var coef: Array<FloatArray> = emptyArray() // [输入声道][L,R] 系数

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val ch = inputAudioFormat.channelCount
        if (ch <= 2) return AudioProcessor.AudioFormat.NOT_SET // 单/双声道不处理
        coef = buildCoef(ch)
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = coef.size
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = inBuf.remaining() / (2 * ch)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4)
        repeat(frames) {
            var l = 0f
            var r = 0f
            for (i in 0 until ch) {
                val s = inBuf.short.toFloat()
                l += s * coef[i][0]
                r += s * coef[i][1]
            }
            out.putShort(l.toInt().coerceIn(-32768, 32767).toShort())
            out.putShort(r.toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    private fun buildCoef(ch: Int): Array<FloatArray> {
        val a = 0.7071f // -3dB
        val c = Array(ch) { FloatArray(2) }
        c[0][0] = 1f // FL → L
        c[1][1] = 1f // FR → R
        when (ch) {
            3 -> c[2].fill(a) // FC
            4 -> { c[2].fill(a); c[3].fill(a) } // FC + BC(或 quad 后置,近似)
            5 -> { c[2].fill(a); c[3][0] = a; c[4][1] = a } // FC BL BR
            6 -> { c[2].fill(a); /* LFE 略 */ c[4][0] = a; c[5][1] = a } // 5.1
            7 -> { c[2].fill(a); c[4].fill(0.5f); c[5][0] = a; c[6][1] = a } // 6.1: FC _ BC SL SR
            else -> { // 7.1: FC _ BL BR SL SR
                c[2].fill(a)
                c[4][0] = a; c[5][1] = a
                if (ch >= 8) { c[6][0] = a; c[7][1] = a }
            }
        }
        // 归一化防削波:让 L/R 系数和为 1
        val sumL = c.sumOf { it[0].toDouble() }.toFloat()
        val sumR = c.sumOf { it[1].toDouble() }.toFloat()
        val g = 1f / maxOf(sumL, sumR, 1f)
        for (row in c) { row[0] *= g; row[1] *= g }
        return c
    }
}
