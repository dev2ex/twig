package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stored order of connections **is** the order the sidebar lists servers in
 * ([com.twig.app.ui.PaneViewModel.addGroup] walks `ConnectionStore.all` as-is), so a
 * write that only updates a field must never move the row.
 *
 * The incident (reported 2026-09-09): after importing a config backup, expanding the
 * first or second server moved it to the bottom of the group. The backup deliberately
 * exports `token` / `userId` / `hostKey` empty, so the *first* expansion of every server
 * writes those back ([Connections.rememberAuth] / `rememberHostKey`) — and `save` was
 * "drop the old entry, append the new one", which reordered the list behind the user's
 * back. Nothing about expanding a server is supposed to be persistent state at all.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionStoreOrderTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    private fun conn(host: String) = SavedConnection(type = "jellyfin", host = "http://$host", user = "u")

    private fun labels() = ConnectionStore.all(ctx).map { it.host }

    @Before
    fun setup() {
        ctx.getSharedPreferences(ConnectionStore.FILE, Application.MODE_PRIVATE).edit().clear().apply()
        ConnectionStore.replaceAll(ctx, listOf(conn("a"), conn("b"), conn("c")))
    }

    @Test
    fun `writing a token back keeps the server where it was`() {
        // What the first expansion of the second server does
        ConnectionStore.save(ctx, ConnectionStore.all(ctx)[1].copy(token = "t", userId = "uid"))
        assertEquals(listOf("http://a", "http://b", "http://c"), labels())
        assertEquals("t", ConnectionStore.all(ctx)[1].token)
    }

    @Test
    fun `a genuinely new connection still goes to the end`() {
        ConnectionStore.save(ctx, conn("d"))
        assertEquals(listOf("http://a", "http://b", "http://c", "http://d"), labels())
    }

    @Test
    fun `editing a server keeps its slot even when the label changes`() {
        // Renaming the host rewrites label(), so this cannot match on the new label
        val old = ConnectionStore.all(ctx)[0]
        ConnectionStore.replace(ctx, old, old.copy(host = "http://a2"))
        assertEquals(listOf("http://a2", "http://b", "http://c"), labels())
    }

    @Test
    fun `an edit that collides with another entry drops the other one`() {
        // Editing "a" into "c" leaves one connection, and it stays in a's slot
        val old = ConnectionStore.all(ctx)[0]
        ConnectionStore.replace(ctx, old, old.copy(host = "http://c"))
        assertEquals(listOf("http://c", "http://b"), labels())
    }
}
