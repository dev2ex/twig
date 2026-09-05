package com.twig.app

import com.twig.fs.network.SftpConfig
import com.twig.fs.network.SftpFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `git worktree list` runs **on the server**, so the paths it prints are the server's.
 * When the connection is rooted at a sub-directory those are not paths the tree can
 * open, and the git view showed the right count with nothing under it — the count only
 * needs the listing, expanding needs to find each directory here.
 * [SshGitData] therefore maps them back through [SftpFileSystem.visiblePath].
 */
class SshGitWorktreeTest {

    private val porcelain = """
        worktree /srv/git/repo
        HEAD 1111111111111111111111111111111111111111
        branch refs/heads/main

        worktree /srv/git/repo/wt/feature
        HEAD 2222222222222222222222222222222222222222
        branch refs/heads/feature

        worktree /elsewhere/spare
        HEAD 3333333333333333333333333333333333333333
        detached
        locked

    """.trimIndent()

    /** Answers only `worktree list`; anything else is "no such command". */
    private fun exec(cmd: String): ByteArray? =
        if ("worktree list" in cmd) porcelain.toByteArray() else null

    /** A connection rooted at `srv/git`; never connects — only [SftpFileSystem.visiblePath] is used. */
    private val rooted = SftpFileSystem(SftpConfig(host = "h", user = "u", path = "srv/git"))

    @Test
    fun mapsServerPathsIntoTheConnectionRoot() {
        val wt = SshGitData(::exec, "/srv/git/repo") { p -> rooted.visiblePath(p) }.worktrees()

        assertEquals(listOf("/repo", "/repo/wt/feature", "/elsewhere/spare"), wt.map { it.path })
        assertEquals(listOf("repo", "feature", "spare"), wt.map { it.name })
        assertEquals(listOf("main", "feature", "3333333"), wt.map { it.branch })
        // "current" is decided on the server path (that is what repoPath is), not the mapped one
        assertEquals(listOf(true, false, false), wt.map { it.current })
        assertEquals(listOf(true, false, false), wt.map { it.main })
        assertTrue(wt[2].locked)
        assertFalse(wt[1].locked)
    }

    /** Rooted at the server root (the common case): the mapping is the identity. */
    @Test
    fun withoutAConnectionRootPathsAreUnchanged() {
        val wt = SshGitData(::exec, "/srv/git/repo").worktrees()
        assertEquals(listOf("/srv/git/repo", "/srv/git/repo/wt/feature", "/elsewhere/spare"), wt.map { it.path })
        assertEquals(listOf(true, false, false), wt.map { it.current })
    }
}
