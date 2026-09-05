package com.twig.fs.archive

import com.twig.core.FsException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 7z filesystem (read-only), built on Apache Commons Compress (pure Java).
 * Encrypted archives are decrypted directly by the library's AES-256 ([SevenZFile.Builder.setPassword]),
 * including **header encryption** — those archives need the password even to list the file names,
 * so [readEntries] also has to carry the password.
 */
class SevenZFileSystem : ArchiveFileSystem() {

    override val scheme: String = SCHEME
    override val displayName: String = "7z archive"
    override fun writable(): Boolean = false

    /** Goes through a random-access channel: works for local and remote alike, remote only fetches the archive header and the data segments actually accessed. */
    private fun openSz(archivePath: String, password: String? = passwordOf(archivePath)): SevenZFile =
        SevenZFile.builder()
            .setSeekableByteChannel(openChannel(archivePath))
            .also { b -> password?.let { b.setPassword(it) } }
            .get()

    override fun readEntries(archivePath: String): List<ArchiveEntry> {
        val list = ArrayList<ArchiveEntry>()
        try {
            openSz(archivePath).use { sz ->
                var e: SevenZArchiveEntry? = sz.nextEntry
                while (e != null) {
                    list.add(
                        ArchiveEntry(
                            name = e.name,
                            isDir = e.isDirectory,
                            size = if (e.isDirectory) 0L else e.size,
                            time = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L,
                        ),
                    )
                    e = sz.nextEntry
                }
            }
        } catch (t: Throwable) {
            // A header-encrypted archive cannot even produce its listing without the password — convert to the UI-recognized exception so the password dialog opens
            throw asPasswordIssue(archivePath, t) ?: t
        }
        return list
    }

    override fun openEntry(archivePath: String, inner: String): InputStream {
        val sz = try {
            openSz(archivePath)
        } catch (t: Throwable) {
            throw asPasswordIssue(archivePath, t) ?: t
        }
        try {
            var e: SevenZArchiveEntry? = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.replace('\\', '/') == inner) {
                    val input = sz.getInputStream(e)
                    return object : FilterInputStream(input) {
                        override fun close() {
                            try { super.close() } finally { sz.close() }
                        }
                    }
                }
                e = sz.nextEntry
            }
        } catch (t: Throwable) {
            sz.close()
            throw asPasswordIssue(archivePath, t) ?: t
        }
        sz.close()
        throw FsException("No such file in 7z archive: $inner")
    }

    // ---- password ----

    /** Archive -> whether it cannot be read without a password (header-encrypted or content-encrypted); probe once and remember. */
    private val encCache = HashMap<String, Boolean>()

    override fun needsPassword(archivePath: String): Boolean {
        val key = stampOf(archivePath) // automatically invalidated when a same-named file is swapped out
        synchronized(encCache) { encCache[key]?.let { return it } }
        val enc = try {
            openSz(archivePath, password = null).use { sz ->
                // If the header is not encrypted, the listing can be enumerated, but the content may still be encrypted: try reading the first non-empty file
                var e: SevenZArchiveEntry? = sz.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.size > 0) {
                        sz.getInputStream(e).read()
                        break
                    }
                    e = sz.nextEntry
                }
            }
            false
        } catch (t: Throwable) {
            // Only "missing password" counts as needing a password; a genuinely broken archive must surface its original exception on the normal path
            isPasswordIssue(t)
        }
        synchronized(encCache) { encCache[key] = enc }
        return enc
    }

    /**
     * Verify the password: read the first non-empty entry **in full** (7z's CRC is only checked at
     * the end of the stream; reading only the head cannot distinguish right from wrong password).
     * Capped at [VERIFY_LIMIT] for large entries; if that is not enough, the LZMA decoder will
     * error out earlier on a wrong password — it does not even produce a valid LZMA stream from
     * random bytes, never mind making it as far as the CRC.
     */
    override fun checkPassword(archivePath: String, password: String): Boolean = try {
        openSz(archivePath, password).use { sz ->
            var e: SevenZArchiveEntry? = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.size > 0) {
                    val input = sz.getInputStream(e)
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (total < VERIFY_LIMIT) {
                        val n = input.read(buf)
                        if (n < 0) break // read the whole entry, which means the CRC was also checked
                        total += n
                    }
                    break
                }
                e = sz.nextEntry
            }
        }
        true
    } catch (t: Throwable) {
        if (isPasswordIssue(t) || isChecksumIssue(t)) false else throw t
    }

    private fun asPasswordIssue(archivePath: String, t: Throwable): ArchivePasswordException? = when {
        t is ArchivePasswordException -> t
        isPasswordIssue(t) -> ArchivePasswordException(archivePath, wrong = hasPassword(archivePath))
        isChecksumIssue(t) && hasPassword(archivePath) -> ArchivePasswordException(archivePath, wrong = true)
        else -> null
    }

    companion object {
        const val SCHEME = "7z"

        /** Maximum bytes to read when verifying the password (enough for LZMA to error out on a wrong password). */
        private const val VERIFY_LIMIT = 4L * 1024 * 1024

        private fun causes(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause.takeIf { c -> c !== it } }

        private fun isPasswordIssue(t: Throwable): Boolean = causes(t).any {
            it is org.apache.commons.compress.PasswordRequiredException ||
                it.message?.contains("password", ignoreCase = true) == true ||
                it.message?.contains("encrypted", ignoreCase = true) == true
        }

        /**
         * With a wrong password AES produces garbage: the LZMA decoder fails right there with
         * `CorruptedInputException`, and even if it accidentally parses, the CRC will catch it.
         * **Only apply this criterion when a password has already been supplied** — otherwise a
         * genuinely corrupted archive would be misreported as a wrong password.
         */
        private fun isChecksumIssue(t: Throwable): Boolean = causes(t).any {
            it is org.tukaani.xz.CorruptedInputException ||
                it is java.io.EOFException ||
                it.message?.contains("checksum", ignoreCase = true) == true ||
                it.message?.contains("corrupt", ignoreCase = true) == true
        }
    }
}
