package com.twig.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.io.File

/** Persisted external-storage grants keep their original local paths in the file tree. */
class AndroidDataAccess(private val ctx: Context) {
    private val saf = SafFileSystem(ctx)
    private data class Grant(val uri: Uri, val id: String, val path: String, val write: Boolean, val direct: Boolean)
    @Volatile private var grants = emptyList<Grant>()

    init { refresh() }

    fun refresh(): Boolean {
        val before = grants
        val volumes = StorageVolumes.cached(ctx)
        grants = ctx.contentResolver.persistedUriPermissions.flatMap { permission ->
            if (!permission.isReadPermission) return@flatMap emptyList()
            val uri = permission.uri
            if (uri.authority != AUTHORITY) return@flatMap emptyList()
            val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return@flatMap emptyList()
            if (':' !in id) return@flatMap emptyList()
            val relative = id.substringAfter(':')
            val data = relative == "Android/data" || relative.startsWith("Android/data/")
            val volume = id.substringBefore(':')
            // Keep ordinary primary-storage grants independent of the direct local tree.
            if (volume == "primary" && !data) return@flatMap emptyList()
            if (relative.isNotEmpty() && relative.split('/').any { it == "." || it == ".." || it.isEmpty() })
                return@flatMap emptyList()
            val roots = if (volume == "primary") listOf(Environment.getExternalStorageDirectory().path)
                else if (volume.matches(Regex("[A-Za-z0-9-]+"))) {
                    listOf("/storage/$volume") + volumes.filter { File(it.path).name == volume }.map { it.path }
                } else return@flatMap emptyList()
            roots.distinct().mapNotNull { root ->
                val base = runCatching { File(root).canonicalPath }.getOrNull() ?: return@mapNotNull null
                val path = runCatching { File(root, relative).canonicalPath }.getOrNull() ?: return@mapNotNull null
                if (path != base && !path.startsWith(base + "/")) return@mapNotNull null
                val direct = !data && File(path).canRead() && File(path).canWrite()
                Grant(uri, id, path, permission.isWritePermission, direct)
            }
        }.sortedByDescending { it.path.length }
        return grants != before
    }

    fun route(path: String): FileSystem? {
        if (grants.isEmpty()) return null
        val normalized = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
        val grant = grants.firstOrNull { normalized == it.path || normalized.startsWith(it.path + "/") } ?: return null
        if (grant.direct) return null
        val relative = documentId(normalized)?.substringAfter(':')
        val own = "Android/data/${ctx.packageName}"
        if (relative != null && (relative == own || relative.startsWith("$own/")) &&
            File(normalized.removeSuffix(relative), own).canRead()) return null
        return Tree(grant)
    }

    /** Only existing grants are shown when Android refuses the parent listing; never invent installed-app folders. */
    fun grantedFolders(root: XFile): List<XFile> = grants.mapNotNull { grant ->
        val relative = grant.path.removePrefix(File(root.path).canonicalPath + "/")
        if (relative == grant.path || relative.contains('/')) null
        else Tree(grant).stat(grant.path)
    }.distinctBy { it.path }

    /** Native Android listings may succeed while exposing only our own package. */
    fun hasFullListing(root: XFile, listed: List<XFile>): Boolean {
        val parent = File(root.path).canonicalPath
        if (route(parent) != null) return true
        val names = listed.map { it.name }.toSet()
        val grantedNames = grants.mapNotNull {
            it.path.removePrefix("$parent/").takeIf { name -> name != it.path && '/' !in name }
        }
        return names.any { it != ctx.packageName } && grantedNames.all { it in names }
    }

    /** Browsing shortcuts only; these do not make an unreadable parent listing complete. */
    fun ownFolder(root: XFile): XFile? {
        if (!isRoot(root)) return null
        // Ensure the app's external files directory exists, including on removable volumes.
        ctx.getExternalFilesDirs(null)
        val folder = File(root.path, ctx.packageName)
        return if (folder.isDirectory && folder.canRead()) localFolder(folder) else null
    }

    /** Authorization candidates only; Android may hide whether these paths exist. */
    data class AppFolder(val file: XFile, val label: String, val system: Boolean = false)

    fun appFolderCandidates(root: XFile): List<AppFolder> {
        if (!isRoot(root)) return emptyList()
        refresh()
        val authorized = grants
        val parent = File(root.path).canonicalPath
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0)
            .filter { it.packageName != ctx.packageName }
            .filter { info ->
                val path = "$parent/${info.packageName}"
                authorized.none { path == it.path || path.startsWith(it.path + "/") }
            }
            .distinctBy { it.packageName }.map { info ->
                AppFolder(XFile("file", File(root.path, info.packageName).path, true),
                    info.loadLabel(pm).toString(),
                    info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun localFolder(folder: File) = XFile("file", folder.canonicalPath, true,
        lastModified = folder.lastModified(), canRead = folder.canRead(), canWrite = folder.canWrite())

    fun accepts(uri: Uri, requested: XFile): Boolean {
        if (uri.scheme != "content" || uri.authority != AUTHORITY) return false
        val requestedId = documentId(requested.path) ?: return false
        val selected = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return false
        if (selected.split('/').any { it == "." || it == ".." || it.isEmpty() }) return false
        val root = requestedId.substringBefore(':') + ":Android/data"
        if (selected != root && !selected.startsWith("$root/")) return false
        if (requestedId == root && selected != root && selected.removePrefix("$root/").contains('/')) return false
        return requestedId == root || requestedId == selected || requestedId.startsWith("$selected/")
    }

    fun grantedPath(uri: Uri): String? = grants.firstOrNull { it.uri == uri }?.path

    fun isGrantRoot(file: XFile): Boolean = file.scheme == "file" &&
        grants.any { !it.direct && it.path == runCatching { File(file.path).canonicalPath }.getOrNull() }

    private inner class Tree(private val grant: Grant) : FileSystem by saf {
        override val scheme = LocalFileSystem.SCHEME
        override val displayName = "Local storage"
        private fun remote(file: XFile): XFile {
            val path = File(file.path).canonicalPath
            if (path != grant.path && !path.startsWith(grant.path + "/")) throw FsException(ctx.getString(R.string.android_data_wrong_folder))
            val suffix = path.removePrefix(grant.path)
            val id = grant.id + if (grant.id.endsWith(':')) suffix.removePrefix("/") else suffix
            return file.copy(scheme = SafFileSystem.SCHEME,
                path = DocumentsContract.buildDocumentUriUsingTree(grant.uri, id).toString())
        }
        private fun local(file: XFile): XFile {
            val id = DocumentsContract.getDocumentId(Uri.parse(file.path))
            val prefix = if (grant.id.endsWith(':')) grant.id else grant.id + "/"
            if (id != grant.id && !id.startsWith(prefix)) throw FsException(ctx.getString(R.string.android_data_wrong_folder))
            val suffix = id.removePrefix(grant.id).removePrefix("/")
            val path = File(grant.path, suffix).canonicalPath
            if (path != grant.path && !path.startsWith(grant.path + "/")) throw FsException(ctx.getString(R.string.android_data_wrong_folder))
            return file.copy(scheme = scheme, path = path, displayName = null, canRead = true,
                canWrite = grant.write && file.canWrite)
        }
        override fun root() = local(saf.rootOf(grant.uri))
        override fun resolve(path: String) = stat(path) ?: throw FsException(ctx.getString(R.string.err_read_failed, File(path).name))
        override fun stat(path: String) = saf.stat(remote(XFile(scheme, path, false)).path)?.let(::local)
        override fun list(dir: XFile) = saf.list(remote(dir))
        override fun openInput(file: XFile) = saf.openInput(remote(file))
        override fun openRandom(file: XFile) = saf.openRandom(remote(file))
        private fun requireWrite() {
            if (!grant.write) throw FsException(ctx.getString(R.string.err_write_failed, File(grant.path).name))
        }
        override fun writable() = grant.write
        override fun openOutput(file: XFile, append: Boolean): java.io.OutputStream {
            requireWrite()
            val target = remote(file)
            val actual = if (saf.exists(target)) target else saf.createFile(remote(XFile(scheme, file.parentPath, true)), file.name)
            return saf.openOutput(actual, append)
        }
        override fun createFile(parent: XFile, name: String): XFile {
            requireWrite()
            return saf.createFile(remote(parent), name)
        }
        override fun mkdir(parent: XFile, name: String): XFile {
            requireWrite()
            return saf.mkdir(remote(parent), name)
        }
        override fun delete(file: XFile) { requireWrite(); saf.delete(remote(file)) }
        override fun rename(file: XFile, newName: String): XFile {
            requireWrite()
            if (saf.list(remote(XFile(scheme, file.parentPath, true))).any { it.name == newName })
                throw FsException(ctx.getString(R.string.err_rename_failed, newName))
            return saf.rename(remote(file), newName)
        }
        override fun exists(file: XFile) = saf.exists(remote(file))
        override fun setModifiedTime(file: XFile, time: Long) = false
        override fun moveWithin(src: XFile, destDir: XFile, newName: String) = false
        override fun parentOf(file: XFile): XFile? =
            if (File(file.path).canonicalPath == grant.path) null else XFile(scheme, file.parentPath, true)
    }

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val ACTION_PREFIX = "grant_android_data:"
        @Volatile var instance: AndroidDataAccess? = null
            private set
        fun install(ctx: Context) {
            val access = AndroidDataAccess(ctx.applicationContext)
            instance = access
            LocalFileSystem.documentAccess = access::route
        }
        fun documentId(path: String): String? {
            if (!path.contains("/Android/data")) return null
            val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
            val primary = Environment.getExternalStorageDirectory().canonicalPath
            val relative: String
            val volume: String
            if (canonical.startsWith("$primary/")) {
                volume = "primary"; relative = canonical.removePrefix("$primary/")
            } else {
                val parts = canonical.split('/')
                if (parts.size < 5 || parts[1] != "storage" || !parts[2].matches(Regex("[A-Za-z0-9-]+"))) return null
                volume = parts[2]; relative = parts.drop(3).joinToString("/")
            }
            return if (relative == "Android/data" || relative.startsWith("Android/data/")) "$volume:$relative" else null
        }
        fun supports(file: XFile) = Build.VERSION.SDK_INT >= 30 && file.scheme == "file" && documentId(file.path) != null
        fun isRoot(file: XFile) = supports(file) && documentId(file.path)?.substringAfter(':') == "Android/data"
        fun initialUri(file: XFile): Uri {
            val id = documentId(file.path)!!
            val volume = DocumentsContract.buildTreeDocumentUri(AUTHORITY, id.substringBefore(':') + ":")
            return DocumentsContract.buildDocumentUriUsingTree(volume, id)
        }
    }
}
