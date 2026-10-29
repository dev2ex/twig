package com.twig.core

/** Optional, read-only Unix attributes. All fields describe the entry itself, without following links. */
interface UnixMetadataSource {
    /** Blocking IO; null means attributes could not be read, never a set of invented defaults. */
    fun unixMetadata(file: XFile): UnixMetadata?
}

data class UnixMetadata(
    val mode: Int,
    val uid: Long,
    val gid: Long,
    val size: Long,
    val modified: Long,
    val accessed: Long,
    /** Inode status change time, not creation time. */
    val changed: Long,
    val selinuxContext: String? = null,
) {
    val isSymbolicLink: Boolean get() = mode and 0xF000 == 0xA000
    val isDirectory: Boolean get() = mode and 0xF000 == 0x4000
}

object UnixPermissions {
    /** Nine permission characters, including setuid/setgid/sticky and their non-executable forms. */
    fun symbolic(mode: Int): String = buildString(9) {
        for (group in 2 downTo 0) {
            val shift = group * 3
            append(if (mode and (4 shl shift) != 0) 'r' else '-')
            append(if (mode and (2 shl shift) != 0) 'w' else '-')
            val execute = mode and (1 shl shift) != 0
            val special = mode and (if (shift == 6) 0x800 else if (shift == 3) 0x400 else 0x200) != 0
            append(when {
                special && shift == 0 -> if (execute) 't' else 'T'
                special -> if (execute) 's' else 'S'
                execute -> 'x'
                else -> '-'
            })
        }
    }

    /** Decode the permission part of find %M without losing special bits. */
    fun parseSymbolic(value: String): Int? {
        if (value.length != 9) return null
        var mode = 0
        for (index in 0..8) {
            val char = value[index]
            if (char == '-') continue
            val bit = 1 shl (8 - index)
            when (index % 3) {
                0 -> if (char == 'r') mode = mode or bit else return null
                1 -> if (char == 'w') mode = mode or bit else return null
                2 -> when (char) {
                    'x' -> mode = mode or bit
                    's', 'S' -> {
                        if (index == 8) return null
                        mode = mode or (if (index == 2) 0x800 else 0x400)
                        if (char == 's') mode = mode or bit
                    }
                    't', 'T' -> {
                        if (index != 8) return null
                        mode = mode or 0x200
                        if (char == 't') mode = mode or bit
                    }
                    else -> return null
                }
            }
        }
        return mode
    }

    fun octal(mode: Int): String = (mode and 0xFFF).toString(8).padStart(4, '0')
}
