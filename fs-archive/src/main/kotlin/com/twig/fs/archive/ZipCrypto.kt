package com.twig.fs.archive

import java.io.InputStream
import java.util.zip.CRC32

/**
 * **Decryption** of the legacy PKWARE encryption (commonly known as ZipCrypto). Both old archives
 * and the built-in "Encrypt" of Windows Explorer use it; the algorithm has long been broken by
 * known-plaintext attacks, so here it is **read-only** — Twig only writes encrypted archives via [ZipAes].
 *
 * The first 12 bytes of an entry's data are the encryption header; after decryption the last byte
 * must equal the check byte (with a data descriptor, the high byte of the modification time;
 * otherwise the high byte of the CRC), which is how we judge whether the password is correct.
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

    /** In-place decrypt a chunk. */
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
                // Standard CRC-32 reflected polynomial 0xEDB88320
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor POLY else c ushr 1 }
                t[i] = c
            }
        }

        private fun crc32(crc: Int, b: Int): Int = (crc ushr 8) xor TABLE[(crc xor b) and 0xff]

        /**
         * Open this entry from [src]: first eat the 12-byte header and check the password against
         * [check], then return a stream that only emits plaintext (still compressed data).
         * [compressed] is the total length including the encryption header.
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

        /** High byte of CRC32 (used as password check byte). */
        fun crcCheckByte(crc: Long): Int = ((crc shr 24) and 0xff).toInt()

        /** When a data descriptor is present, fall back to the high byte of the DOS modification time. */
        fun timeCheckByte(dosTime: Int): Int = (dosTime shr 8) and 0xff

        /** For unit tests: compute the check byte per [CRC32] semantics. */
        fun crcOf(data: ByteArray): Long = CRC32().apply { update(data) }.value
    }
}
