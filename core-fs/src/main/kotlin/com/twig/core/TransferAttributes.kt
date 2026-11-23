package com.twig.core

/** Only attributes that can be copied; inode change times and security labels are not transferable. */
data class TransferAttributes(
    val mode: Int? = null,
    val uid: Long? = null,
    val gid: Long? = null,
    val modified: Long? = null,
    val ownershipDomain: String? = null,
) {
    fun withFallback(other: TransferAttributes) = TransferAttributes(
        mode ?: other.mode, uid ?: other.uid, gid ?: other.gid, modified,
        if (uid != null || gid != null) ownershipDomain else other.ownershipDomain)

    fun forDestination(fs: FileSystem): TransferAttributes = if (ownershipDomain != null &&
        ownershipDomain == (fs as? UnixMetadataSource)?.ownershipDomain) this
        else copy(uid = null, gid = null, ownershipDomain = null)
}

data class MetadataResult(val complete: Boolean, val modified: Boolean = false, val modifiedTime: Long? = null)

/** Optional destination capability. Implementations must never change a symbolic link's target. */
interface UnixMetadataWrite {
    fun applyMetadata(file: XFile, attributes: TransferAttributes): MetadataResult
    /** Create a new link only; an existing entry must never be followed or replaced here. */
    fun createSymbolicLink(parent: XFile, name: String, target: String): XFile
}

object TransferMetadata {
    fun read(fs: FileSystem, file: XFile, refresh: Boolean = false): TransferAttributes {
        val unix = if (!refresh && file.unixMode != null && file.unixUid != null && file.unixGid != null) null else try {
            (fs as? UnixMetadataSource)?.unixMetadata(file, includeContext = false)
        } catch (_: Exception) { null }
        return TransferAttributes(unix?.mode ?: file.unixMode, unix?.uid ?: file.unixUid,
            unix?.gid ?: file.unixGid, file.lastModified.takeIf { it > 0 }, (fs as? UnixMetadataSource)?.ownershipDomain)
    }

    /** Attribute failure never removes successfully written content. */
    fun apply(fs: FileSystem, file: XFile, requested: TransferAttributes): MetadataResult {
        val attributes = requested.forDestination(fs)
        if (attributes.mode == null && attributes.uid == null && attributes.gid == null && attributes.modified == null)
            return MetadataResult(true)
        if (fs is UnixMetadataWrite) return try { fs.applyMetadata(file, attributes) } catch (_: Exception) { MetadataResult(false) }
        val modified = attributes.modified?.takeUnless { file.isSymbolicLink }?.let {
            runCatching { fs.setModifiedTime(file, it) }.getOrDefault(false)
        } ?: false
        return MetadataResult(attributes.mode == null && attributes.uid == null && attributes.gid == null &&
            (attributes.modified == null || file.isSymbolicLink || modified), modified)
    }
}
