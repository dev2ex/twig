package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The boundary of the rule "once registered, reuse it".
 *
 * The scheme is derived deterministically from the connection label, so **changing the
 * password leaves the label unchanged and therefore the scheme unchanged** — reusing
 * unconditionally would hand back the instance built from the stale config as-is. This
 * has shown up historically in two guises:
 *
 * 1. Editing a connection to change its password had no effect (blocked by an explicit
 *    unregister via `PaneViewModel.forgetServer`);
 * 2. With the master password on, a pane expanded a server before it was unlocked — the
 *    "password" read at that time was ciphertext, and the instance built from it kept
 *    carrying it. Unlocking only fixes the storage layer; an already-registered instance
 *    does not fix itself, and the symptom is "login fails, but editing something trivial
 *    and saving again makes it work".
 *
 * The check belongs inside `Connections.ensure` to be independent of caller timing, so
 * this tests it directly.
 *
 * WebDAV is used because its constructor makes no network request at all (SMB/SFTP
 * connect immediately).
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionsRebuildTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    private fun dav(host: String, password: String, token: String = "") = SavedConnection(
        type = "webdav", host = host, user = "vale", password = password, token = token,
    )

    /** ★ This is the regression test for "login fails right after entering the master password". */
    @Test
    fun `password change rebuilds the connection - the instance built with ciphertext while locked must not be reused`() {
        val locked = dav("https://a.example/dav", "enc1:AAAABBBBCCCC")
        val s = Connections.ensure(ctx, locked)
        val stale = FsRegistry.of(s)

        // After unlock, the same connection reads back the plaintext password; the label
        // is unchanged so the scheme is unchanged too
        val unlocked = dav("https://a.example/dav", "real-password")
        val s2 = Connections.ensure(ctx, unlocked)

        assertEquals("scheme is derived from the label, must not change", s, s2)
        assertNotSame("must be replaced with the instance built from the new password", stale, FsRegistry.of(s2))
    }

    /**
     * ★ The converse: the token is **learned after connecting**, not an input needed to
     * build the connection. Comparing it too would make Jellyfin trigger a
     * disconnect-and-reconnect every time it writes the token back, and the reconnect
     * would write it back again — an endless loop.
     */
    @Test
    fun `writing back the token does not trigger a reconnect`() {
        val fresh = dav("https://b.example/dav", "pw")
        val s = Connections.ensure(ctx, fresh)
        val first = FsRegistry.of(s)

        val afterLogin = dav("https://b.example/dav", "pw", token = "abc123")
        Connections.ensure(ctx, afterLogin)

        assertSame("only the token was learned, the connection itself has not changed at all", first, FsRegistry.of(s))
    }

    @Test
    fun `same instance is reused when the config is unchanged`() {
        val c = dav("https://c.example/dav", "pw")
        val s = Connections.ensure(ctx, c)
        val first = FsRegistry.of(s)
        Connections.ensure(ctx, dav("https://c.example/dav", "pw"))
        assertSame(first, FsRegistry.of(s))
    }

    /** A user genuinely changing their password (not a decryption artifact) takes the same path. */
    @Test
    fun `after the user changes the password, the next expand uses the new config`() {
        val s = Connections.ensure(ctx, dav("https://d.example/dav", "old"))
        val first = FsRegistry.of(s)
        Connections.ensure(ctx, dav("https://d.example/dav", "new"))
        assertNotSame(first, FsRegistry.of(s))
    }
}
