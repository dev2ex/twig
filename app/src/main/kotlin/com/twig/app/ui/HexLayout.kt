package com.twig.app.ui

/**
 * 十六进制查看器里几个不碰 View 的纯函数:每行摆几个字节、偏移列几位、搜索框里的
 * 十六进制怎么解析。单独拎出来是为了能在普通 JVM 单测里直接验——版式一旦算错,
 * 屏幕上表现为"莫名其妙横向滚动"或"右边空一大条",肉眼很难反推是哪一项多算了。
 */
object HexLayout {

    /** 偏移列的十六进制位数:够表示整个文件即可,小文件不必顶着 8 位占地方。 */
    fun offsetDigits(size: Long): Int {
        var d = 6
        while (d < 16 && size > (1L shl (4 * d)) - 1) d += 2
        return d
    }

    /** 两字节一组,**组内**那个空格缩到这么宽(见 [bytesPerRow] 的字符预算)。 */
    const val PAIR_GAP = 0.45f

    /**
     * 一行摆几个字节。字符预算(等宽字体,单位=一个字符宽):
     *
     * ```
     * 偏移 offDigits 位 │ 1 格 │ 每字节 hex 2 格 + 组内 PAIR_GAP / 组间 1 格 │ 1 格 │ 每字节 1 格字符
     * ```
     *
     * 两字节一组,于是每 2 字节多一个窄间隙、少一个整空格,摊到每字节头上是
     * `2 + 1 + (PAIR_GAP + 1) / 2` 格;固定开销只有 `offDigits + 1`——偏移后一格、
     * 字符列前一格,再减掉末组后面不存在的那个组间空格,正好抵掉一格。
     *
     * 取 2 的整数倍,免得末组只剩半组。分割线那几个像素由调用方先从 [usablePx] 里扣掉。
     */
    fun bytesPerRow(usablePx: Float, charW: Float, offDigits: Int, min: Int = 4, max: Int = 64): Int {
        if (charW <= 0f) return min
        val cols = usablePx / charW
        val perByte = 2f + 1f + (PAIR_GAP + 1f) / 2f
        val fit = ((cols - offDigits - 1f) / perByte).toInt().coerceIn(min, max)
        return (fit / 2 * 2).coerceAtLeast(min)
    }

    private val C_PREFIX = Regex("0[xX]")

    /**
     * 搜索框里的十六进制:忽略空格/逗号等一切非十六进制字符,奇数位丢掉最后半个字节。
     * `0x` 前缀要**先整对去掉**——只做"筛掉非十六进制字符"的话,`0x4D` 里那个 `0`
     * 会当成一个货真价实的半字节留下来,整串从此错位(粘出来的字节序列常带这前缀)。
     */
    fun parseHex(s: String): ByteArray {
        val digits = C_PREFIX.replace(s, "").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val n = digits.length / 2
        return ByteArray(n) {
            ((digits[it * 2].digitToInt(16) shl 4) or digits[it * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
