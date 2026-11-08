package com.twig.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
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
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
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
    fun `extra internal roots discover user ids in numeric order without inventing absent roots`() {
        val primary = File(sd, "0").apply { mkdirs() }
        assertEquals(listOf(primary.path), StorageVolumes.scanInternalRoots(primary, sd))
        val user101 = File(sd, "101").apply { mkdirs() }
        val user95 = File(sd, "95").apply { mkdirs() }
        File(sd, "10").mkdirs()
        File(sd, "legacy").mkdirs()
        File(sd, "42").writeText("not a directory")
        assertEquals(listOf(primary.path, File(sd, "10").path, user95.path, user101.path),
            StorageVolumes.scanInternalRoots(primary, sd))
        assertEquals(listOf(user95.path, primary.path, File(sd, "10").path, user101.path),
            StorageVolumes.scanInternalRoots(user95, sd))
        assertEquals(listOf(primary.path), StorageVolumes.scanInternalRoots(primary, File(sd, "absent")))
    }

    @Test
    fun `system reported shared roots survive an unlistable parent without duplicate paths`() {
        val primary = File(sd, "0")
        val shared = File(sd, "101")
        val blockedParent = object : File(sd, "emulated") {
            override fun listFiles(): Array<File>? = null
        }
        assertEquals(listOf(primary.path, shared.path), StorageVolumes.scanInternalRoots(
            primary, blockedParent, listOf(shared, primary, shared)))
        primary.mkdirs()
        shared.mkdirs()
        assertEquals(listOf(primary.path, shared.path), StorageVolumes.scanInternalRoots(
            primary, sd, listOf(shared, primary)))
    }

    private fun withExtraStorage(): String {
        val path = File(sd, "101").apply { mkdirs() }.path
        org.robolectric.util.ReflectionHelpers.setStaticField(StorageVolumes::class.java, "internalCache",
            listOf(Environment.getExternalStorageDirectory().absolutePath, path))
        return path
    }

    @Test
    fun `extra internal storage follows internal storage when reordered and hidden and is protected`() {
        val extra = withExtraStorage()
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val vm = PaneViewModel(app).apply { io = dispatcher }
        Prefs.setRootItemOrder(app, RootItems.all.map { it.id }.filter { it != "internal" } + "internal")
        vm.refreshTree()
        val roots = vm.state.value.rows.takeLast(2).map { it as PaneViewModel.FileNode }
        assertEquals(listOf(primary, extra), roots.map { it.file.path })
        assertEquals(listOf(app.getString(R.string.group_internal_storage),
            app.getString(R.string.group_internal_storage) + " (101)"), roots.map { it.label })
        assertEquals(R.drawable.ic_storage, com.twig.app.ui.FileIcons.storageRootIconRes(roots.last().file))
        vm.toggle(roots.last())
        assertEquals(extra, vm.currentDir?.path)
        assertNull(vm.currentSelection())
        Prefs.setRootItemVisible(app, 0, "internal", false)
        vm.refreshRootItems()
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path in setOf(primary, extra) })
        assertNull(vm.currentDir)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `extra storage reveal uses its shortcut and nested copies have unique keys`() {
        val extra = withExtraStorage()
        val vm = PaneViewModel(app).apply { io = dispatcher }
        val children = PaneViewModel::class.java.getDeclaredField("children")
            .apply { isAccessible = true }.get(vm) as MutableMap<String, List<com.twig.core.XFile>>
        val expanded = PaneViewModel::class.java.getDeclaredField("expanded")
            .apply { isAccessible = true }.get(vm) as MutableSet<String>
        children["f:file:/"] = listOf(com.twig.core.XFile("file", extra, isDir = true))
        children["f:file:$extra"] = emptyList()
        expanded.add("f:file:/")
        vm.refreshTree()
        val rows = vm.state.value.rows
        assertEquals(2, rows.filterIsInstance<PaneViewModel.FileNode>().count { it.file.path == extra })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        vm.revealPath(com.twig.core.XFile("file", extra, isDir = true))
        dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.error)
        assertEquals("f:file:$extra", vm.state.value.currentKey)
        assertFalse("reveal must not traverse inaccessible ancestors", "f:file:/" in expanded)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `current user setting hides only extra shortcuts in both panes and retains root protection`() {
        val extra = withExtraStorage()
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val panes = (0..1).map { pane -> PaneViewModel(app).apply { io = dispatcher; paneIndex = pane } }
        assertFalse(Prefs.currentUserInternalStorageOnly(app))
        val before = Prefs.uiSignature(app)
        panes.forEach { vm ->
            val children = PaneViewModel::class.java.getDeclaredField("children")
                .apply { isAccessible = true }.get(vm) as MutableMap<String, List<com.twig.core.XFile>>
            val expanded = PaneViewModel::class.java.getDeclaredField("expanded")
                .apply { isAccessible = true }.get(vm) as MutableSet<String>
            children["f:file:/"] = listOf(com.twig.core.XFile("file", extra, isDir = true))
            children["f:file:$extra"] = emptyList()
            expanded.add("f:file:/")
            vm.refreshTree()
            assertEquals(2, vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().count { it.file.path == extra })
        }
        Prefs.setCurrentUserInternalStorageOnly(app, true)
        assertTrue(before != Prefs.uiSignature(app))
        panes.forEach { vm ->
            vm.refreshRootItems()
            val rows = vm.state.value.rows
            assertTrue(rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path == primary && it.depth == 0 })
            val nested = rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.path == extra }
            assertTrue(nested.depth > 0)
            assertEquals(rows.size, rows.map { it.key }.toSet().size)
            vm.toggle(nested)
            assertEquals(extra, vm.currentDir?.path)
            assertNull("hidden shortcuts must retain whole-root protection", vm.currentSelection())
        }
        Prefs.setCurrentUserInternalStorageOnly(app, false)
        panes.forEach { vm ->
            vm.refreshRootItems()
            val rows = vm.state.value.rows
            assertEquals(2, rows.filterIsInstance<PaneViewModel.FileNode>().count { it.file.path == extra })
            assertEquals(rows.size, rows.map { it.key }.toSet().size)
        }
    }

    @Test
    fun `current user setting skips profile discovery on cold access and repeated polls`() {
        var storageCalls = 0
        val ctx = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.STORAGE_SERVICE) storageCalls++
                return super.getSystemService(name)
            }
        }
        Prefs.setCurrentUserInternalStorageOnly(app, true)
        val primary = Environment.getExternalStorageDirectory().absolutePath
        assertEquals(listOf(primary), StorageVolumes.internalRoots(ctx))
        assertEquals("cold access must not query storage volumes", 0, storageCalls)
        repeat(2) {
            assertFalse(StorageVolumes.refresh(ctx))
        }
        // One ordinary volume query per poll for SD/USB, with no internal-root scan.
        assertEquals(2, storageCalls)
        assertTrue(StorageVolumes.isInternalRoot("/storage/emulated/999"))
        assertFalse(StorageVolumes.isInternalRoot("/storage/emulated/999/Download"))
        Prefs.setCurrentUserInternalStorageOnly(app, false)
        StorageVolumes.internalRoots(ctx)
        assertEquals("disabling the setting must discover profiles without restarting", 3, storageCalls)
    }

    @Test
    fun `current user storage is the first discovered root rather than hard coded user zero`() {
        val current = File(sd, "95").path
        val other = File(sd, "0").path
        org.robolectric.util.ReflectionHelpers.setStaticField(StorageVolumes::class.java, "internalCache", listOf(current, other))
        Prefs.setCurrentUserInternalStorageOnly(app, true)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.refreshTree()
        val roots = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        assertTrue(roots.any { it.file.path == current })
        assertFalse(roots.any { it.file.path == other })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `revealing hidden extra storage uses root or a temporary pin when root is hidden`() {
        val extra = withExtraStorage()
        Prefs.setCurrentUserInternalStorageOnly(app, true)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        val children = PaneViewModel::class.java.getDeclaredField("children")
            .apply { isAccessible = true }.get(vm) as MutableMap<String, List<com.twig.core.XFile>>
        var child = File(extra)
        children["f:file:$extra"] = emptyList()
        while (child.parentFile != null) {
            val parent = child.parentFile!!
            children["f:file:${parent.path}"] = listOf(com.twig.core.XFile("file", child.path, isDir = true))
            child = parent
        }
        vm.refreshTree()
        val target = com.twig.core.XFile("file", extra, isDir = true)
        vm.revealPath(target)
        dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.error)
        assertEquals("f:file:$extra", vm.state.value.currentKey)
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.path == extra }.depth > 0)

        Prefs.setRootItemVisible(app, 0, "root", false)
        vm.refreshRootItems()
        vm.revealPath(target)
        dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(extra, vm.currentDir?.path)
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path == extra && it.depth == 0 })
        assertTrue(Prefs.currentUserInternalStorageOnly(app))
        assertFalse(Prefs.rootItemVisible(app, 0, "root"))
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
    fun `a custom volume label defaults to the SD card icon`() {
        addVolume(sd, "opi_root", primary = false)
        StorageVolumes.cached(app)
        assertEquals(
            R.drawable.ic_sd_card,
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
    fun `storage shortcuts move independently and visibility is scoped to each pane`() {
        addVolume(sd, "SD Card", primary = false)
        val left = PaneViewModel(app).apply { io = dispatcher; paneIndex = 0 }
        val right = PaneViewModel(app).apply { io = dispatcher; paneIndex = 1 }
        val others = RootItems.all.map { it.id }.filter { it !in setOf("internal", "removable") }
        Prefs.setRootItemOrder(app, others + listOf("removable", "internal"))
        left.refreshTree()
        val internalKey = "f:file:${Environment.getExternalStorageDirectory().absolutePath}"
        val volumeKey = "f:file:${sd.absolutePath}"
        assertEquals(listOf(volumeKey, internalKey), left.state.value.rows.takeLast(2).map { it.key })
        val internal = left.state.value.rows.last()
        left.toggle(internal)
        assertEquals(internalKey, left.state.value.currentKey)

        Prefs.setRootItemVisible(app, 0, "internal", false)
        left.refreshRootItems()
        right.refreshTree()
        assertFalse(left.state.value.rows.any { it.key == internalKey })
        assertNull(left.currentDir)
        assertTrue(left.state.value.rows.any { it.key == volumeKey })
        assertEquals(listOf(volumeKey, internalKey), right.state.value.rows.takeLast(2).map { it.key })

        Prefs.setRootItemVisible(app, 0, "removable", false)
        left.refreshRootItems()
        assertFalse(left.state.value.rows.any { it.key == volumeKey })
        assertTrue(right.state.value.rows.any { it.key == volumeKey })
    }

    @Test
    fun `removable slot survives unplugging and hides newly attached volumes`() {
        val vm = PaneViewModel(app).apply { io = dispatcher }
        Prefs.setRootItemOrder(app, RootItems.all.map { it.id }.filter { it != "removable" } + "removable")
        Prefs.setRootItemVisible(app, 0, "removable", false)
        vm.refreshTree()
        addVolume(sd, "SD Card", primary = false)
        assertTrue(StorageVolumes.refresh(app))
        vm.refreshRootItems()
        val volumeKey = "f:file:${sd.absolutePath}"
        assertFalse(vm.state.value.rows.any { it.key == volumeKey })

        Prefs.setRootItemVisible(app, 0, "removable", true)
        vm.refreshRootItems()
        assertEquals(volumeKey, vm.state.value.rows.last().key)
        assertFalse(vm.state.value.rows.any { it.key == "g:removable" })
        ShadowStorageManager.reset()
        assertTrue(StorageVolumes.refresh(app))
        vm.refreshRootItems()
        assertFalse(vm.state.value.rows.any { it.key == volumeKey })
        addVolume(sd, "SD Card", primary = false)
        assertTrue(StorageVolumes.refresh(app))
        vm.refreshRootItems()
        assertEquals(volumeKey, vm.state.value.rows.last().key)
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
