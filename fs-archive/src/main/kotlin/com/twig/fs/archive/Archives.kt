package com.twig.fs.archive

import com.twig.core.XFile

/** 归档格式识别:扩展名 -> 对应文件系统 scheme。 */
object Archives {

    private val EXT_TO_SCHEME = mapOf(
        "zip" to ZipFileSystem.SCHEME,
        "jar" to ZipFileSystem.SCHEME,
        "apk" to ZipFileSystem.SCHEME,
        "7z" to SevenZFileSystem.SCHEME,
        "rar" to RarFileSystem.SCHEME,
    )

    /**
     * 该条目若是可挂载的归档,返回其 scheme;否则 null。
     * 任意来源均可(挂载方负责先把非本地归档物化到本地缓存)。
     */
    fun schemeFor(file: XFile): String? {
        if (file.isDir) return null
        return EXT_TO_SCHEME[file.extension]
    }

    fun isArchive(file: XFile): Boolean = schemeFor(file) != null
}
