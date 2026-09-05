package com.twig.app.secure

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.SavedConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Local encryption of sensitive fields.
 *
 * Robolectric has no real AndroidKeyStore (`KeyGenParameterSpec` and friends need a TEE),
 * so [Secrets.wrapper] is swapped for an in-memory implementation — the only thing
 * replaced is "where the DEK is stored"; how a field is encrypted, how the master
 * password wraps the DEK, and how old plaintext passes through unchanged are all real
 * code.
 */
@RunWith(RobolectricTestRunner::class)
class SecretsTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    /** A fake Keystore: the DEK sits in memory as plaintext. On a real device this step happens inside a TEE, and the key material can never be extracted. */
    private object MemoryWrapper : Secrets.Wrapper {
        var key: ByteArray? = null
        override fun wrap(dek: ByteArray): String {
            key = dek.copyOf()
            return "mem"
        }
        override fun unwrap(blob: String): ByteArray? = key?.copyOf()
        override fun available() = true
    }

    /**
     * A fake "biometric key": an AES key that just sits in memory, no authentication
     * required.
     *
     * On a real device this key is bound to biometrics and needs a TEE authentication
     * token on every use — that half cannot be reproduced in Robolectric (no TEE, no
     * sensor). **What can be tested is the [Secrets] side of it**: whether the two keys
     * genuinely coexist, whether the field ciphertext gets rewritten, and whether it
     * automatically falls back to the master password once the key is invalidated.
     */
    private object MemoryBio : Secrets.BioCrypto {
        var key: javax.crypto.SecretKey? = null

        /** Simulates "the user enrolled a new fingerprint" - on a real device the key is invalidated on the spot and decryption throws. */
        var invalidated = false

        override fun sealCipher(): javax.crypto.Cipher? {
            invalidated = false
            val gen = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }
            key = gen.generateKey()
            return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
        }

        override fun openCipher(iv: ByteArray): javax.crypto.Cipher? {
            val k = key.takeIf { !invalidated } ?: return null
            return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(javax.crypto.Cipher.DECRYPT_MODE, k, javax.crypto.spec.GCMParameterSpec(128, iv))
            }
        }

        override fun dropKey() {
            key = null
        }
    }

    /** Runs the two-step "enable fingerprint" flow: request a Cipher (this is where a real device plugs in authentication) -> wrap the DEK. */
    private fun enableBio() {
        assertTrue(Secrets.bioSeal(ctx, Secrets.bioSealCipher()!!))
    }

    /** Runs the two-step "fingerprint unlock" flow. Returns false = must fall back to the master password. */
    private fun bioUnlock(): Boolean {
        val c = Secrets.bioOpenCipher(ctx) ?: return false
        return Secrets.bioUnlock(ctx, c)
    }

    @Before
    fun setUp() {
        MemoryWrapper.key = null
        MemoryBio.key = null
        MemoryBio.invalidated = false
        Secrets.wrapper = MemoryWrapper
        Secrets.bio = MemoryBio
        Secrets.reset(ctx)
    }

    @After
    fun tearDown() {
        Secrets.wrapper = Secrets.AndroidKeystore
        Secrets.bio = Secrets.BiometricKey
    }

    @Test
    fun `an encrypt round trip - no plaintext is visible in the ciphertext`() {
        val blob = Secrets.enc(ctx, "hunter2")
        assertTrue("the ciphertext must carry the prefix, which is also the check for 'does this need decrypting'", blob.startsWith(Secrets.PREFIX))
        assertFalse("the plaintext must not appear inside the ciphertext", blob.contains("hunter2"))
        assertEquals("hunter2", Secrets.dec(ctx, blob))
    }

    @Test
    fun `encrypting the same plaintext twice gives different results - the IV is random`() {
        assertNotEquals(Secrets.enc(ctx, "same"), Secrets.enc(ctx, "same"))
    }

    @Test
    fun `old plaintext with no prefix is returned unchanged - that is the entire migration logic`() {
        assertEquals("plain-old-password", Secrets.dec(ctx, "plain-old-password"))
    }

    @Test
    fun `an empty string is not encrypted - an empty password just means no password is set`() {
        assertEquals("", Secrets.enc(ctx, ""))
    }

    /**
     * ★ This is the **entire point** of the two-layer key design: toggling the master
     * password only changes "who wraps the DEK" — the field ciphertext underneath must
     * not change by even a single byte.
     *
     * Doing it the other way around (iterate every connection, decrypt then re-encrypt)
     * would leave a half-plaintext, half-ciphertext mess if it failed partway through,
     * with no rollback point.
     */
    @Test
    fun `after enabling the master password, the already-stored field ciphertext is unchanged, byte for byte`() {
        ConnectionStore.save(ctx, conn("mypassword"))
        val before = rawStored()
        assertTrue("what is on disk must be ciphertext", before.contains(Secrets.PREFIX))
        assertFalse("what is on disk must not contain the plaintext password", before.contains("mypassword"))

        assertTrue(Secrets.enableMasterPassword(ctx, "master-pw".toCharArray()))
        assertEquals("the field ciphertext must be completely unchanged", before, rawStored())

        // still readable after unlocking
        Secrets.lock()
        assertTrue(Secrets.locked(ctx))
        assertTrue(Secrets.unlock(ctx, "master-pw".toCharArray()))
        assertEquals("mypassword", ConnectionStore.all(ctx).single().password)
    }

    @Test
    fun `disabling the master password likewise leaves the field ciphertext untouched, and returns to the frictionless state`() {
        ConnectionStore.save(ctx, conn("mypassword"))
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        val before = rawStored()

        assertTrue(Secrets.disableMasterPassword(ctx, "master-pw".toCharArray()))
        assertEquals(before, rawStored())
        assertFalse(Secrets.hasMasterPassword(ctx))

        // after disabling, a process restart no longer requires unlocking
        Secrets.lock()
        assertFalse(Secrets.locked(ctx))
        assertEquals("mypassword", ConnectionStore.all(ctx).single().password)
    }

    @Test
    fun `a wrong master password fails to decrypt - GCM's tag is the check, no separate verification value is stored`() {
        ConnectionStore.save(ctx, conn("mypassword"))
        Secrets.enableMasterPassword(ctx, "right".toCharArray())
        Secrets.lock()
        assertFalse(Secrets.unlock(ctx, "wrong".toCharArray()))
        assertTrue("it remains locked after a failed unlock", Secrets.locked(ctx))
    }

    /**
     * ★ Once the master password is enabled, **the Keystore wrapping must be deleted**.
     * If it is not, two keys open the same lock and the strength is only as good as the
     * weaker one — the master password would be purely decorative.
     */
    @Test
    fun `enabling the master password deletes the Keystore wrapping`() {
        Secrets.enc(ctx, "x") // force the DEK to materialize
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        val sp = ctx.getSharedPreferences("twig_secure", android.content.Context.MODE_PRIVATE)
        assertFalse("if the Keystore wrapping is still there, the master password is effectively not enabled", sp.contains("dek_ks"))
        assertTrue(sp.contains("dek_pw"))
    }

    @Test
    fun `the password cannot be read while locked, but the connection entry itself is still there`() {
        ConnectionStore.save(ctx, conn("mypassword"))
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        Secrets.lock()

        val c = ConnectionStore.all(ctx).single()
        assertEquals("the address and similar fields are unaffected", "10.0.0.1", c.host)
        assertNotEquals("the password cannot be read (it stays as ciphertext), but must not crash", "mypassword", c.password)
    }

    // ---- Fingerprint unlock: a second key that **coexists** with the master password ----

    /**
     * ★ This is the check for the whole feature: **both keys can open the same DEK**,
     * and enabling fingerprint must not rewrite a single byte of field ciphertext (same
     * reason as toggling the master password: no rollback point).
     */
    @Test
    fun `once fingerprint is enabled, both fingerprint and the master password can unlock`() {
        ConnectionStore.save(ctx, conn("mypassword"))
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        val stored = rawStored()

        enableBio()
        assertTrue(Secrets.bioEnabled(ctx))
        assertEquals("enabling fingerprint must not rewrite the field ciphertext", stored, rawStored())

        Secrets.lock()
        assertTrue("the fingerprint key can unlock", bioUnlock())
        assertEquals("mypassword", ConnectionStore.all(ctx).single().password)

        Secrets.lock()
        assertTrue("the master password can still unlock too", Secrets.unlock(ctx, "master-pw".toCharArray()))
        assertEquals("mypassword", ConnectionStore.all(ctx).single().password)
    }

    /**
     * ★ The user enrolls a new fingerprint -> the key is invalidated
     * (`setInvalidatedByBiometricEnrollment`). At this point it **must automatically fall
     * back to the master password**, and the now-useless wrapping must be deleted, so
     * every unlock attempt does not waste a try on it.
     */
    @Test
    fun `after the fingerprint key is invalidated, it automatically falls back to the master password`() {
        ConnectionStore.save(ctx, conn("mypassword"))
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        enableBio()

        Secrets.lock()
        MemoryBio.invalidated = true

        assertFalse("the fingerprint path must fail", bioUnlock())
        assertFalse("its wrapping must be deleted along with it", Secrets.bioEnabled(ctx))
        assertTrue("the master password remains the root", Secrets.unlock(ctx, "master-pw".toCharArray()))
        assertEquals("mypassword", ConnectionStore.all(ctx).single().password)
    }

    /** Disabling the master password = the root is gone, and the fingerprint shortcut goes with it (the DEK returns to frictionless Keystore wrapping). */
    @Test
    fun `disabling the master password also deletes the fingerprint wrapping`() {
        Secrets.enc(ctx, "x")
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        enableBio()

        assertTrue(Secrets.disableMasterPassword(ctx, "master-pw".toCharArray()))
        assertFalse(Secrets.bioEnabled(ctx))
        assertNull("the one in the Keystore should be deleted too, no point leaving it unused", MemoryBio.key)
    }

    /** Disabling fingerprint touches only that one wrapping: the master password and field ciphertext are unaffected. */
    @Test
    fun `disabling fingerprint does not affect the master password`() {
        ConnectionStore.save(ctx, conn("mypassword"))
        Secrets.enableMasterPassword(ctx, "master-pw".toCharArray())
        enableBio()
        val stored = rawStored()

        Secrets.disableBio(ctx)
        assertFalse(Secrets.bioEnabled(ctx))
        assertEquals("the field ciphertext must not be touched", stored, rawStored())

        Secrets.lock()
        assertTrue(Secrets.unlock(ctx, "master-pw".toCharArray()))
        assertEquals("mypassword", ConnectionStore.all(ctx).single().password)
    }

    private fun conn(pw: String) = SavedConnection(
        type = "smb", host = "10.0.0.1", share = "public",
        user = "vale", password = pw, name = "smb-nas",
    )

    /** Looks directly at the JSON string on disk - asserting "was not rewritten" can only be done at this layer. */
    private fun rawStored(): String =
        ctx.getSharedPreferences(ConnectionStore.FILE, android.content.Context.MODE_PRIVATE)
            .getString("list", "")!!
}
