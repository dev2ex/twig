package com.twig.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream

/**
 * Read-only source: wraps a `content://` URI handed to us by another app as an
 * [XFile]. Two entry points share it:
 *  - Share ("Copy to…", [ui.ShareTargetActivity]): handed to [com.twig.core.CopyEngine]
 *    to copy anywhere
 *  - Open ("Open with Twig", [ui.ViewIntentActivity]): handed to the appropriate
 *    viewer / archive mount
 *
 * No new copy/view logic — just one new source, following the project's
 * "new source = new FileSystem" architecture. Entries are always leaf files
 * (no directory support); write methods are never called and throw on the off chance.
 */
class ShareSourceFileSystem(context: android.content.Context) : FileSystem {

    private val ctx = context.applicationContext
    private val resolver: ContentResolver = ctx.contentResolver
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    override val scheme: String = SCHEME
    override val displayName: String = "Share"

    /** [uri] stored in path; [name]/[size] come from OpenableColumns metadata queried by the caller. */
    fun wrap(uri: Uri, name: String, size: Long): XFile =
        XFile(SCHEME, uri.toString(), isDir = false, size = size, displayName = name)

    /** Queries OpenableColumns itself to wrap an entry; if the query misses, name
     * falls back to the URI's last segment and size to the fd's length. */
    fun wrap(uri: Uri): XFile {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }
            .getOrNull()?.takeIf { it >= 0 } ?: 0L
        return wrap(uri, name ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "file" }, size)
    }

    override fun root(): XFile = throw FsException(s(R.string.err_share_no_root))
    override fun resolve(path: String): XFile = XFile(SCHEME, path, isDir = false)
    override fun list(dir: XFile): List<XFile> = emptyList()

    /** Top-level entry — no parent (the default implementation would slice path as a URI, meaningless for content://). */
    override fun parentOf(file: XFile): XFile? = null

    override fun openInput(file: XFile): InputStream =
        resolver.openInputStream(Uri.parse(file.path)) ?: throw FsException(s(R.string.err_read_failed, file.name))

    /**
     * Random-access read: most providers (MediaStore / DocumentsProvider /
     * FileProvider) can give a real fd, which supports pread — this is what
     * streaming archive parsing and video seek rely on, no need to materialize
     * the whole file into a cache. When no fd is available (pure pipe-style
     * providers) we fall back to the base class's "reopen and skip" implementation.
     */
    override fun openRandom(file: XFile): RandomSource {
        val uri = Uri.parse(file.path)
        val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return super.openRandom(file)
        val fis = java.io.FileInputStream(pfd.fileDescriptor)
        return object : RandomSource {
            private val ch = fis.channel
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                ch.read(java.nio.ByteBuffer.wrap(buffer, offset, length), position)
            override fun length(): Long = pfd.statSize.takeIf { it >= 0 } ?: file.size
            override fun close() {
                runCatching { fis.close() }
                runCatching { pfd.close() }
            }
        }
    }

    /** True random access when an fd is available (local disk); falls back to the base class reopen-and-skip when not — caller's responsibility. */
    override fun randomAccessEfficient(): Boolean = true

    override fun writable(): Boolean = false

    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        throw FsException(s(R.string.err_share_read_only))

    override fun mkdir(parent: XFile, name: String): XFile = throw FsException(s(R.string.err_share_read_only))
    override fun delete(file: XFile) = throw FsException(s(R.string.err_share_read_only))
    override fun rename(file: XFile, newName: String): XFile = throw FsException(s(R.string.err_share_read_only))
    override fun exists(file: XFile): Boolean = true

    companion object {
        const val SCHEME = "share"
    }
}
