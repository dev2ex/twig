package com.twig.fs.network

import com.twig.core.SourcePresentation

/** Twig's all-sources mount names; only interpret these inside a marked source listing. */
object TwigDavSources {
    const val NAMESPACE = "urn:twig:share"
    const val PROPERTY = "scope"
    const val ALL = "all-sources"

    fun prefix(type: String): String = when (type) {
        "smb" -> "SMB"
        "ftp" -> "FTP"
        "sftp" -> "SFTP"
        "nfs" -> "NFS"
        "webdav", "dav" -> "WebDAV"
        "s3" -> "S3"
        "saf" -> "SAF"
        else -> type.replaceFirstChar { it.uppercaseChar() }
    }

    fun parse(segment: String): SourcePresentation? {
        val fixed = mapOf("Root" to "root", "Apps" to "apps", "Trash" to "trash")
        fixed[segment]?.let { return SourcePresentation(it, segment) }
        val type = listOf("internal", "external", "smb", "ftp", "sftp", "nfs", "webdav",
            "s3", "saf", "restic", "jellyfin", "emby").firstOrNull {
            segment.startsWith(prefix(it) + "-")
        } ?: return null
        val name = segment.substring(prefix(type).length + 1)
        return name.takeIf { it.isNotBlank() }?.let { SourcePresentation(type, it) }
    }
}
