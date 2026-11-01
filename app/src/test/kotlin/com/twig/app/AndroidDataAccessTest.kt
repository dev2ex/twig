package com.twig.app

import android.app.Application
import android.content.Intent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDataAccessTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var provider: Provider
    private lateinit var fs: LocalFileSystem
    private val packageId = "primary:Android/data/com.example"
    private val tree get() = DocumentsContract.buildTreeDocumentUri(AndroidDataAccess.AUTHORITY, packageId)
    private val root get() = XFile("file", File(Environment.getExternalStorageDirectory(), "Android/data/com.example").canonicalPath, true)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        provider = Provider(File(app.cacheDir, "documents").apply { mkdirs() })
        provider.attachInfo(app, ProviderInfo().apply {
            authority = AndroidDataAccess.AUTHORITY
            exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal(AndroidDataAccess.AUTHORITY, provider)
        fs = LocalFileSystem()
        FsRegistry.register(fs)
        FsRegistry.register(SafFileSystem(app))
        AndroidDataAccess.install(app)
    }
    @After fun cleanup() {
        LocalFileSystem.documentAccess = null
        StorageVolumes.reset()
        FsRegistry.unregister("file"); FsRegistry.unregister("saf")
        Dispatchers.resetMain()
    }
    private fun grant(write: Boolean = true) {
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        AndroidDataAccess.instance!!.refresh()
    }

    @Test fun `grants map only their exact subtree and retain real SAF children`() {
        assertNull(AndroidDataAccess.instance!!.route(root.path))
        grant()
        assertNotNull(AndroidDataAccess.instance!!.route(root.path))
        assertNull(AndroidDataAccess.instance!!.route(root.path + ".other"))
        assertNull(AndroidDataAccess.instance!!.route(root.path + "/../com.other"))
        assertNull(AndroidDataAccess.instance!!.route("/data/data/com.example"))
        assertEquals("com.example", fs.stat(root.path)?.name)
        val children = fs.list(root)
        assertEquals(listOf("files", "hello.txt"), children.map { it.name })
        assertTrue(children.all { it.scheme == "saf" })
        assertEquals("hello", FsRegistry.of(children.last()).openInput(children.last()).bufferedReader().use { it.readText() })
        assertEquals("hello", fs.openInput(XFile("file", root.path + "/hello.txt", false)).bufferedReader().use { it.readText() })
        assertFalse(root.isMovableSource())
    }

    @Test fun `copies edits creates renames and deletes go through the provider`() {
        grant()
        val source = File(app.cacheDir, "source.txt").apply { writeText("copied") }
        CopyEngine.transfer(listOf(XFile("file", source.path, false, size = source.length())), root, false)
        assertEquals("copied", File(provider.base, "source.txt").readText())
        source.writeText("replaced")
        CopyEngine.transfer(listOf(XFile("file", source.path, false, size = source.length())), root, false)
        assertEquals("replaced", File(provider.base, "source.txt").readText())
        val created = fs.createFile(root, "new.txt")
        assertEquals("saf", created.scheme)
        FsRegistry.of(created).openOutput(created).use { it.write("new".toByteArray()) }
        val folder = fs.mkdir(root, "sub")
        assertEquals("saf", folder.scheme)
        fs.openOutput(XFile("file", root.path + "/direct.txt", false)).use { it.write("direct".toByteArray()) }
        assertEquals("direct", File(provider.base, "direct.txt").readText())
        val renamed = fs.rename(XFile("file", root.path + "/direct.txt", false), "renamed.txt")
        assertEquals("saf", renamed.scheme)
        assertFalse(File(provider.base, "direct.txt").exists())
        try {
            fs.rename(XFile("file", root.path + "/renamed.txt", false), "hello.txt")
            fail("must refuse an existing target")
        } catch (_: com.twig.core.FsException) { }
        assertEquals("hello", File(provider.base, "hello.txt").readText())
        fs.delete(XFile("file", root.path + "/renamed.txt", false))
        assertFalse(File(provider.base, "renamed.txt").exists())
        assertFalse(fs.moveWithin(root, XFile("file", "/elsewhere", true), "moved"))
    }

    @Test fun `read only and revoked grants never become writable or empty success`() {
        grant(write = false)
        assertFalse(fs.stat(root.path)!!.canWrite)
        assertTrue(fs.list(root).none { it.canWrite })
        try {
            fs.openOutput(XFile("file", root.path + "/hello.txt", false))
            fail("read only grant must reject writes")
        } catch (_: com.twig.core.FsException) { }
        assertEquals("hello", File(provider.base, "hello.txt").readText())
        assertTrue(SafFileSystem.release(app, SafFileSystem(app).rootOf(tree)))
        assertNull(AndroidDataAccess.instance!!.route(root.path))
        provider.denied = true
        grant()
        try { fs.list(root); fail("denied queries must fail") } catch (_: SecurityException) { }
    }

    @Test fun `picker rejects other apps volumes providers and nested or escaped selections`() {
        val access = AndroidDataAccess.instance!!
        fun uri(id: String) = DocumentsContract.buildTreeDocumentUri(AndroidDataAccess.AUTHORITY, id)
        val data = root.copy(path = root.parentPath)
        assertEquals("primary:Android/data", DocumentsContract.getDocumentId(AndroidDataAccess.initialUri(data)))
        assertEquals(packageId, DocumentsContract.getDocumentId(AndroidDataAccess.initialUri(root)))
        assertEquals("primary:", DocumentsContract.getTreeDocumentId(AndroidDataAccess.initialUri(root)))
        assertTrue(access.accepts(tree, root))
        assertTrue(access.accepts(tree, data))
        assertTrue(access.accepts(uri("primary:Android/data"), root))
        assertFalse(access.accepts(uri("primary:Android/data/com.other"), root))
        assertFalse(access.accepts(uri("primary:Android/data/com.example/files"), data))
        assertFalse(access.accepts(uri("primary:Android/data/com.example/../com.other"), data))
        assertFalse(access.accepts(uri("primary:Android"), root))
        assertFalse(access.accepts(uri("ABCD-1234:Android/data/com.example"), root))
        assertFalse(access.accepts(DocumentsContract.buildTreeDocumentUri("other.provider", packageId), root))
    }

    @Test fun `app folder candidates include user and system apps with their classification`() {
        val data = root.copy(path = root.parentPath)
        val pm = shadowOf(app.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.missing"
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.missing"; nonLocalizedLabel = "Missing App"
            }
        })
        for ((pkg, flag) in listOf("com.system" to android.content.pm.ApplicationInfo.FLAG_SYSTEM,
            "com.updated" to android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) {
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; flags = flag }
            })
        }
        assertFalse(File(data.path, "com.missing").exists())
        val candidates = AndroidDataAccess.instance!!.appFolderCandidates(data)
        assertTrue(candidates.any { it.file.path == File(data.path, "com.missing").path && it.label == "Missing App" })
        assertTrue(candidates.single { it.file.name == "com.system" }.system)
        assertTrue(candidates.single { it.file.name == "com.updated" }.system)
        assertFalse(candidates.single { it.file.name == "com.missing" }.system)
    }

    @Test fun `app picker excludes itself and covered folders but restores revoked grants`() {
        val pm = shadowOf(app.packageManager)
        for (pkg in listOf(app.packageName, "com.example", "com.example.other")) {
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; flags = 0 }
            })
        }
        val access = AndroidDataAccess.instance!!
        val data = root.copy(path = root.parentPath)
        fun names(folder: XFile = data) = access.appFolderCandidates(folder).map { it.file.name }
        assertFalse(names().contains(app.packageName))
        assertTrue(names().contains("com.example"))
        grant(write = false)
        assertFalse(names().contains("com.example"))
        assertTrue(names().contains("com.example.other"))
        assertTrue(names(data.copy(path = "/storage/ABCD-1234/Android/data")).contains("com.example"))
        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(names().contains("com.example"))
        val whole = DocumentsContract.buildTreeDocumentUri(AndroidDataAccess.AUTHORITY, "primary:Android/data")
        app.contentResolver.takePersistableUriPermission(whole, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(names().isEmpty())
    }

    @Test fun `authorization row follows complete listing access even when children stay the same`() = runTest(dispatcher) {
        val data = root.copy(path = root.parentPath)
        val ownDir = File(data.path, app.packageName).apply { mkdirs() }
        val own = AndroidDataAccess.instance!!.ownFolder(data)!!
        var readable = false
        FsRegistry.register(object : com.twig.core.FileSystem by fs {
            override fun list(dir: XFile): List<XFile> {
                if (dir.path != data.path) return fs.list(dir)
                if (!readable) throw com.twig.core.FsException("blocked")
                return listOf(own, XFile("file", data.path + "/com.other", true))
            }
        })
        try {
            val vm = com.twig.app.ui.PaneViewModel(app).apply { io = dispatcher; lockRoot = data }
            vm.refreshTree()
            fun row() = vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>()
                .first { it.file.path == data.path }
            fun hasGrant() = vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.ActionNode>()
                .any { it.id == AndroidDataAccess.ACTION_PREFIX + data.path }
            vm.toggle(row())
            assertTrue(hasGrant())
            readable = true
            vm.refreshNode(row())
            assertFalse(hasGrant())
            readable = false
            vm.refreshNode(row())
            assertTrue(hasGrant())
        } finally { ownDir.deleteRecursively() }
    }

    @Test fun `own data folder is visible and readable even when parent listing fails`() = runTest(dispatcher) {
        val data = root.copy(path = root.parentPath)
        val access = AndroidDataAccess.instance!!
        // Robolectric puts getExternalFilesDirs outside Environment's external-storage root.
        val ownDir = File(data.path, app.packageName).apply { mkdirs() }
        val own = access.ownFolder(data)!!
        val source = File(own.path, "local.txt").apply { writeText("local") }
        val wholeData = DocumentsContract.buildTreeDocumentUri(AndroidDataAccess.AUTHORITY, "primary:Android/data")
        app.contentResolver.takePersistableUriPermission(wholeData,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        access.refresh()
        FsRegistry.register(object : com.twig.core.FileSystem by fs {
            override fun list(dir: XFile): List<XFile> {
                if (dir.path == data.path) throw com.twig.core.FsException("blocked")
                return fs.list(dir)
            }
        })
        try {
            val vm = com.twig.app.ui.PaneViewModel(app).apply { io = dispatcher; lockRoot = data }
            vm.refreshTree()
            vm.toggle(vm.state.value.rows.first())
            val node = vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>().first { it.file.path == own.path }
            vm.toggle(node)
            assertTrue(vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>().any { it.file.path == source.path })
            assertNull(access.route(own.path))
            assertEquals("local", fs.openInput(XFile("file", source.path, false)).bufferedReader().use { it.readText() })
        } finally { ownDir.deleteRecursively() }
    }

    @Test fun `app icons match only direct data directories for both local and SAF listings`() {
        val document = XFile("saf", DocumentsContract.buildDocumentUriUsingTree(tree, packageId).toString(), true)
        assertEquals("com.example", AppPackageFolders.packageOf(root))
        assertEquals("com.example", AppPackageFolders.packageOf(document))
        assertEquals("com.example", AppPackageFolders.packageOf(root.copy(path = "/storage/ABCD-1234/Android/data/com.example")))
        assertNull(AppPackageFolders.packageOf(root.copy(path = root.parentPath)))
        assertNull(AppPackageFolders.packageOf(root.copy(path = root.path + "/files")))
        assertNull(AppPackageFolders.packageOf(document.copy(path =
            DocumentsContract.buildDocumentUriUsingTree(tree, "$packageId/files").toString())))
        assertNull(AppPackageFolders.packageOf(document.copy(path = document.path.replace(AndroidDataAccess.AUTHORITY, "other.provider"))))
        assertNull(AppPackageFolders.packageOf(root.copy(isDir = false)))
    }

    @Test fun `directory scans traverse SAF descendants and existing grants survive a new access instance`() {
        grant()
        val found = mutableListOf<XFile>()
        fs.walk(root, 4, stopped = { false }, visit = { found += it })
        assertEquals(setOf("files", "hello.txt", "nested.txt"), found.map { it.name }.toSet())
        assertEquals(2, fs.listForSize(root) { error("must not stat inaccessible physical paths") }.size)
        assertNotNull(AndroidDataAccess(app).route(root.path))
        val data = root.copy(path = root.parentPath)
        assertEquals(listOf(root.path), AndroidDataAccess.instance!!.grantedFolders(data).map { it.path })
    }

    @Test fun `view model merges grants into a partial native data listing and expands them in place`() = runTest(dispatcher) {
        grant()
        File(root.parentPath).mkdirs() // Native listing has no com.example entry: Android can hide it.
        val vm = com.twig.app.ui.PaneViewModel(app).apply { io = dispatcher; lockRoot = root.copy(path = root.parentPath) }
        vm.refreshTree()
        vm.toggle(vm.state.value.rows.first())
        val pkg = vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>().first { it.file.path == root.path }
        vm.toggle(pkg)
        assertTrue(vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>()
            .any { it.file.scheme == "saf" && it.file.name == "hello.txt" })
        assertTrue(vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.ActionNode>()
            .any { it.id.startsWith(AndroidDataAccess.ACTION_PREFIX) && it.depth == 1 })
        vm.revealGrantedDataFolder(root)
        assertEquals(root.path, vm.currentDir?.path)
        assertTrue(vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>()
            .any { it.file.scheme == "saf" && it.file.name == "hello.txt" })
    }

    @Test fun `blocked data root stays expanded with an explicit authorization action`() = runTest(dispatcher) {
        val data = root.copy(path = root.parentPath)
        FsRegistry.register(object : com.twig.core.FileSystem by fs {
            override fun list(dir: XFile): List<XFile> {
                if (dir.path == data.path) throw com.twig.core.FsException("blocked")
                return fs.list(dir)
            }
        })
        val vm = com.twig.app.ui.PaneViewModel(app).apply { io = dispatcher; lockRoot = data }
        vm.refreshTree()
        repeat(2) {
            vm.toggle(vm.state.value.rows.first())
            val rows = vm.state.value.rows
            assertTrue((rows.first() as com.twig.app.ui.PaneViewModel.FileNode).expanded)
            assertTrue(rows.any { it is com.twig.app.ui.PaneViewModel.ActionNode && it.id == AndroidDataAccess.ACTION_PREFIX + data.path })
            assertNull(vm.state.value.dataAccessFor)
            assertNull(vm.state.value.error)
            vm.dismissDataAccessRequest() // Cancelling a picker must not remove the action.
            assertEquals(rows, vm.state.value.rows)
            vm.toggle(vm.state.value.rows.first())
        }
        try { FsRegistry.of(data).list(data); fail("a blocked directory must still fail outside browsing") }
        catch (_: com.twig.core.FsException) { }
    }

    private fun card(id: String = "ABCD-1234:", write: Boolean = true): Pair<XFile, Uri> {
        provider = Provider(File(app.cacheDir, "card-documents").apply { mkdirs() }, id)
        provider.attachInfo(app, ProviderInfo().apply { authority = AndroidDataAccess.AUTHORITY; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal(AndroidDataAccess.AUTHORITY, provider)
        val uri = DocumentsContract.buildTreeDocumentUri(AndroidDataAccess.AUTHORITY, id)
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        AndroidDataAccess.instance!!.refresh()
        return XFile("file", "/storage/" + id.replace(':', '/').trimEnd('/'), true) to uri
    }

    @Test @Config(sdk = [29, 34]) fun `whole card grant routes the original volume and all mutations on Android 10 too`() {
        val (root, _) = card()
        assertNotNull(AndroidDataAccess.instance!!.route(root.path))
        assertTrue(fs.stat(root.path)!!.canWrite)
        val children = fs.list(root)
        assertTrue(children.all { it.scheme == "saf" })
        assertEquals("hello", fs.openInput(root.copy(path = root.path + "/hello.txt", isDir = false)).bufferedReader().use { it.readText() })
        val source = File(app.cacheDir, "card-source.txt").apply { writeText("copied") }
        CopyEngine.transfer(listOf(XFile("file", source.path, false, source.length())), root, false)
        source.writeText("replaced")
        CopyEngine.transfer(listOf(XFile("file", source.path, false, source.length())), root, false)
        assertEquals("replaced", File(provider.base, source.name).readText())
        val folder = fs.mkdir(root, "new-folder")
        val created = fs.createFile(folder, "new.txt")
        fs.openOutput(created).use { it.write("new".toByteArray()) }
        assertEquals("new", File(provider.base, "new-folder/new.txt").readText())
        val renamed = fs.rename(root.copy(path = root.path + "/hello.txt", isDir = false), "renamed.txt")
        assertEquals("saf", renamed.scheme)
        fs.delete(renamed)
        assertFalse(File(provider.base, "renamed.txt").exists())
        assertFalse(fs.canResumeWrites(root))
        assertFalse(fs.moveWithin(root.copy(path = root.path + "/card-source.txt", isDir = false), root, "moved.txt"))
        assertNull(TrashStore(app).plan(listOf(created)).single().volume)
    }

    @Test @Config(sdk = [29, 34]) fun `card subfolder grants stay bounded and read only grants and revocation update routing`() {
        val (root, uri) = card("ABCD-1234:Documents", write = false)
        val access = AndroidDataAccess.instance!!
        assertNull(access.route("/storage/ABCD-1234"))
        assertNull(access.route(root.path + "Other"))
        assertNull(access.route(root.path + "/../Other"))
        assertNull(access.route("/storage/OTHER/Documents"))
        assertFalse(fs.stat(root.path)!!.canWrite)
        assertTrue(fs.list(root).none { it.canWrite })
        assertThrows(com.twig.core.FsException::class.java) { fs.mkdir(root, "blocked") }
        assertTrue(SafFileSystem.release(app, SafFileSystem(app).rootOf(uri)))
        assertNull(access.route(root.path))
    }

    @Test @Config(sdk = [29, 34]) fun `volume grant refresh updates both panes roots cached children and selected destination`() = runTest(dispatcher) {
        val path = "/storage/ABCD-1234"
        org.robolectric.util.ReflectionHelpers.setStaticField(StorageVolumes::class.java, "cache", listOf(RemovableVolume(path, "SD card")))
        val panes = List(2) { com.twig.app.ui.PaneViewModel(app).apply { io = dispatcher; paneIndex = it; refreshTree() } }
        val (root, uri) = card()
        for (vm in panes) {
            val row = vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>().first { it.file.path == path }
            vm.toggle(row)
            vm.refreshDocumentAccess()
            assertTrue(vm.currentDir!!.canWrite)
            assertTrue(vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>().any { it.file.scheme == "saf" })
        }
        assertTrue(SafFileSystem.release(app, SafFileSystem(app).rootOf(uri)))
        provider.denied = true
        for (vm in panes) {
            vm.refreshDocumentAccess()
            assertFalse(vm.currentDir!!.canWrite)
            assertFalse(vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>().any { it.file.scheme == "saf" })
            assertFalse(vm.state.value.rows.filterIsInstance<com.twig.app.ui.PaneViewModel.FileNode>().first { it.file.path == root.path }.file.canWrite)
        }
    }

    @Test fun `known media rw volume alias maps to the same grant while writable native volumes stay direct`() {
        org.robolectric.util.ReflectionHelpers.setStaticField(StorageVolumes::class.java, "cache",
            listOf(RemovableVolume("/mnt/media_rw/ABCD-1234", "SD card")))
        val (_, uri) = card()
        assertNotNull(AndroidDataAccess.instance!!.route("/mnt/media_rw/ABCD-1234"))
        fs.list(XFile("file", "/mnt/media_rw/ABCD-1234", true))
        assertEquals("hello", fs.openInput(XFile("file", "/mnt/media_rw/ABCD-1234/hello.txt", false)).bufferedReader().use { it.readText() })
        assertNull(AndroidDataAccess.instance!!.route("/mnt/media_rw/OTHER/hello.txt"))
        val native = File(app.cacheDir, "native-card/ABCD-1234").apply { mkdirs() }
        org.robolectric.util.ReflectionHelpers.setStaticField(StorageVolumes::class.java, "cache", listOf(RemovableVolume(native.path, "SD card")))
        AndroidDataAccess.instance!!.refresh()
        assertNull(AndroidDataAccess.instance!!.route(native.path))
        assertTrue(SafFileSystem.release(app, SafFileSystem(app).rootOf(uri)))
        native.parentFile!!.deleteRecursively()
    }

    private class Provider(val base: File, private val rootId: String = "primary:Android/data/com.example") : ContentProvider() {
        var denied = false
        init {
            File(base, "hello.txt").writeText("hello")
            File(base, "files").mkdirs()
            File(base, "files/nested.txt").writeText("nested")
        }
        private fun file(id: String): File {
            if (denied) throw SecurityException("revoked")
            require(id == rootId || id.startsWith(if (rootId.endsWith(':')) rootId else "$rootId/"))
            return File(base, id.removePrefix(rootId).removePrefix("/"))
        }
        private fun id(file: File) = rootId + file.relativeTo(base).path.takeIf { it.isNotEmpty() }
            ?.let { if (rootId.endsWith(':')) it else "/$it" }.orEmpty()
        // Robolectric's stream methods do not dispatch through DocumentsProvider.
        private fun streams(f: File) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(AndroidDataAccess.AUTHORITY, rootId), id(f))
            shadowOf(context!!.contentResolver).registerInputStreamSupplier(uri) { f.inputStream() }
            shadowOf(context!!.contentResolver).registerOutputStreamSupplier(uri) { f.outputStream() }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            if (uri.pathSegments.last() == "children") queryChildDocuments(DocumentsContract.getDocumentId(uri), projection)
            else queryDocument(DocumentsContract.getDocumentId(uri), projection)
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val uri = extras!!.getParcelable<Uri>("uri")!!
            val documentId = DocumentsContract.getDocumentId(uri)
            val id = when (method) {
                "android:createDocument" -> createDocument(documentId, extras.getString(Document.COLUMN_MIME_TYPE)!!, extras.getString("_display_name")!!)
                "android:renameDocument" -> renameDocument(documentId, extras.getString("_display_name")!!)
                "android:deleteDocument" -> { deleteDocument(documentId); return Bundle() }
                else -> error(method)
            }
            return Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, id)) }
        }
        private fun rows(projection: Array<out String>?, files: List<File>): Cursor {
            val cols = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
            return MatrixCursor(cols).apply {
                for (f in files) {
                    streams(f)
                    addRow(cols.map { column -> when (column) {
                            Document.COLUMN_DOCUMENT_ID -> id(f)
                            Document.COLUMN_DISPLAY_NAME -> f.name
                            Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else "text/plain"
                            Document.COLUMN_SIZE -> f.length()
                            Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                                Document.FLAG_SUPPORTS_RENAME or if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0
                            else -> null
                        } }.toTypedArray())
                }
            }
        }
        private fun queryDocument(documentId: String, projection: Array<out String>?) =
            rows(projection, listOf(file(documentId)).filter { it.exists() })
        private fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?) =
            rows(projection, file(parentDocumentId).listFiles()!!.toList())
        private fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val f = File(file(parentDocumentId), displayName)
            check(!f.exists())
            if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
            streams(f)
            return id(f)
        }
        private fun renameDocument(documentId: String, displayName: String): String {
            val from = file(documentId); val to = File(from.parentFile, displayName)
            check(!to.exists()); check(from.renameTo(to)); return id(to)
        }
        private fun deleteDocument(documentId: String) { check(file(documentId).deleteRecursively()) }
    }
}
