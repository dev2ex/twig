package com.twig.app

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.twig.core.FileSystem
import com.twig.core.XFile
import java.io.File
import java.io.IOException

/** Read-only view of recovery payloads, rendered by the ordinary expandable pane tree. */
class TrashFileSystem(private val ctx: Context, val store: TrashStore = TrashStore(ctx)) : FileSystem {
    override val scheme = "trash"
    override val displayName get() = ctx.getString(R.string.trash_title)
    data class Item(val entry: TrashStore.Entry, val relative: String)
    @Volatile private var entries = emptyMap<String, TrashStore.Entry>()
    @Volatile var notices: List<String> = emptyList()
        private set
    override fun root() = XFile(scheme, "/", true, canWrite = false, displayName = displayName)
    private fun failure(path: String): Nothing = throw IOException(ctx.getString(R.string.trash_failed, path))
    private fun token(entry: TrashStore.Entry) = Uri.encode(entry.directory.path)
    private val order = compareBy<XFile> { !it.isDir }.thenBy { it.name.lowercase() }
    /** Listing metadata only: row binding must not stat or scan storage on the UI thread. */
    fun deletedAt(file: XFile): Long? = if (file.scheme == scheme)
        entries[file.path.removePrefix("/").substringBefore('/')]?.deletedAt?.takeIf { it > 0 }
        else null

    fun item(file: XFile): Item {
        if (file.scheme != scheme || file.path == "/") failure(file.path)
        val parts = file.path.removePrefix("/").split('/', limit = 2)
        val entry = entries[parts[0]] ?: store.snapshot().entries.associateBy(::token).let {
            entries = it
            it[parts[0]] ?: failure(file.path)
        }
        return Item(entry, parts.getOrElse(1) { "" }).also { store.payload(it.entry, it.relative) }
    }
    private fun node(entry: TrashStore.Entry, relative: String): XFile {
        val payload = store.payload(entry, relative)
        val st = Os.lstat(payload.path)
        val directory = OsConstants.S_ISDIR(st.st_mode)
        return XFile(scheme, "/${token(entry)}" + if (relative.isEmpty()) "" else "/$relative",
            directory, size = if (directory) 0 else st.st_size, lastModified = st.st_mtime * 1000,
            canRead = directory || OsConstants.S_ISREG(st.st_mode), canWrite = false,
            displayName = if (relative.isEmpty()) entry.name else File(relative).name)
    }
    override fun resolve(path: String): XFile {
        if (path == "/") return root()
        val item = item(XFile(scheme, path, false))
        return node(item.entry, item.relative)
    }
    override fun list(dir: XFile): List<XFile> {
        if (dir.path == "/") {
            val snapshot = store.snapshot()
            entries = snapshot.entries.associateBy(::token)
            notices = snapshot.damaged.map { ctx.getString(R.string.trash_invalid, it) }
            return snapshot.entries.map { node(it, "") }.sortedWith(order)
        }
        val item = item(dir)
        val payload = store.payload(item.entry, item.relative)
        if (!OsConstants.S_ISDIR(Os.lstat(payload.path).st_mode)) failure(dir.path)
        return (payload.listFiles() ?: failure(dir.path)).map {
            node(item.entry, if (item.relative.isEmpty()) it.name else "${item.relative}/${it.name}")
        }.sortedWith(order)
    }
    override fun openInput(file: XFile): java.io.InputStream {
        val item = item(file)
        val payload = store.payload(item.entry, item.relative)
        if (!OsConstants.S_ISREG(Os.lstat(payload.path).st_mode)) failure(file.path)
        return payload.inputStream()
    }
    /** Local decoder input only; retain the original name for type detection and thumbnail keys. */
    fun thumbnailSource(file: XFile): XFile {
        val item = item(file)
        val payload = store.payload(item.entry, item.relative)
        if (!OsConstants.S_ISREG(Os.lstat(payload.path).st_mode)) failure(file.path)
        return file.copy(scheme = "file", path = payload.path, displayName = file.name)
    }
    override fun exists(file: XFile) = runCatching { resolve(file.path) }.isSuccess
    override fun writable() = false
    override fun openOutput(file: XFile, append: Boolean): java.io.OutputStream = failure(file.path)
    override fun mkdir(parent: XFile, name: String): XFile = failure(parent.path)
    override fun delete(file: XFile): Unit = failure(file.path)
    override fun rename(file: XFile, newName: String): XFile = failure(file.path)
}
