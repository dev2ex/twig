package com.twig.git

/**
 * .gitignore matching (a useful subset): `#` comments, `!` negation, trailing `/` for
 * directory-only, containing `/` anchors to the current directory, `*` (doesn't cross `/`),
 * `**` (crosses directories), `?`, `[...]` passed through verbatim.
 * The rule chain = ancestor directories' .gitignore stacked + .git/info/exclude; later
 * ones win (last-match-wins).
 *
 * Each [Ignore] is bound to one directory: paths in rules are relative to that
 * directory; subdirectories stack via [forDir].
 */
class Ignore private constructor(
    private val parent: Ignore?,
    /** Prefix of this layer's directory relative to repo root ("" or "sub/" etc.). */
    private val prefix: String,
    private val rules: List<Rule>,
) {

    private class Rule(val regex: Regex, val negate: Boolean, val dirOnly: Boolean)

    /** [rel] is the path relative to the repo root (no trailing '/'). */
    fun matches(rel: String, isDir: Boolean): Boolean {
        var decided: Boolean? = null
        // this layer's rules: relative to this layer's directory
        if (rel.startsWith(prefix)) {
            val local = rel.substring(prefix.length)
            for (r in rules) { // last-match-wins: scan in order, latter ones override
                if (r.dirOnly && !isDir) continue
                if (r.regex.matches(local)) decided = !r.negate
            }
        }
        if (decided != null) return decided
        return parent?.matches(rel, isDir) ?: false
    }

    companion object {
        /**
         * Builds a matching chain for the repo-internal directory [relDir] (relative to
         * the worktree root, "" for the root): the parent chain [parent] + that
         * directory's .gitignore; the root layer (parent=null) additionally loads
         * .git/info/exclude.
         */
        fun forDir(metaFs: GitFs, workFs: GitFs, relDir: String, parent: Ignore?): Ignore {
            val rules = ArrayList<Rule>()
            if (parent == null) {
                metaFs.readBytes("info/exclude")?.let { parseInto(it.toString(Charsets.UTF_8), rules) }
            }
            val giPath = if (relDir.isEmpty()) ".gitignore" else "$relDir/.gitignore"
            workFs.readBytes(giPath)?.let { parseInto(it.toString(Charsets.UTF_8), rules) }
            val prefix = if (relDir.isEmpty()) "" else "${relDir.trim('/')}/"
            return Ignore(parent, prefix, rules)
        }

        private fun parseInto(text: String, out: MutableList<Rule>) {
            for (raw in text.lineSequence()) {
                var line = raw
                if (line.isBlank() || line.startsWith("#")) continue
                var negate = false
                if (line.startsWith("!")) { negate = true; line = line.substring(1) }
                var dirOnly = false
                if (line.endsWith("/")) { dirOnly = true; line = line.dropLast(1) }
                if (line.isBlank()) continue
                val anchored = line.contains('/')
                if (line.startsWith("/")) line = line.substring(1)
                val rx = toRegex(line, anchored) ?: continue
                out.add(Rule(rx, negate, dirOnly))
            }
        }

        /** gitignore glob → regex; non-anchored patterns can match a name at any depth. */
        private fun toRegex(pat: String, anchored: Boolean): Regex? = runCatching {
            val sb = StringBuilder()
            if (!anchored) sb.append("(?:.*/)?")
            var i = 0
            while (i < pat.length) {
                when (val c = pat[i]) {
                    '*' -> {
                        if (i + 1 < pat.length && pat[i + 1] == '*') {
                            // "**" crosses directories; absorb the slash for "**/" or "/**"
                            sb.append(".*")
                            i++
                            if (i + 1 < pat.length && pat[i + 1] == '/') i++
                        } else sb.append("[^/]*")
                    }
                    '?' -> sb.append("[^/]")
                    '[' -> { // character class passed through verbatim until ]
                        val end = pat.indexOf(']', i + 1)
                        if (end < 0) { sb.append("\\["); } else {
                            sb.append(pat, i, end + 1); i = end
                        }
                    }
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            // matching a directory means everything inside also matches (gitignore semantics: ignore a directory = ignore its whole tree)
            sb.append("(?:/.*)?")
            Regex(sb.toString())
        }.getOrNull()
    }
}
