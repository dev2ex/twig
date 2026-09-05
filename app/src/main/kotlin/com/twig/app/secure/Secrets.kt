package com.twig.app.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local encryption of sensitive fields (passwords / apiKey / token / private-key passphrases).
 *
 * **This project is open source, so any hard-coded key is the same as no
 * key** — it pops out of a decompiler at a glance. Keys can only come from
 * one of two places: this device's secure hardware, or the user's master
 * password. There is no third path.
 *
 * ## Two layers of keys
 *
 * ```
 * Field ciphertext ←── DEK (random 256 bits, generated once and never changes)
 *                       ↑ wrapped by whom
 *       ┌──────────────┼──────────────────┐
 *   Keystore key     scrypt(master pw)   Biometric-bound Keystore key
 *   (default,        (user opt-in)       (optional, coexists
 *    frictionless)                       with the master password)
 * ```
 *
 * The first two are **mutually exclusive** (when the master password is on,
 * the Keystore copy is removed — otherwise two keys open the same lock and
 * the strength is capped by the weaker one); the biometric copy **coexists
 * with the master password** because every use needs the TEE to obtain a
 * genuine biometric authentication, which even root cannot quietly produce.
 * **The master password is always the root**: biometrics can disappear at
 * any time (a new fingerprint is enrolled and invalidates the old one,
 * fingerprints are cleared, the sensor dies), and without a master password
 * to fall back on, every stored password is permanently locked.
 *
 * The two-layer split exists for **one reason only**: toggling the master
 * password only swaps the small piece that wraps the DEK, and the field
 * ciphertext underneath does not need to be re-written a single byte.
 * Otherwise every on/off would have to decrypt and re-encrypt every
 * connection — half a job done is half plaintext and half ciphertext, with
 * no rollback point.
 *
 * ## What this can and cannot do
 *
 * - **Can**: even if `/data/data` is copied wholesale (adb pull / full-device
 *   backup / device-to-device transfer), the passwords cannot be recovered.
 *   The Keystore key is generated inside the TEE/StrongBox, `getEncoded()`
 *   always returns null — **it is not in those files at all**; what was
 *   copied is just an undecryptable pile of bytes.
 * - **Cannot**: once the device is rooted, the attacker can call Keystore
 *   decrypt as the app. This is the shared ceiling of every "frictionless
 *   encryption" (Chrome and WeChat hit it too). The only way over it is the
 *   master password — the key never lands on disk, so even root cannot
 *   obtain it.
 *
 * ## Storage format
 *
 * Ciphertext is written as `enc1:<base64(iv‖ciphertext‖tag)>`. **No prefix
 * means plaintext left behind by an old version**: on read it is returned
 * as-is, and the next write turns it into ciphertext transparently — no
 * migration switch is needed.
 */
object Secrets {

    /** Ciphertext prefix; doubles as the test for "does this value need to be decrypted". */
    const val PREFIX = "enc1:"

    private const val TAG = "twig-sec"
    private const val FILE = "twig_secure"

    /** Keystore-wrapped DEK (the default state). */
    private const val K_DEK_KS = "dek_ks"

    /** Master-password-wrapped DEK (after the master password is turned on). Mutually exclusive with [K_DEK_KS] — if both exist, last toggle did not complete. */
    private const val K_DEK_PW = "dek_pw"

    /**
     * The DEK wrapped by the biometric-bound Keystore key. **Coexists with
     * [K_DEK_PW], not exclusive** — two keys open the same DEK, whichever
     * arrives first is used.
     *
     * This is not the same as the rejected "[K_DEK_KS] as a fallback" — that
     * one can be called **silently** after root, and keeping it would make
     * the master password pointless. This one requires the TEE to obtain a
     * **genuine biometric authentication** first before it will decrypt.
     */
    private const val K_DEK_BIO = "dek_bio"

    private const val GCM_IV = 12
    private const val GCM_TAG_BITS = 128

    /**
     * scrypt parameters. N=32768 / r=8 needs 128·N·r = **32 MB** of memory,
     * which takes a few hundred milliseconds on a phone. The memory hardness
     * is the point: large-scale GPU parallelism is starved at that level,
     * whereas pure-compute KDFs like PBKDF2 are not.
     */
    const val SCRYPT_N = 32768
    const val SCRYPT_R = 8
    const val SCRYPT_P = 1
    const val KEY_LEN = 32

    /**
     * How the DEK is unwrapped. The interface exists purely for testability:
     * Robolectric has no real AndroidKeyStore, so tests inject an in-memory
     * implementation; production is always [AndroidKeystore].
     */
    interface Wrapper {
        /** Wrap the DEK; return null = this device cannot use it (extremely rare, see [available]). */
        fun wrap(dek: ByteArray): String?

        fun unwrap(blob: String): ByteArray?

        /** Whether this device can use it. If not, the whole thing falls back to plaintext storage (functionality does not break, but it is not encrypted). */
        fun available(): Boolean
    }

    /**
     * The biometric key. Same reason for the interface as [Wrapper] — there
     * is no real AndroidKeyStore in Robolectric, let alone a fingerprint
     * sensor.
     *
     * The **two-step** shape is forced by the Biometric API: `BiometricPrompt`
     * wants a Cipher that is **already init'd but not yet doFinal** (a
     * `CryptoObject`); the doFinal step only happens after authentication
     * succeeds. So this interface only hands out the Cipher; the actual
     * encryption / decryption stays in [bioSeal] / [bioUnlock].
     */
    interface BioCrypto {
        /** Create (or replace) the key and init a Cipher in encrypt mode. null = cannot create (usually: no fingerprint enrolled). */
        fun sealCipher(): Cipher?

        /** Init a Cipher in decrypt mode using the stored IV. null = **the key has been invalidated** (a new fingerprint was enrolled). */
        fun openCipher(iv: ByteArray): Cipher?

        /** Delete the key itself. Called when turning off fingerprint unlock, so the Keystore is not left with an unused key. */
        fun dropKey()
    }

    @Volatile
    var wrapper: Wrapper = AndroidKeystore

    @Volatile
    var bio: BioCrypto = BiometricKey

    /** The unlocked DEK. null when the master password is on but not yet unlocked. */
    @Volatile
    private var dek: ByteArray? = null

    private val rng = SecureRandom()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Public: field-level encrypt / decrypt ----

    /**
     * Encrypt one field. Empty string is returned as-is (an empty password is
     * "no password set"; encrypting it would just stick a ciphertext-looking
     * blob into storage for no reason); if the DEK cannot be obtained, the
     * plaintext is returned unchanged as well — **storing plaintext is
     * preferable to storing ciphertext that cannot be decrypted**, because
     * the latter is the same as the user's password vanishing.
     */
    fun enc(ctx: Context, plain: String): String {
        if (plain.isEmpty()) return plain
        val key = key(ctx) ?: return plain
        return runCatching {
            val iv = ByteArray(GCM_IV).also { rng.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val ct = c.doFinal(plain.toByteArray())
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "encrypt failed, storing plaintext: ${it.message}")
            plain
        }
    }

    /**
     * Decrypt one field. **No prefix = legacy plaintext, return as-is**
     * (that is the entirety of the migration logic).
     *
     * If decryption fails the input is returned as-is too: it might be that
     * the Keystore key was invalidated by the system (factory reset, a few
     * models on a lockscreen change), or the user's plaintext might just
     * happen to start with `enc1:`. Neither case should wipe the whole
     * connection — the UI presents it as "this one's password needs to be
     * re-entered", and the address, username and label are still there.
     */
    fun dec(ctx: Context, blob: String): String {
        if (!blob.startsWith(PREFIX)) return blob
        val key = key(ctx) ?: return blob
        return runCatching {
            val raw = Base64.decode(blob.substring(PREFIX.length), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, raw.copyOf(GCM_IV)))
            String(c.doFinal(raw, GCM_IV, raw.size - GCM_IV))
        }.getOrElse {
            Log.w(TAG, "decrypt failed: ${it.message}")
            blob
        }
    }

    // ---- Public: master password ----

    /** Whether the user has turned on the master password. */
    fun hasMasterPassword(ctx: Context): Boolean = sp(ctx).contains(K_DEK_PW)

    /** Master password is on but this process has not yet unlocked it — at this point no stored password can be read. */
    fun locked(ctx: Context): Boolean = hasMasterPassword(ctx) && dek == null

    /** Unlock this process with the master password. Return false = wrong password (the GCM tag is the check; no separate checksum is stored). */
    fun unlock(ctx: Context, password: CharArray): Boolean {
        val blob = sp(ctx).getString(K_DEK_PW, null) ?: return false
        val opened = openWithPassword(blob, password) ?: return false
        dek = opened
        return true
    }

    /** Manually lock (going to background / user-triggered lock). Drops the in-memory DEK only; disk is untouched. */
    fun lock() {
        dek?.fill(0)
        dek = null
    }

    /**
     * Turn on the master password: wrap the DEK with scrypt(password) and
     * **delete the Keystore copy of the wrap**. If the Keystore copy is
     * not removed the master password is theatre — two keys open the same
     * lock and the strength is capped by the weaker one.
     *
     * The app must already be unlocked (so the DEK is available). Return
     * false = DEK cannot be obtained.
     */
    fun enableMasterPassword(ctx: Context, password: CharArray): Boolean {
        val key = key(ctx) ?: return false
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val blob = sealWithPassword(key, password, salt) ?: return false
        // Write the new one first, then delete the old: a power loss in
        // between can leave at most two wraps (on next start pw wins), never
        // zero
        sp(ctx).edit().putString(K_DEK_PW, blob).apply()
        sp(ctx).edit().remove(K_DEK_KS).apply()
        return true
    }

    /**
     * Turn off the master password: verify the old password → re-wrap with
     * Keystore → delete the scrypt copy.
     *
     * **Not a single byte of the underlying field ciphertext is touched**,
     * so the time taken is independent of how many connections are saved.
     * After turning it off, the strength drops back to the Keystore tier
     * (copying the file is still undecryptable, root can decrypt), not back
     * to plaintext.
     */
    fun disableMasterPassword(ctx: Context, password: CharArray): Boolean {
        val blob = sp(ctx).getString(K_DEK_PW, null) ?: return true
        val opened = openWithPassword(blob, password) ?: return false
        val wrapped = wrapper.wrap(opened) ?: return false
        sp(ctx).edit().putString(K_DEK_KS, wrapped).apply()
        sp(ctx).edit().remove(K_DEK_PW).apply()
        // The biometric copy goes with it: the root is gone, the shortcut is
        // pointless, and the DEK is already frictionlessly wrapped by Keystore
        disableBio(ctx)
        dek = opened
        return true
    }

    /** Change the master password. Equivalent to "open with the old, close with the new"; again, the field ciphertext is untouched. */
    fun changeMasterPassword(ctx: Context, old: CharArray, new: CharArray): Boolean {
        val blob = sp(ctx).getString(K_DEK_PW, null) ?: return false
        val opened = openWithPassword(blob, old) ?: return false
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val sealed = sealWithPassword(opened, new, salt) ?: return false
        sp(ctx).edit().putString(K_DEK_PW, sealed).apply()
        dek = opened
        return true
    }

    // ---- Public: fingerprint unlock ----

    /**
     * Whether fingerprint unlock is already on for this device.
     *
     * **Only meaningful when the master password is on**: without it the DEK
     * is already frictionlessly wrapped by [K_DEK_KS], so adding a
     * fingerprint would be one more piece of theatre. The UI therefore hangs
     * this entry under the master-password setting.
     */
    fun bioEnabled(ctx: Context): Boolean = sp(ctx).contains(K_DEK_BIO)

    /** Step one of turning on fingerprint unlock: get a Cipher ready for authentication. null = this device cannot do it. */
    fun bioSealCipher(): Cipher? = bio.sealCipher()

    /**
     * Step two of turning on fingerprint unlock: after authentication
     * succeeds, use that Cipher to wrap the DEK and persist it.
     *
     * **The app must already be unlocked** (so the DEK is available). Same
     * as the two master-password calls: not a single byte of the field
     * ciphertext is touched.
     */
    fun bioSeal(ctx: Context, cipher: Cipher): Boolean {
        val key = key(ctx) ?: return false
        return runCatching {
            val ct = cipher.doFinal(key)
            sp(ctx).edit().putString(K_DEK_BIO, Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)).apply()
            true
        }.getOrElse {
            Log.w(TAG, "bio seal failed: ${it.message}")
            false
        }
    }

    /**
     * Step one of fingerprint unlock: get a Cipher ready for authentication
     * using the stored IV.
     *
     * Two possible reasons for null, handled the same way — **fall back to
     * the master password**: fingerprint unlock was never turned on, or the
     * key has been invalidated by the system (the user enrolled / cleared
     * a fingerprint; see [BiometricKey]). In the latter case the wrap is
     * deleted here, so we don't waste a try on it every unlock.
     */
    fun bioOpenCipher(ctx: Context): Cipher? {
        val blob = sp(ctx).getString(K_DEK_BIO, null) ?: return null
        val raw = runCatching { Base64.decode(blob, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (raw.size <= GCM_IV) return null
        val c = bio.openCipher(raw.copyOf(GCM_IV))
        if (c == null) {
            Log.w(TAG, "bio key invalidated, falling back to master password")
            disableBio(ctx)
        }
        return c
    }

    /** Step two of fingerprint unlock: after authentication, recover the DEK. Return false = wrap is broken, let the caller fall back to the master password. */
    fun bioUnlock(ctx: Context, cipher: Cipher): Boolean {
        val blob = sp(ctx).getString(K_DEK_BIO, null) ?: return false
        val opened = runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            cipher.doFinal(raw, GCM_IV, raw.size - GCM_IV)
        }.getOrElse {
            Log.w(TAG, "bio unlock failed: ${it.message}")
            null
        } ?: return false
        dek = opened
        return true
    }

    /** Turn off fingerprint unlock. Only this wrap is removed — the master password copy and the field ciphertext are untouched. */
    fun disableBio(ctx: Context) {
        sp(ctx).edit().remove(K_DEK_BIO).apply()
        bio.dropKey()
    }

    /**
     * Drop the DEK and remove both wraps (the master-password one and the
     * Keystore one) together.
     *
     * All field ciphertext becomes permanently undecryptable, so **there is
     * no UI entry that calls this** — forgetting the master password has no
     * way out, and leaving such an entry would be the same as the master
     * password never having taken effect. It is kept here for test reset.
     */
    fun reset(ctx: Context) {
        lock()
        sp(ctx).edit().remove(K_DEK_PW).remove(K_DEK_KS).remove(K_DEK_BIO).apply()
        bio.dropKey()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(AndroidKeystore.ALIAS)
        }
    }

    // ---- Internal ----

    /** Get the current DEK; generate one on the fly if there is none (first run). Returns null if the master password is on but not yet unlocked. */
    private fun key(ctx: Context): ByteArray? {
        dek?.let { return it }
        synchronized(this) {
            dek?.let { return it }
            val s = sp(ctx)
            if (s.contains(K_DEK_PW)) return null // master password is required; wait for unlock()
            val wrapped = s.getString(K_DEK_KS, null)
            if (wrapped != null) {
                val opened = wrapper.unwrap(wrapped)
                if (opened != null) {
                    dek = opened
                    return opened
                }
                // The Keystore key is gone (factory reset / a lockscreen
                // change invalidated it). The old ciphertext is already
                // undecryptable; generating a new one at least lets the
                // passwords the user re-enters from now on be saved.
                Log.w(TAG, "DEK unwrap failed, regenerating")
            }
            if (!wrapper.available()) return null
            val fresh = ByteArray(KEY_LEN).also { rng.nextBytes(it) }
            val blob = wrapper.wrap(fresh) ?: return null
            s.edit().putString(K_DEK_KS, blob).apply()
            dek = fresh
            return fresh
        }
    }

    /** `<salt(16)><iv(12)><ciphertext‖tag>`; scrypt parameters are baked into constants (changing them means bumping the blob version). */
    private fun sealWithPassword(key: ByteArray, password: CharArray, salt: ByteArray): String? =
        runCatching {
            val kek = scrypt(password, salt)
            val iv = ByteArray(GCM_IV).also { rng.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            Base64.encodeToString(salt + iv + c.doFinal(key), Base64.NO_WRAP)
        }.getOrNull()

    private fun openWithPassword(blob: String, password: CharArray): ByteArray? =
        runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val kek = scrypt(password, raw.copyOf(16))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(kek, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(16, 16 + GCM_IV)),
            )
            c.doFinal(raw, 16 + GCM_IV, raw.size - 16 - GCM_IV)
        }.getOrNull()

    /**
     * scrypt. Routes through BouncyCastle — it is **already a dependency**
     * (`fs-restic` uses it to decrypt restic repos, `fs-network` uses it to
     * replace Android's stripped-down BC), so the APK delta is 0.
     *
     * The JDK's own `PBKDF2WithHmacSHA256` does not exist on minSdk 24 (it
     * arrived at API 26), and the SHA1 variant's GPU resistance is far too
     * weak to justify using it just to skip a dependency we already have.
     */
    fun scrypt(password: CharArray, salt: ByteArray): ByteArray {
        val pw = String(password).toByteArray(Charsets.UTF_8)
        return org.bouncycastle.crypto.generators.SCrypt.generate(
            pw, salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LEN,
        )
    }

    /** Production implementation: the key material is generated inside the TEE / StrongBox and never leaves the secure world. */
    object AndroidKeystore : Wrapper {

        const val ALIAS = "twig_dek"

        override fun available(): Boolean = runCatching { secretKey() != null }.getOrDefault(false)

        override fun wrap(dek: ByteArray): String? = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secretKey())
            Base64.encodeToString(c.iv + c.doFinal(dek), Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "keystore wrap failed: ${it.message}")
            null
        }

        override fun unwrap(blob: String): ByteArray? = runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, raw.copyOf(GCM_IV)))
            c.doFinal(raw, GCM_IV, raw.size - GCM_IV)
        }.getOrElse {
            Log.w(TAG, "keystore unwrap failed: ${it.message}")
            null
        }

        private fun secretKey(): javax.crypto.SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Deliberately do **not** setUserAuthenticationRequired:
                    // doing so would require a fingerprint / lockscreen prompt
                    // for every single password read, and background services
                    // (WiFi sharing, playback progress reporting) cannot show
                    // a dialog. Anyone who wants that level of strength should
                    // turn on the master password — that path holds for
                    // background services too (unlock once, DEK lives in
                    // process memory).
                    .build(),
            )
            return gen.generateKey()
        }
    }

    /**
     * Production implementation: a **biometric-bound** Keystore key.
     *
     * The only difference from [AndroidKeystore] is two flags, and those two
     * flags are the whole reason this path can coexist with the master
     * password:
     *
     * - `setUserAuthenticationRequired(true)` — every single use of this key
     *   requires the TEE to hold a valid auth token. The post-root attack
     *   that quietly decrypts via [AndroidKeystore] is closed off here.
     * - `setInvalidatedByBiometricEnrollment(true)` — when the user
     *   **enrolls a new fingerprint**, this key is invalidated on the spot
     *   and decrypt throws `KeyPermanentlyInvalidatedException`. That is
     *   exactly what we want: whoever can add a fingerprint to this device
     *   should not also walk away with all the server passwords. Once
     *   invalidated, [bioOpenCipher] deletes the wrap and falls back to the
     *   master password.
     *
     * The platform `BiometricPrompt` is API 28+, so on earlier systems this
     * whole feature is not available (see `ui/Biometrics`). Pulling in
     * `androidx.biometric` for it is not worth it — that package drags a
     * fragment in and adds several hundred KB to the APK, just to support
     * the API 23~27 cohort.
     */
    object BiometricKey : BioCrypto {

        const val ALIAS = "twig_dek_bio"

        override fun sealCipher(): Cipher? = runCatching {
            dropKey() // replace the key every time it's turned on: the old one may have been left over from before the most recent fingerprint enrolment
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            val key = gen.generateKey()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrElse {
            // The most common case: no fingerprint is enrolled on the system,
            // so creating the key throws InvalidAlgorithmParameterException directly
            Log.w(TAG, "bio key create failed: ${it.message}")
            null
        }

        override fun openCipher(iv: ByteArray): Cipher? = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        }.getOrElse {
            // This is where KeyPermanentlyInvalidatedException lands: the user enrolled a new fingerprint
            Log.w(TAG, "bio key unusable: ${it.message}")
            null
        }

        override fun dropKey() {
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
            }
        }
    }
}
