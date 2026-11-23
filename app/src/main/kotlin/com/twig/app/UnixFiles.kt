package com.twig.app

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.twig.core.UnixMetadata
import com.twig.core.TransferAttributes
import com.twig.core.MetadataResult

/** Android syscall hooks for the JVM local filesystem. No shell or xattr reads during listings. */
internal object UnixFiles {
    fun stat(path: String): UnixMetadata? = try {
        metadata(Os.lstat(path))
    } catch (_: ErrnoException) { null }

    fun metadata(st: StructStat) = UnixMetadata(
        st.st_mode, st.st_uid.toLong() and 0xFFFFFFFFL, st.st_gid.toLong() and 0xFFFFFFFFL,
        st.st_size, st.st_mtime * 1000L, st.st_atime * 1000L, st.st_ctime * 1000L,
    )

    fun apply(path: String, attributes: TransferAttributes): MetadataResult {
        val current = stat(path) ?: return MetadataResult(false)
        var complete = true
        var ownerChanged = false
        if ((attributes.uid != null && attributes.uid != current.uid) || (attributes.gid != null && attributes.gid != current.gid)) {
            try { Os.lchown(path, attributes.uid?.toInt() ?: -1, attributes.gid?.toInt() ?: -1); ownerChanged = true }
            catch (_: ErrnoException) { complete = false }
        }
        // chown may clear special bits, so permissions are restored afterwards.
        val mode = attributes.mode?.and(0xFFF)
        if (!current.isSymbolicLink && mode != null && (ownerChanged || current.mode and 0xFFF != mode)) {
            try { Os.chmod(path, mode) }
            catch (_: ErrnoException) { complete = false }
        }
        val modified = attributes.modified?.takeUnless { current.isSymbolicLink }?.let { java.io.File(path).setLastModified(it) } ?: false
        if (attributes.modified != null && !current.isSymbolicLink && !modified) complete = false
        return MetadataResult(complete, modified, if (modified) java.io.File(path).lastModified() else null)
    }

    fun symlink(path: String, target: String): Boolean = try {
        Os.symlink(target, path)
        true
    } catch (_: ErrnoException) { false }

    /** Properties only. getxattr follows links; ls -dZ reports the link's own context instead. */
    fun context(path: String): String? {
        val st = try { Os.lstat(path) } catch (_: ErrnoException) { return null }
        if (Build.VERSION.SDK_INT >= 26 && !OsConstants.S_ISLNK(st.st_mode)) {
            try {
                val value = Os.getxattr(path, "security.selinux").toString(Charsets.UTF_8).trimEnd('\u0000')
                if (value.isNotBlank()) return value
            } catch (_: ErrnoException) { /* Older systems and denied xattrs can still support ls -Z. */ }
        }
        return runCatching {
            val process = ProcessBuilder("/system/bin/ls", "-Zd", "--", path).redirectErrorStream(true).start()
            try {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitFor() == 0) com.twig.fs.local.priv.PrivilegedFs.parseContext(output) else null
            } finally { process.destroy() }
        }.getOrNull()
    }
}
