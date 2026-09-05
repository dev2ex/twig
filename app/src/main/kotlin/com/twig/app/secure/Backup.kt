package com.twig.app.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.twig.app.ConnectionStore
import com.twig.app.Prefs
import com.twig.app.SavedConnection
import com.twig.app.share.ShareStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Export / import for configuration backups (`.twigbak`).
 *
 * ## Why the exported file must be self-contained
 *
 * The local password ciphertext is encrypted with a **Keystore key**, and
 * that key is generated in this device's TEE and never leaves it. Copying
 * the ciphertext to a new phone is the same as copying a pile of bytes that
 * cannot be decrypted. So on export we **decrypt it to plaintext first** and
 * re-encrypt under the export password; on import the other side decrypts
 * it and uses its own DEK to re-encrypt before persisting. The two devices'
 * DEKs are different and mutually unknown — and that is correct.
 *
 * ## Two forms
 *
 * ```
 * Unencrypted   {"twigbak":1,…}               ← just UTF-8 JSON, any text editor can read it
 * Encrypted     TWIGBAK\x01 │ N r p │ salt │ iv │ AES-256-GCM(same JSON)
 *               └────────── plaintext header, fed in whole as AAD ─────┘
 * ```
 *
 * The unencrypted form **deliberately has no container of its own**: the
 * only value of that form is "any tool can open it and look", and wrapping
 * it in yet another custom format would erase that one benefit. At import,
 * the first byte (`{` / `T`) distinguishes the two.
 *
 * The scrypt parameters in the plaintext header are required to decrypt,
 * but the entire header goes into the GCM's AAD — anyone who lowers N from
 * 32768 to 1 to make cracking cheaper immediately fails the tag check.
 *
 * ## Password strength is the file's strength
 *
 * The backup file's destiny is to be thrown into a cloud drive or a chat
 * log of one's own. Once it leaks, the attacker has the **full, offline,
 * infinitely retryable** set of server credentials. scrypt's 32 MB memory
 * hard property blocks large-scale GPU parallelism (for comparison, the
 * WinZip AES used by encrypted zips is 1000 rounds of PBKDF2-HMAC-SHA1,
 * which is roughly ten thousand times faster on the same GPU), but it
 * cannot stop `123456`.
 */
object Backup {

    const val EXT = "twigbak"

    /** Version number sits at byte 8; future format changes will branch from here. */
    private val MAGIC = byteArrayOf(
        'T'.code.toByte(), 'W'.code.toByte(), 'I'.code.toByte(), 'G'.code.toByte(),
        'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 1,
    )

    private const val SALT = 16
    private const val IV = 12
    private const val TAG_BITS = 128

    /** Plaintext header = magic(8) + N/r/p(12) + salt(16) + iv(12). Fed in whole as AAD. */
    private const val HEAD = 8 + 12 + SALT + IV

    private const val VERSION = 1

    /**
     * The prefs files that go through the generic dump.
     *
     * **[Secrets]'s own `twig_secure` is explicitly excluded** — it holds the
     * Keystore-wrapped DEK, which another device cannot unwrap, and it is key
     * material with no reason to ever leave this device.
     * The four files containing passwords are not in this list either; they
     * take the structured plaintext path below.
     */
    private val PREF_FILES = listOf(
        "twig_prefs", "twig_favorites", "twig_sort", "twig_history",
        "twig_compares", "twig_cmds", "twig_playlists", "twig_playback",
    )

    /** Where SFTP private keys are persisted (see `MainActivity`'s keyPickerLauncher). */
    private fun keyDir(ctx: Context) = File(ctx.filesDir, "keys")

    /** Import result, used to give the user a one-liner about how much came in. */
    data class Result(val connections: Int, val passwords: Int, val keys: Int, val prefs: Int)

    class BadPassword : Exception("wrong password or corrupt file")

    class BadFormat : Exception("not a Twig backup")

    // ---- Export ----

    /**
     * Export to [out]. [password] being null / empty = plaintext JSON.
     *
     * Note that everything read here is **plaintext**: `ConnectionStore.all`
     * has already been decrypted. If the master password is on but the app
     * is locked, what we read would still be the raw ciphertext — the
     * caller must ensure the app is unlocked first (see `Secrets.locked`).
     */
    fun export(ctx: Context, out: OutputStream, password: CharArray?) {
        val json = build(ctx).toString(2).toByteArray(Charsets.UTF_8)
        if (password == null || password.isEmpty()) {
            out.write(json)
            out.flush()
            return
        }
        val rng = SecureRandom()
        val salt = ByteArray(SALT).also { rng.nextBytes(it) }
        val iv = ByteArray(IV).also { rng.nextBytes(it) }
        val head = ByteBuffer.allocate(HEAD).apply {
            put(MAGIC)
            putInt(Secrets.SCRYPT_N); putInt(Secrets.SCRYPT_R); putInt(Secrets.SCRYPT_P)
            put(salt); put(iv)
        }.array()

        val kek = Secrets.scrypt(password, salt)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(head)
        out.write(head)
        out.write(c.doFinal(json))
        out.flush()
    }

    private fun build(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("twigbak", VERSION)
        o.put("time", System.currentTimeMillis())
        o.put("app", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "")

        // Connections: toJson() already gives plaintext (encryption only happens
        // when ConnectionStore writes to disk).
        // Tokens are not exported — they will necessarily be invalid on another
        // device, and bringing them along would just make the first connection
        // bounce off a 401 before asking for a re-login.
        // hostKey is also not exported: SFTP's TOFU fingerprint should be
        // re-confirmed on the new device; copying it across would walk right
        // past the "verify on first connect" defence.
        val conns = JSONArray()
        ConnectionStore.all(ctx).forEach { conns.put(it.copy(token = "", userId = "", hostKey = "").toJson()) }
        o.put("connections", conns)

        o.put("resticPw", JSONObject(Prefs.resticPasswords(ctx) as Map<*, *>))
        o.put("archivePw", JSONObject(Prefs.archivePasswords(ctx) as Map<*, *>))

        // Share config: load() has already decrypted, so we store the password in plaintext here
        val share = ShareStore.load(ctx)
        o.put(
            "share",
            JSONObject().apply {
                put("ro", share.readOnly); put("port", share.port)
                put("user", share.user); put("pass", share.password)
                put("name", share.deviceName)
            },
        )

        val prefs = JSONObject()
        PREF_FILES.forEach { f ->
            val dump = dumpPrefs(ctx.getSharedPreferences(f, Context.MODE_PRIVATE))
            if (dump.length() > 0) prefs.put(f, dump)
        }
        o.put("prefs", prefs)

        val keys = JSONObject()
        keyDir(ctx).listFiles()?.filter { it.isFile }?.forEach {
            keys.put(it.name, Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
        }
        o.put("keys", keys)
        return o
    }

    // ---- Import ----

    /** Whether the file is encrypted. Readable from the first 8 bytes alone; no password needed. */
    fun isEncrypted(head: ByteArray): Boolean =
        head.size >= 8 && head.copyOf(8).contentEquals(MAGIC)

    /**
     * Import. [password] is only used when the file is encrypted.
     *
     * **Merge, not replace**: connections with the same label and preferences
     * with the same key are overwritten by the backup, and anything extra on
     * the new device is kept. A backup is "bring the stuff from that
     * machine over", not "turn this machine into that one" — the latter
     * gives no way back if it was a mistake.
     */
    fun import(ctx: Context, raw: ByteArray, password: CharArray?): Result {
        val json = if (isEncrypted(raw)) {
            val pw = password ?: throw BadPassword()
            decrypt(raw, pw)
        } else {
            String(raw, Charsets.UTF_8)
        }
        val o = runCatching { JSONObject(json) }.getOrElse { throw BadFormat() }
        if (!o.has("twigbak")) throw BadFormat()
        return apply(ctx, o)
    }

    private fun decrypt(raw: ByteArray, password: CharArray): String {
        if (raw.size < HEAD + 16) throw BadFormat()
        val head = raw.copyOf(HEAD)
        val b = ByteBuffer.wrap(head)
        b.position(8)
        val n = b.int
        val r = b.int
        val p = b.int
        val salt = ByteArray(SALT).also { b.get(it) }
        val iv = ByteArray(IV).also { b.get(it) }
        // Parameters are taken from the file rather than from the current
        // constants: if the defaults are tuned later, old backups still decrypt
        val kek = runCatching {
            org.bouncycastle.crypto.generators.SCrypt.generate(
                String(password).toByteArray(Charsets.UTF_8), salt, n, r, p, Secrets.KEY_LEN,
            )
        }.getOrElse { throw BadFormat() }
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(head)
            String(c.doFinal(raw, HEAD, raw.size - HEAD), Charsets.UTF_8)
        }.getOrElse {
            // The GCM tag is the "is the password correct" check; we don't
            // store a separate checksum. Cannot decrypt = wrong password or
            // the file has been tampered with.
            throw BadPassword()
        }
    }

    private fun apply(ctx: Context, o: JSONObject): Result {
        // Land private keys first: the connections' keyPath needs to be
        // re-pointed at a path on this device
        var keyCount = 0
        val keyMap = HashMap<String, String>()
        o.optJSONObject("keys")?.let { keys ->
            val dir = keyDir(ctx).apply { mkdirs() }
            keys.keys().forEach { name ->
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                runCatching {
                    val f = File(dir, safe)
                    f.writeBytes(Base64.decode(keys.getString(name), Base64.NO_WRAP))
                    // Private keys are readable only by us: SSHJ doesn't
                    // check permissions, but no other app on this device has
                    // any business seeing them
                    f.setReadable(false, false); f.setReadable(true, true)
                    f.setWritable(false, false); f.setWritable(true, true)
                    keyMap[safe] = f.path
                    keyCount++
                }
            }
        }

        var connCount = 0
        o.optJSONArray("connections")?.let { arr ->
            val existing = ConnectionStore.all(ctx).associateBy { it.label() }.toMutableMap()
            for (i in 0 until arr.length()) {
                runCatching {
                    val c = SavedConnection.fromJson(arr.getJSONObject(i))
                    // The keyPath in the backup is an absolute path on the
                    // other device, which doesn't exist here; re-locate by
                    // file name
                    val fixed = if (c.keyPath.isNotEmpty()) {
                        c.copy(keyPath = keyMap[c.keyPath.substringAfterLast('/')] ?: c.keyPath)
                    } else {
                        c
                    }
                    existing[fixed.label()] = fixed
                    connCount++
                }
            }
            ConnectionStore.replaceAll(ctx, existing.values.toList())
        }

        var pwCount = 0
        o.optJSONObject("resticPw")?.let { m ->
            m.keys().forEach { k -> Prefs.setResticPassword(ctx, k, m.getString(k)); pwCount++ }
        }
        o.optJSONObject("archivePw")?.let { m ->
            m.keys().forEach { k -> Prefs.setArchivePassword(ctx, k, m.getString(k)); pwCount++ }
        }

        o.optJSONObject("share")?.let { s ->
            // scope is not imported: it points at some directory / server on
            // that device and usually doesn't match anything here
            val cur = ShareStore.load(ctx)
            ShareStore.save(
                ctx,
                cur.copy(
                    readOnly = s.optBoolean("ro", cur.readOnly),
                    port = s.optInt("port", cur.port),
                    user = s.optString("user", cur.user),
                    password = s.optString("pass", cur.password),
                    deviceName = s.optString("name", cur.deviceName),
                ),
            )
            pwCount++
        }

        var prefCount = 0
        o.optJSONObject("prefs")?.let { all ->
            all.keys().forEach { file ->
                if (file !in PREF_FILES) return@forEach // anything off the whitelist is not written back, to defend against a constructed backup file
                val sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
                prefCount += restorePrefs(sp, all.getJSONObject(file))
            }
        }
        return Result(connCount, pwCount, keyCount, prefCount)
    }

    // ---- Generic prefs dump / restore ----

    /**
     * Dump by type. SharedPreferences has six value types, and losing the
     * type info makes `getBoolean` throw ClassCastException on restore —
     * shown as "the settings page crashes the moment it opens after import".
     */
    private fun dumpPrefs(sp: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val e = JSONObject()
            when (v) {
                is String -> { e.put("t", "s"); e.put("v", v) }
                is Int -> { e.put("t", "i"); e.put("v", v) }
                is Long -> { e.put("t", "l"); e.put("v", v) }
                is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                is Boolean -> { e.put("t", "b"); e.put("v", v) }
                is Set<*> -> { e.put("t", "ss"); e.put("v", JSONArray(v.map { it.toString() })) }
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    private fun restorePrefs(sp: SharedPreferences, o: JSONObject): Int {
        val ed = sp.edit()
        var n = 0
        o.keys().forEach { k ->
            val e = o.optJSONObject(k) ?: return@forEach
            runCatching {
                when (e.optString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "ss" -> {
                        val a = e.getJSONArray("v")
                        ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                    else -> return@runCatching
                }
                n++
            }
        }
        ed.apply()
        return n
    }

    /** Read the whole file. Backup files are a few tens of KB (private keys are a few KB) so reading the whole thing into memory is fine. */
    fun readAll(input: InputStream): ByteArray = input.use { it.readBytes() }
}
