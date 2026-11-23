package com.twig.core

/** A forward-only filesystem whose current entry remains readable until next() is called. */
interface SequentialSource : FileSystem, java.io.Closeable {
    data class Entry(val file: XFile, val components: List<String>)
    fun next(): Entry?
}
