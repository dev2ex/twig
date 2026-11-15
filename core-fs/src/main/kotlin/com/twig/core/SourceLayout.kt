package com.twig.core

/** Frozen source identities and safe relative output paths, shared by copying and packing. */
object SourceLayout {
    /** Lexical ancestry is valid only for ordinary absolute paths, never document URIs or item IDs. */
    fun lexicalParent(file: XFile): XFile? =
        if (file.path.startsWith("/") && file.path != "/") XFile(file.scheme, file.parentPath, true) else null

    fun chain(file: XFile, parentOf: (XFile) -> XFile? = ::lexicalParent): List<XFile> {
        val result = ArrayList<XFile>()
        val seen = HashSet<String>()
        var current: XFile? = file
        while (current != null && seen.add(current.toUri())) {
            result.add(current)
            current = parentOf(current)?.takeIf { it.scheme == file.scheme }
        }
        return result.reversed()
    }

    /** Selecting a directory and its descendants must never process those descendants twice. */
    fun normalize(items: List<XFile>, parentOf: (XFile) -> XFile? = ::lexicalParent): List<XFile> {
        val unique = items.distinctBy { it.toUri() }
        val directories = unique.filter { it.isDir }.mapTo(HashSet()) { it.toUri() }
        if (unique.size < 2 || directories.isEmpty()) return unique
        return unique.filter { chain(it, parentOf).dropLast(1).none { parent -> parent.toUri() in directories } }
    }

    /** Keep only the highest selected directories / containing directories, never their ancestors. */
    fun paths(items: List<XFile>, parentOf: (XFile) -> XFile? = ::lexicalParent,
              sourceLabel: (XFile) -> String = { it.scheme }): Map<String, String> {
        val chains = items.associateWith { chain(it, parentOf) }
        val candidates = items.map { file ->
            if (file.isDir) file else chains.getValue(file).dropLast(1).lastOrNull() ?: file
        }.distinctBy { it.toUri() }
        val anchors = normalize(candidates, parentOf)
        val used = HashSet<String>()
        val labels = HashMap<String, String>()
        for (anchor in anchors) {
            // Files without known parents need a source label; never slice opaque identifiers.
            val raw = if (anchor.isDir && isSafeSegment(anchor.name)) anchor.name else sourceLabel(anchor)
            val base = raw.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
                .joinToString("").trim().takeIf(::isSafeSegment) ?: "source"
            var label = base
            var number = 2
            while (!used.add(label.lowercase(java.util.Locale.ROOT))) label = "$base (${number++})"
            labels[anchor.toUri()] = label
        }
        val result = LinkedHashMap<String, String>()
        val anchorsByUri = anchors.associateBy { it.toUri() }
        for (file in items) {
            val chain = chains.getValue(file)
            val anchor = chain.firstNotNullOf { anchorsByUri[it.toUri()] }
            val index = chain.indexOfFirst { it.toUri() == anchor.toUri() }
            val prefix = if (anchor.path == "/" && anchors.size == 1 && anchor.toUri() != file.toUri()) emptyList() else listOf(labels.getValue(anchor.toUri()))
            val suffix = if (anchor.isDir) chain.drop(index + 1).map { it.name } else listOf(file.name)
            val parts = prefix + suffix
            require(parts.isNotEmpty() && parts.all(::isSafeSegment)) { "Invalid source path: ${file.name}" }
            result[file.toUri()] = parts.joinToString("/")
        }
        require(result.values.distinct().size == result.size) { "Selected sources have duplicate output paths" }
        return result
    }

    fun isSafeSegment(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }

    fun isSafePath(path: String): Boolean = path.isNotEmpty() && path.split('/').all(::isSafeSegment)
}
