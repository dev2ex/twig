package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wraps Commons Compress's plaintext header in a standard 7z EncodedHeader (AES-256).
 * Only the trailing metadata is rewritten, in bounded chunks, before the .twigpart is committed.
 * Data streams and their offsets stay unchanged. No reflection or additional codec dependency.
 */
internal object SevenZHeaders {
    private const val CYCLES_POWER = 19

    fun encrypt(file: File, password: String, cancelled: CopyEngine.Cancelled) {
        fun checkCancelled() { if (cancelled.isCancelled()) throw ArchiveWriter.Cancelled() }
        checkCancelled()
        RandomAccessFile(file, "rw").use { raf ->
            val start = ByteArray(32).also(raf::readFully)
            val fields = ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN)
            val offset = fields.getLong(12)
            val size = fields.getLong(20)
            if (offset < 0 || size <= 0 || offset > raf.length() - 32 || size != raf.length() - 32 - offset) {
                throw FsException("Invalid 7z header bounds")
            }
            val headerPosition = 32 + offset
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(16).also(random::nextBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            val passwordBytes = password.toByteArray(Charsets.UTF_16LE)
            val counter = ByteArray(8)
            val key = try {
                repeat(1 shl CYCLES_POWER) { round ->
                    if (round and 1023 == 0) checkCancelled()
                    digest.update(salt)
                    digest.update(passwordBytes)
                    digest.update(counter)
                    for (i in counter.indices) {
                        counter[i] = (counter[i] + 1).toByte()
                        if (counter[i] != 0.toByte()) break
                    }
                }
                digest.digest()
            } finally { passwordBytes.fill(0) }
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            try { cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            finally { key.fill(0) }
            val plainCrc = CRC32()
            val packedCrc = CRC32()
            val buffer = ByteArray(64 * 1024)
            var readPosition = headerPosition
            var writePosition = headerPosition
            fun writeEncrypted(bytes: ByteArray) {
                raf.seek(writePosition)
                raf.write(bytes)
                packedCrc.update(bytes)
                writePosition += bytes.size
            }
            while (readPosition < headerPosition + size) {
                checkCancelled()
                val count = minOf(buffer.size.toLong(), headerPosition + size - readPosition).toInt()
                raf.seek(readPosition)
                raf.readFully(buffer, 0, count)
                plainCrc.update(buffer, 0, count)
                readPosition += count
                cipher.update(buffer, 0, count)?.let(::writeEncrypted)
            }
            // 7z AES pads with zero bytes; the original length belongs in CodersUnpackSize.
            writeEncrypted(cipher.doFinal(ByteArray(((16 - size % 16) % 16).toInt())))
            if (plainCrc.value != (fields.getInt(28).toLong() and 0xffffffffL)) {
                throw FsException("7z header checksum mismatch")
            }
            checkCancelled()
            val descriptor = ByteArrayOutputStream().apply {
                write(0x17) // EncodedHeader
                write(0x06) // PackInfo
                uint(offset)
                uint(1)
                write(0x09) // Size
                uint(writePosition - headerPosition)
                write(0x0a); write(1); le32(packedCrc.value)
                write(0) // End PackInfo
                write(0x07) // UnpackInfo
                write(0x0b); uint(1); write(0) // One inline folder
                uint(1) // One coder, with one input and one output
                write(0x24); write(byteArrayOf(0x06, 0xf1.toByte(), 0x07, 0x01))
                uint(34)
                write(0xc0 or CYCLES_POWER); write(0xff); write(salt); write(iv)
                write(0x0c); uint(size) // CodersUnpackSize
                write(0x0a); write(1); le32(plainCrc.value)
                write(0); write(0) // End UnpackInfo, End StreamsInfo
            }.toByteArray()
            raf.seek(writePosition)
            raf.write(descriptor)
            raf.setLength(raf.filePointer)
            val next = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(writePosition - 32).putLong(descriptor.size.toLong())
                .putInt(CRC32().apply { update(descriptor) }.value.toInt()).array()
            raf.seek(8)
            val crc = ByteArrayOutputStream().apply { le32(CRC32().apply { update(next) }.value) }.toByteArray()
            raf.write(crc)
            raf.write(next)
        }
    }

    /** 7z's variable-width unsigned integer, distinct from LEB128. */
    private fun ByteArrayOutputStream.uint(value: Long) {
        require(value >= 0)
        var prefix = 0
        var count = 0
        while (count < 8) {
            if (value < (1L shl (7 * (count + 1)))) {
                prefix = prefix or (value ushr (8 * count)).toInt()
                break
            }
            prefix = prefix or (0x80 ushr count)
            count++
        }
        write(prefix)
        repeat(count) { write((value ushr (8 * it)).toInt() and 0xff) }
    }

    private fun ByteArrayOutputStream.le32(value: Long) {
        repeat(4) { write((value ushr (8 * it)).toInt() and 0xff) }
    }
}
