package com.twig.fs.restic

import org.bouncycastle.crypto.generators.SCrypt
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decryption primitives for a restic repository.
 *
 * Encryption format: each stored object = nonce(16) || ciphertext || Poly1305-MAC(16), AES-256-CTR.
 * The read-only viewer **does not verify the MAC** — it only decrypts;
 * whether the password is correct is judged by whether the decrypted JSON parses.
 */
internal object ResticCrypto {

    /** scrypt-derived 64 bytes; the first 32 bytes are the AES key (used to decrypt the keyfile's data). */
    fun deriveKey(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int): ByteArray =
        SCrypt.generate(password, salt, n, r, p, 64)

    /** AES-256-CTR decryption of a nonce||ct||mac object, returning the plaintext (MAC discarded, not verified). */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size >= 32) { "restic: ciphertext too short" }
        val iv = blob.copyOfRange(0, 16)
        val ct = blob.copyOfRange(16, blob.size - 16) // last 16 bytes are the MAC
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    fun isZstdFrame(d: ByteArray): Boolean =
        d.size >= 4 && d[0] == 0x28.toByte() && d[1] == 0xB5.toByte() &&
            d[2] == 0x2F.toByte() && d[3] == 0xFD.toByte()

    /** Standard Base64 decoding (handwritten to avoid java.util.Base64's API 26 requirement). */
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
