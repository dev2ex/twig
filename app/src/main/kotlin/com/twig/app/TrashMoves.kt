package com.twig.app

import java.io.File
import java.io.IOException
import android.system.ErrnoException

/** Prefer atomic no-replace; old FUSE uses an exclusively created destination reservation. */
internal object TrashMoves {
    @Volatile private var loaded = false

    fun move(source: File, destination: File): Boolean {
        if (!loaded) synchronized(this) {
            if (!loaded) { System.loadLibrary("twigpty"); loaded = true }
        }
        // JNI's modified UTF-8 breaks supplementary characters in filenames; pass actual UTF-8 bytes.
        val error = nativeRename(source.path.toByteArray(Charsets.UTF_8), destination.path.toByteArray(Charsets.UTF_8))
        if (error != 0) {
            val cause = ErrnoException("rename", error)
            throw IOException("${source.path} → ${destination.path}: ${cause.message}", cause)
        }
        return true
    }

    /** Linux errno on failure; never copies content or permanently deletes the source. */
    private external fun nativeRename(source: ByteArray, destination: ByteArray): Int
}
