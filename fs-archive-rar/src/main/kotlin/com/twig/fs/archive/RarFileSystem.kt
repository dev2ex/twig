package com.twig.fs.archive

import com.twig.core.FsException
import com.github.junrar.Archive
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * RAR filesystem (read-only, RAR4 + RAR5).
 * Encrypted archives are handed to junrar's password constructor; header-encrypted archives
 * (where even the file names are encrypted) likewise need the password before the listing can
 * be produced.
 *
 * ★ RAR5 is only in junrar **8.0.0+** (7.5.5 throws `UnsupportedRarV5Exception` even from the
 * [Archive] constructor). Downgrading to 7.x would not surface "unsupported" — it would
 * **pop up the password dialog**: that exception would be swallowed by [needsPassword]'s
 * `getOrElse { true }` below into "this is a header-encrypted archive". See `RarFileSystemTest`.
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
            throw FsException("Cannot read RAR: ${e.message ?: e.javaClass.simpleName}", e)
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

    // ---- password ----

    private val encCache = HashMap<String, Boolean>()

    override fun needsPassword(archivePath: String): Boolean {
        val key = stampOf(archivePath) // automatically invalidated when a same-named file is swapped out
        synchronized(encCache) { encCache[key]?.let { return it } }
        // A header-encrypted archive fails to even construct without a password; otherwise check each entry's own encrypted flag
        val enc = runCatching {
            Archive(File(archivePath)).use { a -> a.isEncrypted || a.fileHeaders.any { it.isEncrypted } }
        }.getOrElse { true }
        synchronized(encCache) { encCache[key] = enc }
        return enc
    }

    /**
     * Verify the password: decrypt the first non-empty entry's head, **and confirm that many
     * bytes really came out**.
     *
     * ★ The criterion cannot be just "no exception was thrown". With a wrong password junrar
     * mostly **silently returns 0 bytes** — both RAR4 and content-encrypted RAR5 behave this
     * way (7.5.5 and 8.1.0 agree); only header-encrypted archives throw `WrongPasswordException`.
     * If we only look at the exception, the user enters the wrong password and is told "password
     * correct", and then the opened file is empty. See `RarFileSystemTest`.
     */
    override fun checkPassword(archivePath: String, password: String): Boolean = try {
        open(archivePath, password).use { archive ->
            val h = archive.fileHeaders.firstOrNull { !it.isDirectory && it.fullUnpackSize > 0 }
            if (h == null) true else archive.getInputStream(h).use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (total < VERIFY_LIMIT) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
                total >= minOf(h.fullUnpackSize, VERIFY_LIMIT)
            }
        }
    } catch (t: Throwable) {
        false // junrar's wrong-password reporting is all over the place (CRC / decode / EOF) — treat all as wrong password
    }

    companion object {
        const val SCHEME = Archives.RAR_SCHEME

        /** Maximum bytes to decrypt when verifying the password. */
        private const val VERIFY_LIMIT = 4L * 1024 * 1024
    }
}
