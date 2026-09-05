package com.twig.app

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ui.FakeFileSystem
import com.twig.app.ui.PaneViewModel
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isMutable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A granted document tree root is a **grant**, not an ordinary folder. Two things follow,
 * and both are the kind that fail silently:
 *
 * - it may be copied but never **moved** — a move deletes the source, which here is the
 *   very folder the grant points at, leaving the grant aimed at nothing;
 * - dropping it has to release the **tree** URI, while the row carries a *document* URI.
 *   Handing back the wrong one throws nothing at all: the grant stays and the row is back
 *   on the next refresh.
 *
 * The row's name is decided here too: a third-party provider's document id is a real path,
 * so the row is named after the app that owns it instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafTreeRootTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Real shape: a tree segment plus a document segment, separators inside the ids as %2F. */
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val rootUri = "$treeUri/document/primary%3ADCIM"
    private val childUri = "$rootUri%2Fholiday"

    private fun saf(uri: String) = XFile("saf", uri, isDir = true, displayName = "x")

    @Before
    fun setUp() {
        // SAF is writable, so isMutable() is true for everything in it — that is precisely
        // why the root needs a rule of its own.
        FsRegistry.register(FakeFileSystem("saf", emptyMap(), writable = true))
    }

    @After
    fun tearDown() { FsRegistry.unregister("saf") }

    @Test
    fun `the granted root is told apart from what is inside it`() {
        assertTrue("docId == treeDocId is the root itself", SafFileSystem.isTreeRoot(saf(rootUri)))
        assertFalse("a folder inside the tree is not", SafFileSystem.isTreeRoot(saf(childUri)))
        assertFalse(
            "and neither is anything from another backend",
            SafFileSystem.isTreeRoot(XFile("file", "/sdcard/DCIM", isDir = true)),
        )
    }

    @Test
    fun `the root can be copied but not moved, unlike what is inside it`() {
        val root = saf(rootUri)
        assertTrue("SAF itself is writable, so the generic test says yes", root.isMutable())
        assertFalse("…but the grant must not be the source of a move", root.isMovableSource())
        assertTrue("only the root is special", saf(childUri).isMovableSource())
    }

    @Test
    fun `dropping the grant works from the document URI the row carries`() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(treeUri.toUri(), flags)
        assertEquals(1, app.contentResolver.persistedUriPermissions.size)

        assertTrue(SafFileSystem.release(app, saf(rootUri)))
        assertEquals(
            "the grant is gone, so the row cannot come back on the next refresh",
            0, app.contentResolver.persistedUriPermissions.size,
        )
    }

    @Test
    fun `dropping a tree that was never granted reports failure instead of pretending`() {
        assertFalse(SafFileSystem.release(app, saf(rootUri)))
    }

    /**
     * A tree granted by a third-party app: the document id is a real path
     * (`/data/data/com.termux/files/home`), so the row has no decent name of its own —
     * the app it belongs to is the only thing worth showing. The package comes back with
     * it so the row can wear the app's icon.
     */
    @Test
    fun `a tree from a third-party app is named after the app`() {
        installProvider("com.termux.documents", "com.termux", "Termux", system = false)
        val root = saf("content://com.termux.documents/tree/$TERMUX_ID/document/$TERMUX_ID")

        val provider = SafFileSystem.providerApp(app, root)
        assertEquals("Termux", provider?.label)
        assertEquals("com.termux", provider?.pkg)
    }

    /**
     * ★ System providers stay out of it: external storage hands out ids like
     * `primary:DCIM`, whose tail already **is** the name to show — replacing every such
     * row with "External Storage" would be strictly worse.
     */
    @Test
    fun `a system provider keeps the folder name`() {
        installProvider("com.android.externalstorage.documents", "com.android.x", "External Storage", system = true)
        assertNull(SafFileSystem.providerApp(app, saf(rootUri)))
    }

    /**
     * Two trees granted by the same app would otherwise be two rows reading "Termux",
     * with nothing to tell them apart — the folder name comes back for those.
     * Everything from a provider we cannot name keeps the document id's tail.
     */
    @Test
    fun `two trees from one app are told apart, and unnamed providers keep the folder`() {
        installProvider("com.termux2.documents", "com.termux2", "Termux", system = false)
        listOf(
            "content://com.termux2.documents/tree/%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fhome",
            "content://com.termux2.documents/tree/%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fusr",
            "content://com.unknown.documents/tree/primary%3ADCIM",
        ).forEach {
            app.contentResolver.takePersistableUriPermission(it.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val vm = PaneViewModel(app)
        vm.expandGroup("saf")
        val names = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.scheme == "saf" }.map { it.file.name }

        assertEquals(listOf("Termux · home", "Termux · usr", "DCIM"), names)
    }

    private fun installProvider(authority: String, pkg: String, label: String, system: Boolean) {
        val info = ProviderInfo().apply {
            this.authority = authority
            this.packageName = pkg
            name = "$pkg.DocumentsProvider" // ShadowPackageManager rejects a component with no class name
            applicationInfo = ApplicationInfo().apply {
                this.packageName = pkg
                nonLocalizedLabel = label
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
        shadowOf(app.packageManager).addOrUpdateProvider(info)
    }

    private companion object {
        const val TERMUX_ID = "%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fhome"
    }
}
