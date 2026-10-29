package com.twig.app

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths

/** Robolectric's default lstat follows symlinks and invents a stat for missing files. */
@Implements(Os::class)
class TrashOsShadow {
    companion object {
        @Implementation @JvmStatic fun lstat(path: String): StructStat = read(path, false)
        @Implementation @JvmStatic fun stat(path: String): StructStat = read(path, true)
        @Implementation @JvmStatic fun readlink(path: String): String = try {
            Files.readSymbolicLink(Paths.get(path)).toString()
        } catch (e: java.io.IOException) { throw ErrnoException("readlink", OsConstants.EINVAL, e) }

        private fun read(path: String, follow: Boolean): StructStat {
            val attrs = try {
                Files.readAttributes(Paths.get(path), "unix:*", *if (follow) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS))
            } catch (e: NoSuchFileException) { throw ErrnoException("stat", OsConstants.ENOENT, e) }
            fun n(key: String) = (attrs[key] as Number).toLong()
            return StructStat(n("dev"), n("ino"), n("mode").toInt(), n("nlink"), n("uid").toInt(),
                n("gid").toInt(), n("rdev"), n("size"),
                (attrs["lastAccessTime"] as java.nio.file.attribute.FileTime).toMillis() / 1000,
                (attrs["lastModifiedTime"] as java.nio.file.attribute.FileTime).toMillis() / 1000,
                (attrs["ctime"] as java.nio.file.attribute.FileTime).toMillis() / 1000, 4096, 0)
        }
    }
}

/** Native syscall behavior is exercised separately; use the host move for Android UI tests. */
@Implements(TrashMoves::class, isInAndroidSdk = false)
class TrashMovesShadow {
    @Implementation fun move(source: java.io.File, destination: java.io.File): Boolean = try {
        Files.move(source.toPath(), destination.toPath())
        true
    } catch (_: java.io.IOException) { false }
}
