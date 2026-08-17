package com.twig.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import java.io.InputStream
import java.io.OutputStream

/**
 * 基于 Storage Access Framework 的文件系统,用于 Android 10+ 未授予 MANAGE_EXTERNAL_STORAGE
 * 时的回退访问。条目以 document tree URI 标识(放在 [XFile.path]),真实名字放在 displayName。
 *
 * 通过 [DocumentsContract] + ContentResolver 无状态操作:任一 tree document URI 都自带 tree 段,
 * 故可据此构造其子项 URI,无需缓存 DocumentFile。
 *
 * 写入复用 [createFile](先 createDocument 拿到 URI 再 openOutput),正是 core 层 createFile 抽象的用武之地。
 */
class SafFileSystem(context: Context) : FileSystem {

    private val ctx = context.applicationContext
    private val resolver = ctx.contentResolver
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    override val scheme: String = SCHEME
    override val displayName: String = "SAF"

    /** 把 ACTION_OPEN_DOCUMENT_TREE 选中的 tree URI 挂载为根。 */
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

    /** SAF 无法无状态求父目录;由 PaneViewModel 的回退栈处理导航。 */
    override fun parentOf(file: XFile): XFile? = null

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        const val SCHEME = "saf"
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
