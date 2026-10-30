package com.twig.app.ui

import android.content.Context
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir
import java.util.Locale
import java.util.concurrent.CancellationException

/** Blocking preflight shared by same-directory copies and quick numbered renames. */
internal object DuplicateEntries {
    fun parents(context: Context, files: List<XFile>, treeParents: Map<XFile, XFile?>): List<Pair<XFile, XFile>> {
        val pairs = files.map { file ->
            val parent = treeParents[file] ?: FsRegistry.of(file).parentOf(file)
            check(parent?.isWritableDir() == true) { context.getString(R.string.err_failed) }
            file to requireNotNull(parent)
        }
        val selected = files.map { it.scheme to it.path }.toSet()
        for ((_, directory) in pairs) {
            var ancestor: XFile? = directory
            val seen = HashSet<Pair<String, String>>()
            while (ancestor != null && seen.add(ancestor.scheme to ancestor.path)) {
                check((ancestor.scheme to ancestor.path) !in selected) { context.getString(R.string.batch_nested) }
                ancestor = treeParents[ancestor] ?: FsRegistry.of(ancestor).parentOf(ancestor)
            }
        }
        return pairs
    }

    fun names(pairs: List<Pair<XFile, XFile>>, cancelled: () -> Boolean = { false }): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun checkCancelled() {
            if (cancelled()) throw CancellationException()
        }
        for (entries in pairs.groupBy { it.second.scheme to it.second.path }.values) {
            checkCancelled()
            val parent = entries.first().second
            // A failed listing must abort; treating it as empty could overwrite an existing entry.
            val taken = FsRegistry.of(parent).list(parent).mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
            for ((file, _) in entries) {
                checkCancelled()
                val name = nextName(file, taken)
                taken.add(name.lowercase(Locale.ROOT))
                result[file.toUri()] = name
            }
        }
        checkCancelled()
        return result
    }

    private fun nextName(file: XFile, taken: Set<String>): String {
        val dot = if (file.isDir) -1 else file.name.lastIndexOf('.')
        val stem = if (dot > 0) file.name.substring(0, dot) else file.name
        val extension = if (dot > 0) file.name.substring(dot) else ""
        val underscore = stem.lastIndexOf('_')
        val suffix = if (underscore > 0) stem.substring(underscore + 1).toLongOrNull() else null
        val numbered = suffix != null && suffix > 0 && suffix < Long.MAX_VALUE
        val base = if (numbered) stem.substring(0, underscore) else stem
        var number = if (numbered) requireNotNull(suffix) + 1 else 1L
        while ("${base}_$number$extension".lowercase(Locale.ROOT) in taken) number = Math.addExact(number, 1L)
        return "${base}_$number$extension"
    }
}
