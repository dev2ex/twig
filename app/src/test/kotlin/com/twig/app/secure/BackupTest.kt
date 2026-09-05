package com.twig.app.secure

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Prefs
import com.twig.app.SavedConnection
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Export / import of config backups.
 *
 * The one thing that matters most is **it still works on a different device**: the local
 * ciphertext is encrypted with the Keystore key and cannot be decrypted on another
 * machine, so export must first decrypt to plaintext and then re-encrypt with the export
 * password. "Switching devices" in these tests means clearing all SharedPreferences and
 * swapping in a fresh DEK — equivalent to installing a new app on a real device.
 */
@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    private object MemoryWrapper : Secrets.Wrapper {
        var key: ByteArray? = null
        override fun wrap(dek: ByteArray): String { key = dek.copyOf(); return "mem" }
        override fun unwrap(blob: String): ByteArray? = key?.copyOf()
        override fun available() = true
    }

    @Before
    fun setUp() {
        MemoryWrapper.key = null
        Secrets.wrapper = MemoryWrapper
        Secrets.reset(ctx)
        wipeAll()
        seed()
    }

    @After
    fun tearDown() {
        Secrets.wrapper = Secrets.AndroidKeystore
    }

    /** Sets up an "old phone": one SMB connection, one archive password, one boolean preference. */
    private fun seed() {
        ConnectionStore.save(
            ctx,
            SavedConnection(
                type = "smb", host = "10.0.0.1", share = "public", user = "vale",
                password = "s3cret-smb", name = "Home NAS",
                token = "stale-token", hostKey = "SHA256:abcdef",
            ),
        )
        Prefs.setArchivePassword(ctx, "/sdcard/x.zip", "zip-pw")
        Prefs.setThumbs(ctx, true)
    }

    private fun wipeAll() {
        listOf(
            "twig_connections", "twig_prefs", "twig_archive_pw", "twig_restic_pw",
            "twig_share", "twig_favorites", "twig_secure",
        ).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun export(pw: CharArray?): ByteArray =
        ByteArrayOutputStream().also { Backup.export(ctx, it, pw) }.toByteArray()

    /** Switching devices: the config is entirely gone, and the DEK is a fresh one too. */
    private fun freshDevice() {
        wipeAll()
        Secrets.lock()
        MemoryWrapper.key = null
    }

    @Test
    fun `an unencrypted export is plain JSON - any text editor can open it`() {
        val raw = export(null)
        assertEquals('{'.code.toByte(), raw[0])
        assertFalse(Backup.isEncrypted(raw))
        val o = JSONObject(String(raw, Charsets.UTF_8))
        assertEquals(1, o.getInt("twigbak"))
        assertTrue("unencrypted means plaintext, the password is visible - that is exactly what the warning text says", String(raw).contains("s3cret-smb"))
    }

    @Test
    fun `no plaintext at all is visible in an encrypted export`() {
        val raw = export("export-pw".toCharArray())
        assertTrue(Backup.isEncrypted(raw))
        val text = String(raw, Charsets.ISO_8859_1)
        assertFalse(text.contains("s3cret-smb"))
        assertFalse(text.contains("zip-pw"))
        assertFalse("even the hostname must not leak", text.contains("10.0.0.1"))
    }

    /** ★ The whole reason this feature exists: switch phones, and the password really still works. */
    @Test
    fun `after importing an encrypted backup on a different device, the password comes back unchanged`() {
        val raw = export("export-pw".toCharArray())
        freshDevice()
        assertTrue("first confirm the switch was really clean", ConnectionStore.all(ctx).isEmpty())

        val r = Backup.import(ctx, raw, "export-pw".toCharArray())
        assertEquals(1, r.connections)

        val c = ConnectionStore.all(ctx).single()
        assertEquals("s3cret-smb", c.password)
        assertEquals("Home NAS", c.name)
        assertEquals("zip-pw", Prefs.archivePassword(ctx, "/sdcard/x.zip"))
        assertTrue(Prefs.thumbs(ctx))
    }

    @Test
    fun `an unencrypted backup can be imported too`() {
        val raw = export(null)
        freshDevice()
        Backup.import(ctx, raw, null)
        assertEquals("s3cret-smb", ConnectionStore.all(ctx).single().password)
    }

    /** What is stored on disk after import is still ciphertext - encrypted with the new device's own DEK, not the one from the backup. */
    @Test
    fun `after import, the local store holds ciphertext, not plaintext`() {
        val raw = export("export-pw".toCharArray())
        freshDevice()
        Backup.import(ctx, raw, "export-pw".toCharArray())

        val stored = ctx.getSharedPreferences("twig_connections", Context.MODE_PRIVATE).getString("list", "")!!
        assertFalse("if importing flattens it straight to plaintext, the whole encrypted store was pointless", stored.contains("s3cret-smb"))
        assertTrue(stored.contains(Secrets.PREFIX))
    }

    @Test
    fun `a wrong password reports BadPassword, not a format error`() {
        val raw = export("right".toCharArray())
        freshDevice()
        try {
            Backup.import(ctx, raw, "wrong".toCharArray())
            throw AssertionError("should have thrown BadPassword")
        } catch (e: Backup.BadPassword) {
            // expected
        }
    }

    /**
     * ★ The plaintext header must be tamper-proof. scrypt's N/r/p and salt participate in
     * both **key derivation** and **GCM's AAD**, so anyone who tries to lower N from
     * 32768 to cut cracking cost only ends up with a file that can never be decrypted
     * again.
     *
     * Changed here to 16384 (a legal power of two, scrypt can actually run with it)
     * rather than 1 — the latter is an illegal parameter that BouncyCastle rejects
     * outright, which would only test "parameter validation", not "tampering makes it
     * undecryptable".
     */
    @Test
    fun `tampering with the scrypt parameters in the file header makes the file undecryptable`() {
        val raw = export("export-pw".toCharArray())
        // N is the first int (big-endian) after the magic: 32768 -> 16384
        raw[8] = 0; raw[9] = 0; raw[10] = 0x40; raw[11] = 0
        freshDevice()
        try {
            Backup.import(ctx, raw, "export-pw".toCharArray())
            throw AssertionError("a tampered file should not be decryptable")
        } catch (e: Backup.BadPassword) {
            // expected: to the user it is just "this file can't be used", no distinction
            // between corrupted and wrong password
        }
    }

    /** Changing even a single byte of the ciphertext body makes it undecryptable - that is exactly what GCM's tag guards against. */
    @Test
    fun `tampering with the ciphertext makes the file undecryptable`() {
        val raw = export("export-pw".toCharArray())
        raw[raw.size - 20] = (raw[raw.size - 20] + 1).toByte()
        freshDevice()
        try {
            Backup.import(ctx, raw, "export-pw".toCharArray())
            throw AssertionError("tampered ciphertext should not be decryptable")
        } catch (e: Backup.BadPassword) {
            // expected
        }
    }

    @Test
    fun `something that is not a backup file reports BadFormat`() {
        try {
            Backup.import(ctx, "hello world".toByteArray(), null)
            throw AssertionError("should have thrown BadFormat")
        } catch (e: Backup.BadFormat) {
            // expected
        }
    }

    /**
     * The token is bound to become invalid on a different device - carrying it over would
     * only make the first connection hit a 401 before logging in again; hostKey is SFTP's
     * TOFU fingerprint, and moving it over directly would bypass the "first connection
     * must be verified" safeguard entirely.
     */
    @Test
    fun `the token and hostKey are not included in the backup`() {
        val text = String(export(null), Charsets.UTF_8)
        assertFalse(text.contains("stale-token"))
        assertFalse(text.contains("SHA256:abcdef"))
    }

    /**
     * ★ Secrets' own DEK must never be exported: it is key material wrapped by the
     * Keystore, cannot be decrypted on a different device, and has no reason to ever
     * leave this machine.
     */
    @Test
    fun `the DEK is not included in the backup`() {
        val o = JSONObject(String(export(null), Charsets.UTF_8))
        val prefs = o.getJSONObject("prefs")
        assertFalse("twig_secure is not on the export whitelist", prefs.has("twig_secure"))
    }

    /**
     * SharedPreferences values have six types. Writing them back with the type
     * information lost makes `getBoolean` throw ClassCastException — the symptom is "the
     * settings page crashes the moment it opens after import".
     */
    @Test
    fun `preference types survive export and import unchanged`() {
        val sp = ctx.getSharedPreferences("twig_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("b", true).putInt("i", 42).putLong("l", 7L)
            .putFloat("f", 1.5f).putString("s", "x")
            .putStringSet("ss", setOf("a", "b")).commit()

        val raw = export(null)
        freshDevice()
        Backup.import(ctx, raw, null)

        val back = ctx.getSharedPreferences("twig_prefs", Context.MODE_PRIVATE)
        assertEquals(true, back.getBoolean("b", false))
        assertEquals(42, back.getInt("i", 0))
        assertEquals(7L, back.getLong("l", 0))
        assertEquals(1.5f, back.getFloat("f", 0f), 0.0001f)
        assertEquals("x", back.getString("s", null))
        assertEquals(setOf("a", "b"), back.getStringSet("ss", null))
    }

    /**
     * Import is a **merge**: a connection already present on the new machine must be
     * kept. A backup is "bring over what was on that machine", not "turn this machine
     * into that one" — if the latter were a mistake there would be no way back.
     */
    @Test
    fun `import merges, it does not delete connections already on this device`() {
        val raw = export(null)
        freshDevice()
        ConnectionStore.save(
            ctx,
            SavedConnection(type = "ftp", host = "192.168.1.2", user = "u", password = "local-only"),
        )

        Backup.import(ctx, raw, null)

        val all = ConnectionStore.all(ctx)
        assertEquals(2, all.size)
        assertEquals("local-only", all.first { it.type == "ftp" }.password)
        assertEquals("s3cret-smb", all.first { it.type == "smb" }.password)
    }

    /** The keyPath in the backup is an absolute path on that other device, which does not exist here, and must be relocated by file name. */
    @Test
    fun `the private key travels with the backup, keyPath is relocated to this device`() {
        val old = java.io.File(ctx.filesDir, "keys").apply { mkdirs() }
        java.io.File(old, "id_ed25519").writeText("-----BEGIN OPENSSH PRIVATE KEY-----\n")
        ConnectionStore.save(
            ctx,
            SavedConnection(
                type = "sftp", host = "h", port = 22, user = "u",
                keyPath = "/data/user/0/com.twig.app/files/keys/id_ed25519",
            ),
        )
        val raw = export(null)

        freshDevice()
        java.io.File(old, "id_ed25519").delete()

        val r = Backup.import(ctx, raw, null)
        assertEquals(1, r.keys)
        val c = ConnectionStore.all(ctx).first { it.type == "sftp" }
        assertTrue("the path must point to a file that really exists on this device", java.io.File(c.keyPath).exists())
        assertTrue(java.io.File(c.keyPath).readText().startsWith("-----BEGIN"))
    }
}
