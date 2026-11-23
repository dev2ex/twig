package com.twig.core

import java.io.OutputStream

/** Explicit opt-in: append must preserve all existing bytes, including after a failed write. */
interface ResumableWrite {
    fun canResumeWrites(directory: XFile): Boolean = true
    fun appendPartial(file: XFile): OutputStream

    /** Exact child lookups for staging validation; listing failures must propagate. */
    fun findWriteEntries(directory: XFile, names: Set<String>): List<XFile> =
        (this as FileSystem).list(directory).filter { it.name in names }
}

/** Owned by one transfer, reused only by its manual retries. Never infer completion from file size. */
class TransferCheckpoint {
    data class Partial(val file: XFile, val name: String, val replacing: XFile? = null)
    data class Failure(val source: XFile, val directory: XFile, var targetName: String = source.name)
    var failure: Failure? = null
    private val skipped = linkedSetOf<String>()
    fun skip(failed: Failure) { skipped.add(locationKey(failed.source, failed.directory)) }
    fun isSkipped(source: XFile, directory: XFile): Boolean = locationKey(source, directory) in skipped
    /** Sequential entry identities are unique within their one transfer, even before parents exist. */
    internal fun isSourceSkipped(source: XFile): Boolean = skipped.any { it.startsWith(source.toUri() + "\u0000") }
    private fun locationKey(source: XFile, directory: XFile) = source.toUri() + "\u0000" + directory.toUri()

    val metadataFailures = linkedSetOf<String>()
    val completed = linkedSetOf<String>()
    val outputs = linkedMapOf<String, XFile>()
    val partials = linkedMapOf<String, Partial>()

    fun complete(key: String, target: XFile? = null) {
        if (target != null) outputs[key] = target
        completed.add(key)
    }

    fun partial(key: String, value: Partial?) {
        if (value == null) partials.remove(key) else partials[key] = value
    }

    fun key(source: XFile, destination: XFile): String =
        listOf(source.toUri(), source.size.toString(), source.lastModified.toString(), destination.toUri())
            .joinToString("\u0000")
}
