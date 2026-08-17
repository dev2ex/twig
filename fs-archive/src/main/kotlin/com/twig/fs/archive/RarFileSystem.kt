package com.twig.fs.archive

import com.twig.core.FsException
import com.github.junrar.Archive
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * RAR 文件系统(只读,仅 RAR4;RAR5 junrar 不支持,会抛错)。
 * 加密包交给 junrar 的密码构造器;头加密(连文件名都加密)的包同样要先有密码才列得出清单。
 */
class RarFileSystem : ArchiveFileSystem() {

    override val scheme: String = SCHEME
    override val displayName: String = "RAR archive"
    override fun writable(): Boolean = false

    private fun open(archivePath: String, password: String? = passwordOf(archivePath)): Archive =
        if (password != null) Archive(File(archivePath), password) else Archive(File(archivePath))

    override fun readEntries(archivePath: String): List<ArchiveEntry> {
        try {
            open(archivePath).use { archive ->
                return archive.fileHeaders.map { h ->
                    ArchiveEntry(
                        name = h.fileName.replace('\\', '/'),
                        isDir = h.isDirectory,
                        size = if (h.isDirectory) 0L else h.fullUnpackSize,
                        time = h.mTime?.time ?: 0L,
                    )
                }
            }
        } catch (e: ArchivePasswordException) {
            throw e
        } catch (e: Exception) {
            if (needsPassword(archivePath) && !hasPassword(archivePath)) {
                throw ArchivePasswordException(archivePath)
            }
            throw FsException("Cannot read RAR (RAR5 is not supported): ${e.message}", e)
        }
    }

    override fun openEntry(archivePath: String, inner: String): InputStream {
        val archive = open(archivePath)
        val header = archive.fileHeaders.firstOrNull {
            !it.isDirectory && it.fileName.replace('\\', '/') == inner
        }
        if (header == null) {
            archive.close()
            throw FsException("No such file in RAR archive: $inner")
        }
        if (header.isEncrypted && !hasPassword(archivePath)) {
            archive.close()
            throw ArchivePasswordException(archivePath)
        }
        return object : FilterInputStream(archive.getInputStream(header)) {
            override fun close() {
                try { super.close() } finally { archive.close() }
            }
        }
    }

    // ---- 密码 ----

    private val encCache = HashMap<String, Boolean>()

    override fun needsPassword(archivePath: String): Boolean {
        val key = stampOf(archivePath) // 同名文件被换掉时自动失效
        synchronized(encCache) { encCache[key]?.let { return it } }
        // 头加密的包无密码连构造都会失败;头没加密时看条目自己的加密位
        val enc = runCatching {
            Archive(File(archivePath)).use { a -> a.isEncrypted || a.fileHeaders.any { it.isEncrypted } }
        }.getOrElse { true }
        synchronized(encCache) { encCache[key] = enc }
        return enc
    }

    /** 校验密码:解出第一个非空条目的开头一段(密码错时 junrar 会在解码时抛错)。 */
    override fun checkPassword(archivePath: String, password: String): Boolean = try {
        open(archivePath, password).use { archive ->
            val h = archive.fileHeaders.firstOrNull { !it.isDirectory && it.fullUnpackSize > 0 }
            if (h != null) {
                archive.getInputStream(h).use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (total < VERIFY_LIMIT) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
            }
        }
        true
    } catch (t: Throwable) {
        false // junrar 密码错的报法五花八门(CRC/解码/EOF),一律当密码不对
    }

    companion object {
        const val SCHEME = "rar"

        /** 校验密码时最多解这么多。 */
        private const val VERIFY_LIMIT = 4L * 1024 * 1024
    }
}
