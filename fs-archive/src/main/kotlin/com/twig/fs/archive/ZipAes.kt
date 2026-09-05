package com.twig.fs.archive

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WinZip AES (zip's extra field 0x9901, method field set to 99). **Both read and write are
 * handwritten here** — commons-compress and java.util.zip do not support encrypted zips at all
 * (not even reading), and pulling in zip4j would add hundreds of KB to the APK, conflicting with
 * the size-first rule.
 *
 * Entry data layout: `salt | pwVerify(2) | AES-CTR ciphertext | authCode(10)`
 *  - salt length 8/12/16 (for AES-128/192/256), stored per entry;
 *  - the keys are derived once via `PBKDF2WithHmacSHA1` (1000 iterations) into
 *    `encryption key | HMAC key | verify value (2)`;
 *  - the ciphertext uses **AES-CTR**, the counter is **little-endian** and starts at 1 — the
 *    JDK's `AES/CTR` is big-endian, so using it directly would decrypt to garbage, which is why
 *    we encrypt counter blocks with `AES/ECB` and XOR them ourselves;
 *  - authCode = the first 10 bytes of `HmacSHA1(HMAC key, ciphertext)`, computed over the **ciphertext** (not plaintext).
 *
 * Writes always use AE-2 (version=2): AE-2 sets the CRC field to 0, so it does not leak a plaintext checksum.
 */
internal object ZipAes {

    /** extra field header ID. */
    const val EXTRA_ID = 0x9901

    /** The compression method recorded in zip for an encrypted entry (the real method is hidden in the extra field). */
    const val METHOD = 99

    private const val ITERATIONS = 1000

    /** Auth code length (HmacSHA1 truncated to 10 bytes, as per the spec). */
    const val AUTH_LEN = 10

    /** Password verify value length. */
    const val VERIFY_LEN = 2

    /** New archives use AES-256. */
    const val STRENGTH_256 = 3

    fun saltLength(strength: Int): Int = when (strength) {
        1 -> 8
        2 -> 12
        else -> 16
    }

    fun keyLength(strength: Int): Int = when (strength) {
        1 -> 16
        2 -> 24
        else -> 32
    }

    /** Fixed overhead per entry (salt + verify value + auth code); used to compute csize. */
    fun overhead(strength: Int): Int = saltLength(strength) + VERIFY_LEN + AUTH_LEN

    class Keys(val enc: ByteArray, val mac: ByteArray, val verify: ByteArray)

    fun derive(password: String, salt: ByteArray, strength: Int): Keys {
        val keyLen = keyLength(strength)
        val total = keyLen * 2 + VERIFY_LEN
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, total * 8)
        val raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(spec).encoded
        return Keys(
            enc = raw.copyOfRange(0, keyLen),
            mac = raw.copyOfRange(keyLen, keyLen * 2),
            verify = raw.copyOfRange(keyLen * 2, total),
        )
    }

    fun mac(key: ByteArray): Mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }

    /**
     * AES-CTR keystream: the 16-byte counter increments **little-endian**, and the first block uses counter=1.
     * Encryption and decryption are the same XOR operation, so the read and write paths share it.
     */
    class Ctr(key: ByteArray) {
        private val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        private val counter = ByteArray(16)
        private val stream = ByteArray(16)
        private var pos = 16

        private fun nextBlock() {
            var i = 0
            while (i < 16) {
                counter[i] = (counter[i] + 1).toByte()
                if (counter[i].toInt() != 0) break
                i++
            }
            cipher.doFinal(counter, 0, 16, stream, 0)
            pos = 0
        }

        /** In-place XOR (encrypt and decrypt are the same operation). */
        fun process(buf: ByteArray, off: Int, len: Int) {
            var i = off
            val end = off + len
            while (i < end) {
                if (pos == 16) nextBlock()
                buf[i] = (buf[i].toInt() xor stream[pos].toInt()).toByte()
                pos++
                i++
            }
        }
    }

    /**
     * Decrypt stream: [src] is positioned at the first byte of the entry's data, [compressed] is
     * the **total length including overhead** (i.e. the compressed size recorded in the zip).
     * The password is verified in the constructor; wrong password throws [ArchivePasswordException] directly.
     */
    class DecryptStream(
        private val src: InputStream,
        password: String,
        strength: Int,
        compressed: Long,
        private val archivePath: String,
    ) : InputStream() {

        private val ctr: Ctr
        private val hmac: Mac
        private var remaining: Long
        private val buf1 = ByteArray(1)
        private var authChecked = false

        init {
            val salt = ByteArray(saltLength(strength))
            readFully(src, salt)
            val keys = derive(password, salt, strength)
            val verify = ByteArray(VERIFY_LEN)
            readFully(src, verify)
            if (!verify.contentEquals(keys.verify)) {
                throw ArchivePasswordException(archivePath, wrong = true)
            }
            ctr = Ctr(keys.enc)
            hmac = mac(keys.mac)
            remaining = compressed - salt.size - VERIFY_LEN - AUTH_LEN
            if (remaining < 0) remaining = 0
        }

        override fun read(): Int {
            val n = read(buf1, 0, 1)
            return if (n < 0) -1 else buf1[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) {
                checkAuth()
                return -1
            }
            val want = minOf(len.toLong(), remaining).toInt()
            val n = src.read(b, off, want)
            if (n <= 0) {
                remaining = 0
                return -1
            }
            hmac.update(b, off, n) // HMAC is computed over the ciphertext, so it must be done before decryption
            ctr.process(b, off, n)
            remaining -= n
            return n
        }

        /**
         * Compare the trailing 10-byte auth code at EOF. **Only runs when EOF is actually reached** —
         * scenarios like the player or thumbnail that only read the head and then close the stream
         * must not error out for "did not finish reading".
         */
        private fun checkAuth() {
            if (authChecked) return
            authChecked = true
            val want = ByteArray(AUTH_LEN)
            if (!runCatching { readFully(src, want); true }.getOrDefault(false)) return
            val got = hmac.doFinal().copyOfRange(0, AUTH_LEN)
            if (!got.contentEquals(want)) {
                throw com.twig.core.FsException("Archive entry failed integrity check (wrong password or damaged data)")
            }
        }

        override fun close() {
            src.close()
        }
    }

    /**
     * Encrypt stream: the constructor writes out salt + verify value, and [close] appends the auth code.
     * **Does not close downstream** — downstream is the whole archive output, and more entries follow.
     */
    class EncryptStream(out: OutputStream, password: String, private val strength: Int) :
        FilterOutputStream(out) {

        private val ctr: Ctr
        private val hmac: Mac
        private val work = ByteArray(8192)
        private var finished = false

        /** Bytes written for this entry beyond the ciphertext (salt + verify value + auth code). */
        val overheadBytes: Int get() = overhead(strength)

        init {
            val salt = ByteArray(saltLength(strength))
            java.security.SecureRandom().nextBytes(salt)
            val keys = derive(password, salt, strength)
            out.write(salt)
            out.write(keys.verify)
            ctr = Ctr(keys.enc)
            hmac = mac(keys.mac)
        }

        override fun write(b: Int) {
            work[0] = b.toByte()
            write(work, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var done = 0
            while (done < len) {
                val n = minOf(work.size, len - done)
                System.arraycopy(b, off + done, work, 0, n)
                ctr.process(work, 0, n)
                hmac.update(work, 0, n) // also computed over the ciphertext
                out.write(work, 0, n)
                done += n
            }
        }

        /** Write the auth code to finish; do not close downstream. */
        override fun close() {
            if (finished) return
            finished = true
            out.write(hmac.doFinal(), 0, AUTH_LEN)
            out.flush()
        }
    }

    /** Parse the raw bytes of the 0x9901 extra field, returning (strength, real compression method); null on malformed format. */
    fun parseExtra(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 7) return null
        val strength = data[4].toInt() and 0xff
        val method = (data[5].toInt() and 0xff) or ((data[6].toInt() and 0xff) shl 8)
        return strength to method
    }

    /** Assemble the data portion (excluding the 4-byte header) of the 0x9901 extra field: AE-2 + given strength + real method. */
    fun buildExtra(strength: Int, method: Int): ByteArray = byteArrayOf(
        2, 0, // version: AE-2
        'A'.code.toByte(), 'E'.code.toByte(),
        strength.toByte(),
        (method and 0xff).toByte(), ((method shr 8) and 0xff).toByte(),
    )

    private fun readFully(input: InputStream, buf: ByteArray) {
        var done = 0
        while (done < buf.size) {
            val n = input.read(buf, done, buf.size - done)
            if (n < 0) throw com.twig.core.FsException("Unexpected end of encrypted entry")
            done += n
        }
    }
}
