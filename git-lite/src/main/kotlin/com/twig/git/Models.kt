package com.twig.git

/** A single commit record. */
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

/** Kind of change. */
enum class ChangeKind { ADDED, MODIFIED, DELETED }

data class GitChange(val path: String, val kind: ChangeKind)

/** Worktree status: branch + three groups of changes. */
data class GitStatus(
    val branch: String,
    val staged: List<GitChange>,
    val unstaged: List<GitChange>,
    val untracked: List<String>,
)

/** A local branch: name + commit sha it points to. */
data class GitBranch(val name: String, val sha: String, val current: Boolean)

/**
 * A worktree (`git worktree`).
 *
 * [path] is its **working directory**, taken from the absolute path recorded in the
 * repo — which is the path on "the machine that created this worktree"; when viewed
 * over a network source it must be mapped back to a locally reachable path by the
 * upper layer (see `:app`'s `worktreeChild`).
 * [main] true means the main worktree (the repo's own, not created via `git worktree add`).
 */
data class GitWorktree(
    val name: String,
    val path: String,
    val branch: String,
    val locked: Boolean,
    val current: Boolean,
    val main: Boolean = false,
)
