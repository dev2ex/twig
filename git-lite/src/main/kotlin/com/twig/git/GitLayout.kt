package com.twig.git

/**
 * Parsed result of the `.git` inside a worktree directory.
 *
 * - Plain repo: `.git` is a directory, [gitDir] == [commonDir].
 * - Worktree (`git worktree add`) / submodule: `.git` is a text file `gitdir: <path>`,
 *   [gitDir] points to `<main repo>/.git/worktrees/<name>` (for submodules it's
 *   `.git/modules/<name>`). That worktree directory contains **only** the "one-per-worktree"
 *   files: HEAD, index, ORIG_HEAD, logs/HEAD. objects, refs, packed-refs and
 *   info/exclude all live in [commonDir] (pointed to by the `commondir` file under gitDir).
 *   Reading them separately is the only way to both get this worktree's HEAD/index
 *   and reach the object database.
 */
class GitDirs(val gitDir: String, val commonDir: String) {
    /** Whether this needs a "two-piece" metadata view like [WorktreeGitFs] (submodule commondir defaults to self, so does not). */
    val split: Boolean get() = gitDir != commonDir
}

/**
 * Resolves `.git`. Paths are always '/' separated (so for both local Android and
 * SMB/WebDAV/SFTP), which is why local and remote share this single implementation,
 * just passing in the "look at directory" / "read text" two actions as parameters.
 */
object GitLayout {

    /**
     * @param workDir Worktree root directory path (no trailing '/')
     * @param isDir   Whether the given path is an existing directory
     * @param readText Read the text at the given path; returns null if it isn't a file or doesn't exist
     * @return Returns null if it cannot be resolved (not a repo, or the gitdir points to a directory that can't be found)
     */
    fun resolve(workDir: String, isDir: (String) -> Boolean, readText: (String) -> String?): GitDirs? {
        val base = workDir.trimEnd('/').ifEmpty { "/" }
        val dotGit = join(base, ".git")
        val gitDir = when {
            isDir(dotGit) -> dotGit
            else -> {
                val text = readText(dotGit) ?: return null
                val ref = text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("gitdir:") }
                    ?.removePrefix("gitdir:")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return null
                resolveDir(base, ref, isDir) ?: return null
            }
        }
        val common = readText(join(gitDir, "commondir"))?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolveDir(gitDir, it, isDir) }
            ?: gitDir
        return GitDirs(gitDir, common)
    }

    /**
     * Resolves the path written in `.git` / `commondir` to a real directory.
     *
     * ★ Git writes an **absolute path** in `gitdir:`, and that is the absolute path on
     * "the machine that created this worktree" — what is seen over an SMB/WebDAV mount
     * is just some subtree of that machine, so using the path directly to look it up
     * will always miss. So when the direct lookup fails, fall back another layer: worktrees
     * are often created inside the repo itself (`repo/.claude/worktrees/x`), so walking
     * up from the worktree directory looking for the same tail (`.git/worktrees/x`)
     * matches it up.
     */
    private fun resolveDir(base: String, ref: String, isDir: (String) -> Boolean): String? {
        val direct = join(base, ref)
        if (isDir(direct)) return direct
        val idx = direct.indexOf("/.git/")
        if (idx < 0) return null
        val tail = direct.substring(idx + 1) // ".git/worktrees/<name>"
        var dir = base
        while (true) {
            val cand = join(dir, tail)
            if (isDir(cand)) return cand
            if (dir.isEmpty() || dir == "/") return null
            dir = dir.substringBeforeLast('/', "").ifEmpty { "/" } // the mount point may sit right under root
        }
    }

    /** Joins and normalizes (collapsing "." and ".." segments); ignores [base] when [rel] is an absolute path. */
    fun join(base: String, rel: String): String {
        val abs = rel.startsWith("/")
        val raw = if (abs) rel else "$base/$rel"
        val out = ArrayList<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1)
                        else if (!raw.startsWith("/")) out.add("..")
                else -> out.add(seg)
            }
        }
        val joined = out.joinToString("/")
        return if (raw.startsWith("/")) "/$joined" else joined
    }
}
