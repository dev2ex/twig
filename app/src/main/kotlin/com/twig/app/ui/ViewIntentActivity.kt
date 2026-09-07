package com.twig.app.ui

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import com.twig.app.R
import com.twig.app.ShareSourceFileSystem
import com.twig.app.TwigApp
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File

/**
 * "Open with Twig" entry point: catches ACTION_VIEW from other apps, wraps
 * file:// / content:// URIs into [XFile], then dispatches to the built-in viewers
 * (text/image/audio/video) or to the main screen (archives are mounted in place
 * and expanded).
 *
 * Displays no UI itself (transparent theme) and finishes immediately after dispatch.
 *
 * ★ The target viewer must start in **the task stack of this Activity** (no
 * FLAG_ACTIVITY_NEW_TASK): the temporary read grant on a content:// URI follows
 * the receiving task stack, so after this Activity finishes, as long as the same
 * stack still has our UI, the grant is still valid; once it lands in a new stack
 * the viewer may SecurityException on the first read.
 */
class ViewIntentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TwigApp.registerBaseFs(this) // on cold start FsRegistry is still empty (main screen never came up)
        val uri = intent?.data
        val file = uri?.let { runCatching { toXFile(it) }.getOrNull() }
        if (file == null) {
            Toast.makeText(this, R.string.err_unsupported_type, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        // If the master password is on, unlock first: this path can open viewers and
        // also mount archives onto the main screen tree; skipping it would mean the
        // master password only locks the front door
        SecurityUi.gate(this) {
            // Dispatch by filename extension (same OpenFiles rules as tapping a file in
            // the pane); when unrecognised (content:// display names may lack a suffix)
            // OpenDispatch falls back to the caller's MIME major type.
            OpenDispatch.open(this, file, intent.type)
            finish()
        }
    }

    /** file:// falls straight onto a local source; everything else (content://) is carried by [ShareSourceFileSystem]. */
    private fun toXFile(uri: Uri): XFile? = when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let { p ->
            val f = File(p)
            if (!f.isFile) null
            else XFile("file", f.absolutePath, isDir = false, size = f.length(), lastModified = f.lastModified())
        }
        "content" -> (FsRegistry.of(ShareSourceFileSystem.SCHEME) as ShareSourceFileSystem).wrap(uri)
        else -> null
    }
}
