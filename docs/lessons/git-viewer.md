# git viewer

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Cache layering in the git view (2026-07-17)**: the tree's `children` cache
  (PaneViewModel) → `GitFileSystem`'s status/log/branches/diff caches → at the bottom,
  `ObjectStore.packsLoaded` (the pack list is loaded once) and `XFileGitFs.listCache`
  (remote directory listings cached forever). The first two are covered by `invalidate()`
  and the long-press "Refresh"; **the bottom layer is not affected by any refresh** — after
  `git fetch`/`gc` produces new packs in a local repository, or after a remote SMB/WebDAV
  repository changes, refreshing may still not show the new commits (new local commits are
  loose objects and are unaffected). Fixing it would require a dropCaches hook on
  `GitFs`/`GitData` plus handling concurrent closing of pack readers; the payoff was judged
  low and it is not done. Also: local and SSH repositories (`cheapGitSchemes`) force a
  refresh on every expand, while SMB/WebDAV rely on the manual refresh only.
- **git worktrees / submodules (★ 2026-08-11, `GitLayout`/`WorktreeGitFs`)**: when `.git`
  is a file (`gitdir: <path>`), the directory it points at **is not a complete repository**
  — it only holds the per-worktree things (HEAD, index, ORIG_HEAD, logs/HEAD), while
  `objects`, `refs/heads`, `packed-refs` and `info/exclude` all live in the main
  repository's `.git` that its `commondir` points to. The old code used the gitdir directly
  as metaFs, and the symptom was **no error but everything empty**: empty history, empty
  branches, empty diffs, and status counting every file in the index as newly added (the
  HEAD tree could not be read). Now `GitLayout.resolve` resolves (gitDir, commonDir) and
  routes by path through `WorktreeGitFs` when they differ. The split follows
  gitrepository-layout's "per-worktree file" list — **getting it backwards also produces no
  error**: HEAD landing in the common directory simply becomes the main repository's branch.
  - **The path in `gitdir:` is absolute, and it is the path on the machine where the
    worktree was created.** Over SMB/WebDAV we only see some subtree of that machine, so
    using it directly always misses — when the direct lookup fails, walk up from the
    worktree directory looking for the same tail (`.git/worktrees/<name>`), since putting
    worktrees inside the repository itself is a common layout.
  - Deciding "is this a repository" cannot just check that `.git` exists: `.git` being a
    file whose target cannot be found = not a readable repository, otherwise the long-press
    menu offers Git and tapping it shows a blank screen. `GitRepo.isRepo` and the tree's
    detection (`PaneViewModel.listChildren`, **which must no longer require `isDir`**) use
    the same resolution.
  - **The "Worktrees" node in the virtual tree** (`GitFileSystem`'s `/worktrees`): each
    worktree is **another whole `GitFileSystem`**, registered under its own scheme.
    Children belonging to a different FileSystem is something the tree already supports
    (same as mounting an archive) — the benefit is that changes/history/diff need no
    changes at all. Two things: the nested level passes **null** for `host` (so it does not
    list worktrees again, otherwise A→B→A recurses forever), and like "other branches" it
    **does not list itself**, so with no other worktrees the node does not appear.
  - **Worktree paths must be mapped**: `worktrees/<name>/gitdir` records an absolute path on
    that machine, which simply does not exist on this side. Anchor on the main worktree's
    path here and try progressively longer tails (`…/repo/nested/wt` → `wt`, `nested/wt`, …),
    then confirm by checking that that directory's `.git` really points at
    `worktrees/<name>` — checking only "the directory exists" would match an unrelated
    directory with the same name. ★ Do not try to derive a mount offset from "the current
    worktree's recorded path ↔ the host path": viewed from the **main** repository, the main
    worktree's path is computed on this side (the parent of commondir), so there is no
    "recorded path" to compare with and the derived offset is always empty.
  - **Never use `FileSystem.resolve()` to test existence or type** (★ learned on a real
    SFTP device, 2026-08-11): `SftpFileSystem.resolve` is literally
    `XFile(scheme, path, isDir = true)` — it does not stat, never fails, and calls every
    path a directory. Using it, a worktree's `.git` **file** is treated as a directory and
    not a single byte can be read; the symptom is "Worktrees (3)" with the right count but
    nothing expanding (the count only needs `git worktree list`/metadata, expanding needs to
    read `.git`). `GitVfs.statEntry` now **lists the parent directory and finds by name**;
    every place that needs a type goes through it, never through resolve.
  - **A connection root makes the server's paths untranslatable** (★ 2026-09-03, same
    symptom as above on SSH: the right count, an empty expansion). On SFTP the repository
    is driven by `git` **on the server** (`SshGitData`), so `git worktree list --porcelain`
    prints paths in the *server's* coordinates — `/srv/git/repo/wt`. That was the same
    string as the tree's path until connections gained an optional start directory
    (1.6.0): rooted at `srv/git`, the tree calls that directory `/repo/wt`, so every
    lookup missed and every worktree mapped to null. `serverPath()` was applied at the
    three places where a path leaves for a shell, but paths also come **back** in command
    output; `SftpFileSystem.visiblePath()` is the inverse, and `SshGitData` takes it as a
    mapper. Two details: `current` is still decided on the *server* path (that is what
    `repoPath` is), and a worktree outside the root keeps its server form rather than
    being dropped — useless as an address, but still usable by the suffix matching above.
  - Tests: `GitWorktreeTest` (local, fixtures built with a real `git worktree add`) and
    `RemoteGitWorktreeTest` (remote, with `MountFs` exposing a local directory as a share,
    specifically covering the absolute-path fallback and listing "Worktrees" through
    `GitFileSystem` and descending into one). The remote one was the easiest to skip, and
    it is the only one exercising the fallback and path mapping.
    `SshGitWorktreeTest` covers the remote-exec side: the porcelain output is fixed text,
    what is asserted is the mapping through a rooted connection.

