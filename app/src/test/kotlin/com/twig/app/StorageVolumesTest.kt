package com.twig.app

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ui.PaneViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowStorageManager
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

/**
 * The "SD card / USB drive" rows on the tree root. The whole judgment rests on
 * **filtering rules** — which volumes should appear, and what name they get: get any of
 * these wrong and the symptom is "internal storage shows up as two rows on the root" or
 * "the card is inserted but invisible", and neither one raises an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageVolumesTest {

    private lateinit var app: Application
    private lateinit var sd: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        StorageVolumes.reset()
        sd = File(System.getProperty("java.io.tmpdir"), "twig-sd-1A2B-3C4D").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        StorageVolumes.reset()
        ShadowStorageManager.reset()
        sd.deleteRecursively()
    }

    private fun addVolume(
        path: File,
        desc: String,
        primary: Boolean,
        state: String = Environment.MEDIA_MOUNTED,
        uuid: String? = "1A2B-3C4D",
    ) {
        val sm = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val v = StorageVolumeBuilder("vol", path, desc, Process.myUserHandle(), state)
            .setIsPrimary(primary)
            .setIsRemovable(!primary)
            .setFsUuid(uuid)
            .build()
        Shadow.extract<ShadowStorageManager>(sm).addStorageVolume(v)
    }

    @Test
    fun `a removable volume enters the tree root, the primary volume does not duplicate a row`() {
        // ★ On some ROMs the primary volume's isRemovable is also true — filtering by
        // that would add an extra "Internal Storage" row
        addVolume(Environment.getExternalStorageDirectory(), "Internal Storage", primary = true)
        addVolume(sd, "SD Card", primary = false)

        val vols = StorageVolumes.cached(app)
        assertEquals(listOf(sd.absolutePath), vols.map { it.path })
        assertEquals("SD Card", vols[0].label)
    }

    @Test
    fun `a custom volume label shares the internal storage icon`() {
        addVolume(sd, "opi_root", primary = false)
        StorageVolumes.cached(app)
        assertEquals(
            R.drawable.ic_storage,
            com.twig.app.ui.FileIcons.storageRootIconRes(com.twig.core.XFile("file", sd.absolutePath, isDir = true)),
        )
        assertEquals(R.drawable.ic_sd_card, com.twig.app.ui.FileIcons.removableIconRes("SD card"))
        assertEquals(R.drawable.ic_usb_drive, com.twig.app.ui.FileIcons.removableIconRes("USB drive"))
    }

    @Test
    fun `an unmounted volume does not appear`() {
        addVolume(sd, "SD Card", primary = false, state = Environment.MEDIA_UNMOUNTED)
        assertTrue(StorageVolumes.cached(app).isEmpty())
    }

    @Test
    fun `an empty description falls back to the uuid instead of a nameless row`() {
        // passing an empty string to XFile.displayName also turns name into an empty
        // string, which would show up on the tree as a blank row
        addVolume(sd, "", primary = false)
        assertEquals("1A2B-3C4D", StorageVolumes.cached(app).single().label)
    }

    @Test
    fun `a volume root cannot become the default action target - guards against wiping the whole card`() {
        addVolume(sd, "SD Card", primary = false)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.refreshTree()
        val row = vm.state.value.rows.single { it.key == "f:file:${sd.absolutePath}" }
        vm.toggle(row) // expanding this row = the green highlight lands on the volume root
        assertNull("the whole card must not become the default copy/delete target", vm.currentSelection())
        // control by inversion: same row, same key, but once it's no longer a volume
        // root it is an ordinary directory and selectable as usual
        ShadowStorageManager.reset()
        StorageVolumes.reset()
        assertEquals(sd.absolutePath, vm.currentSelection()?.path)
    }

    @Test
    fun `built-in storage and root stay together before removable volumes`() {
        addVolume(sd, "SD Card", primary = false)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.refreshTree()
        val labels = vm.state.value.rows.mapNotNull { (it as? PaneViewModel.FileNode)?.label }
        val i = labels.indexOf("SD Card")
        assertTrue("the volume row did not appear at the root: $labels", i > 0)
        assertEquals(app.getString(R.string.group_internal_storage), labels[i - 2])
        assertEquals(app.getString(R.string.group_root), labels[i - 1])
        assertEquals(app.getString(R.string.private_space), labels[i + 1])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `a volume below expanded root does not collide with its later shortcut`() {
        val volumePath = "/storage/1A2B-3C4D"
        addVolume(File(volumePath), "SD Card", primary = false)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        val children = PaneViewModel::class.java.getDeclaredField("children")
            .apply { isAccessible = true }.get(vm) as MutableMap<String, List<com.twig.core.XFile>>
        val expanded = PaneViewModel::class.java.getDeclaredField("expanded")
            .apply { isAccessible = true }.get(vm) as MutableSet<String>
        children["f:file:/"] = listOf(com.twig.core.XFile("file", "/storage", isDir = true))
        children["f:file:/storage"] = listOf(com.twig.core.XFile("file", volumePath, isDir = true))
        expanded.addAll(listOf("f:file:/", "f:file:/storage"))
        vm.refreshTree()
        val rows = vm.state.value.rows
        val volumeRows = rows.filterIsInstance<PaneViewModel.FileNode>().filter { it.file.path == volumePath }
        assertEquals(2, volumeRows.size)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(
            setOf("f:file:$volumePath", "${com.twig.app.ui.TreeKeys.NESTED_STORAGE_PREFIX}f:file:$volumePath"),
            volumeRows.map { it.key }.toSet(),
        )
    }

    @Test
    fun `a private directory can be reverse-mapped to its volume root, the primary volume case is dropped`() {
        val primary = "/storage/emulated/0"
        assertEquals(
            "/storage/1A2B-3C4D",
            StorageVolumes.volumeRootOf("/storage/1A2B-3C4D/Android/data/com.twig.app/files", primary)?.path,
        )
        assertNull(StorageVolumes.volumeRootOf("$primary/Android/data/com.twig.app/files", primary))
        assertNull(StorageVolumes.volumeRootOf("/data/user/0/com.twig.app/files", primary))
        // on some ROMs the primary volume's private directory is written as /sdcard/…,
        // which does not match the primary string — the shape check blocks it
        assertNull(StorageVolumes.volumeRootOf("/sdcard/Android/data/com.twig.app/files", primary))
        assertNull(StorageVolumes.volumeRootOf("/storage/emulated/0/Android/data/com.twig.app/files", primary))
    }


    /**
     * ★ User-reported: after pulling out a USB drive, a row lingers on the tree that
     * cannot be opened.
     *
     * Unplugging fires neither a broadcast nor a callback, so "it's gone" can likewise
     * only be discovered by the periodic rescan that runs every few seconds while in the
     * foreground — the criterion is **the rescan must be able to tell it is gone**,
     * otherwise that row hangs around until the next cold start.
     */
    @Test
    fun `after unplugging, the row does not linger on the tree`() {
        addVolume(sd, "SD Card", primary = false)
        StorageVolumes.refresh(app)
        assertEquals(listOf(sd.absolutePath), StorageVolumes.cached(app).map { it.path })

        ShadowStorageManager.reset() // unplugged
        assertTrue("the rescan must be able to tell it is gone", StorageVolumes.refresh(app))
        assertTrue(StorageVolumes.cached(app).isEmpty())
    }

    /**
     * ★ Another user report: "plugging in a USB drive does nothing, turning it off and
     * back on makes it appear".
     *
     * The event path cannot be relied on — testing on a real device showed that when the
     * system mounts a USB drive as "invisible to apps", it fires neither
     * ACTION_MEDIA_MOUNTED nor calls `StorageVolumeCallback` (both were tried on a real
     * device, neither ever fired), while `getStorageVolumes()` actually does return this
     * volume. So while the main UI is in the foreground it calls [StorageVolumes.refresh]
     * itself every few seconds; this test pins that behavior down.
     */
    @Test
    fun `after plugging in, the next poll sees it without switching away and back`() {
        StorageVolumes.refresh(app) // at startup: no card at all
        assertTrue(StorageVolumes.cached(app).isEmpty())

        addVolume(sd, "SD Card", primary = false) // plugged in at this moment
        assertTrue("polling must be able to detect the change, otherwise the UI never rebuilds the tree", StorageVolumes.refresh(app))
        assertEquals(listOf(sd.absolutePath), StorageVolumes.cached(app).map { it.path })
        assertFalse("without another plug/unplug the UI should not keep rebuilding the tree", StorageVolumes.refresh(app))
    }
}
