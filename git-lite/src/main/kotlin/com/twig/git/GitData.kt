package com.twig.git

/**
 * Git data source abstraction: the UI / virtual tree only depends on this interface.
 * The implementation can be a local/remote .git parser ([RepoGitData]), or remote git
 * command execution over SSH (the server computes locally and only returns results,
 * which is an order of magnitude faster on remote repositories).
 */
interface GitData : java.io.Closeable {

    fun branch(): String

    fun status(): GitStatus

    fun log(skip: Int, limit: Int): List<GitCommit>

    /** All local branches. */
    fun branches(): List<GitBranch>

    /** All linked worktrees (`git worktree`, including the main worktree); returns an empty list if unavailable. */
    fun worktrees(): List<GitWorktree>

    /** Same as [log], but starts from a given commit (e.g. some branch tip) instead of HEAD. */
    fun logRef(ref: String, skip: Int, limit: Int): List<GitCommit>

    fun commit(sha: String): GitCommit?

    /** List of changes for a commit relative to its first parent. */
    fun diff(sha: String): List<GitChange>

    /**
     * Content of [path] in some context; returns null if it does not exist.
     * [rev]: "WORK" = working area, "INDEX" = staging area, "HEAD", a commit SHA, or
     * "SHA^" (first parent).
     */
    fun content(rev: String, path: String): ByteArray?
}

/** Implementation based on [GitRepo] (.git parser). */
class RepoGitData(private val repo: GitRepo) : GitData {

    override fun branch(): String = repo.branch()

    override fun status(): GitStatus = repo.status()

    override fun log(skip: Int, limit: Int): List<GitCommit> = repo.log(skip, limit)

    override fun branches(): List<GitBranch> = repo.branches()

    override fun worktrees(): List<GitWorktree> = repo.worktrees()

    override fun logRef(ref: String, skip: Int, limit: Int): List<GitCommit> = repo.logFrom(ref, skip, limit)

    override fun commit(sha: String): GitCommit? = repo.commit(sha)

    override fun diff(sha: String): List<GitChange> =
        repo.commit(sha)?.let { repo.diff(it) } ?: emptyList()

    override fun content(rev: String, path: String): ByteArray? = when {
        rev == "WORK" -> repo.readWorkFile(path)
        rev == "INDEX" -> repo.indexEntries()[path]?.sha?.let { repo.readBlob(it) }
        rev == "HEAD" -> repo.headSha()?.let { treeBlob(it, path) }
        rev.endsWith("^") -> repo.commit(rev.dropLast(1))?.parents?.firstOrNull()
            ?.let { treeBlob(it, path) }
        else -> treeBlob(rev, path)
    }

    private fun treeBlob(commitSha: String, path: String): ByteArray? =
        repo.commit(commitSha)?.let { repo.flattenTree(it.tree)[path] }
            ?.let { repo.readBlob(it) }

    override fun close() = repo.close()
}
