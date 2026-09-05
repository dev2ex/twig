package com.twig.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.twig.core.FsRegistry
import com.twig.fs.archive.SevenZFileSystem
import com.twig.fs.archive.SingleFileSystem
import com.twig.fs.archive.TarFileSystem
import com.twig.fs.archive.ZipFileSystem
import com.twig.fs.local.LocalFileSystem

/** App entry point: apply the persisted theme as early as possible to avoid startup flash; also register the base sources that don't depend on user configuration. */
class TwigApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this))
        // Read the decoding priority list once into memory: subtitle / lyrics
        // callers don't carry a Context.
        // Also inject it into the core-fs hook — the media server's lyrics come
        // back from the server as a text string (not an openInput() stream),
        // and that side in fs-network cannot reach this preference itself.
        TextCodec.load(this)
        com.twig.core.TextDecoding.decode = { TextCodec.decode(it).text }
        registerBaseFs(this)
        MediaScan.install(this) // local writes → notify the system media store, so copied photos show up in Gallery
        Privileged.restore(this) // if root/Shizuku was on last time, reconnect it in the background (silently stay off on failure)
    }

    companion object {
        /**
         * Base file systems (local / archives / SAF / apps / external content://
         * sources).
         * ★ Must be registered in Application, not just in MainActivity — the
         * "Open with Twig" entry ([ui.ViewIntentActivity]) and the share-target
         * paths can cold-start the process with no main UI present, and at that
         * point FsRegistry is still empty, so a viewer asking for a FileSystem
         * would throw "file system not registered".
         */
        fun registerBaseFs(ctx: Context) {
            val app = ctx.applicationContext
            FsRegistry.register(LocalFileSystem())
            FsRegistry.register(ZipFileSystem())
            FsRegistry.register(SevenZFileSystem())
            FsRegistry.register(TarFileSystem())
            // Single-file compression: one scheme per format, one shared implementation
            FsRegistry.register(SingleFileSystem.gzip())
            FsRegistry.register(SingleFileSystem.xz())
            FsRegistry.register(SingleFileSystem.bzip2())
            // zstd is the one codec :fs-archive cannot decode on its own (JNI, and that
            // module is plain JVM) — hand it the stream wrapper here
            FsRegistry.register(SingleFileSystem.zstd { com.twig.fs.zstd.ZstdInputStream(it) })
            rarFileSystem()?.let { FsRegistry.register(it) } // the libre flavor has no RAR, see RarSupport.kt
            FsRegistry.register(SafFileSystem(app))
            FsRegistry.register(AppsFileSystem(app))
            FsRegistry.register(ShareSourceFileSystem(app))
        }
    }
}
