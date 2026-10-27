package com.twig.fs.smb

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.RandomSource
import com.twig.core.XFile
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.NtlmPasswordAuthenticator.AuthenticationType
import jcifs.smb.SmbFile
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.Properties

/** Explicit legacy-only backend. Never selected as an automatic fallback from SMB2/3. */
class Smb1FileSystem(private val config: SmbConfig, override val scheme: String = "smb") : FileSystem, com.twig.core.ResumableWrite {
    override val displayName = "SMB1"
    private val base = segments(config.share)
    private val serverMode = base.isEmpty()
    init {
        val server = URI("smb://${config.host}/")
        require(!server.rawAuthority.isNullOrEmpty() && server.userInfo == null &&
            '@' !in config.host && '\\' !in config.host && server.path == "/" &&
            server.query == null && server.fragment == null) { "Invalid SMB host" }
    }
    private val context: CIFSContext = BaseContext(PropertyConfiguration(Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB1")
        setProperty("jcifs.smb.client.maxVersion", "SMB1")
        setProperty("jcifs.smb.client.useSMB2Negotiation", "false")
        setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        setProperty("jcifs.smb.client.allowGuestFallback", "false")
        setProperty("jcifs.smb.client.port139.enabled", "true")
        setProperty("jcifs.smb.client.connTimeout", "8000")
        setProperty("jcifs.smb.client.responseTimeout", "15000")
        setProperty("jcifs.smb.client.soTimeout", "15000")
    })).withCredentials(when {
        config.user.isEmpty() && config.password.isEmpty() -> NtlmPasswordAuthenticator(AuthenticationType.NULL)
        // The three-argument constructor always chooses USER, even for "guest".
        // Mark an explicit passwordless guest login without allowing named users to fall back.
        else -> NtlmPasswordAuthenticator(config.domain, config.user, config.password,
            if (config.user.equals("guest", ignoreCase = true) && config.password.isEmpty())
                AuthenticationType.GUEST else AuthenticationType.USER)
    })

    fun connect() {
        try {
            val url = if (serverMode) urlFor("/IPC$", true) else urlFor("/", true)
            SmbFile(url, context).use { it.connect() }
        } catch (e: Exception) {
            runCatching { context.close() }
            throw e
        }
    }

    fun disconnect() { context.close() }
    override fun root() = XFile(scheme, "/", isDir = true)
    override fun resolve(path: String) = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> {
        if (!dir.isDir) throw FsException("Not a directory: ${dir.path}")
        return resource(dir).use { parent ->
            val shares = serverMode && segments(dir.path).isEmpty()
            parent.listFiles().mapNotNull { child ->
                child.use {
                    val name = it.name.trimEnd('/')
                    if (shares && (it.type != SmbConstants.TYPE_SHARE || name.endsWith('$'))) null
                    else metadata(it, join(dir.path, name))
                }
            }.sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })
        }
    }

    override fun stat(path: String): XFile? {
        if (serverMode && segments(path).isEmpty()) return root()
        // A URL with no trailing slash still permits querying the actual type.
        return resource(XFile(scheme, path, isDir = false)).use {
            if (it.exists()) metadata(it, path) else null
        }
    }

    override fun exists(file: XFile): Boolean = resource(file).use { it.exists() }

    override fun openInput(file: XFile): InputStream {
        requireEntry(file)
        val owner = resource(file)
        try {
            return object : FilterInputStream(owner.openInputStream()) {
                override fun close() { try { super.close() } finally { owner.close() } }
            }
        } catch (e: Exception) { owner.close(); throw e }
    }

    override fun appendPartial(file: XFile): OutputStream = openOutput(file, true)

    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        requireEntry(file)
        val owner = resource(file)
        try {
            return object : FilterOutputStream(owner.openOutputStream(append)) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                override fun close() { try { super.close() } finally { owner.close() } }
            }
        } catch (e: Exception) { owner.close(); throw e }
    }

    override fun mkdir(parent: XFile, name: String): XFile {
        checkName(name)
        val file = XFile(scheme, join(parent.path, name), isDir = true)
        requireEntry(file)
        resource(file).use { it.mkdir() }
        return file
    }

    override fun delete(file: XFile) {
        requireEntry(file)
        resource(file).use { it.delete() }
    }

    override fun rename(file: XFile, newName: String): XFile {
        requireEntry(file)
        checkName(newName)
        val target = file.copy(path = join(file.parentPath, newName))
        resource(file).use { source ->
            resource(target).use { dest ->
                if (dest.exists()) throw FsException("Already exists: $newName")
                source.renameTo(dest, false)
            }
        }
        return target
    }

    override fun randomAccessEfficient() = true
    override fun openRandom(file: XFile): RandomSource {
        requireEntry(file)
        val owner = resource(file)
        val reader = try { owner.openRandomAccess("r") } catch (e: Exception) { owner.close(); throw e }
        return object : RandomSource {
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                require(position >= 0) { "Negative read offset" }
                reader.seek(position)
                return reader.read(buffer, offset, length)
            }
            @Synchronized override fun length(): Long = reader.length()
            @Synchronized override fun close() { try { reader.close() } finally { owner.close() } }
        }
    }

    override fun setModifiedTime(file: XFile, time: Long): Boolean {
        resource(file).use { it.setLastModified(time) }
        return true
    }

    private fun resource(file: XFile) = SmbFile(urlFor(file.path, file.isDir), context)
    private fun metadata(file: SmbFile, path: String): XFile {
        val dir = file.isDirectory
        // jCIFS gives share roots a synthetic READONLY attribute, not their create permissions.
        // Allow writes inside a share and let the server enforce access; requireEntry still
        // prevents modifying the share itself. Ordinary entries retain their reported flags.
        val canWrite = file.type == SmbConstants.TYPE_SHARE || file.canWrite()
        return XFile(scheme, path, isDir = dir, size = if (dir) 0 else file.length(),
            lastModified = file.lastModified(), canWrite = canWrite)
    }

    internal fun urlFor(path: String, directory: Boolean): String {
        val parts = base + segments(path)
        val absolute = "/" + parts.joinToString("/") + if (directory && parts.isNotEmpty()) "/" else ""
        // jCIFS's URL handler expects literal names (and restores '#' from the fragment).
        // Percent-encoding here creates a different file on the server.
        return "smb://${config.host}$absolute"
    }

    private fun requireEntry(file: XFile) {
        val depth = segments(file.path).size
        if (depth == 0 || (serverMode && depth == 1)) throw FsException("Cannot modify a share root")
    }
    private fun checkName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name)
            throw FsException("Invalid file name: $name")
    }
    private fun join(dir: String, name: String) = dir.trimEnd('/') + "/" + name
    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }.also { parts ->
        if (parts.any { it == "." || it == ".." || '\\' in it }) throw FsException("Invalid SMB path")
    }
}
