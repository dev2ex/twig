package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.SourcePresentation
import com.twig.core.SourcePresentationSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.network.TwigDavSources
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class SharedSourceRowTest {
    @Test fun `shared source rows use localized labels and icons while preserving paths and capacity`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ctx = ContextThemeWrapper(app, R.style.Theme_Twig)
        val fs = object : FileSystem by LocalFileSystem(), SourcePresentationSource {
            override val scheme = "shared-source-test"
            override fun sourcePresentation(file: XFile): SourcePresentation? =
                if (file.parentPath == "/") TwigDavSources.parse(file.name) else null
        }
        FsRegistry.register(fs)
        try {
            val cases = listOf(
                Triple("Internal-Storage", ctx.getString(R.string.group_internal_storage), R.drawable.ic_storage),
                Triple("Root", ctx.getString(R.string.group_root), R.drawable.ic_storage_root),
                Triple("Apps", ctx.getString(R.string.apps_root), R.drawable.ic_file_apk),
                Triple("Trash", ctx.getString(R.string.trash_title), R.drawable.ic_trash),
                Triple("SMB-NAS-West", "NAS-West", R.drawable.ic_lan),
            )
            val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
            val holder = adapter.createViewHolder(FrameLayout(ctx), 0)
            for ((name, label, icon) in cases) {
                val file = XFile(fs.scheme, "/$name", true)
                adapter.submitList(null)
                adapter.submitList(listOf(PaneViewModel.FileNode(file, 1, false, false, capacity = "20 B / 100 B")))
                adapter.bindViewHolder(holder, 0)
                assertEquals(label, holder.itemView.findViewById<TextView>(R.id.name).text.toString())
                assertEquals("20 B / 100 B", holder.itemView.findViewById<TextView>(R.id.meta).text.toString())
                assertEquals(icon, FileIcons.sharedSourceIconRes(file))
                assertEquals(name, file.name)
                assertEquals("/$name", file.path)
            }
            val ordinary = XFile(fs.scheme, "/folder/SMB-NAS", true)
            assertEquals("SMB-NAS", FileIcons.displayName(ctx, ordinary))
            assertNull(FileIcons.sharedSourceIconRes(ordinary))
            val unknown = XFile(fs.scheme, "/Unknown-name", true)
            assertEquals("Unknown-name", FileIcons.displayName(ctx, unknown))
        } finally {
            FsRegistry.unregister(fs.scheme)
        }
    }
}
