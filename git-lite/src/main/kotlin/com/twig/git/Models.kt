package com.twig.git

/** 一条提交记录。 */
data class GitCommit(
    val sha: String,
    val parents: List<String>,
    val tree: String,
    val author: String,
    val email: String,
    val timeSec: Long,
    val message: String,
) {
    val title: String get() = message.lineSequence().firstOrNull().orEmpty()
    val shortSha: String get() = sha.take(7)
}

/** 变更类型。 */
enum class ChangeKind { ADDED, MODIFIED, DELETED }

data class GitChange(val path: String, val kind: ChangeKind)

/** 工作区状态:分支 + 三组变更。 */
data class GitStatus(
    val branch: String,
    val staged: List<GitChange>,
    val unstaged: List<GitChange>,
    val untracked: List<String>,
)

/** 一条本地分支:名字 + 指向的 commit sha。 */
data class GitBranch(val name: String, val sha: String, val current: Boolean)

/**
 * 一条工作区(`git worktree`)。
 *
 * [path] 是它的**工作目录**,取自仓库里记着的绝对路径 —— 那是"建这个 worktree 的那台
 * 机器"上的路径,经网络来源看时得由上层映射回本地可达的路径(见 :app 的 `worktreeChild`)。
 * [main] 为 true 表示主工作区(仓库自己那份,不是 `git worktree add` 出来的)。
 */
data class GitWorktree(
    val name: String,
    val path: String,
    val branch: String,
    val locked: Boolean,
    val current: Boolean,
    val main: Boolean = false,
)
