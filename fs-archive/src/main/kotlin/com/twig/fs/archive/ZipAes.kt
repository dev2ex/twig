package com.twig.fs.archive

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WinZip AES(zip 的 extra field 0x9901,method 字段填 99)。**读写都在这儿手写**——
 * commons-compress 与 java.util.zip 对加密 zip 一律不支持(连读都不支持),而引入
 * zip4j 是几百 KB 的 APK 增量,与体积优先冲突。
 *
 * 条目数据布局:`salt | pwVerify(2) | AES-CTR 密文 | authCode(10)`
 *  - salt 长度 8/12/16(对应 AES-128/192/256),随条目存在包里;
 *  - 密钥由 `PBKDF2WithHmacSHA1`(1000 轮)一次派生出 `加密密钥 | HMAC 密钥 | 校验值(2)`;
 *  - 密文用 **AES-CTR**,计数器是**小端**且从 1 开始 —— JDK 的 `AES/CTR` 是大端,
 *    直接用会全解成乱码,所以这里用 `AES/ECB` 加密计数块自己异或;
 *  - authCode = `HmacSHA1(HMAC 密钥, 密文)` 的前 10 字节,对**密文**算(不是明文)。
 *
 * 写出一律用 AE-2(version=2):AE-2 的 CRC 字段填 0,不泄露明文校验值。
 */
internal object ZipAes {

    /** extra field 头 ID。 */
    const val EXTRA_ID = 0x9901

    /** 加密条目在 zip 里登记的压缩方法(真实方法藏在 extra field 里)。 */
    const val METHOD = 99

    private const val ITERATIONS = 1000

    /** 认证码长度(HmacSHA1 截断到 10 字节,规范如此)。 */
    const val AUTH_LEN = 10

    /** 密码校验值长度。 */
    const val VERIFY_LEN = 2

    /** 新建包用 AES-256。 */
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

    /** 一条目的固定开销(salt + 校验值 + 认证码),算 csize 用。 */
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
     * AES-CTR 的密钥流:计数器 16 字节**小端**递增,第一块用 counter=1。
     * 加解密是同一套异或,所以读写共用。
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

        /** 就地异或(加密/解密同一操作)。 */
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
     * 解密流:[src] 已定位到条目数据首字节,[compressed] 是**含头尾开销**的整段长度
     * (即 zip 里记的压缩大小)。构造时校验密码,不对直接抛 [ArchivePasswordException]。
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
            hmac.update(b, off, n) // HMAC 对密文算,必须在解密之前
            ctr.process(b, off, n)
            remaining -= n
            return n
        }

        /**
         * 读完末尾 10 字节认证码比对。**只在真读到末尾时做**——播放器/缩略图这类只读开头
         * 就关流的场景不该因为"没读完"报错。
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
     * 加密流:构造即写出 salt + 校验值,[close] 时补认证码。**不关闭下游**——
     * 下游是整个归档的输出流,还要接着写下一个条目。
     */
    class EncryptStream(out: OutputStream, password: String, private val strength: Int) :
        FilterOutputStream(out) {

        private val ctr: Ctr
        private val hmac: Mac
        private val work = ByteArray(8192)
        private var finished = false

        /** 本条目除密文之外多写的字节数(salt + 校验值 + 认证码)。 */
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
                hmac.update(work, 0, n) // 同样对密文算
                out.write(work, 0, n)
                done += n
            }
        }

        /** 写出认证码收尾;不关下游。 */
        override fun close() {
            if (finished) return
            finished = true
            out.write(hmac.doFinal(), 0, AUTH_LEN)
            out.flush()
        }
    }

    /** 解析 0x9901 extra field 的原始字节,返回 (强度, 真实压缩方法);格式不对返回 null。 */
    fun parseExtra(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 7) return null
        val strength = data[4].toInt() and 0xff
        val method = (data[5].toInt() and 0xff) or ((data[6].toInt() and 0xff) shl 8)
        return strength to method
    }

    /** 组装 0x9901 extra field 的数据部分(不含 4 字节头);AE-2 + 指定强度 + 真实方法。 */
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
