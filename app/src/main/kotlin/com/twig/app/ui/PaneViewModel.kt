package com.twig.app.ui

import android.app.Application
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twig.app.AppsFileSystem
import com.twig.app.CompareSession
import com.twig.app.CompareStore
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Favorite
import com.twig.app.FavoritesStore
import com.twig.app.Format
import com.twig.app.GitFileSystem
import com.twig.app.HistoryEntry
import com.twig.app.HistoryStore
import com.twig.app.OpenFiles
import com.twig.app.XFileGitFs
import com.twig.git.GitRepo
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.SafFileSystem
import com.twig.app.SavedConnection
import com.twig.app.SortSpec
import com.twig.app.StorageVolumes
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.ArchivePasswordException
import com.twig.fs.archive.Archives
import com.twig.fs.network.DavConfig
import com.twig.fs.network.FtpConfig
import com.twig.fs.network.FtpFileSystem
import com.twig.fs.network.SftpConfig
import com.twig.fs.network.SftpFileSystem
import com.twig.fs.network.WebDavFileSystem
import com.twig.fs.restic.ResticFileSystem
import com.twig.fs.restic.ResticRepo
import com.twig.fs.zstd.NativeZstd
import com.twig.fs.smb.SmbConfig
import com.twig.fs.smb.SmbFileSystem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live state for one recursive directory scan (properties card directory fileKey -> this object). */
private class DirScanState(
    var stat: DirStat = DirStat(),
    var scanning: Boolean = true,
    var job: kotlinx.coroutines.Job? = null,
)

/** Live state for one recursive wildcard search (owner directory fileKey -> this object). */
private class SearchState(
    val pattern: String,
    var scanning: Boolean = true,
    val results: MutableList<XFile> = mutableListOf(),
    var job: kotlinx.coroutines.Job? = null,
)

/**
 * The X-plore-style "whole tree" model: top-level nodes are internal storage / root
 * / LAN / FTP / documents tree (SAF). SMB/FTP servers are tree nodes under their
 * LAN/FTP group (expand to connect); archives are expandable file nodes.
 * We never "enter" directories — everything is expanded/collapsed in place in the
 * same tree.
 *
 * [currentDir] = the most recently clicked directory node, the target for new
 * folder / paste (copy/move); its row is bordered to highlight it.
 */
class PaneViewModel(app: Application) : AndroidViewModel(app) {

    // ---- Node types ----

    sealed interface Node {
        val key: String
        val depth: Int
    }

    /** Real file/directory (also including the storage top-level nodes, SAF tree roots, archives). */
    data class FileNode(
        val file: XFile,
        override val depth: Int,
        val expandable: Boolean,
        val expanded: Boolean,
        /** Top-level node display name (e.g. "Internal storage"); null for regular nodes, which use file.name. */
        val label: String? = null,
        /** Capacity text for storage nodes. */
        val capacity: String? = null,
        val loading: Boolean = false,
        /**
         * The same file can appear in two places on the tree — an archive a different
         * App opened with Twig is pinned at the top of the tree ([externalMount]),
         * while its **original row in storage** is also still there. The row key is
         * the only way DiffUtil identifies rows, and two rows with the same key get
         * confused (symptom: one of them expands empty). The external mount subtree
         * carries this prefix as a whole, so it is fully separate from the original
         * location; regular use leaves it as an empty string.
         */
        val keyPrefix: String = "",
    ) : Node {
        override val key: String get() = keyPrefix + fileKey(file)
    }

    /** Virtual group: LAN / FTP / SAF. */
    data class GroupNode(val id: String, val label: String, val expanded: Boolean) : Node {
        override val depth: Int get() = 0
        override val key: String get() = "g:$id"
    }

    /** Saved server (expanding it connects); [info] holds any supplemental info gained after connecting (e.g. SMB version). */
    data class ServerNode(
        val conn: SavedConnection,
        val expanded: Boolean,
        val connecting: Boolean,
        val info: String? = null,
    ) : Node {
        override val depth: Int get() = 1
        override val key: String get() = "s:${conn.label()}"
    }

    /** Per-group action item, e.g. "Add server…". */
    data class ActionNode(val id: String, val label: String) : Node {
        override val depth: Int get() = 1
        override val key: String get() = "a:$id"
    }

    /**
     * Favorite item (expand to connect / unlock on demand and jump straight to that directory).
     * [conn] is the *current* saved config for the connection this favorite lives on
     * (only the conn/restic kinds have one) — display pulls the up-to-date alias /
     * protocol type from it, not from the [Favorite.label] frozen at creation time
     * (renaming a connection updates the favorite accordingly).
     */
    data class FavoriteNode(
        val fav: Favorite,
        val conn: SavedConnection?,
        override val depth: Int,
        val expanded: Boolean,
        val connecting: Boolean,
    ) : Node {
        override val key: String get() = "fav:${fav.id}"
    }

    /**
     * Compare favorite (reference to a directory on each side). Unlike [FavoriteNode],
     * this is not an expandable real directory — tapping it jumps straight to
     * [CompareActivity] for a fresh scan, and the long-press menu is a different one
     * (see `PaneFragment.compareFavMenu`).
     */
    data class CompareNode(val session: CompareSession, override val depth: Int) : Node {
        override val key: String get() = "cmp:${session.id}"
    }

    /**
     * Properties card (opened from the long-press menu's "Properties"): pinned
     * directly below the corresponding row, with its left edge aligned to the file
     * icon; one tab per information group, plus a "Hashes" tab for files (computed
     * automatically for local files, requires a tap for network ones).
     * [details] being null means it's still loading. The ✕ button or selecting the
     * menu item again closes it; folding the owning directory clears it.
     * Directories also get [dirStat]: recursive file/directory count + total size,
     * refreshed as the scan progresses (a spinner shows during [scanning]).
     */
    data class InfoNode(
        val file: XFile,
        override val depth: Int,
        val details: com.twig.app.FileInfo.Details?,
        val tab: Int = 0,
        val hashRows: List<Pair<String, String>>? = null,
        val hashing: Boolean = false,
        val dirStat: DirStat? = null,
        val scanning: Boolean = false,
    ) : Node {
        override val key: String get() = "info:${fileKey(file)}"
    }

    /**
     * Virtual directory of recursive wildcard search results, pinned directly under
     * the searched directory's row (independent of whether that directory is itself
     * expanded). Name shows the live match count as "Search results (N)", and the
     * rows fill as scanning progresses — children are plain [FileNode] entries
     * (pinned one level down, reuse the regular copy/delete/properties/expand path).
     * This is not a regular directory: tapping this row collapses and removes the
     * virtual directory from the tree (discarding any results already gathered),
     * and re-runs the search when needed. The long-press menu shows statistics
     * ([matchedFiles] / [matchedDirs] counted separately).
     */
    data class SearchNode(
        val root: XFile,
        override val depth: Int,
        val pattern: String,
        val scanning: Boolean,
        val matchedFiles: Int,
        val matchedDirs: Int,
    ) : Node {
        override val key: String get() = "search:${fileKey(root)}"
    }

    /** A restic backup repository detected inside a directory (needs a password to unlock; once unlocked, children are snapshots). */
    data class ResticNode(
        val repoDir: XFile,
        override val depth: Int,
        val expanded: Boolean,
        val unlocked: Boolean,
        /** Unlocking is in progress (scrypt + reading snapshot list; can take tens of seconds on a remote repo) — row shows a spinner. */
        val connecting: Boolean = false,
    ) : Node {
        override val key: String get() = "restic:${fileKey(repoDir)}"
    }

    data class State(
        val rows: List<Node> = emptyList(),
        val currentDir: XFile? = null,
        /** Most recently selected (clicked expand/collapse) node key, used for the highlight box around the tree and restoring position. */
        val currentKey: String? = null,
        val error: String? = null,
        /**
         * Expanding this encrypted archive needs a password (UI uses this to show a
         * password dialog); [error] being non-null at the same time means the
         * previous password was wrong. Like [error], this is "one-shot" — the next
         * [rebuild] clears it.
         */
        val passwordFor: XFile? = null,
        /**
         * In the middle of restoring the last position (progressively expanding, the
         * row count keeps changing). The UI uses it to tell "this version of the rows
         * is not the final one", repeatedly anchoring the scroll to the target row
         * during restore, and only stopping when it becomes false.
         */
        val restoring: Boolean = false,
        /** Preferred target row when restoring position (the file last opened); when it doesn't resolve, the UI falls back to [currentKey]. */
        val scrollKey: String? = null,
    )

    // ---- State ----
    //
    // ★ **The threading rule is one thing only: every collection below is written only on the main thread.**
    //
    // The `withContext(io)` blocks are only responsible for "fetching the data";
    // they produce plain-data [Listing] / [Restored] / [RevealPlan] and the main
    // thread's applyListing / applyRestored / applyReveal puts them into the tables.
    // Write new async paths in the same shape — never modify any of the tables below
    // inside an IO block.
    //
    // Why this rule: restoring position, connecting to servers, and detecting
    // git/restic repos used to modify these tables directly on IO, while
    // refresh()/refreshLocal()/toggleFile() can run as **independent concurrent
    // coroutines**. Two IO threads modifying the same table at the same time would,
    // in the best case, drop updates; in the worst case, read a half-modified state
    // (children has a new list, keyFile is still behind). After moving the writes to
    // a single thread, every rebuild() sees a consistent snapshot, and the
    // concurrent containers are just belt-and-braces rather than the only line of
    // defense.
    //
    // We still keep the concurrent containers below because **reads** are still
    // cross-thread (the IO side reads children/gitInfo to decide "do I need to
    // re-list", see planReveal) — keeping them is less hassle than going back to
    // bare HashMap.
    //
    // Exceptions: serverScheme / schemeToConn / serverInfo / resticScheme /
    // schemeToRestic are written by schemeForConn / schemeForRestic on IO. They
    // are **caches of a deterministic function** (the scheme is computed by
    // Connections.schemeOf(conn)), concurrent writes write the same value and are
    // idempotent; and connSchemeBlocking is a public entry point PaneFragment calls
    // directly on IO, so moving it back to the main thread would mean changing the
    // public contract, which isn't worth it.

    // ---- (1) Cross-thread ----

    /** Order matters ([expandedDescriptors] stores the restore order by it), so it's a synchronized LinkedHashSet.
     *  Single-element add/remove/contains are directly safe; **iteration / removeAll{} must synchronize on expanded yourself**. */
    private val expanded: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())
    private val children = ConcurrentHashMap<String, List<XFile>>()
    /** Expandable-node key -> the XFile needed to re-list it (server's root for servers). */
    private val keyFile = ConcurrentHashMap<String, XFile>()
    /** Server key -> registered scheme (marker that we're connected). */
    private val serverScheme = ConcurrentHashMap<String, String>()
    /** Supplemental info gathered after connecting (label -> e.g. "SMB3"). */
    private val serverInfo = ConcurrentHashMap<String, String>()
    /** Directory detected as a git repo (dirKey -> registered virtual fs scheme + display name). */
    private val gitInfo = ConcurrentHashMap<String, Pair<String, String>>()
    /** Reverse lookup (let "Last position" recover the host directory from a git scheme). */
    private val schemeToGitHost = ConcurrentHashMap<String, XFile>()
    /**
     * "Cheap" git schemes (local direct read / remote SSH runs the git command):
     * status()/log() themselves don't cache, they read the live state on every call;
     * the root nodes for these always force a fresh pull on every expand and don't
     * use the [children] cache. SMB/WebDAV etc. without a git command fall back to
     * [XFileGitFs] parsing the .git objects, which makes every file fetch costly,
     * so they don't qualify — keep the cache and rely on the manual "Refresh" menu
     * item for updates.
     */
    private val cheapGitSchemes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Directory keys identified as a restic repository. */
    private val resticRepos: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** restic node key -> registered restic scheme (unlocked). */
    private val resticScheme = ConcurrentHashMap<String, String>()
    /** Reverse lookup (let "Favorites" recover the source from a dynamic scheme). */
    private val schemeToConn = ConcurrentHashMap<String, SavedConnection>()
    private val schemeToRestic = ConcurrentHashMap<String, XFile>()

    // ---- (2) Main-thread-only ----

    /** Archive handed in by an external App, pinned at the top of the tree temporarily (see [mountExternal]); only one at a time. */
    private var externalMount: XFile? = null
    private val connecting = HashSet<String>()
    /** Node key currently asynchronously loading children (used to show the loading spinner). */
    private val loadingKeys = HashSet<String>()

    /**
     * Key of the row "claimed" by the current tap (see [claimExpand] / [stillExpanding]).
     *
     * Expansion is asynchronous and the final step is the row's own [accordionExpand] —
     * and the accordion only keeps one chain open. So when you **tap open a slow
     * directory, get tired of waiting and tap open another**, the first one's
     * completion collapses the second one instead (the user sees "the directory I
     * just opened closed itself, and what popped up is the one from before"). The
     * rule is **the later tap wins**: each tap calls [claimExpand] to claim, and
     * when an in-flight completion lands and sees it's no longer the one, it only
     * caches the children (instant expand next time), without expanding, without
     * changing the current directory, and without surfacing any error.
     *
     * We don't cancel that coroutine: `listChildren` is blocking IO and cancel
     * can't interrupt it; meanwhile `runCatching` catches `CancellationException`
     * too and would surface it to the user as a "listing failed" error — the user
     * gave up on it themselves, but they would get an error toast anyway. (Since
     * `mine` ends up false for an abandoned key, that failure branch never actually
     * calls [emitFailure] regardless of what — or whether — it throws.)
     *
     * One case *can* stop early: materializing a nested archive to the local cache
     * (see [localArchive]) is our own streaming copy loop, not one opaque blocking
     * call, so it polls [abandoned] between chunks and aborts instead of downloading
     * an abandoned huge archive to completion in the background after the row that
     * asked for it has already collapsed or been superseded.
     */
    private var pendingExpand: String? = null

    /**
     * Rows whose request has been abandoned but is **still in flight** (see [claimExpand]).
     *
     * Since the request can't be cancelled, it still sits in [loadingKeys] /
     * [connecting] — those tables' semantics are now "is anything in flight", and
     * **the spinner and "Connecting..." label are decided via this table as
     * well**: the user no longer cares about them, and keeping the spinner turning
     * / the "Connecting..." label visible would make them think the UI is stuck
     * (most visible on slow-to-connect sources like SFTP).
     *
     * Conversely, the in-flight status is still useful: if the user taps that row
     * back before the completion lands, just re-claim and put the spinner back —
     * **don't re-issue the request** (see the `-> Unit` branch inside `toggleFile`/
     * `toggleServer`).
     *
     * ★ Backed by a concurrent set (not a plain `HashSet`) even though it's written
     * from the main thread: [localArchive]'s copy loop reads it from the IO thread to
     * bail out of materializing an abandoned nested archive early (see there) — the
     * one case where the in-flight work *can* cooperatively stop instead of running
     * to completion in the background.
     */
    private val abandoned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Apk default: a tap installs it directly, not expand it as an archive; once the user picks "Open as archive" we record it here and only from that node onward is browsing the archive contents allowed. */
    private val forcedArchive = HashSet<String>()
    /** File key (fileKey) for which the properties card is open; the card content is cached and dropped when closed/folded. */
    private val infoOpen = LinkedHashSet<String>()
    private val infoCache = HashMap<String, com.twig.app.FileInfo.Details>()
    /** Currently selected tab on the card, already-computed hashes, keys currently computing. */
    private val infoTab = HashMap<String, Int>()
    private val hashCache = HashMap<String, List<Pair<String, String>>>()
    private val hashing = HashSet<String>()
    /** Recursive stats for a directory properties card: directory fileKey -> live state (the card closing/folding cancels — see [rebuild]). */
    private val dirScan = HashMap<String, DirScanState>()
    /** Recursive wildcard search: searched directory fileKey -> live state. See [SearchNode]. */
    private val searchState = HashMap<String, SearchState>()
    /** Directory keys that have already had their auxiliary rows (properties card / search results) attached in this rebuild, to avoid duplicates when one directory appears in two places. */
    private val attachedKeys = HashSet<String>()

    var currentDir: XFile? = null
    /** Most recently selected node key (any type: directory / server / group / favorite / restic). */
    private var currentKey: String? = null
        private set

    // ---- Intermediate state for restoring the last position ----
    private var restoring = false
    /** Preferred target row to scroll to ("Go to containing folder" wants to land on that file); null means locate by current directory. */
    private var scrollKey: String? = null
    /** Current directory descriptor not yet resolved to an XFile: network locations need the corresponding server to connect first (serverScheme built) before they can be resolved. */
    private var pendingCurrentDesc: String? = null

    /**
     * Dispatcher for blocking IO. **Only exists for testability** — in production this
     * is always [Dispatchers.IO].
     *
     * Tests swap it for a controlled dispatcher so `advanceUntilIdle()` can reach
     * those `withContext` blocks; otherwise they'd run on a real thread pool that
     * virtual time cannot govern, the test would keep asserting before the IO is
     * done, and the test would be flaky.
     *
     * Made it a writable field rather than a constructor parameter: `by viewModels()`
     * goes through `AndroidViewModelFactory`, which reflectively looks for the
     * `(Application)` constructor; adding a parameter means relying on
     * `@JvmOverloads` to catch it — if that doesn't catch it, it explodes at
     * **runtime**, and a test that just `new`s the VM would never catch it.
     * This hatchback is only for tests; don't touch it from production code.
     */
    @androidx.annotation.VisibleForTesting
    internal var io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    private var sort = SortSpec.load(getApplication())

    /** Whether to show hidden files; refreshed at the top of every [rebuild]. */
    private var showHidden = Prefs.showHidden(getApplication())

    /** Children are written already in the current sort order; virtual nodes (servers, favorites, ...) are unaffected. */
    private fun putChildren(key: String, list: List<XFile>) {
        children[key] = sortList(key, list)
    }

    /** Change the sort: re-sort all cached children and rebuild. */
    fun setSort(spec: SortSpec) {
        sort = spec
        resortAll()
    }

    /** After settings (thumbnails / grid mode) change, re-sort cached children under the new rules. */
    fun resortAll() {
        // children may be modified concurrently from IO threads; pull, null-check, then write back — no !!
        for (k in children.keys.toList()) children[k]?.let { children[k] = sortList(k, it) }
        rebuild()
    }

    /**
     * Files that can produce a thumbnail are grouped right after directories, with
     * the tree list and the grid treated alike — when thumbnails are on, image and
     * text rows mixed together are hard to scan; grouping them feels more like a
     * gallery. In grid mode this grouping still happens even with thumbnails off
     * (cells show large icons then), otherwise cells and full-width rows mixed
     * together would be even messier. When both are off (tree list + no thumbnails)
     * we keep the original sort, matching the long-standing behavior.
     */
    private fun sortList(key: String, list: List<XFile>): List<XFile> {
        if (keepsServerOrder(key)) return list
        val app = getApplication<Application>()
        val cmp = sort.comparator()
        val grid = Prefs.thumbsGrid(app)
        // Both off (tree list + no thumbnails): no grouping, matching the long-standing behavior.
        if (!Prefs.thumbs(app) && grid == 0) return SortRules.sorted(list, cmp, null)
        // Grid "All files" mode: archives, like directories, render as full expandable rows;
        // wedged between cells they'd split the grid, so they cluster right after directories;
        // in other modes an archive is a normal row and doesn't deserve its own group.
        val archivesFirst = grid == 2
        return SortRules.sorted(list, cmp) { f ->
            SortRules.groupOf(f.isDir, expandableArchive(f), Thumbs.canThumb(f), archivesFirst)
        }
    }

    /**
     * This directory's order is **decided by the server**, not re-sorted by the user's
     * chosen sort.
     *
     * A media server's "Continue Watching" comes back sorted by most-recently played,
     * its four "Latest" rows by most-recently added, and **a playlist's order is the
     * order the user arranged it in** — that order is the entire reason those
     * directories exist in the first place. Sorting them again by name/size would
     * push a half-watched item to the middle of the list, looking exactly like
     * "Continue Watching isn't updating"; playlists would just be shuffled.
     */
    private fun keepsServerOrder(key: String): Boolean {
        val f = keyFile[key] ?: return false
        return runCatching {
            com.twig.fs.network.JellyfinFileSystem.keepsServerOrder(f.path) &&
                Connections.ofScheme(f.scheme)?.isMediaServer() == true
        }.getOrDefault(false)
    }

    /**
     * Hidden files (names starting with '.') are filtered by preference. Filtering
     * happens only at **render** time — the [children] cache always holds the full
     * set — so flipping the switch back on doesn't require re-listing, which saves
     * a network round-trip on remote sources.
     */
    private fun visible(list: List<XFile>?): List<XFile> {
        val l = list ?: return emptyList()
        return if (showHidden) l else l.filter { !it.name.startsWith(".") }
    }

    /**
     * Whether an archive renders as an expandable full row (same judgment as in [addFile]).
     * Apks and apk bundles are not expanded by default (a tap goes to install), unless the user
     * manually picked "Open as archive".
     */
    private fun expandableArchive(f: XFile, key: String = fileKey(f)): Boolean =
        !f.isDir && Archives.isArchive(f) &&
            (!OpenFiles.isInstallable(f) || forcedArchive.contains(key))

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * First assembly. When [descriptors] is non-empty, restore the previous expansions
     * (local directory + network location + groups/servers, with the network ones
     * reconnecting via the saved connections); otherwise default-expand internal
     * storage. [currentDesc] restores the current directory.
     */
    fun bootstrap(descriptors: List<String> = emptyList(), currentDesc: String? = null) {
        if (_state.value.rows.isNotEmpty()) return
        if (descriptors.isEmpty()) {
            rebuild()
            (state.value.rows.firstOrNull { it is FileNode && (it as FileNode).label != null } as? FileNode)
                ?.let { toggleFile(it) }
            return
        }
        val parsed = descriptors.sortedBy { it }.map { it.split('\t') }
        // Expand groups first (to build the tree structure); pure in-memory, no IO needed.
        parsed.filter { it.getOrNull(0) == "group" }.forEach { expanded.add("g:${it[1]}") }
        // Mark servers/local directories as "loading" before rendering the first
        // skeleton: every root node is visible immediately, and locations that
        // aren't connected/listed yet show a spinner instead of leaving the whole
        // tree blank until restore completes.
        // Network directories ("conn") sit under the server row, and that row's
        // spinner covers them, so they don't need their own pre-mark.
        for (p in parsed) when (p.getOrNull(0)) {
            "server" -> if (connFor(p[1]) != null) connecting.add("s:${p[1]}")
            "file" -> loadingKeys.add(fileKey(XFile("file", p[1], isDir = true)))
            "apps" -> loadingKeys.add(fileKey(XFile(AppsFileSystem.SCHEME, p[1], isDir = true)))
            "fav" -> if (FavoritesStore.contains(getApplication(), p[1])) connecting.add("fav:${p[1]}")
        }
        restoring = true
        pendingCurrentDesc = currentDesc
        resolvePending() // Local positions resolve immediately; network ones retry inside the loop once the server is up.
        rebuild()
        viewModelScope.launch {
            for (p in parsed) {
                if (p.getOrNull(0) == "group") continue
                // IO only retrieves data; the table writes happen on the main thread
                // below — see the class header comment for the threading rule.
                val fetched = withContext(io) {
                    runCatching {
                        when (p[0]) {
                            "server" -> fetchServer(p[1])
                            "file" -> fetchDir(XFile("file", p[1], isDir = true))
                            "apps" -> fetchDir(XFile(AppsFileSystem.SCHEME, p[1], isDir = true, canWrite = false))
                            "conn" -> connFor(p[1])?.let { fetchDir(XFile(schemeForConn(it), p[2], isDir = true)) }
                            "fav" -> fetchFavoriteById(p[1])
                            else -> null
                        }
                    }.getOrNull()
                }
                fetched?.let { applyRestored(it) }
                when (p[0]) {
                    "server" -> connecting.remove("s:${p[1]}")
                    "file" -> loadingKeys.remove(fileKey(XFile("file", p[1], isDir = true)))
                    "apps" -> loadingKeys.remove(fileKey(XFile(AppsFileSystem.SCHEME, p[1], isDir = true)))
                    "fav" -> connecting.remove("fav:${p[1]}")
                    else -> Unit
                }
                resolvePending()
                rebuild()
            }
            upgradeContainerKey()
            restoring = false
            rebuild() // Only the last version of rows is final — UI uses this to stop repeated scroll anchoring.
        }
    }

    /**
     * Reconcile the highlight-box key at the end of restore.
     *
     * Favorites/servers **don't have a row of their own for their root directory** —
     * children sit directly under the `fav:`/`s:` row (see [addFavorite]/[addServer]),
     * so when last parked at a favorite's root, the highlight box frames the `fav:`
     * row. But the saved state can only store "which directory" (see [currentDescriptor]
     * and [currentDir]); the "reached via favorite" fact is dropped there. During
     * restore, [resolvePending] sets the key by `fileKey`, and that points to **a
     * row that doesn't exist**, showing up as "no green box after reopen, at the
     * position reached from the favorite".
     *
     * ★ This step must come **after** the restore loop: [containerKeyFor] looks up
     * [keyFile], and `fav:`/`s:` entries only land there once [restoreFavorite]/
     * [restoreServer] have run. Putting it into [resolvePending] is not enough —
     * for local paths that call already succeeds at the very start of the loop and
     * clears pendingCurrentDesc, at which point keyFile is still empty.
     *
     * [up] already dealt with the same class of problem using [containerKeyFor] (see
     * its comment); this was the missing twin.
     */
    private fun upgradeContainerKey() {
        val cur = currentDir ?: return
        // The user tapped a different row during restore (currentKey is no longer the
        // one we derived from currentDir) — don't overwrite their choice.
        if (currentKey != fileKey(cur)) return
        containerKeyFor(cur)?.let { currentKey = it }
    }

    /**
     * Try to resolve the not-yet-resolved "current directory" descriptor into an XFile.
     * Network descriptors depend on [serverScheme] (the server has to be connected
     * first), so it's called repeatedly during restore rather than once at the start
     * of bootstrap.
     */
    private fun resolvePending() {
        val d = pendingCurrentDesc ?: return
        descToXFile(d)?.let {
            currentDir = it
            currentKey = fileKey(it)
            pendingCurrentDesc = null
        }
    }

    /**
     * One payload fetched by the IO phase, waiting to be merged into the tree. See
     * [applyRestored].
     *
     * The reason this type exists is to separate "fetch" from "write": the
     * restore/locate chain used to mutate [children] / [keyFile] / [expanded]
     * directly inside `withContext(io)`; now IO only produces Restored, and the
     * actual table writes happen back on the main thread.
     */
    private class Restored(
        val key: String,
        val file: XFile,
        val listing: Listing,
        /** Snapshot list also fetched for a restic favorite (mounted on the `restic:` node). */
        val extra: Pair<String, List<XFile>>? = null,
    )

    /** Merge [Restored] into the tree. **Main thread only.** */
    /**
     * Merge a "connected and the root is ready" result into the table.
     *
     * ★ [expand] must be turn-offable: a connection that's been abandoned
     * ([landExpand] answers false) still needs the table write (the connection is
     * already built and its children are already fetched, so the next tap expands
     * instantly), but it **must not enter [expanded]** — that `expanded.add` skips
     * [accordionExpand], so that server would **open a second path of its own**
     * alongside the one the user already opened, and the tree would show two
     * unrelated paths open at the same time.
     */
    private fun applyRestored(r: Restored, expand: Boolean = true) {
        keyFile[r.key] = r.file
        putListing(r.key, r.listing)
        r.extra?.let { (k, v) -> children[k] = sortList(k, v) }
        if (expand) expanded.add(r.key)
    }

    private fun fetchServer(label: String): Restored? {
        val conn = connFor(label) ?: return null
        val root = XFile(schemeForConn(conn), "/", isDir = true)
        return Restored("s:$label", root, listChildren(root)) // via listChildren so git/restic get detected
    }

    private fun fetchDir(dir: XFile): Restored =
        Restored(fileKey(dir), dir, listChildren(dir)) // via listChildren so git/restic get detected

    /** Reconnect / unlock the directory a favorite points at (a restic favorite without a stored password fails — it stays folded, same as a manual expand). */
    private fun fetchFavoriteById(id: String): Restored? {
        val fav = FavoritesStore.all(getApplication()).firstOrNull { it.id == id } ?: return null
        return resolveFavorite(fav, password = null)
    }

    private fun connFor(label: String): SavedConnection? =
        ConnectionStore.all(getApplication()).firstOrNull { it.label() == label }

    private fun descToXFile(d: String): XFile? = TreeKeys.dirOfDescriptor(d, ::sessionSchemeOf)

    /**
     * Connection label -> scheme registered in this session. Returns null when the
     * connection was deleted, or when that server isn't connected yet this session
     * — descriptor resolution uses this to decide "this one cannot be restored".
     */
    private fun sessionSchemeOf(label: String): String? =
        if (connFor(label) == null) null else serverScheme["s:$label"]

    /** Descriptors of saved-last positions (local / apps / network / group / server / favorite; restic repository nodes themselves, and SAF, are skipped). */
    fun expandedDescriptors(): List<String> = expandedSnapshot().mapNotNull { key ->
        TreeKeys.descriptorOf(key) { scheme -> schemeToConn[scheme]?.label() }
    }

    /**
     * Current directory descriptor; when not yet resolved (user exited before the network server connected), keep it as-is. Persistable sources are only local, the apps tree and connected servers; everything else (zip/restic/saf/git) returns null.
     */
    fun currentDescriptor(): String? {
        val cur = currentDir ?: return pendingCurrentDesc
        return when {
            cur.scheme == "file" -> "file\t${cur.path}"
            cur.scheme == AppsFileSystem.SCHEME -> "apps\t${cur.path}"
            schemeToConn.containsKey(cur.scheme) -> "conn\t${schemeToConn[cur.scheme]!!.label()}\t${cur.path}"
            else -> pendingCurrentDesc
        }
    }

    /**
     * The children-cache bucket key for the layer [file] is in. For a normal directory
     * row that's [fileKey], but **favorites / servers / restic repositories don't have
     * a row of their own for their root directory** — children sit directly under the
     * `fav:`/`s:`/`restic:` row (see [addFavorite]/[addServer]), and [children] is
     * stored under that key. So looking up `f:scheme:parentPath` directly misses, and
     * an image opened from a favorite used to come back as a single file showing 1/1
     * with no pagination. When the lookup misses, fall back to the `f:` form (the
     * caller treats a miss as "single image").
     */
    private fun siblingsKey(file: XFile): String {
        val fk = "f:${file.scheme}:${file.parentPath}"
        if (children.containsKey(fk)) return fk
        // ★ `parentPath` is split on '/', while a **SAF path is a whole document URI**
        // (the '/' inside child and parent document ids are encoded as %2F) — what
        // we get out is not a parent directory at all, so "what other images / audio
        // are in the same directory" finds nothing: only one image can be viewed,
        // music can't build a play queue. Ask the tree directly: who contains this
        // row (same idea as [upTarget]).
        parentRow(file)?.let { return it.first }
        // The favorite/server root path may have a trailing slash (whatever was stored);
        // parentPath never does.
        val parent = file.parentPath.trimEnd('/').ifEmpty { "/" }
        return keyFile.entries.firstOrNull { (k, v) ->
            k != fk && v.scheme == file.scheme &&
                v.path.trimEnd('/').ifEmpty { "/" } == parent && children.containsKey(k)
        }?.key ?: fk
    }

    /**
     * [file]'s parent directory's **name as displayed in the tree**, used as the
     * title of ad-hoc lists like "now playing".
     *
     * ★ We can't take the last segment of `file.parentPath`: the last segment of a
     * media server's path is the item id (Emby uses pure numbers), so the list would
     * be called "40". The tree row's `XFile` carries a `displayName` (the album
     * name) — use it directly; only fall back to the trailing path segment when
     * nothing is found (e.g. the directory was never expanded).
     */
    fun parentLabel(file: XFile): String {
        keyFile[siblingsKey(file)]?.takeIf { it.isDir }?.let { return it.name }
        return file.parentPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    }

    /** Image list of [file]'s directory + this file's index in it (used by the image viewer to page left/right). */
    fun imageSiblings(file: XFile): Pair<List<XFile>, Int> {
        // Match what's actually on screen: hidden files are also excluded from paging;
        // if the target itself isn't in the list (a hidden item opened from elsewhere), fall back to a single-image view.
        val images = visible(children[siblingsKey(file)]).filter { !it.isDir && OpenFiles.isImage(it) }
            .takeIf { l -> l.any { it.path == file.path } } ?: listOf(file)
        val idx = images.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return images to idx
    }

    /** Audio list of [file]'s directory (using the panel's current sort) + this file's index (used by the music play queue). */
    fun audioSiblings(file: XFile): Pair<List<XFile>, Int> {
        val audios = visible(children[siblingsKey(file)]).filter { !it.isDir && OpenFiles.isAudio(it) }
            .takeIf { l -> l.any { it.path == file.path } } ?: listOf(file)
        val idx = audios.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return audios to idx
    }

    /** Convert an XFile into a persistable playlist track (local / connected server); other sources return null. */
    fun trackFrom(file: XFile): com.twig.app.PlaylistTrack? = when {
        file.scheme == "file" ->
            com.twig.app.PlaylistTrack(
                kind = "local", path = file.path, size = file.size, lastModified = file.lastModified,
                displayName = file.displayName.orEmpty(),
            )
        schemeToConn.containsKey(file.scheme) ->
            com.twig.app.PlaylistTrack(
                kind = "conn", path = file.path, connLabel = schemeToConn[file.scheme]!!.label(),
                size = file.size, lastModified = file.lastModified,
                // ★ Must carry this: the trailing segment of a media server's path is the item id
                // (Emby's are pure numbers), so rebuilding the name from path alone yields "38", "40".
                displayName = file.displayName.orEmpty(),
            )
        // SAF: the document URI itself is a persistent authorization (takePersistableUriPermission),
        // valid across sessions, so it can land on "Now playing" too. ★ The name can only rely on
        // displayName — the URI doesn't carry a filename, and dropping that leaves the list as a
        // row of content://… URIs without even an extension (media3 needs it to identify the container).
        file.scheme == com.twig.app.SafFileSystem.SCHEME ->
            com.twig.app.PlaylistTrack(
                kind = "saf", path = file.path, size = file.size, lastModified = file.lastModified,
                displayName = file.displayName.orEmpty(),
            )
        else -> null
    }

    // ---- Interaction ----

    fun toggle(node: Node) {
        when (node) {
            is FileNode -> toggleFile(node)
            is GroupNode -> {
                currentKey = node.key
                currentDir = null // Group heading (LAN/FTP/...) isn't a real directory; clear it so copy/new don't pick up the previously-selected directory.
                if (!expanded.remove(node.key)) accordionExpand(node.key)
                rebuild()
            }
            is ServerNode -> toggleServer(node)
            is ResticNode -> if (node.unlocked) toggleRestic(node) // Unlocked — Fragment shows password prompt if needed.
            is FavoriteNode -> Unit // Handled by Fragment (may need password).
            is CompareNode -> Unit // Handled by Fragment (jumps to compare page).
            is ActionNode -> Unit // Handled by Fragment.
            is InfoNode -> { // The ✕ button
                val key = fileKey(node.file)
                infoOpen.remove(key)
                stopDirScan(key)
                rebuild()
            }
            is SearchNode -> closeSearch(node) // Tapping the virtual directory row itself: collapse and remove.
        }
    }

    fun expandGroup(id: String) {
        expanded.add("g:$id")
        rebuild()
    }

    /**
     * The currently "green-highlighted" file/directory (non-file nodes like
     * servers/groups return null), used as the default target for copy/delete/
     * rename when nothing is checked.
     * Root nodes like internal storage and removable volumes are excluded, so a
     * whole storage volume cannot be wiped by accident.
     */
    fun currentSelection(): XFile? = currentKey?.let { k ->
        keyFile[k]?.takeIf {
            fileKey(it) == k && it.path != "/" &&
                !(
                    it.scheme == "file" &&
                        (
                            it.path == Environment.getExternalStorageDirectory().absolutePath ||
                                StorageVolumes.isVolumeRoot(it.path) // A whole SD card / USB stick shouldn't be the default target either.
                            )
                    )
        }
    }

    /** Get (creating if needed) the registered scheme for a connection; blocking IO, returns null on failure. */
    fun connSchemeBlocking(conn: SavedConnection): String? =
        runCatching { schemeForConn(conn) }.getOrNull()

    /** Rebuild the tree only (groups re-read saved connections / SAF grants), without re-listing directories. */
    fun refreshTree() = rebuild()

    /**
     * When non-null the tree only keeps this one root — used by the picker's
     * "Pick a script on this server": other sources (local / other servers) don't
     * appear on the tree at all, so they can't be selected, which is better than
     * letting them be selected and then failing.
     */
    var lockRoot: XFile? = null

    /** Display name of the [lockRoot] row (server name). */
    var lockLabel: String? = null

    /**
     * Mount an archive that an external App opened "with Twig" ([ViewIntentActivity]):
     * it becomes a row at the top of the tree, expanded in place; the expansion logic
     * is exactly the same as expanding an archive already in the tree (via [listChildren]
     * → `ArchiveFileSystem.rootOf`).
     * Only keep the most recent one — external entries don't belong to any real
     * directory, keeping a history would just pile up at the top.
     */
    fun mountExternal(archive: XFile) {
        val key = EXTERNAL_KEY_PREFIX + fileKey(archive)
        externalMount = archive
        keyFile[key] = archive
        currentKey = key
        children.remove(key) // Re-opening the same archive (its contents may have changed) doesn't reuse the children cache.
        claimExpand(key)
        loadingKeys.add(key)
        expanded.add(key)
        rebuild()
        viewModelScope.launch {
            val r = runCatching { withContext(io) { listChildren(archive, key) } }
            val mine = landExpand(key)
            r.fold(
                {
                    putListing(key, it)
                    if (mine) {
                        it.mountRoot?.let { root -> currentDir = root } // An externally-opened archive can also act as a destination directory.
                        accordionExpand(key)
                    }
                    rebuild()
                },
                { expanded.remove(key); rebuild(); if (mine) emitFailure(it, archive) },
            )
        }
    }

    /** Whether this scheme is a connected server (used by the treemap to decide whether "show on the other side" is possible). */
    fun isConnScheme(s: String): Boolean = schemeToConn.containsKey(s)

    /** The connection backing this scheme (used by the path bar's server-name / type icon); null for non-server sources. */
    fun connOf(s: String): SavedConnection? = schemeToConn[s]

    /**
     * Progressively expand and locate [target] in the tree (used by the treemap's
     * "show on the other side"):
     * for local, pick a root based on whether it's under internal storage or root;
     * for a server, expand the group and server node first, then drill down.
     * Other sources (inside archives / restic / etc.) aren't supported.
     * [focus] is the file in [target] that should scroll into view ("Go to containing
     * folder" gives one when arriving from the music player / a desktop shortcut);
     * the highlight still frames the directory itself; the list just scrolls to that row.
     * With [openGit], also expand the Git virtual node inside that directory and
     * locate it (the "Last position" entry for a git item).
     */
    fun revealPath(target: XFile, focus: XFile? = null, openGit: Boolean = false) {
        viewModelScope.launch {
            val r = runCatching {
                val plan = withContext(io) { planReveal(target, openGit) }
                applyReveal(plan)   // Main-thread table write
                plan.gitKey
            }
            r.fold(
                { gitKey ->
                    currentDir = target
                    currentKey = fileKey(target)
                    scrollKey = gitKey ?: focus?.let { fileKey(it) }
                    rebuild()
                },
                { rebuild(); emitError(it.message) },
            )
        }
    }

    /**
     * The "plan" for locating: which keys to keep expanded, which directories to
     * list now, which the git virtual root is.
     * The IO phase only produces this — it never touches any VM state; the merge
     * is in [applyReveal].
     */
    private class RevealPlan(
        val keep: Set<String>,
        /** In order from root to target; already-cached entries carry only XFile, ones that need listing carry [Restored]. */
        val expand: List<Pair<String, XFile>>,
        val fetched: List<Restored>,
        val gitKey: String?,
    )

    /** Main thread: merge [RevealPlan] into the tree, and fold up any branch not on the chain per the accordion rule. */
    private fun applyReveal(plan: RevealPlan) {
        // Locating is also an "expand" — it has to be claimed: otherwise an in-flight
        // expand completing right now would accordionExpand itself, folding up the
        // very chain we just located (see pendingExpand).
        plan.expand.lastOrNull()?.first?.let { claimExpand(it) }
        plan.fetched.forEach { applyRestored(it) }
        plan.expand.forEach { (key, x) ->
            keyFile[key] = x
            expanded.add(key)
        }
        // Fold up every other currently-expanded branch (other root / other server / etc.).
        synchronized(expanded) { expanded.removeAll { it !in plan.keep } }
    }

    /** IO: compute the reveal plan (connecting and listing on demand), without writing any VM state. */
    private fun planReveal(target: XFile, openGit: Boolean): RevealPlan {
        // Accordion rule: after locating the same side keeps only this one expanded chain, all others fold up (matches accordionExpand).
        val keep = HashSet<String>()
        val chain = ArrayList<String>()
        val expand = ArrayList<Pair<String, XFile>>()
        val fetched = ArrayList<Restored>()
        if (target.scheme == "file") {
            val ext = Environment.getExternalStorageDirectory().absolutePath
            val root = if (target.path == ext || target.path.startsWith("$ext/")) ext else "/"
            var p = target.path
            while (true) {
                chain.add(0, p)
                if (p == root || p == "/" || p.isEmpty()) break
                p = XFile("file", p, isDir = true).parentPath
            }
            if (chain.firstOrNull() != root) chain.add(0, root)
        } else {
            // The first time this session, the scheme isn't in schemeToConn yet — the scheme is
            // generated deterministically from the connection (Connections.schemeOf); look up
            // the saved connection by it and register the connection on the spot instead of
            // throwing an error.
            val conn = schemeToConn[target.scheme]
                ?: ConnectionStore.all(getApplication()).firstOrNull { Connections.schemeOf(it) == target.scheme }
                    ?.also { schemeForConn(it) }
                ?: throw FsException(str(R.string.err_reveal_unsupported))
            val group = when {
                conn.type == "smb" -> "lan"
                conn.type == "webdav" -> "dav"
                conn.isMediaServer() -> "media" // Jellyfin and Emby share one group.
                else -> conn.type
            }
            keep.add("g:$group")
            expand.add("g:$group" to XFile(target.scheme, "/", isDir = true))
            val sKey = "s:${conn.label()}"
            if (!children.containsKey(sKey)) {
                fetchServer(conn.label())?.let { fetched.add(it) }
            }
            keep.add(sKey)
            expand.add(sKey to XFile(target.scheme, "/", isDir = true))
            var p = target.path // Children of a server root hang under the server node — the chain itself does not contain "/".
            while (p != "/" && p.isNotEmpty()) {
                chain.add(0, p)
                p = XFile(target.scheme, p, isDir = true).parentPath
            }
        }
        for (d in chain) {
            val x = XFile(target.scheme, d, isDir = true)
            val key = fileKey(x)
            keep.add(key)
            if (!children.containsKey(key)) fetched.add(fetchDir(x)) else expand.add(key to x)
        }
        // Git virtual nodes hang off their host-directory row. While walking up the chain
        // above, listChildren already calls ensureGit to register them; here we just need
        // to expand the Git node too — otherwise the user would only see the host
        // directory and have to tap Git separately.
        var gitKey: String? = null
        if (openGit) {
            gitInfo[fileKey(target)]?.let { (s, _) ->
                val root = XFile(s, "/", isDir = true)
                val gk = fileKey(root)
                if (children.containsKey(gk)) expand.add(gk to root) else fetched.add(fetchDir(root))
                keep.add(gk)
                gitKey = gk
            }
        }
        return RevealPlan(keep, expand, fetched, gitKey)
    }

    // ---- Go to path (jump under a root) ----

    /**
     * Expand and locate by a path typed by the user, starting from one **root row**
     * (storage root / server root / document-tree root / favorite). A trailing
     * directory becomes the current directory; a trailing file leaves the highlight on
     * its directory and scrolls the list to that row (same split as [revealPath]'s
     * `focus`).
     *
     * ★ Why descend **by name** instead of joining a path and handing it to
     * [revealPath]: that one reconstructs every level by cutting the target path on
     * '/', which only holds for local paths and connected servers. **A SAF path is a
     * whole document URI** (the '/'s inside the child's and the parent's document id
     * are encoded as %2F), so a joined path is not any row in the tree (the same
     * lesson as [siblingsKey]); a media server's path is a chain of item ids, while
     * the user only ever has names. Descending costs one `list` per level — exactly
     * what [planReveal] pays as well, since it has to list every level anyway to hang
     * the children.
     *
     * [rootKey] is that row's key: `s:<label>` for a server, `fav:<id>` for a
     * favorite, `f:…` for a storage/document-tree root — which is also the bucket the
     * children hang on (see [siblingsKey]), so it must come from the row, not be
     * recomputed here. [root] may be null for a server that has not been connected in
     * this session; it is connected inside the IO phase (see [fetchServer]).
     */
    fun revealUnder(rootKey: String, root: XFile?, path: String) {
        val segs = relativeSegments(root, path)
        viewModelScope.launch {
            // Ancestors read the current rows, so it has to happen on the main thread.
            // The row was long-pressed, hence visible, hence its ancestors are expanded.
            // ★ A server is the exception: it may never have been opened this session,
            // so its group is named explicitly rather than read off the rows — landing
            // folds away everything outside this chain, the group row included.
            val chain = ancestorKeysOf(rootKey) + rootKey + setOfNotNull(serverGroupKey(rootKey))
            val r = runCatching { withContext(io) { planDescend(rootKey, root, segs) } }
            r.fold(
                { plan ->
                    applyDescend(chain, plan)
                    currentDir = plan.target
                    currentKey = plan.targetKey
                    scrollKey = plan.focus?.let { fileKey(it) }
                    rebuild()
                },
                { rebuild(); emitError(it.message) },
            )
        }
    }

    /**
     * Split the typed path into segments to descend through.
     *
     * An absolute path pasted from elsewhere is the common case, so when it really
     * sits under this root, that prefix is cut off first. This only applies when the
     * root's own path is slash-shaped — a SAF document URI or a media server's item
     * ids are matched by name only.
     */
    private fun relativeSegments(root: XFile?, path: String): List<String> {
        var rel = path.trim()
        val rp = root?.path.orEmpty()
        if (rp.startsWith("/") && rp != "/" && rel.startsWith("/")) {
            rel = when {
                rel == rp -> ""
                rel.startsWith("$rp/") -> rel.removePrefix("$rp/")
                else -> rel // not under this root: descending by name reports which segment is missing
            }
        }
        return rel.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
    }

    /** The chain [revealUnder] has to land: which rows to expand, what was fetched, where we stopped. */
    private class DescendPlan(
        val expand: List<Pair<String, XFile>>,
        val fetched: List<Restored>,
        val target: XFile,
        /** Key of the row the highlight lands on — the root's own key when nothing was descended. */
        val targetKey: String,
        val focus: XFile?,
    )

    /** IO: descend from [root] segment by segment, producing the plan; touches no VM state. */
    private fun planDescend(rootKey: String, root: XFile?, segs: List<String>): DescendPlan {
        val expand = ArrayList<Pair<String, XFile>>()
        val fetched = ArrayList<Restored>()
        // Cached children are used as they are (same as planReveal); otherwise list now.
        fun kidsOf(key: String, dir: XFile): List<XFile> {
            children[key]?.let { expand.add(key to dir); return it }
            val r = Restored(key, dir, listChildren(dir))
            fetched.add(r)
            return r.listing.children
        }
        var key = rootKey
        var dir: XFile
        var kids: List<XFile>
        if (root == null) {
            // Server not connected in this session: connect by label, same as planReveal's lookup
            val r = fetchServer(rootKey.removePrefix("s:")) ?: throw FsException(str(R.string.err_conn_deleted))
            fetched.add(r); dir = r.file; kids = r.listing.children
        } else {
            dir = root; kids = kidsOf(key, dir)
        }
        var focus: XFile? = null
        for ((i, s) in segs.withIndex()) {
            val hit = kids.firstOrNull { it.name == s }
                ?: kids.firstOrNull { it.name.equals(s, ignoreCase = true) }
                ?: throw FsException(str(R.string.err_goto_missing, s))
            if (!hit.isDir) {
                // A file is only allowed as the last segment: stop on its directory and
                // scroll to it. Descending *into* an archive is a mount, not a listing.
                if (i != segs.lastIndex) throw FsException(str(R.string.err_goto_not_dir, s))
                focus = hit
                break
            }
            dir = hit
            key = fileKey(hit)
            kids = kidsOf(key, dir)
        }
        return DescendPlan(expand, fetched, dir, key, focus)
    }

    /** Group row a server row belongs to; null for any other kind of root. */
    private fun serverGroupKey(rootKey: String): String? {
        val label = rootKey.removePrefix("s:").takeIf { it != rootKey } ?: return null
        return connFor(label)?.let { "g:${groupIdFor(it)}" }
    }

    /** Main thread: land a [DescendPlan] and fold away every branch off this chain. */
    private fun applyDescend(rootChain: Set<String>, plan: DescendPlan) {
        // Same as applyReveal: claim the expansion, or a slower one still in flight
        // lands later and accordions this whole chain shut (see pendingExpand).
        claimExpand(plan.targetKey)
        plan.fetched.forEach { applyRestored(it) }
        plan.expand.forEach { (k, x) -> keyFile[k] = x; expanded.add(k) }
        expanded.addAll(rootChain) // the way down to the root has to stay open too
        val keep = rootChain + plan.expand.map { it.first } + plan.fetched.map { it.key }
        synchronized(expanded) { expanded.removeAll { it !in keep } }
    }

    // ---- Recent locations (see [HistoryStore]: records directories, not files) ----

    /** A file was opened inside [file]: record the **directory** in recent locations. */
    fun noteOpenedIn(file: XFile) {
        noteHistory(XFile(file.scheme, file.parentPath, isDir = true), "dir")
    }

    private fun noteHistory(dir: XFile, kind: String) {
        val e = when {
            dir.scheme == "file" -> HistoryEntry(kind, dir.path)
            schemeToConn.containsKey(dir.scheme) ->
                HistoryEntry(kind, dir.path, schemeToConn[dir.scheme]!!.label())
            else -> return // Sources like archive contents / restic / SAF can't be recorded as "how to reach here" — skip.
        }
        HistoryStore.add(getApplication(), e)
    }

    /**
     * Jump to a recent location. The connection might not have been opened this session
     * yet — look up the saved connection by label, derive the deterministic scheme via
     * [Connections.schemeOf], and hand it to [revealPath], which connects on demand
     * (it handles that itself). Returns false when the connection has been deleted,
     * so the UI can show a hint.
     */
    fun revealHistory(e: HistoryEntry): Boolean {
        val scheme = if (e.connLabel.isEmpty()) {
            "file"
        } else {
            val conn = ConnectionStore.all(getApplication()).firstOrNull { it.label() == e.connLabel }
                ?: return false
            Connections.schemeOf(conn)
        }
        revealPath(XFile(scheme, e.path, isDir = true), openGit = e.kind == "git")
        return true
    }

    /**
     * Called after editing/deleting a server: drop that connection's cache and collapsed
     * nodes so the next expansion reconnects with the **new** configuration.
     *
     * ★ The scheme must be removed from [FsRegistry] too. The scheme is generated
     * deterministically from the connection label, so changing just the password or
     * host key keeps the same scheme; clearing only these VM tables is not enough —
     * the "reuse if already registered" check at the start of [Connections.ensure]
     * will hand back the **old** instance, and the change appears to have no effect
     * until the app is restarted. Also tear down the old connection so its socket
     * isn't left dangling.
     */
    fun forgetServer(label: String) {
        val key = "s:$label"
        val scheme = serverScheme.remove(key)
        if (scheme != null) {
            schemeToConn.remove(scheme)
            Connections.drop(scheme)
        }
        serverInfo.remove(label)
        children.remove(key)
        expanded.remove(key)
        keyFile.remove(key)
        rebuild()
    }

    /**
     * Called when a document-tree grant is revoked: clear what this row left behind in
     * the tree (cached children, expansion state, green highlight) and re-list — same
     * pattern as [forgetServer]. If we don't, re-granting the same tree later will
     * expand into this stale list. The row itself doesn't need removal: the SAF group
     * reads `persistedUriPermissions` fresh every time.
     */
    fun forgetSaf(file: XFile) {
        val key = fileKey(file)
        children.remove(key)
        expanded.remove(key)
        keyFile.remove(key)
        if (currentKey == key) {
            currentKey = null
            currentDir = null
        }
        rebuild()
    }

    /**
     * Collapse the current directory and move "current directory" up one level.
     * Server/favorite roots are not standalone [FileNode] rows (their children hang
     * directly under the [ServerNode]/[FavoriteNode] row — see [addServer]/
     * [addFavorite]), so when `parentPath` walks up to one of those we collapse the
     * container node itself instead; one level further collapses its containing
     * [GroupNode], walking all the way back to the top of the tree. We no longer
     * short-circuit by clearing `currentDir` at the first source-side root.
     */
    fun up() {
        val key = currentKey ?: return
        when {
            key.startsWith("g:") -> {
                expanded.remove(key)
                currentKey = null
                currentDir = null
            }
            key.startsWith("s:") -> {
                expanded.remove(key)
                Thumbs.cancelPending(descendantFilesByKey(key))
                val conn = serverScheme[key]?.let { schemeToConn[it] }
                currentKey = conn?.let { "g:${groupIdFor(it)}" }
                currentDir = null
            }
            key.startsWith("fav:") -> {
                expanded.remove(key)
                currentKey = "g:fav"
                currentDir = null
            }
            else -> {
                val cur = currentDir ?: return
                expanded.remove(key)
                val up = upTarget(key, cur)
                val parent = up?.second
                currentDir = parent // When landing on a group row there is no "current directory" — same as the g:/s: branches.
                currentKey = when {
                    up == null -> null
                    parent == null -> up.first
                    else -> up.first ?: containerKeyFor(parent) ?: fileKey(parent)
                }
            }
        }
        rebuild()
    }

    /**
     * Where "Up" should land: the key of the row that becomes current in the tree
     * (returns null when only a string fallback is available) and the directory to
     * use as `currentDir`; null at the very top.
     *
     * Two spots used to drop the green highlight entirely:
     * - The **archive root** has `"/"` as its path (it IS the archive's own root),
     *   and the old code treated that as "we're at the top" and cleared everything.
     *   But it is not at the top of the tree — it hangs off a host file, so its
     *   parent should be the **directory containing the host file**, matching what
     *   collapsing an archive inside the tree does (see [toggleFile]).
     * - **SAF's path is a full document URI**, and `parentPath` splits it on `/` —
     *   not an actual parent directory. So `currentKey` ends up pointing at a row
     *   that doesn't exist — on screen, the green highlight just disappears.
     *
     * So ask the tree first: **whoever contains it is its parent**. That holds for
     * every source; string fallback is only for "ancestor never expanded" cases.
     */
    private fun upTarget(key: String, cur: XFile): Pair<String?, XFile?>? {
        if (mountRoots[key]?.let { it.scheme == cur.scheme && it.path == cur.path } == true) {
            val host = keyFile[key] ?: return null
            parentRow(host)?.let { return it } // The parent directory of the host file's row in the tree.
            val dir = runCatching { FsRegistry.of(host).parentOf(host) }.getOrNull() ?: return null
            return null to dir
        }
        parentRow(cur)?.let { return it }
        // Nothing in the tree contains it — so it is either the top-level row (no
        // parent), or a row hanging **directly under a group** (SAF grant roots do
        // this; like server roots, fall back to its containing group title).
        (_state.value.rows.firstOrNull { it.key == key } as? FileNode)?.let { row ->
            if (row.depth == 0) return null
            groupRowAbove(key)?.let { return it to null }
        }
        if (cur.path == "/" || cur.path.isEmpty()) return null
        return null to cur.copy(path = cur.parentPath)
    }

    /** The nearest group row above [key] in the tree (only meaningful when it is not a top-level root — see [upTarget]). */
    private fun groupRowAbove(key: String): String? {
        val rows = _state.value.rows
        val i = rows.indexOfFirst { it.key == key }
        if (i < 0) return null
        for (j in i - 1 downTo 0) if (rows[j] is GroupNode) return rows[j].key
        return null
    }

    /** Of the expanded directories, who contains [file] — return that row's key and XFile. */
    private fun parentRow(file: XFile): Pair<String, XFile>? {
        val k = children.entries.firstOrNull { (_, list) ->
            list.any { it.scheme == file.scheme && it.path == file.path }
        }?.key ?: return null
        return keyFile[k]?.let { k to it }
    }

    /** The group id the server lives in (lan/dav/sftp/ftp) — matches the mapping used by [revealPath]. */
    private fun groupIdFor(conn: SavedConnection): String = when {
        conn.type == "smb" -> "lan"
        conn.type == "webdav" -> "dav"
        conn.isMediaServer() -> "media" // Jellyfin and Emby share one group.
        else -> conn.type
    }

    /** Whether [x] is exactly the root of some expanded server/favorite — if so, return that node's key ("s:.." / "fav:.."),
     *  used by [up] to fall back to the container node itself rather than a fileKey that was never really expanded. */
    private fun containerKeyFor(x: XFile): String? = keyFile.entries.firstOrNull { (k, v) ->
        (k.startsWith("s:") || k.startsWith("fav:")) && v.scheme == x.scheme && v.path == x.path
    }?.key

    /**
     * Only re-list expanded local directories (so external apps creating/removing files
     * stay in sync); network directories are not touched.
     * If nothing changed, skip rebuilding the tree to avoid a pointless refresh.
     */
    fun refreshLocal() {
        val targets = expandedSnapshot().mapNotNull { k ->
            keyFile[k]?.takeIf { it.scheme == "file" && it.isDir }?.let { k to it }
        }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            // IO only reads; the assignment below runs on the main thread anyway
            // (sortList still reads Prefs, which has to be on the main thread).
            val fresh = HashMap<String, Listing>()
            withContext(io) {
                for ((k, f) in targets) runCatching { fresh[k] = listChildren(f) }
            }
            var changed = false
            for ((k, l) in fresh) {
                val sorted = sortList(k, applyListing(l))
                if (children[k] != sorted) changed = true
                children[k] = sorted
            }
            if (changed) rebuild()
        }
    }

    /**
     * Clear [dir]'s own children cache (whether expanded or not). [refresh] only
     * re-lists "currently expanded" directories — so if you create a directory on a
     * collapsed one (long-press menu, no need to open it first) and it was previously
     * expanded then collapsed, the cache holds the stale list, refresh doesn't touch
     * it, and the next expansion shows the pre-creation state (only a full app
     * restart rebuilds everything and "seems to fix it"). Clearing here guarantees the
     * next expansion re-fetches.
     */
    fun invalidate(dir: XFile) {
        children.remove(fileKey(dir))
    }

    /** Refresh after a mutation: re-list every expanded directory. */
    fun refresh() {
        val targets = expandedSnapshot().mapNotNull { k -> keyFile[k]?.let { k to it } }
        viewModelScope.launch {
            val fresh = HashMap<String, Listing>()
            withContext(io) {
                for ((k, f) in targets) runCatching { fresh[k] = listChildren(f) }
            }
            for ((k, l) in fresh) putListing(k, l)
            rebuild()
        }
    }

    // ---- Expansion logic ----

    /**
     * Accordion expansion: collapse every node not on this node's ancestor chain
     * (directories / servers / groups / favorites all participate), keeping only
     * the "currently selected" expansion chain open across the whole tree.
     */
    /**
     * Claim this tap: any earlier in-flight expansion (if it isn't this same row)
     * is now abandoned — its spinner is collected immediately and it won't auto-expand
     * itself when it lands. See [pendingExpand].
     *
     * **Every "open this row" entry point must call this**, including the synchronous
     * branch that just expands from cache: synchronous expansion also calls
     * [accordionExpand], and without claiming, a slower one can still collapse it
     * after the fact.
     */
    private fun claimExpand(key: String) {
        pendingExpand?.takeIf { it != key }?.let { abandoned.add(it) }
        abandoned.remove(key) // Tapping back on a row we just abandoned: the spinner is reinstated, and the landing counts again.
        pendingExpand = key
    }

    /** Asynchronous expansion lands: clear the spinner and answer "does this still count?" (see [pendingExpand]). */
    private fun landExpand(key: String): Boolean {
        loadingKeys.remove(key)
        connecting.remove(key)
        abandoned.remove(key)
        return (pendingExpand == key).also { if (it) pendingExpand = null }
    }

    /** Should this row show a spinner? Real work is in flight and the user hasn't abandoned it yet (see [abandoned]). */
    private fun busy(key: String): Boolean =
        key !in abandoned && (loadingKeys.contains(key) || connecting.contains(key))

    private fun accordionExpand(key: String) {
        val ancestors = ancestorKeysOf(key)
        // removeAll{} iterates internally; iterating a synchronizedSet must be guarded by the set itself.
        synchronized(expanded) { expanded.removeAll { it != key && it !in ancestors } }
        expanded.add(key)
    }

    /** Snapshot of [expanded]; anywhere we iterate it, take a snapshot first — don't iterate directly (see field comments). */
    private fun expandedSnapshot(): List<String> = synchronized(expanded) { expanded.toList() }

    private fun ancestorKeysOf(key: String): Set<String> =
        TreeKeys.ancestorKeys(_state.value.rows.map { TreeKeys.Row(it.key, it.depth) }, key)

    /**
     * Long-press menu "Properties": toggle the info card under this file's row. Multiple
     * cards can stay open at once, independently; first open reads asynchronously
     * (EXIF / media info / app info may involve IO).
     */
    fun toggleInfo(n: FileNode) = toggleInfo(n.file)

    /** Same as above but takes a file directly (favorite rows have no matching [FileNode]; the menu's "Properties" entry uses this). */
    fun toggleInfo(file: XFile) {
        val key = fileKey(file)
        if (!infoOpen.remove(key)) {
            infoOpen.add(key)
            if (file.isDir) startDirScan(key, file)
            if (!infoCache.containsKey(key)) {
                viewModelScope.launch {
                    val d = withContext(io) {
                        runCatching { com.twig.app.FileInfo.load(getApplication(), file) }
                            .getOrElse {
                                com.twig.app.FileInfo.Details(emptyList(), it.message ?: str(R.string.info_failed))
                            }
                    }
                    if (infoOpen.contains(key)) {
                        infoCache[key] = d
                        rebuild()
                    }
                }
            }
        } else {
            stopDirScan(key)
        }
        rebuild()
    }

    /**
     * Recursive stats for a directory's properties card (file count / dir count /
     * total size): scan begins as soon as the card opens, refreshing along the way.
     * Closing the card (toggling via [toggleInfo] again / ✕) or collapsing the
     * directory (clean-up inside [rebuild]) cancels it — scanning large trees,
     * especially on network sources, is expensive and shouldn't keep running
     * when the card is no longer visible.
     */
    private fun startDirScan(key: String, dir: XFile) {
        dirScan.remove(key)?.job?.cancel()
        val st = DirScanState()
        dirScan[key] = st
        st.job = viewModelScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            scanDirStat(dir, io).collect {
                st.stat = it
                rebuild()
            }
            // If the scan finishes too quickly, keep the spinner a bit longer (see
            // DIR_SCAN_MIN_SPIN_MS): the numbers are already final, this half-second
            // only affects when the spinner stops.
            val left = DIR_SCAN_MIN_SPIN_MS - (android.os.SystemClock.elapsedRealtime() - t0)
            if (left > 0) kotlinx.coroutines.delay(left)
            st.scanning = false
            rebuild()
        }
    }

    private fun stopDirScan(key: String) {
        dirScan.remove(key)?.job?.cancel()
    }

    /** For unit tests only: count of in-flight directory-recursive stat scans (must drop to zero after the card is closed/collapsed). */
    internal fun activeDirScans(): Int = dirScan.count { it.value.job?.isActive == true }

    /**
     * Switch the properties card's tab. Switching to the "Hash" tab (index == section
     * count) auto-starts computation for local files; network files wait for the user
     * to tap "Compute" (a whole-file read — bandwidth/time is up to the user).
     */
    fun selectInfoTab(n: InfoNode, idx: Int) {
        val key = fileKey(n.file)
        infoTab[key] = idx
        val isHash = !n.file.isDir && idx == (n.details?.sections?.size ?: 0)
        if (isHash && n.file.scheme == "file" && !hashCache.containsKey(key) && !hashing.contains(key)) {
            startHash(key, n.file)
        }
        rebuild()
    }

    /** Manual "compute hash" button on the Hash tab (network files). */
    fun computeHash(n: InfoNode) {
        val key = fileKey(n.file)
        if (!hashCache.containsKey(key) && !hashing.contains(key)) startHash(key, n.file)
        rebuild()
    }

    private fun startHash(key: String, file: XFile) {
        hashing.add(key)
        viewModelScope.launch {
            val r = runCatching {
                withContext(io) { com.twig.app.FileInfo.hashes(file) }
            }
            hashing.remove(key)
            if (infoOpen.contains(key)) {
                hashCache[key] = r.getOrElse { listOf(str(R.string.err_hash_failed) to (it.message ?: str(R.string.err_hash_read_error))) }
                rebuild()
            }
        }
    }

    /**
     * Kick off a recursive wildcard search: results hang as a virtual directory under
     * [root]'s row, streamed in and rebuilt every ~250ms (throttled to avoid having
     * many hits trigger a full tree rebuild one by one). Re-issuing against the same
     * directory cancels the previous job and starts over with a clean slate.
     * [root] must currently have a visible [FileNode] row in the tree (otherwise the
     * virtual directory has nowhere to hang); otherwise this call is silently dropped —
     * [rebuild]'s cleanup logic also cancels the job and discards state when the row
     * becomes invisible.
     *
     * The highlight frame ([CurrentDirFrame] frames the currentKey row + its direct
     * children) moves to the search virtual directory itself ([SearchNode.key]) rather
     * than staying on the searched directory — otherwise the frame would enclose
     * "directory + search-results virtual-directory title row" and skip the result list
     * one level below (see the fallback in [closeSearch]).
     */
    fun startSearch(root: XFile, pattern: String) {
        val key = fileKey(root)
        if (!hasSearchAnchor(key)) return
        searchState.remove(key)?.job?.cancel()
        val st = SearchState(pattern)
        searchState[key] = st
        currentDir = root
        currentKey = "search:$key"
        rebuild()
        var lastUi = 0L
        st.job = viewModelScope.launch {
            scanSearch(root, pattern).collect { f ->
                st.results.add(f)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUi > 250) {
                    lastUi = now
                    rebuild()
                }
            }
            st.scanning = false
            rebuild()
        }
    }

    /**
     * Does the tree have a place to hang this search's results?
     *
     * ★ Don't just check "is there a row whose key equals `fileKey(root)`": **server
     * and favorite rows have keys `s:<label>` / `fav:…`**, and their attached rows
     * hang on the directory `keyFile[that key]` points at (see [addServer] /
     * [addFavorite]). Checking only the former would mean **a search issued at a
     * server root is silently dropped** — symptom: "I tap search and nothing happens".
     */
    private fun hasSearchAnchor(key: String): Boolean {
        val rows = _state.value.rows
        if (rows.any { it.key == key }) return true
        return rows.any { row -> keyFile[row.key]?.let { fileKey(it) == key } == true }
    }

    /**
     * Tap on the search virtual-directory row itself: cancel any in-flight scan and
     * remove it from the tree (results are not kept — the search must be re-issued).
     * If the highlight frame is currently on this search node, fall back to the
     * searched directory itself (rather than pointing at a key that no longer exists).
     */
    /**
     * For search results hung under a server/favorite row, look up by the directory
     * that row points at.
     *
     * ★ Search-result keys are `fileKey(directory)` (see [addAttachments]), **not**
     * the row's own `s:<label>` / `fav:<id>` — using the row's key returns nothing.
     */
    private fun searchDirKey(rowKey: String): String? = keyFile[rowKey]?.let { fileKey(it) }

    private fun hasSearchUnder(rowKey: String) =
        searchDirKey(rowKey)?.let { searchState.containsKey(it) } == true

    /** When collapsing a server/favorite row, also remove its attached search results (the attached-row render ignores expansion state). */
    private fun dropSearchUnder(rowKey: String) {
        searchDirKey(rowKey)?.let { searchState.remove(it)?.job?.cancel() }
    }

    private fun closeSearch(node: SearchNode) {
        val rootKey = fileKey(node.root)
        searchState.remove(rootKey)?.job?.cancel()
        if (currentKey == node.key) currentKey = rootKey
        rebuild()
    }

    /** Long-press menu "Open as archive": APKs aren't expandable by default; here we let it through and mount as a normal archive. */
    fun openAsArchive(n: FileNode) {
        forcedArchive.add(n.key)
        toggleFile(n.copy(expandable = true))
    }

    /**
     * Long-press menu "Refresh": drop this node's child cache and re-fetch (git root
     * nodes also clear the status/log/diff cache).
     * Applies to any expandable node, not just git — the user can manually refresh a
     * directory / archive contents at any time.
     */
    fun refreshNode(n: FileNode) {
        if (!n.expandable) return
        val key = n.key
        children.remove(key)
        // Even while collapsed, drop the git cache: a manual refresh is the only
        // refresh path for SMB/WebDAV repositories, logCache has no TTL, so if we
        // don't clear on "refresh" while collapsed, the next expansion still shows
        // the old state.
        val isExpanded = expanded.contains(key)
        if (isExpanded) loadingKeys.add(key)
        rebuild()
        viewModelScope.launch {
            val r = runCatching {
                withContext(io) {
                    // Run invalidate right before the request fires (not before the
                    // main-thread rebuild above): clearing too early makes rebuild
                    // render with the empty cache, flashing the "registered-at-load
                    // time" branch name before the new one lands.
                    (FsRegistry.of(n.file) as? GitFileSystem)?.invalidate()
                    if (isExpanded) listChildren(n.file) else null
                }
            }
            loadingKeys.remove(key)
            r.fold(
                { it?.let { l -> putListing(key, l) }; rebuild() }, // Rebuild even when collapsed: the header may have updated with the invalidate.
                { rebuild(); emitFailure(it, n.file) },
            )
        }
    }

    private fun toggleFile(n: FileNode) {
        if (!n.expandable) return
        if (n.file.isDir) currentDir = n.file
        val key = n.key
        currentKey = key
        claimExpand(key)
        // When expanding (not collapsing) a Git virtual root, record the host
        // directory in recent locations — Git-view subnodes (Changes / History / a
        // particular commit) share the scheme but have paths other than "/", so we
        // don't double-record.
        if (key !in expanded && n.file.path == "/" && n.file.scheme.startsWith("git")) {
            schemeToGitHost[n.file.scheme]?.let { noteHistory(it, "git") }
        }
        // Local/SSH git (where status()/log() don't cache by themselves) — every node
        // (root / Changes / History / a particular commit) is forcibly re-fetched on
        // expansion, bypassing the children cache. Without this special case, only
        // the root would re-fetch along the expansion chain, while subnodes (e.g.
        // "History") would still hit their own children cache — appearing "not
        // refreshed".
        // (Remote git over SMB/WebDAV via XFileGitFs is too expensive to do this; see
        // the cheapGitSchemes comment.)
        val freshGit = n.file.scheme in cheapGitSchemes
        // Media server "Continue watching" / "Latest" content is always changing (just
        // watched half of it, just added to library); re-fetch on every expansion —
        // otherwise "come back after finishing an episode, Continue Watching is still
        // showing the old list".
        val freshMedia = runCatching {
            com.twig.fs.network.JellyfinFileSystem.isLiveDir(n.file.path) &&
                Connections.ofScheme(n.file.scheme)?.isMediaServer() == true
        }.getOrDefault(false)
        when {
            expanded.remove(key) -> {
                Thumbs.cancelPending(descendantFiles(n.file))
                // When collapsing this directory, also remove the search virtual
                // directory hanging under it (it doesn't naturally disappear from
                // rows — its render ignores exp, see addFile — so it would sit
                // lonely under a collapsed directory).
                searchState.remove(key)?.job?.cancel()
                // Collapsing an archive: paste target must not stay inside the
                // archive — fall back to the containing directory.
                if (mountRoots.containsKey(key)) {
                    runCatching { FsRegistry.of(n.file).parentOf(n.file) }.getOrNull()
                        ?.let { currentDir = it }
                }
                rebuild()
            }
            searchState.containsKey(key) -> {
                // This directory itself isn't actually expanded — the chevron just
                // looks "expanded" because a search result hangs there (see addFile).
                // Tapping this row should behave like the chevron suggests — treat it
                // as "collapse": only close the search, don't fall into the
                // expansion branch and really expand it, listing files/dirs this
                // directory never asked for.
                searchState.remove(key)?.job?.cancel()
                rebuild()
            }
            children.containsKey(key) && !freshGit && !freshMedia -> {
                mountRoots[key]?.let { currentDir = it } // Archive: current directory becomes the archive root.
                accordionExpand(key); rebuild()
            }
            // This row is already being fetched (common after "abandon then tap
            // back"): claimExpand above has reclaimed it and the spinner is
            // reinstated — sending another request is just a duplicate. Same
            // pattern as the connecting branch in toggleServer.
            loadingKeys.contains(key) -> rebuild()
            else -> {
                loadingKeys.add(key); rebuild() // Show the spinner first.
                viewModelScope.launch {
                    val r = runCatching {
                        withContext(io) {
                            // Run invalidate right before the request fires, not on
                            // the main thread earlier: clearing there makes the
                            // rebuild above render the Git node's title with an
                            // empty cache — flashing the "registered at load"
                            // branch name for a frame before the new one lands.
                            if (freshGit) (FsRegistry.of(n.file) as? GitFileSystem)?.invalidate()
                            listChildren(n.file, key)
                        }
                    }
                    // Meanwhile the user opened something else; this expansion is
                    // abandoned: children are still cached (so a future tap
                    // expands instantly), but no auto-expand, no currentDir move,
                    // no error dialog (see pendingExpand).
                    val mine = landExpand(key)
                    r.fold(
                        {
                            putListing(key, it)
                            if (mine) {
                                // Expanding an archive = select the archive root, so
                                // it can be a copy/new-folder target just like an
                                // expanded directory. The archive's row in the tree
                                // is the host file (isDir=false), so the
                                // `if (isDir)` line above doesn't cover it.
                                it.mountRoot?.let { root -> currentDir = root }
                                accordionExpand(key)
                            }
                            rebuild()
                        },
                        { rebuild(); if (mine) emitFailure(it, n.file) },
                    )
                }
            }
        }
    }

    private fun toggleServer(n: ServerNode) {
        val key = n.key
        currentKey = key
        claimExpand(key) // Connecting a server is the slowest expansion — the "later tap wins" rule applies (see pendingExpand).
        when {
            expanded.remove(key) -> {
                Thumbs.cancelPending(descendantFilesByKey(key))
                // When collapsing a server, also drop any search results hanging under
                // it (attached-row rendering ignores expansion state — see
                // addServer/addAttachments), same as toggleFile for a regular
                // directory — otherwise the server collapses but a pile of search
                // results stay orphaned under it.
                dropSearchUnder(key)
                currentDir = keyFile[key] ?: currentDir // After collapse, highlight stays on the server row and target syncs to its root; don't keep the previous directory.
                rebuild()
            }
            hasSearchUnder(key) -> {
                // Server isn't expanded, only search results hang under it: treat
                // the tap as "collapse" — only close the search, don't fall into
                // the branch below and actually expand the whole server (same rule
                // as the toggleFile one).
                dropSearchUnder(key); rebuild()
            }
            children.containsKey(key) -> {
                currentDir = keyFile[key] ?: currentDir
                accordionExpand(key); rebuild()
            }
            // Already connecting (common after "abandon then tap back"): claimExpand
            // has already reclaimed it and the spinner is reinstated; no second
            // connection, the in-flight one will count when it lands.
            connecting.contains(key) -> rebuild()
            else -> {
                connecting.add(key)
                rebuild()
                viewModelScope.launch {
                    val r = runCatching { withContext(io) { connectAndList(n.conn, key) } }
                    val mine = landExpand(key)
                    r.fold(
                        {
                            applyRestored(it, expand = mine) // Connection is already up; fall through to normal landing, just skip expanding.
                            if (mine) {
                                currentDir = keyFile[key] ?: currentDir
                                accordionExpand(key)
                            }
                            rebuild()
                        },
                        { rebuild(); if (mine) emitError(it.message) },
                    )
                }
            }
        }
    }

    /** Connect to a server (register a unique scheme per server in FsRegistry) and list its root; IO thread, no VM state writes. */
    private fun connectAndList(conn: SavedConnection, key: String): Restored {
        val root = XFile(schemeForConn(conn), "/", isDir = true)
        return Restored(key, root, listChildren(root))
    }

    /**
     * Get (or establish) the registered scheme for a connection; server nodes and
     * favorites share the same connection. The actual connect/register work is
     * delegated to [Connections.ensure] (the single source of truth); this method
     * only maintains the VM's reverse-lookup table.
     */
    private fun schemeForConn(conn: SavedConnection): String {
        val nodeKey = "s:${conn.label()}"
        serverScheme[nodeKey]?.let { return it }
        val s = Connections.ensure(getApplication(), conn) { info -> serverInfo[conn.label()] = info }
        serverScheme[nodeKey] = s
        schemeToConn[s] = conn
        return s
    }

    /** Get (or establish) the registered scheme for a restic repository. */
    private fun schemeForRestic(repoDir: XFile, password: String): String {
        val nodeKey = "restic:${fileKey(repoDir)}"
        resticScheme[nodeKey]?.let { return it }
        val repo = ResticRepo.open(
            FsRegistry.of(repoDir), repoDir, password, NativeZstd(),
            com.twig.app.ResticCache.of(getApplication()),
        )
        val s = "restic" + Integer.toHexString(nodeKey.hashCode())
        FsRegistry.register(ResticFileSystem(repo, s))
        resticScheme[nodeKey] = s
        schemeToRestic[s] = repoDir
        return s
    }

    /**
     * Unlock a restic repository with a password: open it, register ResticFileSystem,
     * list snapshots as children.
     *
     * While unlocking the row shows a spinner (reuse [connecting]) and **duplicate
     * taps are ignored** — unlocking a network repo takes tens of seconds. Previously
     * there was neither a spinner nor dedup, so the user thought "nothing happened" and
     * tapped again, each tap launching a full `ResticRepo.open` (scrypt + reading the
     * whole index), all queued on SMB's serial lock — clicking more made it slower,
     * ending in "never opens".
     */
    fun unlockRestic(repoDir: XFile, password: String, onResult: (Boolean, String?) -> Unit) {
        val nodeKey = "restic:${fileKey(repoDir)}"
        claimExpand(nodeKey) // Before the dedup: tapping back on a just-abandoned repo should reclaim it and reinstate the spinner.
        if (!connecting.add(nodeKey)) { rebuild(); return } // Already unlocking; this tap is not stacked on top.
        rebuild() // Show the spinner first.
        viewModelScope.launch {
            val r = runCatching {
                withContext(io) {
                    val scheme = schemeForRestic(repoDir, password)
                    scheme to FsRegistry.of(scheme).list(XFile(scheme, "/", isDir = true))
                }
            }
            val mine = landExpand(nodeKey)
            r.fold(
                onSuccess = { (_, snaps) ->
                    putChildren(nodeKey, snaps)
                    if (mine) accordionExpand(nodeKey)
                    rebuild()
                    onResult(true, null)
                },
                onFailure = {
                    forgetRestic(nodeKey)
                    rebuild()
                    onResult(false, it.message)
                },
            )
        }
    }

    /**
     * Drop an unlock that didn't complete. ★ Rollback is mandatory (settled
     * 2026-08-04): [schemeForRestic] records the scheme in [resticScheme] the moment
     * the repo opens, but the immediately following "list snapshots" can still fail
     * (SMB disconnect / short read / index mid-read timeout). Without rollback, the
     * node renders as unlocked with empty `children`, and later taps only go through
     * [toggleRestic] expanding an empty node — **never retrying the unlock**. That
     * is exactly the user-reported symptom: "first time I entered the password it
     * worked, after that it never expands again".
     */
    private fun forgetRestic(nodeKey: String) {
        val s = resticScheme.remove(nodeKey) ?: return
        schemeToRestic.remove(s)
        FsRegistry.unregister(s)
        children.remove(nodeKey)
        expanded.remove(nodeKey)
    }

    /** Collapse / expand an already-unlocked restic node. */
    fun toggleRestic(node: ResticNode) {
        currentKey = node.key
        claimExpand(node.key)
        if (expanded.remove(node.key)) {
            Thumbs.cancelPending(descendantFilesByKey(node.key))
            rebuild()
        } else {
            accordionExpand(node.key); rebuild()
        }
    }

    // ---- Favorites ----

    /** Build a favorite from a directory node (returns null for unsupported sources). */
    fun favoriteFrom(file: XFile): Favorite? {
        if (!file.isDir) return null
        return when {
            file.scheme == "file" ->
                Favorite(label = file.name, kind = "local", path = file.path)
            schemeToConn.containsKey(file.scheme) -> {
                val c = schemeToConn[file.scheme]!!
                Favorite(label = "${c.displayLabel()}:${file.name}", kind = "conn", path = file.path, connLabel = c.label())
            }
            // SAF: the grant was taken via takePersistableUriPermission, document
            // URIs survive across sessions, so this location CAN be persisted (same
            // as the saf branch in trackFrom). ★ The name must be stored too — the
            // URI has no file name; slicing the path's last segment only yields a
            // string of %XX escapes (see Favorite.pathName).
            file.scheme == SafFileSystem.SCHEME ->
                Favorite(label = file.name, kind = "saf", path = file.path, pathName = file.name)
            schemeToRestic.containsKey(file.scheme) -> {
                val repoDir = schemeToRestic[file.scheme]!! // Underlying repo XFile (possibly on SMB, etc.).
                val repoConn = when {
                    repoDir.scheme == "file" -> ""
                    schemeToConn.containsKey(repoDir.scheme) -> schemeToConn[repoDir.scheme]!!.label()
                    // Repo lives on a source where "how to reach here" can't be
                    // persisted (inside an archive); repos on SAF would need an
                    // extra dimension (repo also being saf) — not done.
                    else -> return null
                }
                Favorite(
                    label = "restic:${file.name}",
                    kind = "restic",
                    path = file.path,
                    repoPath = repoDir.path,
                    repoConnLabel = repoConn,
                )
            }
            else -> null
        }
    }

    fun addFavorite(fav: Favorite) {
        FavoritesStore.add(getApplication(), fav)
        expanded.add("g:fav")
        rebuild()
    }

    fun removeFavorite(fav: Favorite) {
        FavoritesStore.remove(getApplication(), fav)
        rebuild()
    }

    /** Rename a favorite (passing an empty string restores the auto-generated full path name). */
    fun renameFavorite(fav: Favorite, newLabel: String) {
        FavoritesStore.rename(getApplication(), fav, newLabel)
        rebuild()
    }

    // ---- Compare favorites ----

    fun removeCompare(session: CompareSession) {
        CompareStore.remove(getApplication(), session)
        rebuild()
    }

    fun renameCompare(session: CompareSession, newLabel: String) {
        CompareStore.rename(getApplication(), session, newLabel)
        rebuild()
    }

    /**
     * The actual directory a favorite points at — only resolvable after it has been
     * expanded (connected / unlocked) once ([resolveFavorite] stores it in [keyFile]);
     * returns null if it hasn't, so menu items that need the directory are hidden.
     */
    fun favoriteTarget(node: FavoriteNode): XFile? = keyFile[node.key]

    /** The root directory a server node points at — same as [favoriteTarget]; only resolvable after connecting (expanding) once. */
    fun serverTarget(node: ServerNode): XFile? = keyFile[node.key]

    /** Refresh a favorite node: drop the cached children and re-list (connection / unlock is already set up, no need to re-run resolveFavorite). */
    fun refreshFavorite(node: FavoriteNode) = refreshVirtual(node.key)

    /** Same, but for a server node (connection already up, no reconnect). */
    fun refreshServer(node: ServerNode) = refreshVirtual(node.key)

    /** Refresh for virtual rows like favorite/server whose children hang on their own key; see [refreshNode] for the regular-directory version. */
    private fun refreshVirtual(key: String) {
        val target = keyFile[key] ?: return
        children.remove(key)
        if (!expanded.contains(key)) { rebuild(); return }
        connecting.add(key) // Favorite rows reuse the "connecting" spinner indicator.
        rebuild()
        viewModelScope.launch {
            val r = runCatching { withContext(io) { listChildren(target) } }
            connecting.remove(key)
            r.fold(
                { putListing(key, it); rebuild() },
                { rebuild(); emitError(it.message) },
            )
        }
    }

    /**
     * Expand / collapse a favorite node. On expand, connect (network) / unlock
     * (restic) as needed and list the target directory.
     * For restic, [password] provides the password (empty = fail, Fragment shows a
     * dialog to retry).
     */
    fun toggleFavorite(node: FavoriteNode, password: String?, onResult: (Boolean, String?) -> Unit) {
        val key = node.key
        currentKey = key
        claimExpand(key)
        when {
            expanded.remove(key) -> {
                dropSearchUnder(key) // Same as toggleServer: drop search results under this row when collapsing.
                currentDir = keyFile[key] ?: currentDir // A favorite is itself a real directory; sync currentDir to it the same way as toggleServer.
                rebuild(); onResult(true, null)
            }
            hasSearchUnder(key) -> {
                dropSearchUnder(key); rebuild(); onResult(true, null)
            }
            children.containsKey(key) -> {
                currentDir = keyFile[key] ?: currentDir
                accordionExpand(key); rebuild(); onResult(true, null)
            }
            // Already connecting (common after "abandon then tap back"): claimExpand
            // has already reclaimed it and the spinner is reinstated; no second
            // connection, the in-flight one will count when it lands.
            connecting.contains(key) -> rebuild()
            else -> {
                connecting.add(key); rebuild()
                viewModelScope.launch {
                    val r = runCatching { withContext(io) { resolveFavorite(node.fav, password) } }
                    val mine = landExpand(key)
                    r.fold(
                        { restored ->
                            applyRestored(restored, expand = mine)
                            if (mine) {
                                currentDir = keyFile[key] ?: currentDir
                                accordionExpand(key)
                            }
                            rebuild(); onResult(true, null)
                        },
                        { rebuild(); onResult(false, it.message) },
                    )
                }
            }
        }
    }

    private fun resolveFavorite(fav: Favorite, password: String?): Restored {
        var extra: Pair<String, List<XFile>>? = null
        val scheme = when (fav.kind) {
            "local" -> "file"
            "conn" -> {
                val conn = ConnectionStore.all(getApplication()).firstOrNull { it.label() == fav.connLabel }
                    ?: throw com.twig.core.FsException(str(R.string.err_conn_deleted))
                schemeForConn(conn)
            }
            "restic" -> {
                // First, make sure the repo's underlying source is reachable (local or some network connection).
                val repoScheme = if (fav.repoConnLabel.isEmpty()) {
                    "file"
                } else {
                    val conn = ConnectionStore.all(getApplication()).firstOrNull { it.label() == fav.repoConnLabel }
                        ?: throw com.twig.core.FsException(str(R.string.err_repo_conn_deleted))
                    schemeForConn(conn)
                }
                val repoDir = XFile(repoScheme, fav.repoPath, isDir = true)
                val pw = password ?: Prefs.resticPassword(getApplication(), fav.repoPath)
                    ?: throw com.twig.core.FsException(str(R.string.err_need_restic_password))
                val s = schemeForRestic(repoDir, pw)
                // Favorites take this independent unlock path — they don't go through
                // unlockRestic() in the tree's normal restic-node expansion, so the
                // snapshot-list cache (nodeKey) doesn't get filled in as a side effect.
                // Without it, expanding "all snapshots" for the same repo later in the
                // tree reads the cache (resticScheme already says "unlocked") and gets
                // an empty list.
                val nodeKey = "restic:${fileKey(repoDir)}"
                // The snapshot list is also just fetched here; persistence is delegated to applyRestored.
                if (!children.containsKey(nodeKey)) {
                    extra = nodeKey to FsRegistry.of(s).list(XFile(s, "/", isDir = true))
                }
                s
            }
            "saf" -> SafFileSystem.SCHEME
            else -> throw com.twig.core.FsException(str(R.string.err_unsupported_favorite))
        }
        // ★ displayName must travel back: SAF's path last-segment is NOT a name; without
        // it, the long-press menu title, properties card, and the destination name
        // when copying all become a string of percent-encoded characters (same lesson
        // as the PlaylistTrackNameTest).
        val target = XFile(scheme, fav.path, isDir = true, displayName = fav.pathName.ifEmpty { null })
        return Restored("fav:${fav.id}", target, listChildren(target), extra) // listChildren also identifies git / restic along the way.
    }

    /**
     * One listing's result: children + whatever was **incidentally discovered** this time.
     *
     * Discoveries (restic repo / git repo) used to be written directly into the VM's
     * tables by [listChildren], which runs on the IO thread. Now they're carried back
     * and persisted on the main thread by [applyListing] — IO only fetches data, and
     * that's the only threading rule worth remembering in this class.
     */
    private class Listing(
        val children: List<XFile>,
        /** This directory itself is a restic repository (value is `fileKey(dir)`). */
        val resticRepo: String? = null,
        /** This directory contains a .git; the data source has been built and registered in FsRegistry, pending registration in the VM's reverse-lookup table. */
        val git: Git? = null,
        /**
         * This listing was for an archive; value is the **mounted archive root** (scheme is the zip/7z/rar family).
         * On expand, use this as "current directory" so copy/new-folder can land inside
         * the archive — the XFile on this row in the tree is the **host file**
         * (isDir=false), which cannot be used as a directory.
         */
        val mountRoot: XFile? = null,
    ) {
        class Git(val dirKey: String, val dir: XFile, val scheme: String, val label: String, val cheap: Boolean)
    }

    /**
     * Apply [listChildren]'s discoveries into VM state and return the children.
     * **Main thread only.**
     * Idempotent: re-applying the same directory has no side effects (schemes are
     * deterministically derived from keys).
     */
    private fun applyListing(l: Listing): List<XFile> {
        l.resticRepo?.let { resticRepos.add(it) }
        l.git?.let { g ->
            gitInfo[g.dirKey] = g.scheme to g.label
            schemeToGitHost[g.scheme] = g.dir
            if (g.cheap) cheapGitSchemes.add(g.scheme)
        }
        return l.children
    }

    /** [applyListing] + sort + write into [children]; main thread. */
    private fun putListing(key: String, l: Listing) {
        l.mountRoot?.let { mountRoots[key] = it }
        putChildren(key, applyListing(l))
    }

    /**
     * Expanded archive key → archive-root directory. When an archive is expanded,
     * "current directory" must land here so copy/new-folder can enter the archive
     * (see [toggleFile]). **Do NOT stuff this into [keyFile]** — that table stores
     * "the XFile needed to re-list", which for archives has to be the **host file**
     * itself (refresh goes through [listChildren]'s archive branch and needs to
     * materialize + decrypt); putting the archive root in there would scramble everything.
     */
    private val mountRoots = ConcurrentHashMap<String, XFile>()

    /**
     * List children: directories are listed directly (also identifying restic repos / git repos); archives are mounted and the archive root is listed. IO thread.
     * [rowKey] is the tree row this listing was requested for, so a nested archive that needs local materialization ([localArchive]) can notice mid-copy that the
     * row was abandoned (collapsed / superseded by a later tap) and stop instead of downloading to completion in the background; null for call sites that don't
     * go through the claim/land/[abandoned] dance (session restore, favorites) and so have nothing meaningful to check.
     */
    private fun listChildren(file: XFile, rowKey: String? = null): Listing =
        if (file.isDir) {
            val kids = FsRegistry.of(file).list(file)
            Listing(
                children = kids,
                resticRepo = if (ResticRepo.looksLikeRepo(kids)) fileKey(file) else null,
                // .git may also be a **file** (worktree / submodule, contents are "gitdir: …") — don't only recognize directories.
                git = if (kids.any { it.name == ".git" }) buildGit(file) else null,
            )
        } else {
            // zip/7z parse streamingly over the seekable read channel (no full download for remote hosts). Materialize to cache first in two cases (see archiveTarget):
            // - rar: junrar only accepts local files
            // - nested archive (archive-in-archive) with the inner entry compressed: seeking through the outer decompression stream degenerates into repeated full decompressions. STORED (uncompressed, the normal case for zip-in-zip) can be sliced directly — no materialization, instant open.
            val (afs, archive) = archiveTarget(file, rowKey)
            // ★ rootOf must run **before** the encryption probe: non-local hosts (SMB/WebDAV/S3...) are only registered at mount time, before that needsPassword would try to open a remote path as a local file, read no bytes, and get silently swallowed by the internal runCatching into "no password needed" — remote encrypted archives would never prompt for a password again (see RemoteArchiveMountTest).
            val root = afs.rootOf(archive)
            unlockIfEncrypted(afs, archive, file)
            Listing(afs.list(root), mountRoot = root)
        }

    // ---- Encrypted archives ----

    /**
     * Unlock an encrypted archive before mounting. Already unlocked? Pass through. A
     * saved password exists? Use it. Otherwise (or saved one is wrong) throw
     * [ArchivePasswordException] for the UI to prompt. **IO thread** (reads the archive header).
     *
     * [original] is the file the user tapped; [archive] may be a materialized local
     * copy of it — the password is keyed by [original], so an encrypted archive on SMB
     * still works after the cache filename changes.
     */
    private fun unlockIfEncrypted(afs: ArchiveFileSystem, archive: XFile, original: XFile) {
        val path = archive.path
        if (afs.hasPassword(path) || !afs.needsPassword(path)) return
        val key = archivePwKey(original)
        val saved = Prefs.archivePassword(getApplication(), key) ?: throw ArchivePasswordException(path)
        if (!afs.checkPassword(path, saved)) {
            // The saved password no longer works (archive was re-encrypted) — clear it so we don't keep retrying.
            Prefs.setArchivePassword(getApplication(), key, null)
            throw ArchivePasswordException(path, wrong = true)
        }
        afs.setPassword(path, saved)
    }

    /**
     * The user gave a password in the dialog: if it checks out, store it and re-expand
     * [file]; if not, return false so the UI can ask again.
     * [save] decides whether to persist it ([Prefs.setArchivePassword]).
     */
    fun unlockArchive(file: XFile, password: String, save: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                withContext(io) {
                    val (afs, archive) = archiveTarget(file)
                    afs.rootOf(archive) // Same as above: register the host by mounting first so remote archives can be read.
                    if (!afs.checkPassword(archive.path, password)) return@withContext false
                    afs.setPassword(archive.path, password)
                    true
                }
            }.getOrElse { emitFailure(it, null); false }
            onResult(ok)
            if (!ok) return@launch
            if (save) Prefs.setArchivePassword(getApplication(), archivePwKey(file), password)
            // The previous expansion failed, so its children aren't cached — go through the full mount again here.
            children.remove(fileKey(file))
            expandFileNode(file)
        }
    }

    /** Password storage key: use the **file the user tapped** (not the cache copy's path), so changing device cache doesn't break it. */
    private fun archivePwKey(file: XFile): String = "${file.scheme}:${file.path}"

    /**
     * Archive file → (matching ArchiveFileSystem, the XFile that actually gets mounted).
     * Materialization rules match [listChildren] exactly — extracted because both places need them. IO thread.
     */
    private fun archiveTarget(file: XFile, rowKey: String? = null): Pair<ArchiveFileSystem, XFile> {
        val scheme = Archives.schemeFor(file) ?: throw FsException(str(R.string.err_unsupported_type))
        val afs = FsRegistry.of(scheme) as ArchiveFileSystem
        val hostFs = runCatching { FsRegistry.of(file) }.getOrNull()
        val needLocal = scheme == Archives.RAR_SCHEME ||
            (hostFs is ArchiveFileSystem && !hostFs.fastRandom(file))
        return afs to if (needLocal) localArchive(file, rowKey) else file
    }

    /** Re-expand this row after a successful unlock (the row that already exists in the tree, or the externally mounted row). */
    private fun expandFileNode(file: XFile) {
        val key = fileKey(file)
        if (externalMount?.let { fileKey(it) } == key) {
            mountExternal(file) // It uses the EXTERNAL_KEY_PREFIX key family internally.
            return
        }
        val node = _state.value.rows.filterIsInstance<FileNode>().firstOrNull { it.key == key }
        if (node != null) {
            expanded.remove(key) // toggleFile toggles; ensure it starts collapsed.
            toggleFile(node)
        }
    }

    /**
     * Materialize an archive into the cache directory (RAR and nested archives
     * require this), keyed by source + path + size + mtime so re-expanding the same
     * archive doesn't re-download / re-extract; local files are returned as-is.
     *
     * [rowKey], when given, is polled between chunks: if the row that asked for this
     * has since been abandoned (collapsed, or superseded by a later tap — see
     * [abandoned]), the copy aborts instead of downloading/decompressing a — possibly
     * huge — nested archive to completion for a row nobody is waiting on anymore.
     */
    private fun localArchive(file: XFile, rowKey: String? = null): XFile {
        if (file.scheme == ArchiveFileSystem.HOST_SCHEME) return file
        val dir = com.twig.app.CacheDirs.dir(getApplication(), com.twig.app.CacheDirs.ARCHIVES)
        val key = Integer.toHexString(
            "${file.scheme}:${file.path}:${file.size}:${file.lastModified}".hashCode(),
        )
        val out = java.io.File(dir, "${key}_${file.name}")
        // ★ A size of 0 must not be used as the validity check: bz2 cannot report its
        // uncompressed size (the format has no such field), so a tar inside one is a host
        // of "unknown size" — comparing against 0 would re-inflate the whole thing on
        // every expand. Dropping that check is safe because the cache key already covers
        // source + path + size + mtime: a different host is a different key.
        if (!out.exists() || (file.size > 0 && out.length() != file.size)) {
            val tmp = java.io.File(dir, "$key.part")
            try {
                // 1MB buffer (the default 8KB turns network round-trip latency into many small reads; see CopyEngine);
                // also our own read/write loop rather than InputStream.copyTo so there's a spot to poll abandonment.
                FsRegistry.of(file).openInput(file).use { ins ->
                    tmp.outputStream().use { outs ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            if (rowKey != null && rowKey in abandoned) {
                                throw java.io.IOException("archive materialization abandoned: $rowKey")
                            }
                            val n = ins.read(buf)
                            if (n < 0) break
                            outs.write(buf, 0, n)
                        }
                    }
                }
                out.delete()
                if (!tmp.renameTo(out)) throw FsException(str(R.string.err_cache_archive_failed, file.name))
            } finally {
                tmp.delete()
            }
            com.twig.app.CacheDirs.trim(dir, keep = out)
        }
        out.setLastModified(System.currentTimeMillis()) // Mark one LRU use.
        return XFile(
            scheme = ArchiveFileSystem.HOST_SCHEME, path = out.path,
            isDir = false, size = out.length(), lastModified = file.lastModified,
        )
    }

    // ---- Building the tree ----

    private fun rebuild() {
        val rows = ArrayList<Node>()
        attachedKeys.clear()
        showHidden = Prefs.showHidden(getApplication()) // Read once per round, not once per directory from SharedPreferences.
        val lock = lockRoot
        if (lock != null) {
            addFile(rows, lock, 0, lockLabel)
        } else {
            val ext = Environment.getExternalStorageDirectory().absolutePath
            // Externally opened archives go at the top — they don't belong to any storage tree, so wedged in the middle they'd be hard to find.
            externalMount?.let { addFile(rows, it, 0, keyPrefix = EXTERNAL_KEY_PREFIX) }
            addGroup(rows, "fav", str(R.string.group_fav))
            // Saved comparisons have no dedicated empty state — if none were ever saved, don't show the group at all (saves space).
            if (CompareStore.all(getApplication()).isNotEmpty()) {
                addGroup(rows, "cmp", str(R.string.compare_saved))
            }
            addFile(rows, XFile("file", ext, isDir = true), 0, str(R.string.group_internal_storage), capacity(ext))
            // SD card / USB drive: wedged between internal storage and the root. The list is cached (see StorageVolumes class comments) — rebuild runs on every render, so don't rescan storageVolumes here.
            for (v in StorageVolumes.cached(getApplication())) {
                addFile(rows, XFile("file", v.path, isDir = true), 0, v.label, capacity(v.path))
            }
            addFile(rows, XFile("file", "/", isDir = true), 0, str(R.string.group_root), capacity("/"))
            addGroup(rows, "lan", str(R.string.group_lan))
            addGroup(rows, "ftp", "FTP")
            addGroup(rows, "sftp", "SSH (SFTP)")
            addGroup(rows, "dav", "WebDAV")
            addGroup(rows, "s3", "S3")
            addGroup(rows, "media", str(R.string.group_media))
            addGroup(rows, "saf", str(R.string.group_saf))
            // Apps management: virtual read-only source, like a storage node it's an in-place expandable tree (installed / system).
            addFile(
                rows,
                XFile(AppsFileSystem.SCHEME, "/", isDir = true, canWrite = false),
                0,
                str(R.string.apps_root),
            )
        }
        // Auto-close and discard the cache of info cards whose containing directory was collapsed (so no card row was built).
        val liveInfo = rows.mapNotNullTo(HashSet()) { (it as? InfoNode)?.let { n -> fileKey(n.file) } }
        infoOpen.retainAll(liveInfo)
        infoCache.keys.retainAll(infoOpen)
        infoTab.keys.retainAll(infoOpen)
        hashCache.keys.retainAll(infoOpen)
        // If a card is gone (closed / collapsed), stop scanning its directory.
        dirScan.keys.toList().forEach { k -> if (k !in infoOpen) dirScan.remove(k)?.job?.cancel() }
        // Auto-cancel and discard search tasks whose root row is no longer visible (ancestor collapsed), same as info cards.
        val liveSearch = rows.mapNotNullTo(HashSet()) { (it as? SearchNode)?.let { n -> fileKey(n.root) } }
        searchState.keys.toList().forEach { k ->
            if (k !in liveSearch) {
                searchState.remove(k)?.job?.cancel()
                if (currentKey == "search:$k") currentKey = null // The highlight used to point at it; if the row is gone, don't leave it dangling.
            }
        }
        _state.value = State(rows, currentDir, currentKey, null, null, restoring, scrollKey)
    }

    private fun addFile(
        rows: MutableList<Node>,
        file: XFile,
        depth: Int,
        label: String? = null,
        capacity: String? = null,
        /** See [FileNode.keyPrefix]; the whole subtree must carry it, otherwise archive entries would clash with the row at their original location. */
        keyPrefix: String = "",
    ) {
        val key = keyPrefix + fileKey(file)
        val expandable = file.isDir || expandableArchive(file, key)
        if (expandable) keyFile[key] = file
        val exp = expandable && expanded.contains(key)
        // A directory with a search result hangs shows the chevron as "expanded" (even if not actually expanded) — visually echoing the search-result virtual directory below; this only affects the icon, the actual children list is still only fetched/rendered when exp is true, so a collapsed directory that starts a search won't also show its original files/dirs below.
        rows += FileNode(
            file, depth, expandable, exp || searchState.containsKey(key),
            label, capacity, busy(key), keyPrefix,
        )
        addAttachments(rows, file, depth)
        if (exp) {
            // Put the git virtual root first, no need to dig through a pile of real files.
            // Don't use the display name frozen at registration in gitInfo (after switching branches it'd keep showing the old one); read GitFileSystem.displayName instead — it follows the latest branch in statusCache.
            gitInfo[key]?.let { (s, gl) ->
                val live = (runCatching { FsRegistry.of(s) }.getOrNull() as? GitFileSystem)?.displayName ?: gl
                addFile(rows, XFile(s, "/", isDir = true, displayName = live, canWrite = false), depth + 1)
            }
            visible(children[key]).forEach { addFile(rows, it, depth + 1, keyPrefix = keyPrefix) }
            if (resticRepos.contains(key)) addRestic(rows, file, depth + 1)
        }
    }

    /**
     * Attached rows hanging under a directory row: info cards + search-result virtual
     * directory (both render regardless of whether the directory is expanded; when
     * expanded they come before its children). [depth] is the row's own depth.
     * Favorite rows don't go through [addFile], but their menu can still open
     * properties / start a search — so this method is shared between the two.
     */
    private fun addAttachments(rows: MutableList<Node>, file: XFile, depth: Int) {
        val key = fileKey(file)
        // The same directory may appear multiple times in the tree (favorite + its
        // original position in the storage tree, the same directory in search results,
        // …). Attached-row keys are keyed only by directory, and re-attaching would
        // collide keys in DiffUtil — only honor the first occurrence in this round.
        if (!attachedKeys.add(key)) return
        if (infoOpen.contains(key)) {
            rows += InfoNode(
                file, depth + 1, infoCache[key],
                tab = infoTab[key] ?: 0,
                hashRows = hashCache[key],
                hashing = hashing.contains(key),
                dirStat = dirScan[key]?.stat,
                scanning = dirScan[key]?.scanning == true,
            )
        }
        searchState[key]?.let { st ->
            rows += SearchNode(
                file, depth + 1, st.pattern, st.scanning,
                matchedFiles = st.results.count { !it.isDir },
                matchedDirs = st.results.count { it.isDir },
            )
            st.results.forEach { addFile(rows, it, depth + 2) }
        }
    }

    /**
     * Directory detected to have a .git: build the data source, register the virtual
     * FileSystem, and carry back what needs registering (**IO thread**).
     *
     * Registration in [FsRegistry] stays here rather than moving to the main thread —
     * building GitData itself reads .git (SSH repos also run a remote exec) and must
     * be on IO; FsRegistry itself is thread-safe. What moves is just the writes to
     * the VM's reverse-lookup tables, see [applyListing].
     */
    private fun buildGit(dir: XFile): Listing.Git? {
        val key = fileKey(dir)
        if (gitInfo.containsKey(key)) return null // Already registered — don't rebuild.
        val (data, cheap) = runCatching { com.twig.app.gitDataFor(dir) }.getOrNull() ?: return null
        val branch = runCatching { data.branch() }.getOrDefault("?")
        val scheme = "git" + Integer.toHexString(key.hashCode())
        val label = "Git ($branch)"
        // host passes dir: the virtual tree's "worktree" needs it to map the repo's
        // stored absolute paths back here (see GitFileSystem).
        FsRegistry.register(GitFileSystem(getApplication(), data, scheme, label, host = dir))
        return Listing.Git(key, dir, scheme, label, cheap)
    }

    private fun addRestic(rows: MutableList<Node>, repoDir: XFile, depth: Int) {
        val nodeKey = "restic:${fileKey(repoDir)}"
        // "Unlocked" = the repo is open **AND** the snapshot list was also fetched.
        // If we only checked resticScheme, a single "open succeeded, list snapshots
        // failed" would pin this row as unlocked forever, yet tapping it expands to
        // nothing (see [forgetRestic]); checking both means a half-done state
        // re-walks the unlock path on tap, self-healing.
        val unlocked = resticScheme.containsKey(nodeKey) && children.containsKey(nodeKey)
        val exp = expanded.contains(nodeKey)
        rows += ResticNode(repoDir, depth, exp, unlocked, busy(nodeKey))
        if (exp && unlocked) {
            // fs-restic is a pure JVM module with no string resources available, so
            // the "latest" virtual directory (path=="/latest") only carries the raw,
            // language-neutral name; localized text is owned here at this one render point.
            visible(children[nodeKey]).forEach { f ->
                val shown = if (f.path == "/latest") f.copy(displayName = str(R.string.restic_latest)) else f
                addFile(rows, shown, depth + 1)
            }
        }
    }

    private fun addGroup(rows: MutableList<Node>, id: String, label: String) {
        val key = "g:$id"
        val exp = expanded.contains(key)
        rows += GroupNode(id, label, exp)
        if (!exp) return
        val app = getApplication<Application>()
        when (id) {
            "lan", "ftp", "sftp", "dav", "s3" -> {
                val type = when (id) {
                    "lan" -> "smb"
                    "dav" -> "webdav"
                    else -> id
                }
                for (conn in ConnectionStore.all(app).filter { it.type == type }) addServer(rows, conn)
                val label = when (type) {
                    "smb" -> str(R.string.action_add_smb)
                    "ftp" -> str(R.string.action_add_ftp)
                    "sftp" -> str(R.string.action_add_sftp)
                    "s3" -> str(R.string.action_add_s3)
                    else -> str(R.string.action_add_webdav)
                }
                rows += ActionNode("add_$type", label)
                // Another Twig's WiFi sharing is WebDAV — scanning saves it directly as a WebDAV connection.
                if (type == "webdav") rows += ActionNode("add_scan_twig", str(R.string.share_scan))
            }
            // The only group holding two types: Jellyfin and Emby endpoints share the
            // same origin and the same FileSystem implementation; splitting them into
            // two groups would just add a near-identical layer to the sidebar.
            "media" -> {
                for (conn in ConnectionStore.all(app).filter { it.isMediaServer() }) addServer(rows, conn)
                rows += ActionNode("add_jellyfin", str(R.string.action_add_jellyfin))
                rows += ActionNode("add_emby", str(R.string.action_add_emby))
            }
            "saf" -> {
                safRoots().forEach { addFile(rows, it, 1) }
                rows += ActionNode("add_saf", str(R.string.action_add_saf))
            }
            "fav" -> {
                val favs = FavoritesStore.all(app)
                if (favs.isEmpty()) rows += ActionNode("fav_hint", str(R.string.fav_hint))
                else {
                    val conns = ConnectionStore.all(app).associateBy { it.label() }
                    for (fav in favs) addFavorite(rows, fav, conns)
                }
            }
            "cmp" -> {
                for (s in CompareStore.all(app)) rows += CompareNode(s, depth = 1)
            }
        }
    }

    /**
     * Authorized document trees, one row each (in the order the system returns them).
     *
     * Row names come in two flavors: **third-party app providers use the app name**
     * (see [SafFileSystem.providerApp] — their document id is a real path, with a
     * last-segment like "home" that's neither recognizable nor nice-looking); the
     * rest (external storage / downloads — system providers) use the document id's last segment.
     * ★ When the same app authorizes multiple trees, just the app name can't tell
     * them apart — append the directory name.
     */
    private fun safRoots(): List<XFile> {
        val app = getApplication<Application>()
        val roots = app.contentResolver.persistedUriPermissions.mapNotNull { perm ->
            runCatching {
                val docId = DocumentsContract.getTreeDocumentId(perm.uri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(perm.uri, docId)
                // The document id can be "primary:DCIM" or a real path; last-segment cut on both separators.
                val tail = docId.substringAfterLast('/').substringAfterLast(':').ifEmpty { docId }
                val file = XFile("saf", docUri.toString(), isDir = true, displayName = tail)
                Triple(file, SafFileSystem.providerApp(app, file)?.label, tail)
            }.getOrNull()
        }
        return roots.map { (file, appLabel, tail) ->
            when {
                appLabel == null -> file
                roots.count { it.second == appLabel } > 1 -> file.copy(displayName = "$appLabel · $tail")
                else -> file.copy(displayName = appLabel)
            }
        }
    }

    private fun addFavorite(rows: MutableList<Node>, fav: Favorite, conns: Map<String, SavedConnection>) {
        val key = "fav:${fav.id}"
        val exp = expanded.contains(key)
        val conn = when (fav.kind) {
            "conn" -> conns[fav.connLabel]
            "restic" -> conns[fav.repoConnLabel]
            else -> null
        }
        rows += FavoriteNode(fav, conn, depth = 1, expanded = exp, connecting = busy(key))
        // Info cards / search results hang under it like a regular directory row (key based on the actual directory the favorite points at).
        keyFile[key]?.let { dir -> addAttachments(rows, dir, depth = 1) }
        if (exp) {
            visible(children[key]).forEach { addFile(rows, it, 2) }
            // The favorite's directory itself is a restic repo root (fav.kind is
            // local/conn, not yet through resolveFavorite's restic branch for unlock):
            // consistent with regular browsing, add a "restic repository" hint row
            // to allow expand-to-unlock.
            keyFile[key]?.let { dir -> if (resticRepos.contains(fileKey(dir))) addRestic(rows, dir, 2) }
        }
    }

    private fun addServer(rows: MutableList<Node>, conn: SavedConnection) {
        val key = "s:${conn.label()}"
        val exp = expanded.contains(key)
        rows += ServerNode(conn, exp, busy(key), serverInfo[conn.label()])
        // Info cards / search results hang under it like a regular directory row (key based on the server root directory), same as [addFavorite].
        keyFile[key]?.let { dir -> addAttachments(rows, dir, depth = 1) }
        if (exp) visible(children[key]).forEach { addFile(rows, it, 2) }
    }

    // ---- Utilities ----

    /** Get a string resource (multi-language: follows AppCompatDelegate's current locale). */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun capacity(path: String): String? = runCatching {
        val st = StatFs(path)
        val total = st.blockCountLong * st.blockSizeLong
        val avail = st.availableBlocksLong * st.blockSizeLong
        str(R.string.capacity_format, Format.size(avail), Format.size(total))
    }.getOrNull()

    private fun emitError(msg: String?) {
        _state.value = _state.value.copy(error = msg ?: str(R.string.err_operation_failed))
    }

    /**
     * Unified exit point for failed expansions: an encrypted archive's missing
     * password isn't an "error" — it needs to ask the user for the password; that
     * path goes through [State.passwordFor], don't toast the English exception
     * message from fs-archive here.
     */
    private fun emitFailure(t: Throwable, file: XFile?) {
        if (t is ArchivePasswordException && file != null) {
            _state.value = _state.value.copy(
                passwordFor = file,
                error = if (t.wrong) str(R.string.archive_wrong_pw) else null,
            )
        } else {
            emitError(t.message)
        }
    }

    /** When collapsing a directory node ([FileNode]), collect its (still-expanded sub-dirs / archives, recursive) cached children and hand them to [Thumbs.cancelPending] to drop not-yet-started thumbnail jobs from the thread pool queue. */
    private fun descendantFiles(dir: XFile, out: MutableList<XFile> = mutableListOf()): List<XFile> =
        descendantFilesByKey(fileKey(dir), out)

    /** Same as [descendantFiles], but starting from any node key — server roots / restic repo roots have keys that aren't `fileKey()` style ("s:" / "restic:" prefix), yet children are still cached in the same [children] table. */
    private fun descendantFilesByKey(rootKey: String, out: MutableList<XFile> = mutableListOf()): List<XFile> {
        val kids = children[rootKey] ?: return out
        for (c in kids) {
            out.add(c)
            if (expanded.contains(fileKey(c))) descendantFiles(c, out)
        }
        return out
    }

    companion object {
        /**
         * The key prefix for the externally-opened ("Open with Twig") subtree hanging
         * off the top of the tree. It's **two rows** alongside the file's original
         * row in the storage tree; without distinguishing them they'd collide on
         * DiffUtil's keys.
         */
        const val EXTERNAL_KEY_PREFIX = "x:"

        /** See [TreeKeys.fileKey]; this same-name entry point stays so the dozens of call sites don't all need changing. */
        fun fileKey(file: XFile): String = TreeKeys.fileKey(file)
    }
}
