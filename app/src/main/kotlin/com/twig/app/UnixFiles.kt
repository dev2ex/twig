package com.twig.app

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.twig.core.UnixMetadata

/** Android syscall hooks for the JVM local filesystem. No shell or xattr reads during listings. */
internal object UnixFiles {
    fun stat(path: String): UnixMetadata? = try {
        metadata(Os.lstat(path))
    } catch (_: ErrnoException) { null }

    fun metadata(st: StructStat) = UnixMetadata(
        st.st_mode, st.st_uid.toLong() and 0xFFFFFFFFL, st.st_gid.toLong() and 0xFFFFFFFFL,
        st.st_size, st.st_mtime * 1000L, st.st_atime * 1000L, st.st_ctime * 1000L,
    )

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
