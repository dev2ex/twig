package com.twig.fs.archive

import com.twig.core.XFile

/** Archive format detection: extension -> matching FileSystem scheme. */
object Archives {

    /**
     * The RAR scheme. **The constant lives here, not on [RarFileSystem]**: that
     * class lives in :fs-archive-rar, which the libre flavor does not depend on at
     * all (licensing reason — see CLAUDE.md "RAR and F-Droid"). Anything that
     * needs to look at the scheme must use this constant, never that class — the
     * libre flavor would not compile otherwise.
     */
    const val RAR_SCHEME = "rar"

    private val EXT_TO_SCHEME = mapOf(
        "zip" to ZipFileSystem.SCHEME,
        "jar" to ZipFileSystem.SCHEME,
        "apk" to ZipFileSystem.SCHEME,
        // Multi-apk bundles (xapk = APKPure, apks = bundletool/SAI, apkm = APKMirror):
        // zips holding base.apk + its split apks. Like apk they are not expanded on a
        // tap (the UI installs them instead), but "Open as archive" needs them mounted.
        "xapk" to ZipFileSystem.SCHEME,
        "apks" to ZipFileSystem.SCHEME,
        "apkm" to ZipFileSystem.SCHEME,
        "7z" to SevenZFileSystem.SCHEME,
        "rar" to RAR_SCHEME,
        "tar" to TarFileSystem.SCHEME,
        // Single-file compression: mounted as a one-entry archive, so `foo.tar.gz`
        // opens to `foo.tar` and expanding that gives the tar (see SingleFileSystem).
        "gz" to SingleFileSystem.GZIP_SCHEME,
        "xz" to SingleFileSystem.XZ_SCHEME,
        "bz2" to SingleFileSystem.BZIP2_SCHEME,
        "zst" to SingleFileSystem.ZSTD_SCHEME,
        // .tgz/.txz/.tbz(2): same mounts, and SingleFileSystem puts the ".tar" back on
        // the entry name so the second layer still recognises it
    ) + SingleFileSystem.TAR_SHORTHAND

    /**
     * Returns the scheme of this entry's archive format if it's a mountable
     * archive, otherwise null. Works for any source — the caller is responsible
     * for materializing non-local archives into the local cache before mounting.
     */
    fun schemeFor(file: XFile): String? {
        if (file.isDir) return null
        return EXT_TO_SCHEME[file.extension]
    }

    fun isArchive(file: XFile): Boolean = schemeFor(file) != null
}
