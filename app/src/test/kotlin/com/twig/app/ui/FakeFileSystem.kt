package com.twig.app.ui

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * An in-memory file system for tests: register it into [com.twig.core.FsRegistry] and it
 * passes for a server.
 *
 * Why this is needed — the network branch of `revealPath` / `resolveFavorite` needs to
 * "connect to some server and then expand it level by level"; doing that for real would
 * require an actual FTP/SMB server. But the logic of those two code paths themselves
 * (looking up the connection, expanding group and server nodes, drilling down level by
 * level along a path) has nothing to do with which protocol is on the other end, so
 * running it against a fake is exactly what isolates them.
 *
 * Register this ahead of time under the target scheme, and the "already registered, so
 * reuse it" line at the top of [com.twig.app.Connections.ensure] returns immediately
 * without `new`-ing up a real FtpFileSystem.
 */
class FakeFileSystem(
    override val scheme: String,
    /** Directory path -> child names; files do not appear here (see [files]). */
    private val dirs: Map<String, List<String>>,
    /** File path -> content. */
    private val files: Map<String, String> = emptyMap(),
    /** File path -> mtime (milliseconds); defaults to 0 when not given. The compare feature judges by time, so this needs to be precisely controllable. */
    private val times: Map<String, Long> = emptyMap(),
    /** Whether the whole source is writable. Read-only by default -- most test cases only use it as "a server that can list directories". */
    private val writable: Boolean = false,
) : FileSystem {

    override val displayName: String = "Fake($scheme)"

    /** Records which paths `list` was called on, so a test can assert "it really did expand level by level". */
    val listed = mutableListOf<String>()

    /** Records which files were actually read, so a test can assert "the read that should have been skipped really was skipped". */
    val opened = mutableListOf<String>()

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = when {
        dirs.containsKey(path) -> XFile(scheme, path, isDir = true)
        files.containsKey(path) -> XFile(
            scheme, path, isDir = false,
            size = files.getValue(path).length.toLong(),
            lastModified = times[path] ?: 0L,
        )
        else -> throw FsException("no such path: $path")
    }

    override fun list(dir: XFile): List<XFile> {
        listed += dir.path
        val kids = dirs[dir.path] ?: throw FsException("no such directory: ${dir.path}")
        val prefix = if (dir.path.endsWith("/")) dir.path else "${dir.path}/"
        return kids.map { resolve("$prefix$it") }
    }

    override fun openInput(file: XFile): InputStream {
        opened += file.path
        return ByteArrayInputStream((files[file.path] ?: throw FsException("no such file")).toByteArray())
    }

    override fun exists(file: XFile): Boolean =
        dirs.containsKey(file.path) || files.containsKey(file.path)

    // ---- read-only: these paths are never hit by the code paths under test ----
    override fun writable(): Boolean = writable
    override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("read-only")
    override fun mkdir(parent: XFile, name: String): XFile = throw FsException("read-only")
    override fun delete(file: XFile) = throw FsException("read-only")
    override fun rename(file: XFile, newName: String): XFile = throw FsException("read-only")
}
