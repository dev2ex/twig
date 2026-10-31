package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import com.twig.core.FsRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CopyDestinationTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private fun pane(index: Int = 0) = activity.supportFragmentManager.findFragmentByTag("pane$index") as PaneFragment
    private lateinit var source: File
    private lateinit var target: File
    private fun entry(file: File) = XFile("file", file.path, file.isDirectory, file.length())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun state() = ViewModelProvider(pane())[DestinationState::class.java]
    private fun confirmation() = pane().childFragmentManager.findFragmentByTag(CopyConfirmDialog.TAG) as CopyConfirmDialog
    private fun dialog() = confirmation().requireDialog() as AlertDialog
    private fun open(move: Boolean = false, movable: Boolean = true, immediate: Boolean = false,
                     files: List<XFile> = listOf(entry(source)), sourceSide: Int = 0) {
        ReflectionHelpers.callInstanceMethod<Void>(pane(sourceSide), "performCopy",
            ReflectionHelpers.ClassParameter.from(List::class.java, files),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, move),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, movable),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, immediate))
        pane(sourceSide).childFragmentManager.executePendingTransactions()
        idle()
    }
    private fun launchPicker() {
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        initialPicker()
    }
    private fun choose(directory: File = target) {
        pane(1).viewModel.currentDir = entry(directory)
        activity.onClipTargetChanged()
        activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).performClick()
        idle()
    }
    private fun initialPicker() {
        assertNull(pane().childFragmentManager.findFragmentByTag(CopyConfirmDialog.TAG))
        assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.copy_destination_bar).visibility)
        assertTrue(activity.isPaneActive(pane(1)))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    private fun cancelPicker() {
        activity.findViewById<android.view.View>(R.id.btn_copy_destination_cancel).performClick()
        idle()
    }

    private fun openCompression(files: List<XFile> = listOf(entry(source)), immediate: Boolean = false,
                                allowMove: Boolean = true) {
        ReflectionHelpers.callInstanceMethod<Void>(pane(), "performCompress",
            ReflectionHelpers.ClassParameter.from(List::class.java, files),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, allowMove),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, immediate))
        pane().childFragmentManager.executePendingTransactions()
        idle()
    }

    private fun compressionDialog() =
        (pane().childFragmentManager.findFragmentByTag(DestinationState.CONFIRMATION_TAG) as CompressConfirmDialog)
            .requireDialog() as AlertDialog

    private fun launchCompressionPicker() {
        compressionDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        initialPicker()
    }

    private fun compressionSession(): Transfers.Session {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Transfers.active == null && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(5)
        }
        return requireNotNull(Transfers.active)
    }

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        Prefs.setRememberLocation(app, false)
        Prefs.setCompressLevel(app, com.twig.fs.archive.ArchiveWriter.Level.STANDARD)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val root = File(activity.cacheDir, "copy-destination-test").apply { deleteRecursively(); mkdirs() }
        source = File(root, "source/payload.txt").apply { parentFile!!.mkdirs(); writeText("payload") }
        target = File(root, "target").apply { mkdirs() }
        pane(1).viewModel.currentDir = entry(target)
    }

    @After fun cleanup() {
        if (!::controller.isInitialized) return
        Transfers.active?.let { session ->
            session.cancel()
            val worker = Thread { Transfers.execute(activity, session) {} }
            worker.start()
            worker.join(5000)
            assertFalse(worker.isAlive)
            idle()
        }
        Transfers.consumeFinished()
        controller.pause().stop().destroy()
        if (::source.isInitialized) source.parentFile!!.parentFile!!.deleteRecursively()
        FileClipboard.clear()
        FileClipboard.setMove(false)
    }

    @Test fun `missing destination can be picked and copied without selecting the other pane`() {
        pane(1).viewModel.currentDir = null
        open()
        initialPicker()
        choose()
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(dialog().findViewById<TextView>(R.id.tv_dest)!!.text.contains(target.path))
        assertEquals(target.path, pane(1).viewModel.currentDir!!.path)
        assertTrue(activity.isPaneActive(pane()))
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val session = requireNotNull(Transfers.active)
        val work = session.work as Transfers.Work.Copy
        assertEquals(listOf(entry(source)), work.items)
        assertEquals(target.path, work.dest.path)
        assertFalse(work.move)
        val worker = Thread { Transfers.execute(activity, session) {} }
        worker.start()
        worker.join(5000)
        assertFalse(worker.isAlive)
        idle()
        assertTrue(session.finished!!.isSuccess)
        assertEquals("payload", File(target, source.name).readText())
        assertTrue(source.exists())
    }

    @Test fun `compression can select its first destination and create an archive`() {
        Prefs.setCompressFormat(activity, com.twig.fs.archive.ArchiveWriter.Format.ZIP)
        pane(1).viewModel.currentDir = null
        openCompression()
        assertFalse(compressionDialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        launchCompressionPicker()
        choose()
        val form = compressionDialog()
        assertEquals("payload.zip", form.findViewById<android.widget.EditText>(R.id.et_name)!!.text.toString())
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val session = compressionSession()
        val work = session.work as Transfers.Work.Compress
        assertEquals(listOf(entry(source)), work.items)
        assertEquals(target.path, work.destDir.path)
        assertFalse(work.move)
        val worker = Thread { Transfers.execute(activity, session) {} }
        worker.start()
        worker.join(5000)
        assertFalse(worker.isAlive)
        idle()
        assertTrue(session.finished!!.isSuccess)
        java.util.zip.ZipFile(File(work.target.path)).use { zip ->
            assertEquals("payload", zip.getInputStream(zip.getEntry(source.name)).bufferedReader().use { it.readText() })
        }
        assertTrue(source.exists())
    }

    @Test fun `compression retains edited fields through destination cancellation and recreation`() {
        openCompression()
        var form = compressionDialog()
        form.findViewById<android.widget.RadioGroup>(R.id.rg_format)!!.check(R.id.rb_7z)
        form.findViewById<android.widget.EditText>(R.id.et_name)!!.setText("edited.7z")
        form.findViewById<android.widget.EditText>(R.id.et_password)!!.setText("secret")
        form.findViewById<CheckBox>(R.id.cb_move)!!.isChecked = true
        assertFalse(form.findViewById<android.widget.EditText>(R.id.et_password)!!.isSaveEnabled)
        form.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        initialPicker()
        assertEquals(activity.getString(R.string.compress_save_to),
            activity.findViewById<TextView>(R.id.tv_copy_destination_label).text.toString())
        controller.recreate()
        idle()
        assertTrue(activity.isChoosingDestination())
        cancelPicker()
        form = compressionDialog()
        assertEquals("edited.7z", form.findViewById<android.widget.EditText>(R.id.et_name)!!.text.toString())
        assertEquals("secret", form.findViewById<android.widget.EditText>(R.id.et_password)!!.text.toString())
        assertEquals(R.id.rb_7z, form.findViewById<android.widget.RadioGroup>(R.id.rg_format)!!.checkedRadioButtonId)
        assertTrue(form.findViewById<CheckBox>(R.id.cb_move)!!.isChecked)
        assertEquals(target.path, state().draft!!.destination!!.path)
        controller.recreate()
        idle()
        form = compressionDialog()
        assertEquals("secret", form.findViewById<android.widget.EditText>(R.id.et_password)!!.text.toString())
        assertEquals("edited.7z", form.findViewById<android.widget.EditText>(R.id.et_name)!!.text.toString())
        val options = state().draft!!.compression!!
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertNull(state().draft)
        assertEquals("", options.password)
        assertNull(Transfers.active)
    }

    @Test fun `replacement compression destination preserves options in the started work`() {
        openCompression()
        val form = compressionDialog()
        form.findViewById<android.widget.RadioGroup>(R.id.rg_format)!!.check(R.id.rb_zip)
        form.findViewById<android.widget.EditText>(R.id.et_name)!!.setText("chosen.zip")
        form.findViewById<android.widget.EditText>(R.id.et_password)!!.setText("secret")
        form.findViewById<CheckBox>(R.id.cb_move)!!.isChecked = true
        form.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        val replacement = File(target.parentFile, "replacement").apply { mkdirs() }
        choose(replacement)
        compressionDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val work = compressionSession().work as Transfers.Work.Compress
        assertEquals(replacement.path, work.destDir.path)
        assertEquals("chosen.zip", work.target.name)
        assertEquals("secret", work.password)
        assertTrue(work.move)
        assertEquals(com.twig.fs.archive.ArchiveWriter.Format.ZIP, work.format)
    }

    @Test fun `compression accepts the source parent but rejects source directories and readonly destinations`() {
        val dir = source.parentFile!!
        pane(1).viewModel.currentDir = null
        openCompression(files = listOf(entry(dir)))
        launchCompressionPicker()
        for (invalid in listOf(entry(dir), entry(dir).copy(path = dir.path + "/nested"), entry(target).copy(canWrite = false))) {
            pane(1).viewModel.currentDir = invalid
            activity.onClipTargetChanged()
            assertFalse(activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).isEnabled)
        }
        choose(dir.parentFile!!)
        assertTrue(compressionDialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertNull(Transfers.active)
    }

    @Test fun `immediate compression without a target opens settings and can be cancelled`() {
        pane(1).viewModel.currentDir = null
        openCompression(immediate = true)
        compressionDialog().cancel()
        idle()
        assertNull(state().draft)
        assertTrue(activity.isPaneActive(pane()))
        assertNull(Transfers.active)
        assertTrue(source.exists())
    }

    @Test fun `source directory level and name encryption choices survive recreation and reach the worker`() {
        pane(1).viewModel.currentDir = null
        openCompression()
        var form = compressionDialog()
        val sourceCheck = form.findViewById<CheckBox>(R.id.cb_source_directory)!!
        assertTrue(sourceCheck.isEnabled)
        sourceCheck.isChecked = true
        assertTrue(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertEquals(source.parentFile!!.path, form.findViewById<TextView>(R.id.tv_dest)!!.text.toString())
        form.findViewById<android.widget.RadioGroup>(R.id.rg_format)!!.check(R.id.rb_7z)
        assertEquals(android.view.View.GONE, form.findViewById<CheckBox>(R.id.cb_hide_names)!!.visibility)
        form.findViewById<android.widget.EditText>(R.id.et_password)!!.setText("secret")
        val hide = form.findViewById<CheckBox>(R.id.cb_hide_names)!!
        assertEquals(android.view.View.VISIBLE, hide.visibility)
        assertTrue(hide.isChecked)
        hide.isChecked = false
        form.findViewById<android.widget.Spinner>(R.id.sp_level)!!.setSelection(0)
        idle()
        controller.recreate()
        idle()
        form = compressionDialog()
        assertTrue(form.findViewById<CheckBox>(R.id.cb_source_directory)!!.isChecked)
        assertFalse(form.findViewById<CheckBox>(R.id.cb_hide_names)!!.isChecked)
        assertEquals(0, form.findViewById<android.widget.Spinner>(R.id.sp_level)!!.selectedItemPosition)
        launchCompressionPicker()
        cancelPicker()
        form = compressionDialog()
        assertTrue(form.findViewById<CheckBox>(R.id.cb_source_directory)!!.isChecked)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val work = compressionSession().work as Transfers.Work.Compress
        assertEquals(source.parentFile!!.path, work.destDir.path)
        assertEquals(com.twig.fs.archive.ArchiveWriter.Level.FAST, work.level)
        assertFalse(work.hideNames)
        assertEquals("secret", work.password)
    }

    @Test fun `choosing another directory replaces the source directory choice and retains stored level`() {
        openCompression()
        var form = compressionDialog()
        form.findViewById<CheckBox>(R.id.cb_source_directory)!!.isChecked = true
        form.findViewById<android.widget.Spinner>(R.id.sp_level)!!.setSelection(2)
        idle()
        launchCompressionPicker()
        choose()
        form = compressionDialog()
        assertFalse(form.findViewById<CheckBox>(R.id.cb_source_directory)!!.isChecked)
        assertEquals(2, form.findViewById<android.widget.Spinner>(R.id.sp_level)!!.selectedItemPosition)
        assertEquals(com.twig.fs.archive.ArchiveWriter.Level.STORE, Prefs.compressLevel(activity))
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val work = compressionSession().work as Transfers.Work.Compress
        assertEquals(target.path, work.destDir.path)
        assertEquals(com.twig.fs.archive.ArchiveWriter.Level.STORE, work.level)
    }

    @Test fun `source directory cannot select unrelated parents or readonly sources`() {
        openCompression(files = listOf(entry(source), entry(File(target, "elsewhere.txt"))))
        assertFalse(compressionDialog().findViewById<CheckBox>(R.id.cb_source_directory)!!.isEnabled)
        compressionDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        val scheme = "readonly-compression-options"
        FsRegistry.register(FakeFileSystem(scheme, mapOf("/dir" to listOf("file")), mapOf("/dir/file" to "data")))
        try {
            openCompression(files = listOf(XFile(scheme, "/dir/file", false)))
            val form = compressionDialog()
            assertFalse(form.findViewById<CheckBox>(R.id.cb_source_directory)!!.isEnabled)
            form.findViewById<android.widget.EditText>(R.id.et_password)!!.setText("secret")
            form.findViewById<android.widget.RadioGroup>(R.id.rg_format)!!.check(R.id.rb_zip)
            assertEquals(android.view.View.GONE, form.findViewById<CheckBox>(R.id.cb_hide_names)!!.visibility)
        } finally { FsRegistry.unregister(scheme) }
    }

    @Test fun `immediate compression with a writable target keeps the direct transfer path`() {
        openCompression(immediate = true)
        assertFalse(activity.isChoosingDestination())
        assertTrue(compressionSession().work is Transfers.Work.Compress)
        assertNull(pane().childFragmentManager.findFragmentByTag(DestinationState.CONFIRMATION_TAG))
    }

    @Test fun `readonly compression sources cannot enable move and lost drafts restore no password`() {
        openCompression(allowMove = false)
        var form = compressionDialog()
        assertEquals(android.view.View.GONE, form.findViewById<CheckBox>(R.id.cb_move)!!.visibility)
        form.findViewById<android.widget.EditText>(R.id.et_name)!!.setText("")
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.findViewById<android.widget.EditText>(R.id.et_name)!!.setText("../escape.zip")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(form.findViewById<android.widget.EditText>(R.id.et_name)!!.error)
        assertNull(Transfers.active)
        form.findViewById<android.widget.EditText>(R.id.et_password)!!.setText("secret")
        state().clear()
        controller.recreate()
        idle()
        assertNull(pane().childFragmentManager.findFragmentByTag(DestinationState.CONFIRMATION_TAG))
        assertNull(Transfers.active)
    }

    @Test fun `canceling replacement picking after recreation preserves destination and move choice`() {
        pane(1).viewModel.currentDir = entry(target)
        open()
        dialog().findViewById<CheckBox>(R.id.cb_move)!!.isChecked = true
        launchPicker()
        controller.recreate()
        idle()
        cancelPicker()
        assertTrue(dialog().isShowing)
        assertTrue(dialog().findViewById<CheckBox>(R.id.cb_move)!!.isChecked)
        assertEquals(target.path, state().draft!!.destination!!.path)
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertNull(Transfers.active)
    }

    @Test fun `read only and same directory destinations stay disabled until a valid choice`() {
        pane(1).viewModel.currentDir = entry(target).copy(canWrite = false)
        open()
        initialPicker()
        choose(source.parentFile!!)
        assertFalse(activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).isEnabled)
        assertEquals(activity.getString(R.string.clip_same_dir),
            activity.findViewById<TextView>(R.id.tv_copy_destination).text.toString())
        choose()
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun `a directory cannot be copied into itself or a descendant`() {
        val directory = source.parentFile!!
        val descendant = File(directory, "child").apply { mkdirs() }
        open(files = listOf(entry(directory)))
        launchPicker()
        for (invalid in listOf(directory, descendant)) {
            choose(invalid)
            assertFalse(activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).isEnabled)
            assertEquals(activity.getString(R.string.clip_into_self),
                activity.findViewById<TextView>(R.id.tv_copy_destination).text.toString())
        }
        choose()
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun `draft and pending picker result survive recreation without changing sources`() {
        pane(1).viewModel.currentDir = null
        open(move = true)
        val original = state().draft!!
        initialPicker()
        controller.recreate()
        idle()
        assertSame(original, state().draft)
        initialPicker()
        choose()
        assertEquals(listOf(entry(source)), state().draft!!.sources)
        assertTrue(dialog().findViewById<CheckBox>(R.id.cb_move)!!.isChecked)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val session = requireNotNull(Transfers.active)
        assertTrue((session.work as Transfers.Work.Copy).move)
        val worker = Thread { Transfers.execute(activity, session) {} }
        worker.start()
        worker.join(5000)
        assertFalse(worker.isAlive)
        idle()
        assertTrue(session.finished!!.isSuccess)
        assertFalse(source.exists())
        assertEquals("payload", File(target, source.name).readText())
    }

    @Test fun `read only source hides move mode and cannot turn copying into moving`() {
        open(movable = false)
        assertEquals(android.view.View.GONE, dialog().findViewById<CheckBox>(R.id.cb_move)!!.visibility)
        launchPicker()
        choose()
        dialog().findViewById<CheckBox>(R.id.cb_move)!!.isChecked = true
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse((requireNotNull(Transfers.active).work as Transfers.Work.Copy).move)
    }

    @Test fun `immediate copy without a destination picks first and still requires confirmation`() {
        pane(1).viewModel.currentDir = null
        open(immediate = true)
        initialPicker()
        assertNull(Transfers.active)
        choose()
        assertTrue(dialog().isShowing)
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertNull(Transfers.active)
    }

    @Test fun `canceling initial destination picking returns to the selected files`() {
        pane(1).viewModel.currentDir = null
        val adapter = ReflectionHelpers.getField<FileAdapter>(pane(), "adapter")
        pane().viewModel.lockRoot = entry(source.parentFile!!)
        pane().viewModel.refreshTree()
        pane().viewModel.toggle(pane().viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (adapter.currentList.none { it is PaneViewModel.FileNode && it.file.path == source.path } &&
            System.nanoTime() < deadline) {
            idle()
            Thread.sleep(5)
        }
        val selected = adapter.currentList.filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == source.path }.file
        adapter.setSelection(listOf(selected))
        pane().actionCopy(move = false)
        idle()
        initialPicker()
        controller.recreate()
        idle()
        cancelPicker()
        assertNull(state().draft)
        assertNull(pane().childFragmentManager.findFragmentByTag(CopyConfirmDialog.TAG))
        val restored = ReflectionHelpers.getField<FileAdapter>(pane(), "adapter")
        assertEquals(listOf(selected), restored.selectedItems())
        assertNull(Transfers.active)
        assertTrue(source.exists())
    }

    @Test fun `canceling the confirmation discards the draft without copying`() {
        open()
        dialog().cancel()
        idle()
        assertNull(state().draft)
        assertNull(Transfers.active)
        assertTrue(source.exists())
        assertFalse(File(target, source.name).exists())
    }

    @Test fun `picker results accept writable remote backends and reject read only backends`() {
        val readOnly = FakeFileSystem("readonly-copy-target", mapOf("/" to emptyList()))
        val writable = FakeFileSystem("sftp-copy-target", mapOf("/uploads" to emptyList()), writable = true)
        FsRegistry.register(readOnly)
        FsRegistry.register(writable)
        try {
            open()
            launchPicker()
            for (fs in listOf(readOnly, writable)) {
                pane(1).viewModel.currentDir = XFile(fs.scheme, "/uploads", true)
                activity.onClipTargetChanged()
                assertEquals(fs.writable(), activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).isEnabled)
            }
            activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).performClick()
            idle()
            assertEquals(writable.scheme, state().draft!!.destination!!.scheme)
            FsRegistry.unregister(writable.scheme)
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog().isShowing)
            assertFalse(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertNull(Transfers.active)
        } finally {
            FsRegistry.unregister(readOnly.scheme)
            FsRegistry.unregister(writable.scheme)
        }
    }

    @Test fun `restored confirmation without its transient source draft dismisses safely`() {
        open()
        state().clear()
        controller.recreate()
        idle()
        assertNull(pane().childFragmentManager.findFragmentByTag(CopyConfirmDialog.TAG))
        assertNull(Transfers.active)
        assertTrue(source.exists())
    }

    @Test fun `pending size calculation survives recreation without listing the source twice`() {
        val scheme = "sftp-copy-size"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val fake = FakeFileSystem(scheme, mapOf("/batch" to listOf("file")), mapOf("/batch/file" to "123"))
        FsRegistry.register(object : com.twig.core.FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                reads.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return fake.list(dir)
            }
        })
        try {
            pane(1).viewModel.currentDir = null
            open(files = listOf(XFile(scheme, "/batch", true)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.recreate()
            idle()
            assertEquals(1, reads.get())
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (state().plan.value == null && System.nanoTime() < deadline) {
                idle()
                Thread.sleep(5)
            }
            idle()
            assertEquals(3L, state().plan.value?.bytes)
            choose()
            assertEquals(com.twig.app.Format.size(3), dialog().findViewById<TextView>(R.id.tv_size)!!.text.toString())
            assertEquals(1, reads.get())
        } finally {
            release.countDown()
            FsRegistry.unregister(scheme)
        }
    }

    @Test fun `canceling destination selection stops size scanning after an in-flight listing`() {
        val scheme = "sftp-cancel-copy-size"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val reads = AtomicInteger()
        val fake = FakeFileSystem(scheme,
            mapOf("/batch" to listOf("sub"), "/batch/sub" to listOf("file")),
            mapOf("/batch/sub/file" to "123"))
        FsRegistry.register(object : com.twig.core.FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                reads.incrementAndGet()
                if (dir.path == "/batch") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return fake.list(dir)
            }
        })
        try {
            pane(1).viewModel.currentDir = null
            open(files = listOf(XFile(scheme, "/batch", true)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val job = ReflectionHelpers.getField<kotlinx.coroutines.Job>(state(), "planning")
            job.invokeOnCompletion { finished.countDown() }
            cancelPicker()
            assertTrue(job.isCancelled)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (finished.count > 0 && System.nanoTime() < deadline) {
                idle()
                Thread.sleep(5)
            }
            assertEquals(0L, finished.count)
            assertEquals(1, reads.get())
            assertNull(state().draft)
            assertNull(state().plan.value)
            assertNull(Transfers.active)
        } finally {
            release.countDown()
            FsRegistry.unregister(scheme)
        }
    }

    @Test fun `copying from the right chooses only the left pane and returns to the right`() {
        pane(0).viewModel.currentDir = null
        activity.focusPane(pane(1))
        open(sourceSide = 1)
        assertTrue(activity.isPaneActive(pane(0)))
        assertTrue(pane(1).destinationState.choosingDestination)
        assertNull(shadowOf(activity).nextStartedActivity)
        pane(0).viewModel.currentDir = entry(target)
        activity.onClipTargetChanged()
        activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).performClick()
        idle()
        assertTrue(activity.isPaneActive(pane(1)))
        assertEquals(target.path, pane(1).destinationState.draft!!.destination!!.path)
        assertNotNull(pane(1).childFragmentManager.findFragmentByTag(CopyConfirmDialog.TAG))
    }

    @Test fun `Back cancels destination mode without replacing existing clipboard contents`() {
        FileClipboard.put(listOf(entry(source)))
        FileClipboard.setMove(true)
        val clipboard = FileClipboard.state.value
        pane(1).viewModel.currentDir = null
        open()
        assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.clip_bar).visibility)
        activity.onBackPressed()
        idle()
        assertFalse(activity.isChoosingDestination())
        assertTrue(activity.isPaneActive(pane()))
        assertEquals(clipboard, FileClipboard.state.value)
        assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.clip_bar).visibility)
        assertNull(state().draft)
    }

    @Test fun `source pane cannot become a destination and landscape recreation retains that restriction`() {
        pane().viewModel.currentDir = entry(target)
        pane(1).viewModel.currentDir = null
        open()
        activity.focusPane(pane())
        activity.onPaneSwipe(1f)
        assertTrue(activity.isPaneActive(pane(1)))
        assertFalse(activity.findViewById<android.view.View>(R.id.btn_copy_destination_confirm).isEnabled)
        controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 800
            screenHeightDp = 400
        })
        idle()
        assertTrue(activity.isPaneActive(pane(1)))
        assertTrue(activity.isChoosingDestination())
        val container = activity.findViewById<android.view.ViewGroup>(R.id.pane_a)
        assertEquals(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS, container.descendantFocusability)
        assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, container.importantForAccessibility)
        cancelPicker()
        assertTrue(activity.isPaneActive(pane()))
        assertEquals(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS, container.descendantFocusability)
        assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, container.importantForAccessibility)
    }

    @Test fun `pane switch long presses cannot replace the source directory while choosing`() {
        val original = entry(source.parentFile!!)
        pane().viewModel.currentDir = original
        open()
        launchPicker()
        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val switch = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }
            .single { it.title == activity.getString(R.string.menu_swap_pane) }.actionView!!
        switch.performLongClick()
        activity.findViewById<android.view.View>(R.id.as_swap).performLongClick()
        idle()
        assertEquals(original, pane().viewModel.currentDir)
        assertEquals(target.path, pane(1).viewModel.currentDir!!.path)
        assertTrue(activity.isPaneActive(pane(1)))
        cancelPicker()
        assertEquals(original, pane().viewModel.currentDir)
        assertTrue(activity.isPaneActive(pane()))
        assertEquals(listOf(entry(source)), state().draft!!.sources)
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp-land")
    fun `source touches use screen coordinates in an offset window and swallow the entire gesture`() {
        open()
        launchPicker()
        controller.visible()
        val decor = activity.window.decorView
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1200, 600)
        val attachInfo = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowLeft", 900)
        ReflectionHelpers.setField(attachInfo, "mWindowTop", 300)
        val sourceBounds = android.graphics.Rect()
        val targetBounds = android.graphics.Rect()
        var sourceTouches = 0
        var targetTouches = 0
        val sourcePath = pane().requireView().findViewById<android.view.View>(R.id.tv_path)
        val targetPath = pane(1).requireView().findViewById<android.view.View>(R.id.tv_path)
        sourcePath.setOnTouchListener { _, _ -> sourceTouches++; true }
        targetPath.setOnTouchListener { _, _ -> targetTouches++; true }
        assertTrue(sourcePath.getGlobalVisibleRect(sourceBounds))
        assertTrue(targetPath.getGlobalVisibleRect(targetBounds))
        val rootLocation = IntArray(2)
        decor.getLocationOnScreen(rootLocation)
        assertEquals(900, rootLocation[0])
        assertEquals(300, rootLocation[1])
        fun dispatch(action: Int, bounds: android.graphics.Rect): Boolean {
            val event = android.view.MotionEvent.obtain(0, 10, action,
                (bounds.centerX() + 900).toFloat(), (bounds.centerY() + 300).toFloat(), 0)
            // Activity receives window-local x/y while rawX/rawY retain screen coordinates.
            event.offsetLocation(-900f, -300f)
            return try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        assertTrue(dispatch(android.view.MotionEvent.ACTION_DOWN, sourceBounds))
        assertTrue(ReflectionHelpers.getField(activity, "ignoringSourceTouch"))
        assertTrue(dispatch(android.view.MotionEvent.ACTION_MOVE, targetBounds))
        assertTrue(dispatch(android.view.MotionEvent.ACTION_UP, targetBounds))
        assertEquals(0, sourceTouches)
        assertEquals(0, targetTouches)
        dispatch(android.view.MotionEvent.ACTION_DOWN, targetBounds)
        assertFalse(ReflectionHelpers.getField(activity, "ignoringSourceTouch"))
        dispatch(android.view.MotionEvent.ACTION_UP, targetBounds)
        assertTrue(targetTouches > 0)
        cancelPicker()
        dispatch(android.view.MotionEvent.ACTION_DOWN, sourceBounds)
        assertFalse(ReflectionHelpers.getField(activity, "ignoringSourceTouch"))
        dispatch(android.view.MotionEvent.ACTION_UP, sourceBounds)
    }
}
