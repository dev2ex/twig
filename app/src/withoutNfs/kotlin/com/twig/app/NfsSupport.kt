package com.twig.app

import android.content.Context
import android.widget.Toast
import com.twig.core.FileSystem
import com.twig.core.FsException

/** Keeps saved NFS configurations readable without linking the optional backend. */
@Suppress("UNUSED_PARAMETER")
internal object NfsSupport {
    const val enabled = false

    fun connect(ctx: Context, conn: SavedConnection, scheme: String, onInfo: (String) -> Unit): Unit =
        throw FsException(ctx.getString(R.string.err_reveal_unsupported))

    fun disconnect(fs: FileSystem?) = Unit

    fun showEditor(activity: MainActivity, edit: SavedConnection?, save: (SavedConnection) -> Unit) {
        Toast.makeText(activity, R.string.err_reveal_unsupported, Toast.LENGTH_SHORT).show()
    }
}
