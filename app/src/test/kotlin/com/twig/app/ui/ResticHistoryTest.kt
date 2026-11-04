package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.*
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.zstd.NativeZstd
import io.airlift.compress.zstd.ZstdDecompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/** Exercise real encrypted repositories; substitute only the Android native decoder. */
@Implements(value = NativeZstd::class, isInAndroidSdk = false)
class JvmResticZstd {
    @Implementation fun decompress(input: ByteArray, expectedSize: Int): ByteArray {
        val size = if (expectedSize >= 0) expectedSize else ZstdDecompressor.getDecompressedSize(input, 0, input.size).toInt()
        return ByteArray(size).also { ZstdDecompressor().decompress(input, 0, input.size, it, 0, it.size) }
    }
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() = Unit
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [JvmResticZstd::class])
class ResticHistoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: PaneViewModel
    private lateinit var repository: File
    private fun entry(path: String = "/latest/tmp/twig_rsrc") =
        HistoryEntry("restic", path, repoPath = repository.path)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        HistoryStore.clear(app)
        FsRegistry.register(LocalFileSystem())
        repository = File(Environment.getExternalStorageDirectory(), "recent-restic")
        val fixture = listOf(File("../fs-restic/src/test/resources/repo"), File("fs-restic/src/test/resources/repo"))
            .first { it.isDirectory }
        fixture.copyRecursively(repository, overwrite = true)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
        repository.deleteRecursively()
        HistoryStore.clear(app)
        app.getSharedPreferences(Prefs.FILE_RESTIC_PW, 0).edit().clear().commit()
        FsRegistry.all().filter { it.scheme.startsWith("restic") }.forEach { FsRegistry.unregister(it.scheme) }
    }

    @Test fun `network repository reconnects from persisted locator in a new pane with a saved password`() = runTest(dispatcher) {
        val conn = SavedConnection("ftp", "restic-history.invalid", port = 21, user = "test")
        ConnectionStore.save(app, conn)
        val remoteScheme = Connections.schemeOf(conn)
        val local = LocalFileSystem()
        val base = repository.parentFile!!.path
        FsRegistry.register(object : com.twig.core.FileSystem by local {
            override val scheme = remoteScheme
            override fun root() = XFile(scheme, "/", true)
            override fun list(dir: XFile): List<XFile> = local.list(dir.copy(scheme = "file", path = base + dir.path))
                .map { it.copy(scheme = scheme, path = it.path.removePrefix(base)) }
            override fun openInput(file: XFile) = local.openInput(file.copy(scheme = "file", path = base + file.path))
            override fun openRandom(file: XFile) = local.openRandom(file.copy(scheme = "file", path = base + file.path))
            override fun resolve(path: String) = local.resolve(base + path).copy(scheme = scheme, path = path)
            override fun stat(path: String) = local.stat(base + path)?.copy(scheme = scheme, path = path)
        })
        try {
            val recent = entry().copy(repoPath = "/${repository.name}", repoConnLabel = conn.label())
            var completed = false
            var restoreError: String? = null
            assertTrue("Saved connection must be found: ${ConnectionStore.all(app)}", vm.revealHistory(recent, "test123") { ok, error ->
                restoreError = error
                completed = ok
            })
            advanceUntilIdle()
            assertTrue("Restore must succeed: $restoreError", completed)
            vm.noteChangedIn(vm.state.value.currentDir!!)
            val persisted = HistoryEntry.fromJson(JSONObject(HistoryStore.all(app).single().toJson().toString()))
            assertEquals(recent, persisted)
            assertTrue(vm.state.value.rows.any { it is PaneViewModel.ServerNode && it.conn.label() == conn.label() })
            FsRegistry.all().filter { it.scheme.startsWith("restic") }.forEach { FsRegistry.unregister(it.scheme) }
            // Legacy plaintext is accepted by Secrets.dec; bypass keystore setup in this JVM fixture.
            app.getSharedPreferences(Prefs.FILE_RESTIC_PW, 0).edit().putString(recent.repoPath, "test123").commit()
            val fresh = PaneViewModel(app).apply { io = dispatcher }
            assertFalse(fresh.resticHistoryNeedsPassword(persisted))
            fresh.revealHistory(persisted) { ok, error -> assertTrue(error, ok) }
            advanceUntilIdle()
            assertEquals(recent.path, fresh.state.value.currentDir!!.path)
        } finally {
            ConnectionStore.remove(app, conn)
            Connections.drop(remoteScheme)
        }
    }

    @Test fun `unlock locate record deduplicate and reopen latest without another password`() = runTest(dispatcher) {
        assertTrue(vm.resticHistoryNeedsPassword(entry()))
        var success = false
        assertTrue(vm.revealHistory(entry(), "test123") { ok, error -> assertNull(error); success = ok })
        advanceUntilIdle()
        assertTrue(success)
        val target = vm.state.value.currentDir!!
        assertEquals(entry().path, target.path)
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == "hello.txt" })
        assertTrue(vm.state.value.rows.any { it is PaneViewModel.ResticNode })
        vm.noteChangedIn(target)
        vm.noteChangedIn(target)
        val recorded = HistoryStore.all(app).single()
        assertEquals(entry().id, recorded.id)
        assertEquals("", recorded.snapshotLabel)
        assertEquals(recorded, HistoryEntry.fromJson(JSONObject(recorded.toJson().toString())))
        assertFalse(vm.resticHistoryNeedsPassword(recorded))
        vm.collapseAll()
        assertTrue(vm.revealHistory(recorded) { ok, error -> assertTrue(error, ok) })
        advanceUntilIdle()
        assertEquals(recorded.path, vm.state.value.currentDir!!.path)
    }

    @Test fun `fixed snapshot uses its path id instead of its displayed time and persists metadata`() = runTest(dispatcher) {
        vm.revealHistory(entry(), "test123") { ok, error -> assertTrue(error, ok) }
        advanceUntilIdle()
        val snapshot = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.scheme.startsWith("restic") && it.file.path.count { c -> c == '/' } == 1 && it.file.path != "/latest" }.file
        val fixed = entry(snapshot.path + "/tmp/twig_rsrc")
        vm.revealHistory(fixed) { ok, error -> assertTrue(error, ok) }
        advanceUntilIdle()
        vm.noteChangedIn(vm.state.value.currentDir!!)
        val recorded = HistoryStore.all(app).single()
        assertEquals(fixed.path, recorded.path)
        assertTrue(recorded.snapshotLabel.contains("testhost"))
        assertEquals(recorded, HistoryEntry.fromJson(JSONObject(recorded.toJson().toString())))
        vm.noteChangedIn(vm.state.value.currentDir!!.copy(path = entry().path))
        assertEquals(2, HistoryStore.all(app).size)
    }

    @Test fun `missing password wrong password and removed snapshots report failure without changing location`() = runTest(dispatcher) {
        val initial = vm.state.value.currentDir
        for (password in listOf(null, "wrong")) {
            var failure: String? = null
            vm.revealHistory(entry(), password) { ok, error -> assertFalse(ok); failure = error }
            advanceUntilIdle()
            assertFalse(failure.isNullOrBlank())
            assertEquals(initial, vm.state.value.currentDir)
        }
        vm.revealHistory(entry(), "test123") { ok, error -> assertTrue(error, ok) }
        advanceUntilIdle()
        val current = vm.state.value.currentDir
        var failure: String? = null
        vm.revealHistory(entry("/deadbeef/tmp")) { ok, error -> assertFalse(ok); failure = error }
        advanceUntilIdle()
        assertFalse(failure.isNullOrBlank())
        assertEquals(current, vm.state.value.currentDir)
    }

    @Test fun `collapse abandons pending restore and deleted connections do not become local paths`() = runTest(dispatcher) {
        var called = false
        vm.revealHistory(entry(), "test123") { _, _ -> called = true }
        vm.collapseAll()
        advanceUntilIdle()
        assertFalse(called)
        assertFalse(vm.revealHistory(entry().copy(repoConnLabel = "sftp://deleted/backup")))
        assertTrue(vm.expandedDescriptors().isEmpty())
    }

    @Test fun `repository identity participates in deduplication and old recent entries remain compatible`() {
        for (e in listOf(entry(), entry().copy(repoPath = "/other"), entry().copy(repoConnLabel = "smb://nas/share"))) {
            HistoryStore.add(app, e)
        }
        assertEquals(3, HistoryStore.all(app).size)
        val old = HistoryEntry.fromJson(JSONObject("""{"kind":"dir","path":"/photos","connLabel":""}"""))
        assertEquals(HistoryEntry("dir", "/photos"), old)
        assertEquals("dir||/photos", old.id)
    }
}
