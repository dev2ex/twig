package com.twig.fs.archive

import com.twig.core.FileSystem
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * The smallest possible "remote" source: a scheme other than `file`, backed by a local
 * directory. Shared by the archive tests that need a non-local host — mounting one is
 * a different code path from a local archive (bytes come from
 * `FsRegistry.of(host).openRandom(host)`, registered only at `rootOf` time).
 *
 * [opens] counts how many read channels were handed out, which is how the tests assert
 * that reading an entry does not re-walk the archive.
 */
internal class MountFs(private val rootDir: File) : FileSystem {
    val opens = AtomicInteger()

    override val scheme = "remote"
    override val displayName = "Remote"
    override fun root() = XFile(scheme, "/", isDir = true)
    private fun real(path: String) = File(rootDir, path.trimStart('/'))
    override fun resolve(path: String): XFile {
        val f = real(path)
        return XFile(scheme, path, isDir = f.isDirectory, size = f.length(), lastModified = f.lastModified())
    }
    override fun list(dir: XFile): List<XFile> =
        real(dir.path).listFiles()?.map { resolve("${dir.path.trimEnd('/')}/${it.name}") } ?: emptyList()
    override fun openInput(file: XFile): InputStream = real(file.path).inputStream()
    override fun openOutput(file: XFile, append: Boolean): OutputStream = real(file.path).outputStream()
    override fun mkdir(parent: XFile, name: String): XFile {
        File(real(parent.path), name).mkdirs()
        return resolve("${parent.path.trimEnd('/')}/$name")
    }
    override fun delete(file: XFile) { real(file.path).deleteRecursively() }
    override fun rename(file: XFile, newName: String): XFile {
        val dst = File(real(file.path).parentFile, newName)
        real(file.path).renameTo(dst)
        return resolve("${file.parentPath.trimEnd('/')}/$newName")
    }
    override fun exists(file: XFile) = real(file.path).exists()
    override fun randomAccessEfficient() = true
    override fun openRandom(file: XFile): RandomSource {
        opens.incrementAndGet()
        val raf = RandomAccessFile(real(file.path), "r")
        return object : RandomSource {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                raf.seek(position)
                return raf.read(buffer, offset, length)
            }
            override fun length() = raf.length()
            override fun close() = raf.close()
        }
    }
}
