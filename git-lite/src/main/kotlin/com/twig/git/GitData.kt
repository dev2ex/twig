package com.twig.git

/**
 * Git 数据源抽象:UI/虚拟树只依赖此接口。
 * 实现可以是本地/远程 .git 解析([RepoGitData]),也可以是经 SSH
 * 远程执行 git 命令(服务端本地算好只回传结果,远程仓库快一个量级)。
 */
interface GitData : java.io.Closeable {

    fun branch(): String

    fun status(): GitStatus

    fun log(skip: Int, limit: Int): List<GitCommit>

    /** 所有本地分支。 */
    fun branches(): List<GitBranch>

    /** 关联的所有工作区(`git worktree`,含主工作区);拿不到返回空表。 */
    fun worktrees(): List<GitWorktree>

    /** 同 [log],从指定 commit(如某分支 tip)开始,而非 HEAD。 */
    fun logRef(ref: String, skip: Int, limit: Int): List<GitCommit>

    fun commit(sha: String): GitCommit?

    /** 某提交相对首父的变更列表。 */
    fun diff(sha: String): List<GitChange>

    /**
     * [path] 在某语境下的内容;不存在返回 null。
     * [rev]:"WORK"=工作区、"INDEX"=暂存区、"HEAD"、提交 SHA、或 "SHA^"(首父)。
     */
    fun content(rev: String, path: String): ByteArray?
}

/** 基于 [GitRepo](.git 解析)的实现。 */
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
