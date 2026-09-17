package com.twig.fs.restic

import com.twig.core.FsException
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

    private val B64_TABLE = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
    }

    /**
     * Standard Base64 decoding (handwritten to avoid java.util.Base64's API 26 requirement).
     *
     * A character outside the alphabet means a damaged key file, and says so: it used to be
     * skipped when ASCII and an ArrayIndexOutOfBounds crash when not (the 128-entry table
     * indexed by `c.code`), instead of "this repository's key is corrupt".
     */
    fun base64(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in s) {
            if (c == '=' || c.isWhitespace()) continue
            val v = if (c.code < 128) B64_TABLE[c.code] else -1
            if (v < 0) throw FsException("Corrupt restic key data (invalid base64)")
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
