package com.twig.fs.archive

import java.io.InputStream
import java.util.zip.CRC32

/**
 * 传统 PKWARE 加密(俗称 ZipCrypto)的**解密**。老包与 Windows 资源管理器自带的
 * 「加密」用的都是它;算法早已被已知明文攻击攻破,所以这里**只读不写**——
 * Twig 新建的加密包一律走 [ZipAes]。
 *
 * 条目数据前 12 字节是加密头,解密后最后一字节应等于校验字节(有数据描述符时是修改
 * 时间高字节,否则是 CRC 高字节),据此判断密码对不对。
 */
internal class ZipCrypto(password: String) {

    private var key0 = 0x12345678
    private var key1 = 0x23456789
    private var key2 = 0x34567890

    init {
        for (b in password.toByteArray(Charsets.UTF_8)) updateKeys(b.toInt() and 0xff)
    }

    private fun updateKeys(c: Int) {
        key0 = crc32(key0, c)
        key1 += key0 and 0xff
        key1 = key1 * 134775813 + 1
        key2 = crc32(key2, key1 ushr 24)
    }

    private fun decryptByte(): Int {
        val temp = (key2 or 2) and 0xffff
        return ((temp * (temp xor 1)) ushr 8) and 0xff
    }

    /** 就地解密一段。 */
    fun decrypt(buf: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val c = (buf[i].toInt() and 0xff) xor decryptByte()
            updateKeys(c)
            buf[i] = c.toByte()
        }
    }

    companion object {
        const val HEADER_LEN = 12

        private const val POLY = 0xEDB88320.toInt()

        private val TABLE = IntArray(256).also { t ->
            for (i in 0 until 256) {
                var c = i
                // 标准 CRC-32 反射多项式 0xEDB88320
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor POLY else c ushr 1 }
                t[i] = c
            }
        }

        private fun crc32(crc: Int, b: Int): Int = (crc ushr 8) xor TABLE[(crc xor b) and 0xff]

        /**
         * 解开 [src] 上这一条目:先吃掉 12 字节头并按 [check] 校验密码,
         * 返回只吐明文(仍是压缩数据)的流。[compressed] 是含加密头的整段长度。
         */
        fun open(
            src: InputStream,
            password: String,
            compressed: Long,
            check: Int,
            archivePath: String,
        ): InputStream {
            val z = ZipCrypto(password)
            val head = ByteArray(HEADER_LEN)
            var done = 0
            while (done < head.size) {
                val n = src.read(head, done, head.size - done)
                if (n < 0) throw com.twig.core.FsException("Unexpected end of encrypted entry")
                done += n
            }
            z.decrypt(head, 0, head.size)
            if ((head[HEADER_LEN - 1].toInt() and 0xff) != (check and 0xff)) {
                throw ArchivePasswordException(archivePath, wrong = true)
            }
            var remaining = compressed - HEADER_LEN
            return object : InputStream() {
                private val one = ByteArray(1)

                override fun read(): Int {
                    val n = read(one, 0, 1)
                    return if (n < 0) -1 else one[0].toInt() and 0xff
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0L) return -1
                    val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
                    if (n <= 0) return -1
                    z.decrypt(b, off, n)
                    remaining -= n
                    return n
                }

                override fun close() = src.close()
            }
        }

        /** CRC32 的高字节(密码校验用)。 */
        fun crcCheckByte(crc: Long): Int = ((crc shr 24) and 0xff).toInt()

        /** 有数据描述符时改用 DOS 修改时间的高字节。 */
        fun timeCheckByte(dosTime: Int): Int = (dosTime shr 8) and 0xff

        /** 给单测用:按 [CRC32] 语义算校验字节。 */
        fun crcOf(data: ByteArray): Long = CRC32().apply { update(data) }.value
    }
}
