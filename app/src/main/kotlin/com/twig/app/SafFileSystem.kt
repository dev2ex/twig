package com.twig.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.core.isMutable
import java.io.InputStream
import java.io.OutputStream

/**
 * File system backed by the Storage Access Framework, used as a fallback on
 * Android 10+ when MANAGE_EXTERNAL_STORAGE hasn't been granted. Entries are
 * identified by document tree URIs (stored in [XFile.path]), with the real name
 * in displayName.
 *
 * Stateless operation through [DocumentsContract] + ContentResolver: any tree
 * document URI already carries its tree segment, so child URIs can be built from
 * it without caching DocumentFile.
 *
 * Writes reuse [createFile] (createDocument first to get the URI, then openOutput) —
 * exactly the use case the core layer's createFile abstraction was built for.
 */
class SafFileSystem(context: Context) : FileSystem {

    private val ctx = context.applicationContext
    private val resolver = ctx.contentResolver
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    override val scheme: String = SCHEME
    override val displayName: String = "SAF"

    /** Mounts a tree URI selected via ACTION_OPEN_DOCUMENT_TREE as the root. */
    fun rootOf(treeUri: Uri): XFile {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return XFile(SCHEME, docUri.toString(), isDir = true, displayName = queryName(docUri) ?: "SAF")
    }

    override fun root(): XFile = throw FsException(s(R.string.err_saf_mount))

    override fun resolve(path: String): XFile = XFile(SCHEME, path, isDir = true)

    override fun list(dir: XFile): List<XFile> {
        val dirUri = Uri.parse(dir.path)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            dirUri, DocumentsContract.getDocumentId(dirUri),
        )
        val out = ArrayList<XFile>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
            val lmIdx = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val cid = c.getString(idIdx)
                val mime = c.getString(mimeIdx)
                val isDir = mime == Document.MIME_TYPE_DIR
                val childUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, cid)
                out.add(
                    XFile(
                        scheme = SCHEME,
                        path = childUri.toString(),
                        isDir = isDir,
                        size = if (isDir) 0L else c.getLong(sizeIdx),
                        lastModified = c.getLong(lmIdx),
                        displayName = c.getString(nameIdx),
                    ),
                )
            }
        } ?: throw FsException(s(R.string.err_read_dir_failed, dir.path))
        out.sortWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
        return out
    }

    override fun openInput(file: XFile): InputStream =
        resolver.openInputStream(Uri.parse(file.path)) ?: throw FsException(s(R.string.err_read_failed, file.name))

    override fun openOutput(file: XFile, append: Boolean): OutputStream =
        resolver.openOutputStream(Uri.parse(file.path), if (append) "wa" else "w")
            ?: throw FsException(s(R.string.err_write_failed, file.name))

    /**
     * Random-access read via fd. A SAF entry is actually a local file (just
     * delivered via the provider), and the fd from `openFileDescriptor` supports
     * pread — same approach as `ShareSourceFileSystem`.
     *
     * ★ Without this, the entire SAF tree gets treated as "network source":
     * video thumbnails degrade to "only the file header is fed" (MKV/AVI
     * basically can't get a frame), player seek becomes "reopen and skip",
     * archives must be materialized whole. That's half the reason "things in
     * SAF feel like a separate app".
     */
    override fun openRandom(file: XFile): RandomSource {
        val pfd = runCatching { resolver.openFileDescriptor(Uri.parse(file.path), "r") }.getOrNull()
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

    /** True random access when an fd is available (local disk); falls back to the base class reopen-and-skip when not. */
    override fun randomAccessEfficient(): Boolean = true

    override fun createFile(parent: XFile, name: String): XFile {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent.path), mime, name)
            ?: throw FsException(s(R.string.err_create_file_failed, name))
        return XFile(SCHEME, uri.toString(), isDir = false, displayName = name)
    }

    override fun mkdir(parent: XFile, name: String): XFile {
        val uri = DocumentsContract.createDocument(
            resolver, Uri.parse(parent.path), Document.MIME_TYPE_DIR, name,
        ) ?: throw FsException(s(R.string.err_create_dir_failed, name))
        return XFile(SCHEME, uri.toString(), isDir = true, displayName = name)
    }

    override fun delete(file: XFile) {
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(file.path))) {
            throw FsException(s(R.string.err_delete_failed, file.name))
        }
    }

    override fun rename(file: XFile, newName: String): XFile {
        val uri = DocumentsContract.renameDocument(resolver, Uri.parse(file.path), newName)
            ?: throw FsException(s(R.string.err_rename_failed, file.name))
        return XFile(SCHEME, uri.toString(), isDir = file.isDir, displayName = newName)
    }

    override fun exists(file: XFile): Boolean =
        runCatching {
            resolver.query(Uri.parse(file.path), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

    /** SAF can't compute the parent directory statelessly; navigation is handled by PaneViewModel's back stack. */
    override fun parentOf(file: XFile): XFile? = null

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        const val SCHEME = "saf"

        /**
         * Whether this URI is the **document tree root itself** (the row hanging
         * directly off the SAF group in the tree), not some child of the root: if
         * the document id equals the tree document id, it's the root.
         *
         * Stateless check — no need to scan the persisted-grant list. Child URIs
         * are built by [DocumentsContract.buildDocumentUriUsingTree] (tree segment
         * copied verbatim from the parent, document segment replaced with the
         * child's own id), so equality only happens at the root level.
         */
        fun isTreeRoot(file: XFile): Boolean = file.scheme == SCHEME && runCatching {
            val uri = Uri.parse(file.path)
            DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getDocumentId(uri)
        }.getOrDefault(false)

        /** Which app's DocumentsProvider granted this (see [providerApp]). */
        data class ProviderApp(val pkg: String, val label: String)

        /** authority -> provider App; cached once looked up, queried fresh on each list rebuild. */
        private val providerApps = HashMap<String, ProviderApp?>()

        /**
         * Which **third-party app's** DocumentsProvider granted this document tree,
         * or null if not from one.
         *
         * Third-party apps' document ids are often actual paths (Termux gives
         * `/data/data/com.termux/files/home`), with nothing meaningful to display as
         * the row's name — and "whose tree is this?" is exactly what the user
         * cares about. So those rows use the App name + App icon instead.
         *
         * ★ System providers (external storage, downloads) are excluded: their
         * document ids look like `primary:DCIM`, and the last segment is already
         * a perfectly good name; replacing it with "External storage" is strictly worse.
         */
        fun providerApp(ctx: Context, file: XFile): ProviderApp? {
            if (file.scheme != SCHEME) return null
            val authority = runCatching { Uri.parse(file.path).authority }.getOrNull() ?: return null
            synchronized(providerApps) {
                if (providerApps.containsKey(authority)) return providerApps[authority]
            }
            val pm = ctx.packageManager
            val ai = runCatching { pm.resolveContentProvider(authority, 0) }.getOrNull()?.applicationInfo
            val app = ai?.takeIf { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                ?.let { ProviderApp(it.packageName, it.loadLabel(pm).toString()) }
            synchronized(providerApps) { providerApps[authority] = app }
            return app
        }

        /**
         * Revokes the persisted grant for this document tree (**the directory itself
         * is not touched in any way**); returns true only when the grant was actually
         * released.
         *
         * ★ What we need to give back is the **tree URI** originally taken, but the
         * row stores a document URI: releasing the latter is a **silent no-op** —
         * the grant stays in place, refresh once and the row is back. So we look up
         * the original by authority + tree document id in `persistedUriPermissions`,
         * and release exactly the read/write bits it actually holds.
         */
        fun release(ctx: Context, file: XFile): Boolean {
            val uri = runCatching { Uri.parse(file.path) }.getOrNull() ?: return false
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return false
            val resolver = ctx.contentResolver
            val perm = resolver.persistedUriPermissions.firstOrNull {
                it.uri.authority == uri.authority &&
                    runCatching { DocumentsContract.getTreeDocumentId(it.uri) }.getOrNull() == treeId
            } ?: return false
            val flags = (if (perm.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (perm.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (flags == 0) return false
            return runCatching { resolver.releasePersistableUriPermission(perm.uri, flags) }.isSuccess
        }

        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/**
 * Whether this entry can serve as a move source — on top of [isMutable], also
 * excludes the **document tree root** row.
 *
 * It looks like an ordinary directory in the tree (SAF is writable, `isMutable()`
 * is true), but it is actually a **grant**: move = copy + delete source, and
 * what's deleted is the authorized root directory itself — while the grant
 * remains, pointing at something that no longer exists. Copy/compress treat the
 * source as read-only, so they aren't affected by this restriction.
 *
 * The check lives here, not in core-fs: `saf` is an :app-private source, the
 * core layer doesn't know about it.
 */
fun XFile.isMovableSource(): Boolean = isMutable() && !SafFileSystem.isTreeRoot(this)
