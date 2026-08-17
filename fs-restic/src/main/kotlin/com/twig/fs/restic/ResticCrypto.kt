package com.twig.fs.restic

import org.bouncycastle.crypto.generators.SCrypt
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * restic 仓库的解密原语。
 *
 * 加密格式:每个存储对象 = nonce(16) || 密文 || Poly1305-MAC(16),AES-256-CTR 加密。
 * 只读查看器**不校验 MAC**,仅解密;密码是否正确通过"解出的 JSON 能否解析"判断。
 */
internal object ResticCrypto {

    /** scrypt 派生 64 字节;前 32 字节即 AES 加密密钥(用于解 keyfile 的 data)。 */
    fun deriveKey(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int): ByteArray =
        SCrypt.generate(password, salt, n, r, p, 64)

    /** AES-256-CTR 解密一个 nonce||ct||mac 对象,返回明文(丢弃 MAC,不校验)。 */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size >= 32) { "restic: ciphertext too short" }
        val iv = blob.copyOfRange(0, 16)
        val ct = blob.copyOfRange(16, blob.size - 16) // 末 16 字节为 MAC
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    fun isZstdFrame(d: ByteArray): Boolean =
        d.size >= 4 && d[0] == 0x28.toByte() && d[1] == 0xB5.toByte() &&
            d[2] == 0x2F.toByte() && d[3] == 0xFD.toByte()

    /** 标准 Base64 解码(自实现,避开 java.util.Base64 的 API 26 要求)。 */
    fun base64(s: String): ByteArray {
        val table = IntArray(128) { -1 }
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alpha.indices) table[alpha[i].code] = i
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in s) {
            if (c == '=' || c == '\n' || c == '\r') continue
            val v = table[c.code]
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
