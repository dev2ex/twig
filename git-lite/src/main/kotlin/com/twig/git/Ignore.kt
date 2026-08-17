package com.twig.git

/**
 * .gitignore 匹配(常用子集):`#` 注释、`!` 取反、尾部 `/` 仅目录、
 * 含 `/` 锚定到所在目录、`*`(不跨 `/`)、`**`(跨目录)、`?`、`[...]` 原样透传。
 * 规则链 = 祖先目录的 .gitignore 依次叠加 + .git/info/exclude;后加载的优先(last-match-wins)。
 *
 * 每个 [Ignore] 绑定一个目录:规则里的路径都相对该目录;子目录通过 [forDir] 叠加。
 */
class Ignore private constructor(
    private val parent: Ignore?,
    /** 该层规则所在目录相对仓库根的前缀(""、"sub/"…)。 */
    private val prefix: String,
    private val rules: List<Rule>,
) {

    private class Rule(val regex: Regex, val negate: Boolean, val dirOnly: Boolean)

    /** [rel] 为相对仓库根的路径(不带尾 '/')。 */
    fun matches(rel: String, isDir: Boolean): Boolean {
        var decided: Boolean? = null
        // 自身这层规则:相对本层目录
        if (rel.startsWith(prefix)) {
            val local = rel.substring(prefix.length)
            for (r in rules) { // last-match-wins:顺序扫,后者覆盖
                if (r.dirOnly && !isDir) continue
                if (r.regex.matches(local)) decided = !r.negate
            }
        }
        if (decided != null) return decided
        return parent?.matches(rel, isDir) ?: false
    }

    companion object {
        /**
         * 为仓库内目录 [relDir](相对工作区根,"" 为根)构造匹配链:
         * 父链 [parent] + 该目录的 .gitignore;根层(parent=null)额外加载 .git/info/exclude。
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

        /** gitignore glob → 正则;非锚定模式可匹配任意层级下的名字。 */
        private fun toRegex(pat: String, anchored: Boolean): Regex? = runCatching {
            val sb = StringBuilder()
            if (!anchored) sb.append("(?:.*/)?")
            var i = 0
            while (i < pat.length) {
                when (val c = pat[i]) {
                    '*' -> {
                        if (i + 1 < pat.length && pat[i + 1] == '*') {
                            // "**" 跨目录;"**/" 或 "/**" 的斜杠一并吸收
                            sb.append(".*")
                            i++
                            if (i + 1 < pat.length && pat[i + 1] == '/') i++
                        } else sb.append("[^/]*")
                    }
                    '?' -> sb.append("[^/]")
                    '[' -> { // 字符组原样透传到 ]
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
            // 目录匹配后其内部全部命中(gitignore 语义:忽略目录即忽略整棵)
            sb.append("(?:/.*)?")
            Regex(sb.toString())
        }.getOrNull()
    }
}
