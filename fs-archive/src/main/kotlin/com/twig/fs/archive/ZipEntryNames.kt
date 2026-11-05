package com.twig.fs.archive

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.IdentityHashMap
import java.util.zip.CRC32

/** Decode names independently, keeping distinct byte names addressable even after replacement. */
internal class ZipEntryNames(entries: List<ZipArchiveEntry>, preferred: List<Charset>) {
    private class Node(val desired: String) {
        val children = linkedMapOf<String, Node>()
        var name = desired
        var directory = false
    }

    private val names = IdentityHashMap<ZipArchiveEntry, String>()

    init {
        val root = Node("")
        val paths = IdentityHashMap<ZipArchiveEntry, List<Node>>()
        // Keep user priority, but always try GB18030 before replacement decoding.
        val charsets = (listOf(Charsets.UTF_8) + preferred + Charset.forName("GB18030")).distinct()
        for (entry in entries) {
            val raw = entry.rawName
            val unicode = entry.getExtraField(UnicodePathExtraField.UPATH_ID) as? UnicodePathExtraField
            val declared = if (!entry.generalPurposeBit.usesUTF8ForNames() && unicode != null &&
                CRC32().apply { update(raw) }.value == unicode.nameCRC32
            ) strict(unicode.unicodeName, Charsets.UTF_8) else null
            // Even an incorrect UTF-8 flag must not prevent trying the user's encodings.
            val cs = charsets.firstOrNull { strict(raw, it) != null }
            val decoded = declared ?: if (cs != null) String(raw, cs) else String(raw, Charsets.UTF_8)
            val components = decoded.replace('\\', '/').removePrefix("/").trimEnd('/').split('/')
            // Latin-1 is only an identity for raw bytes, never a guessed display encoding.
            val slashParts = String(raw, Charsets.ISO_8859_1).removePrefix("/").trimEnd('/').split('/')
            val rawParts = when {
                slashParts.size == components.size -> slashParts
                declared == null && cs != null -> {
                    // A decoded backslash is a legacy separator; a Shift_JIS trail byte
                    // 0x5c inside a real character must never be treated as one.
                    components.map { String(it.toByteArray(cs), Charsets.ISO_8859_1) }
                }
                else -> String(raw, Charsets.ISO_8859_1).replace('\\', '/')
                    .removePrefix("/").trimEnd('/').split('/')
            }
            var parent = root
            val path = ArrayList<Node>()
            for ((index, part) in components.withIndex()) {
                val identity = if (rawParts.size == components.size) rawParts[index] else part
                val child = parent.children.getOrPut(identity) { Node(part) }
                path.add(child)
                if (index < components.lastIndex || entry.isDirectory) child.directory = true
                parent = child
            }
            paths[entry] = path
        }
        val pending = java.util.ArrayDeque<Node>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            // Reserve literal names before adding suffixes, including names that already end in (2).
            val reserved = parent.children.values.map { it.desired }.toHashSet()
            val used = HashSet<String>()
            for (child in parent.children.values) {
                if (!used.add(child.name)) {
                    var number = 2
                    val dot = if (child.directory) -1 else child.desired.lastIndexOf('.').takeIf { it > 0 } ?: -1
                    val stem = if (dot < 0) child.desired else child.desired.substring(0, dot)
                    val extension = if (dot < 0) "" else child.desired.substring(dot)
                    do { child.name = "$stem (${number++})$extension" }
                    while (child.name in reserved || !used.add(child.name))
                }
                pending.add(child)
            }
        }
        for (entry in entries) {
            names[entry] = paths.getValue(entry).joinToString("/") { it.name } + if (entry.isDirectory) "/" else ""
        }
    }

    operator fun get(entry: ZipArchiveEntry): String = names.getValue(entry)

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
