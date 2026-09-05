package com.twig.app

import android.content.Context
import com.twig.fs.restic.ObjectCache
import java.io.File

/**
 * Local cache for the restic index (`cacheDir/restic`).
 *
 * The `index/` directory typically holds dozens of files totalling tens of MB, and
 * every time you open a repository it gets read in full — on slow networks that is
 * a noticeable wait before any directory even expands. restic's files are
 * content-addressed (filename is the content hash), so the content never changes,
 * which means a cached entry never becomes invalid; we only need a size cap
 * ([CacheDirs.trim]).
 *
 * What we store is the **raw ciphertext**, not the decrypted index — see the
 * note in [ObjectCache].
 */
class ResticCache(private val dir: File) : ObjectCache {

    override fun read(key: String): ByteArray? {
        val f = fileFor(key) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
            ?.also { f.setLastModified(System.currentTimeMillis()) } // mark as used, for the LRU
    }

    override fun write(key: String, bytes: ByteArray) {
        val f = fileFor(key) ?: return
        runCatching {
            // Write to a temp file, then rename: if we're killed mid-write we don't
            // leave a short file that "looks like" a cache hit.
            val tmp = File(dir, "${f.name}.part")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(f)) CacheDirs.trim(dir, keep = f) else tmp.delete()
        }
    }

    /**
     * The key is restic's content hash (hex); used as-is for the filename.
     * ★ We still have to validate: the key comes from a remote directory listing,
     *   and without validation we'd let the server choose where to write outside
     *   cacheDir (`../` and friends). Anything that isn't hex is silently not cached.
     */
    private fun fileFor(key: String): File? =
        if (key.isNotEmpty() && key.length <= 64 && key.all { it in '0'..'9' || it in 'a'..'f' }) {
            File(dir, key)
        } else {
            null
        }

    companion object {
        fun of(ctx: Context) = ResticCache(CacheDirs.dir(ctx, CacheDirs.RESTIC))
    }
}
