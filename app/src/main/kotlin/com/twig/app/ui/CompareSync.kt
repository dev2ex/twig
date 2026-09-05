package com.twig.app.ui

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.CompareSession
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.resolveCompareSide
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Directory sync: **plan** and execution. There are two entry points — the compare page's
 * top bar and the long-press menu on a "compare favorite" row — so the classification
 * logic cannot live inside [CompareActivity]: that side walks the already-scanned result
 * tree, while the favorite row has no UI at all and has to scan on the fly ([buildSyncPlan]
 * consumes [scanCompare]'s event stream directly). Both paths share [syncActionFor] and
 * [targetIsNewer]; otherwise "the same direction and the same files but two entry points
 * produce different results" is bound to happen eventually.
 *
 * The direction is passed in explicitly by the caller (0 = left, 1 = right) and **no
 * longer follows the active side** — "sync to the other side" requires the user to see
 * which side is highlighted first, and this is an irreversible operation.
 */

/** How one item should be handled in a sync. */
enum class SyncAct {
    /** Source-only or differing-on-both files: push to the target side. Directories are taken as a whole, no descent. */
    COPY,

    /** Target-side-only: only deleted in mirror sync, kept in incremental sync (filtered by the switch at execution time; see [SyncPlan.deletesFor]). */
    DELETE,

    /** Directory: descend to inspect children. */
    DESCEND,

    /** Files identical on both sides: do nothing. */
    SKIP,
}

/**
 * How to handle one item. **Does not carry the "incremental or not" decision**: target-side-
 * only items are always recorded as [SyncAct.DELETE], and the checkbox in the confirmation
 * dialog decides whether to actually delete — otherwise changing the toggle in the dialog
 * would force a full-tree rescan.
 */
fun syncActionFor(state: PairState, isDir: Boolean, from: Int): SyncAct {
    val srcOnly = if (from == 0) PairState.LEFT_ONLY else PairState.RIGHT_ONLY
    val dstOnly = if (from == 0) PairState.RIGHT_ONLY else PairState.LEFT_ONLY
    return when {
        state == srcOnly -> SyncAct.COPY
        state == dstOnly -> SyncAct.DELETE
        isDir -> SyncAct.DESCEND // DIFF/SCANNING/SAME directories all descend; judgement is left to the children
        state == PairState.DIFF -> SyncAct.COPY
        else -> SyncAct.SKIP
    }
}

/**
 * Whether the target-side copy is **newer** than the source-side. The test must go through
 * [sameTime] rather than a bare `>`: mtime precision varies wildly across sources (see
 * [CompareOptions.timeToleranceMs]); with bare `>`, almost every item in a local↔FTP
 * comparison would be reported as "target is newer", and the warning would mean nothing.
 */
fun targetIsNewer(src: XFile, dst: XFile, o: CompareOptions): Boolean =
    !sameTime(src.lastModified, dst.lastModified, o) && dst.lastModified > src.lastModified

/**
 * What one item needs to do. [key] is the path relative to both roots; the target
 * directory is computed from it plus the target root ([compareDestDir]). For a DELETE
 * item, [file] is the **target-side** copy; [targetNewer] does not apply to it.
 */
class SyncItem(val key: String, val file: XFile, val targetNewer: Boolean = false)

/** What one sync needs to do. The incremental / overwrite toggles are only filtered **at execution time**; the plan itself is complete. */
class SyncPlan(val copies: List<SyncItem>, val deletes: List<SyncItem>) {
    /** Items where the target-side copy is newer (not overwritten by default; see the confirmation dialog). */
    val newer: List<SyncItem> = copies.filter { it.targetNewer }

    fun copiesFor(overwriteNewer: Boolean): List<SyncItem> =
        if (overwriteNewer) copies else copies.filterNot { it.targetNewer }

    fun deletesFor(incremental: Boolean): List<SyncItem> = if (incremental) emptyList() else deletes

    fun isEmpty(incremental: Boolean, overwriteNewer: Boolean) =
        copiesFor(overwriteNewer).isEmpty() && deletesFor(incremental).isEmpty()
}

/** Whether [dirKey] falls inside the subtree rooted at [prefix] (inclusive). */
private fun under(dirKey: String, prefix: String) =
    dirKey == prefix || dirKey.startsWith("$prefix/")

/**
 * Scan once and compute the sync plan directly (used by the "compare favorite" row path,
 * which has no result tree).
 *
 * Only consumes [CompareEvent.Children]: a directory's state in the preorder event is
 * SCANNING (except for single-side-only ones), which maps neatly to "descend"; but a
 * single-side-only directory is locked in the moment it appears, so it is taken as a
 * whole and its subtree is [closed] off — otherwise the children would be recorded again,
 * which would not match the [CompareActivity] side's "hand the whole directory to
 * CopyEngine and let it recurse" convention, and the progress bar's item count would
 * multiply several times over.
 */
suspend fun buildSyncPlan(
    left: XFile,
    right: XFile,
    options: CompareOptions,
    from: Int,
    io: CoroutineDispatcher = Dispatchers.IO,
    onProgress: (CompareEvent.Progress) -> Unit = {},
): SyncPlan {
    val copies = ArrayList<SyncItem>()
    val deletes = ArrayList<SyncItem>()
    val closed = ArrayList<String>()
    scanCompare(left, right, options, io).collect { ev ->
        when (ev) {
            is CompareEvent.Progress -> onProgress(ev)
            is CompareEvent.Children -> {
                if (closed.any { under(ev.dirKey, it) }) return@collect
                for (e in ev.rows) {
                    val key = if (ev.dirKey.isEmpty()) e.name else "${ev.dirKey}/${e.name}"
                    val src = if (from == 0) e.left else e.right
                    val dst = if (from == 0) e.right else e.left
                    when (syncActionFor(e.state, e.isDir, from)) {
                        SyncAct.COPY -> {
                            if (src != null) {
                                copies += SyncItem(key, src, dst != null && targetIsNewer(src, dst, options))
                                if (e.isDir) closed += key
                            }
                        }
                        SyncAct.DELETE -> {
                            if (dst != null) {
                                deletes += SyncItem(key, dst)
                                if (e.isDir) closed += key
                            }
                        }
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }
    }
    return SyncPlan(copies, deletes)
}

/**
 * Target directory = target root + the item's relative parent path in the tree. A deep
 * file therefore lands in the same-named subdirectory on the other side, rather than
 * piling up at the root.
 */
fun compareDestDir(destRoot: XFile, key: String): XFile {
    val relParent = key.substringBeforeLast('/', "")
    val base = destRoot.path.trimEnd('/')
    val path = if (relParent.isEmpty()) base.ifEmpty { "/" } else "$base/$relParent"
    return XFile(destRoot.scheme, path, isDir = true)
}

/** Build the target directory level by level (use it directly if it already exists); returns null on failure. **Talks to the network; must be called on a background thread**. */
fun ensureDir(dir: XFile): XFile? = runCatching {
    val fs = FsRegistry.of(dir)
    if (fs.exists(dir)) return@runCatching fs.resolve(dir.path)
    val parts = dir.path.trim('/').split('/').filter { it.isNotEmpty() }
    var cur = fs.resolve("/")
    for (p in parts) {
        val next = XFile(dir.scheme, "${cur.path.trimEnd('/')}/$p", isDir = true)
        cur = if (fs.exists(next)) fs.resolve(next.path) else fs.mkdir(cur, p)
    }
    cur
}.getOrNull()

/**
 * The sync confirmation dialog. Three things must be in front of the user **before** OK
 * is pressed: how many items to push, whether to delete target-side extras, and whether
 * to overwrite the items where the target side is newer. The latter two are irreversible
 * and cannot be decided by a default for the user — so "delete extras" follows
 * [CompareOptions.incrementalSync] (default incremental = don't delete), and
 * "overwrite newer" starts at **unchecked** every time and only appears when there are
 * actually newer items.
 *
 * [onOptions] returns the modified options (the incremental toggle takes effect in place).
 * **We do not persist here**: the compare page wants the global last-used options stored,
 * while the "compare favorite" path wants to store **that favorite's own** options, and
 * only the caller knows where to store. [onStarted] gives back the keys involved once
 * the transfer has actually started; the compare page uses them for partial reclassification.
 */
fun confirmSync(
    ctx: Context,
    scope: CoroutineScope,
    dstRoot: XFile,
    to: Int,
    plan: SyncPlan,
    options: CompareOptions,
    onOptions: (CompareOptions) -> Unit = {},
    onStarted: (List<String>) -> Unit = {},
) {
    // ★ Read-only sources (media servers / restic / 7z / RAR / git viewer...) cannot be
    // sync targets. The direction is chosen by the user themselves now, so blocking here
    // is better than letting them fill in the confirmation dialog only to see an error
    // afterwards; both entry points share this single check
    if (!dstRoot.isWritableDir()) {
        toastOn(ctx, ctx.getString(R.string.msg_dest_not_writable))
        return
    }
    val toName = ctx.getString(if (to == 0) R.string.compare_side_left else R.string.compare_side_right)
    val d = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * d).toInt()

    val summary = TextView(ctx)
    fun refreshSummary(overwriteNewer: Boolean) {
        // ★ The count displayed must be the **items that will actually be pushed**: when
        // "overwrite newer" is unchecked, those K items are dropped; showing the total
        // all the time makes the progress bar's number not match what the user sees
        summary.text = ctx.getString(
            R.string.compare_sync_confirm, plan.copiesFor(overwriteNewer).size, toName,
        )
    }
    val cbIncremental = CheckBox(ctx).apply {
        text = ctx.getString(R.string.compare_sync_incremental, toName, plan.deletes.size)
        isChecked = options.incrementalSync
        visibility = if (plan.deletes.isEmpty()) View.GONE else View.VISIBLE
    }
    val cbOverwrite = CheckBox(ctx).apply {
        text = ctx.getString(R.string.compare_sync_overwrite_newer, toName, plan.newer.size)
        isChecked = false
        visibility = if (plan.newer.isEmpty()) View.GONE else View.VISIBLE
        setOnCheckedChangeListener { _, on -> refreshSummary(on) }
    }
    refreshSummary(false)
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(8), px(20), px(8))
        addView(summary)
        addView(cbIncremental)
        addView(cbOverwrite)
    }

    AlertDialog.Builder(ctx)
        .setTitle(ctx.getString(R.string.compare_sync_to, toName))
        .setView(box)
        .setNegativeButton(R.string.dialog_cancel, null)
        .setPositiveButton(R.string.dialog_ok) { _, _ ->
            val incremental = cbIncremental.isChecked
            if (incremental != options.incrementalSync) onOptions(options.copy(incrementalSync = incremental))
            runSync(ctx, scope, dstRoot, plan, incremental, cbOverwrite.isChecked, onStarted)
        }
        .show()
}

/**
 * Execute: first delete target-side extras (mirror mode), then start the transfer session.
 *
 * **Deletes are run before the transfer on purpose**: the two sets are disjoint (the
 * deletes are target-side-only items), and the transfer is an async session; doing
 * deletes on its completion callback would only make a "half-cancelled" state even
 * harder to describe.
 *
 * ★ The conflict decision defaults to [CopyEngine.Decision.OVERWRITE]: every DIFF item
 * in a sync already exists on the target side; without a default the user would have to
 * answer a conflict dialog for every single one (hundreds of items would be hundreds of
 * prompts). Overwrite-or-not was already asked in the confirmation dialog — that is where
 * it should be asked.
 */
private fun runSync(
    ctx: Context,
    scope: CoroutineScope,
    dstRoot: XFile,
    plan: SyncPlan,
    incremental: Boolean,
    overwriteNewer: Boolean,
    onStarted: (List<String>) -> Unit,
) {
    val copies = plan.copiesFor(overwriteNewer)
    val deletes = plan.deletesFor(incremental)
    if (copies.isEmpty() && deletes.isEmpty()) {
        toastOn(ctx, ctx.getString(R.string.compare_nothing_to_sync))
        return
    }
    val app = ctx.applicationContext
    scope.launch {
        // ★ runCatching per item: one delete failing (permission / busy) should not stall
        // the rest of the batch — mirror-sync deletes are independent, and quitting
        // halfway would just leave behind an unexplained mess of leftovers
        val failed = withContext(Dispatchers.IO) {
            deletes.count { runCatching { FsRegistry.of(it.file).delete(it.file) }.isFailure }
        }
        if (failed > 0) toastOn(ctx, ctx.getString(R.string.compare_sync_delete_failed, failed))
        if (copies.isEmpty()) {
            onStarted(deletes.map { it.key })
            if (failed == 0) toastOn(ctx, ctx.getString(R.string.msg_done))
            return@launch
        }
        val pairs = withContext(Dispatchers.IO) {
            copies.mapNotNull { item ->
                ensureDir(compareDestDir(dstRoot, item.key))?.let { item.file to it }
            }
        }
        if (pairs.isEmpty()) {
            toastOn(ctx, ctx.getString(R.string.msg_dest_not_writable))
            return@launch
        }
        val session = Transfers.Session(
            Transfers.Work.Sync(pairs, move = false),
            R.string.progress_copy,
            Format.pathLabel(dstRoot), FileIcons.sourceIconRes(dstRoot.scheme), null,
        ).apply { applyAll = CopyEngine.Decision.OVERWRITE }
        val started = runCatching { Transfers.start(app, session) }
            .getOrElse { toastOn(ctx, it.message ?: ctx.getString(R.string.err_failed)); return@launch }
        if (!started) return@launch toastOn(ctx, ctx.getString(R.string.transfer_busy))
        onStarted((copies + deletes).map { it.key })
    }
}

private fun toastOn(ctx: Context, msg: String) =
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

/**
 * Sync from the "compare favorite" row's long-press menu: **does not enter the compare
 * page**, scans once in the background and goes straight to the confirmation dialog.
 *
 * A cancellable dialog must be shown during scanning: full-tree recursion on network
 * sources takes minutes (see [scanCompare]'s comment); showing nothing is
 * indistinguishable from "I tapped and nothing happened". Cancel = cancel the coroutine;
 * the one round-trip blocked inside list() still runs to completion before exiting, the
 * same convention as the scan page.
 *
 * [onOptions] returns the modified options — the incremental toggle is **per-compare-
 * favorite** ([CompareSession.options]); changing it in the dialog must be stored back
 * into that favorite, not written into the global last-used options.
 * [onStarted] is called once the transfer has actually started; the caller uses it to
 * attach the progress dialog (without it, only the notification shows activity, and the
 * UI is indistinguishable from "I tapped and nothing happened").
 */
fun syncFromSaved(
    ctx: Context,
    scope: CoroutineScope,
    session: CompareSession,
    to: Int,
    onOptions: (CompareOptions) -> Unit = {},
    onStarted: () -> Unit = {},
) {
    val toName = ctx.getString(if (to == 0) R.string.compare_side_left else R.string.compare_side_right)
    val text = TextView(ctx).apply {
        val d = ctx.resources.displayMetrics.density
        setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        text = ctx.getString(R.string.compare_sync_scanning, 0, 0)
    }
    var job: Job? = null
    val box = AlertDialog.Builder(ctx)
        .setTitle(ctx.getString(R.string.compare_sync_to, toName))
        .setView(text)
        .setCancelable(false)
        .setNegativeButton(R.string.dialog_cancel) { _, _ -> job?.cancel() }
        .show()

    job = scope.launch {
        val roots = withContext(Dispatchers.IO) {
            resolveCompareSide(ctx, session.left) to resolveCompareSide(ctx, session.right)
        }
        val (l, r) = roots
        if (l == null || r == null) {
            box.dismiss()
            return@launch toastOn(ctx, ctx.getString(R.string.compare_open_failed))
        }
        val from = 1 - to
        val plan = runCatching {
            buildSyncPlan(l, r, session.options, from) { p ->
                text.text = ctx.getString(R.string.compare_sync_scanning, p.entries, p.diffs)
            }
        }.getOrElse {
            box.dismiss()
            if (it is CancellationException) throw it
            return@launch toastOn(ctx, it.message ?: ctx.getString(R.string.err_failed))
        }
        box.dismiss()
        if (plan.copies.isEmpty() && plan.deletes.isEmpty()) {
            return@launch toastOn(ctx, ctx.getString(R.string.compare_nothing_to_sync))
        }
        confirmSync(ctx, scope, if (to == 0) l else r, to, plan, session.options, onOptions) { onStarted() }
    }
}
