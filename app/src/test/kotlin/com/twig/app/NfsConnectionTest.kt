package com.twig.app

import android.Manifest
import android.app.Application
import android.os.Looper
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NfsConnectionTest {
    @Test fun oldRecordsDefaultTo2049AndCustomPortsRemainDistinct() {
        val old = JSONObject().put("type", "nfs").put("host", "nas").put("share", "/export")
            .put("port", 21) // The evaluation build serialized the unused FTP default.
        val standard = SavedConnection.fromJson(old)
        assertEquals(2049, standard.nfsPort)
        assertEquals("nfs://nas/export?version=3&uid=65534&gid=65534", standard.label())
        val custom = standard.copy(nfsPort = 3050)
        assertEquals(custom, SavedConnection.fromJson(custom.toJson()))
        assertNotEquals(Connections.schemeOf(standard), Connections.schemeOf(custom))
        assertEquals("nas:3050/export", custom.rawShortLabel())
        assertTrue(custom.copy(host = "::1").label().startsWith("nfs://[::1]:3050/"))
    }

    @Test fun disabledBuildPreservesSavedConnectionsAndRefusesToConnect() {
        org.junit.Assume.assumeFalse(NfsSupport.enabled)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val conn = SavedConnection("nfs", "2001:db8::1", share = "/export", nfsPort = 3050)
        ConnectionStore.save(app, conn)
        assertEquals(conn, ConnectionStore.all(app).single { it.type == "nfs" })
        assertEquals("nfs://[2001:db8::1]:3050/export?version=3&uid=65534&gid=65534", conn.label())
        assertFalse(RootItems.ordered(listOf("nfs", "fav")).any { it.id == "nfs" })
        val scheme = Connections.schemeOf(conn)
        try {
            Connections.ensure(app, conn)
            fail("A build without NFS must reject saved NFS locations")
        } catch (_: com.twig.core.FsException) {
            assertFalse(com.twig.core.FsRegistry.all().any { it.scheme == scheme })
        }
        assertEquals(conn, ConnectionStore.all(app).single { it.type == "nfs" })
    }

    @Test fun dialogSplitsPastedAddressesAndPreservesManualPortTyping() {
        org.junit.Assume.assumeTrue(NfsSupport.enabled)
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            activity.onAddServer("nfs")
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fun input(id: Int) = dialog.findViewById<EditText>(id)!!
            val host = input(R.id.et_host)
            val port = input(R.id.et_port)
            assertEquals("2049", port.text.toString())
            assertFalse(activity.getString(R.string.action_add_nfs).contains('＋'))
            host.setText("nas:3050")
            assertEquals("nas", host.text.toString())
            assertEquals("3050", port.text.toString())
            host.setText("[2001:db8::1]:4096")
            assertEquals("2001:db8::1", host.text.toString())
            assertEquals("4096", port.text.toString())
            host.setText("nas")
            ":2049".forEach { host.append(it.toString()) }
            assertEquals("nas:2049", host.text.toString())
            input(R.id.et_path).setText("/export")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse("Valid connection should close the editor", dialog.isShowing)
            val saved = ConnectionStore.all(activity).single { it.type == "nfs" }
            assertEquals("nas", saved.host)
            assertEquals(2049, saved.nfsPort)
            assertEquals(3, saved.nfsVersion)
        } finally { controller.stop().destroy() }
    }
}
