package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Comparison verdict for a pair of entries. */
enum class PairState {
    /** Present on both sides and considered equal. */
    SAME,

    /** Present on both sides but different (size / time / content). */
    DIFF,

    /** Exists only on the left side. */
    LEFT_ONLY,

    /** Exists only on the right side. */
    RIGHT_ONLY,

    /** Directory: subtree not yet scanned, aggregate state unknown. */
    SCANNING,
}

/**
 * Classification options. The defaults are a set that "does not misreport across sources";
 * see each field for the rationale — a bare `lastModified ==` comparison would light up
 * nearly everything in local↔FTP / local↔zip comparisons.
 */
data class CompareOptions(
    /**
     * Time tolerance. mtime precision varies wildly across sources: local ms, SMB 100 ns,
     * WebDAV's Last-Modified seconds, FTP often seconds or minutes, **zip entries are
     * DOS time (2-second granularity)**, and exFAT/FAT32 the same 2 seconds. The default
     * of 2 s just covers the coarsest of those.
     */
    val timeToleranceMs: Long = 2_000L,
    /**
     * Allow whole-hour offsets. FTP's MDTM/LIST often cannot tell GMT from the server's
     * local time, so an entire directory can be off by several hours — that is not a
     * content change, it is a timezone mismatch.
     */
    val allowHourShift: Boolean = true,
    /** Ignore case when pairing: ext4 is case-sensitive, SMB/exFAT are not, otherwise README.md and Readme.md would each be left as orphans. */
    val ignoreCase: Boolean = true,
    /**
     * Size cap for content comparison, **split into local and network tiers**: reading
     * locally costs almost nothing, while the network has to download both sides in full,
     * which conflicts with the "avoid full reads" principle, so it is off by default (0).
     * If either side of a pair exceeds its tier's cap, that pair is not compared by
     * content and falls back to time-based classification.
     */
    val contentLimitLocal: Long = 1L shl 20,
    val contentLimitNetwork: Long = 0L,
    /**
     * Read content only when **the time differs**: if size and time both match, classify
     * directly as same without reading. The vast majority of files fall into this bucket,
     * so the savings are substantial; turning it off means reading every pair whenever
     * sizes match, catching "timestamp preserved but content changed" (at the cost of a
     * full read per pair).
     */
    val contentOnlyIfTimeDiffers: Boolean = true,
    /** Exclude rules; see [matchesExclude]; pruned during the scan, not filtered after. */
    val excludes: List<String> = emptyList(),
    /**
     * Incremental sync: only push source-only and differing-on-both items; **target-side
     * extras are kept**. Turning it off switches to mirror sync — target-side-only
     * files/directories are also deleted, leaving both sides identical at the end. Default
     * is incremental: deletion is irreversible, and "the other side still has other stuff"
     * is extremely common in two-way-used directories.
     */
    val incrementalSync: Boolean = true,
) {
    /** Whether content comparison is enabled on either side. */
    val contentEnabled: Boolean get() = contentLimitLocal > 0 || contentLimitNetwork > 0
}

/** One entry from the scan: at least one side is non-null. */
data class CompareEntry(
    /** Name used for pairing and display (taken from whichever side has it). */
    val name: String,
    val left: XFile?,
    val right: XFile?,
    val isDir: Boolean,
    val state: PairState,
) {
    /** Representative entry on either side; used for icon, extension, etc. */
    val any: XFile get() = left ?: right!!
}

/** Incremental events during a scan; a parent directory's [Children] always arrives before its descendants' events. */
sealed interface CompareEvent {
    /** A directory's children have been listed (preorder, visible immediately). [dirKey] is the path relative to the root; the root is the empty string. */
    data class Children(val dirKey: String, val rows: List<CompareEntry>) : CompareEvent

    /** A directory's whole subtree has been scanned; report the aggregate state (DIFF if any descendant is not SAME). */
    data class DirDone(val dirKey: String, val state: PairState) : CompareEvent

    /** Progress: compared entry count, diff count among them, and the relative path currently being scanned. */
    data class Progress(val entries: Int, val diffs: Int, val path: String) : CompareEvent

    /**
     * An entry was blocked by an exclude rule. Recorded so that "delete this rule and
     * only restore the entries it actually blocked" works without a full-tree rescan.
     * [dirKey] is the containing directory, [name] is the entry name.
     */
    data class Excluded(val dirKey: String, val name: String, val rule: String) : CompareEvent

    /** The entry cap was hit; the result is incomplete. */
    data object Truncated : CompareEvent
}

/** Cycle / blow-up guard: same magnitude of guardrails as [scanSearch] and [scanDirStat]. */
private const val COMPARE_MAX_DEPTH = 64
private const val COMPARE_MAX_ENTRIES = 100_000
private const val COMPARE_CONTENT_BUF = 64 * 1024

/**
 * Exclude-rule matching. Rules containing `/` match against the **relative path** as a whole
 * (e.g. "build" + slash + "*.o"); otherwise they match against the **file name** (`*.tmp`).
 * Both use full-name glob semantics — unlike [matchesSearchPattern], which falls back to
 * substring matching when no wildcard is present: a substring match for `build` would
 * wrongly catch `rebuild.log` too.
 */
fun matchesExclude(name: String, relPath: String, patterns: List<String>): Boolean =
    excludeRuleFor(name, relPath, patterns) != null

/**
 * Like [matchesExclude], but returns **which rule** blocked it. When a rule is deleted,
 * this precisely locates "the entries that rule originally blocked" so only those are
 * restored, instead of rescanning the whole tree.
 */
fun excludeRuleFor(name: String, relPath: String, patterns: List<String>): String? =
    patterns.firstOrNull { p ->
        val pat = p.trim()
        if (pat.isEmpty()) false
        else if (pat.contains('/')) globMatches(relPath, pat) else globMatches(name, pat)
    }

/** `*` / `?` full-name glob match (case-insensitive); without wildcards, exact equality is required. */
internal fun globMatches(text: String, pattern: String): Boolean {
    val regex = buildString {
        append("(?i)")
        for (c in pattern) when (c) {
            '*' -> append(".*")
            '?' -> append(".")
            else -> append(Regex.escape(c.toString()))
        }
    }
    return regex.toRegex().matches(text)
}

/**
 * Whether two timestamps count as "the same moment": first check the tolerance, then check
 * whether they differ by only an integer number of hours (timezone mismatch).
 * Half-hour timezones (India +5:30 etc.) are not covered by this rule — in that case
 * raise the tolerance or turn off time-based classification.
 */
internal fun sameTime(a: Long, b: Long, o: CompareOptions): Boolean {
    val d = abs(a - b)
    if (d <= o.timeToleranceMs) return true
    if (!o.allowHourShift) return false
    val hour = 3_600_000L
    val k = (d + hour / 2) / hour
    return k > 0 && abs(d - k * hour) <= o.timeToleranceMs
}

/**
 * Whether the source counts as "local" (decides which content-compare cap applies).
 * Items inside archives are treated as network — the outer archive may live on SMB (same
 * convention as thumbnails).
 *
 * ★ SAF counts as local: the document URI points to a file on this device, and reads go
 * `openFileDescriptor` → fd → pread ([SafFileSystem.randomAccessEfficient] is true), with
 * no IPC round-trip per read — entirely unlike "the outer file may be remote" archives.
 */
internal fun isLocalSide(f: XFile): Boolean =
    f.scheme == "file" || f.scheme == com.twig.app.SafFileSystem.SCHEME

/** Whether this pair is small enough to compare by content; each side applies its own cap; if either side exceeds, do not compare content. */
internal fun contentComparable(l: XFile, r: XFile, o: CompareOptions): Boolean {
    fun limit(f: XFile) = if (isLocalSide(f)) o.contentLimitLocal else o.contentLimitNetwork
    val lim = minOf(limit(l), limit(r))
    return lim > 0 && l.size <= lim && r.size <= lim
}

/**
 * Block-by-block streaming comparison of two files, **returning on the first difference**.
 * Saves more than half versus "hash both sides" — different files usually diverge early,
 * while hashing requires reading both sides fully.
 * A read failure (permission / connection lost) is treated as "different" — better to let
 * the user see a difference and investigate than to falsely report "same".
 */
internal suspend fun contentEquals(l: XFile, r: XFile): Boolean {
    if (l.size != r.size) return false
    return runCatching {
        FsRegistry.of(l).openInput(l).use { a ->
            FsRegistry.of(r).openInput(r).use { b -> streamsEqual(a, b) }
        }
    }.getOrDefault(false)
}

private suspend fun streamsEqual(a: InputStream, b: InputStream): Boolean {
    val ba = ByteArray(COMPARE_CONTENT_BUF)
    val bb = ByteArray(COMPARE_CONTENT_BUF)
    while (true) {
        coroutineContext.ensureActive()
        val na = a.readFully(ba)
        val nb = b.readFully(bb)
        if (na != nb) return false
        if (na == 0) return true
        for (i in 0 until na) if (ba[i] != bb[i]) return false
    }
}

/** Fill buf as much as possible (streams may short-read); returns the number of bytes actually read, 0 = EOF. */
private fun InputStream.readFully(buf: ByteArray): Int {
    var n = 0
    while (n < buf.size) {
        val k = read(buf, n, buf.size - n)
        if (k < 0) break
        n += k
    }
    return n
}

/**
 * Classify two **files** (not directories). If content comparison is enabled and the pair
 * is small enough — and not skipped by [CompareOptions.contentOnlyIfTimeDiffers] — then
 * content is decisive; otherwise size + time.
 * Different sizes means different; in that case time does not need to be checked, nor
 * content read.
 */
internal suspend fun compareFiles(l: XFile, r: XFile, o: CompareOptions): PairState {
    if (l.size != r.size) return PairState.DIFF
    val timeSame = sameTime(l.lastModified, r.lastModified, o)
    // The vast majority of pairs have matching size and time; with
    // [CompareOptions.contentOnlyIfTimeDiffers] on, those are skipped directly, which is
    // where the biggest chunk of reads would have come from.
    val skipByTime = o.contentOnlyIfTimeDiffers && timeSame
    if (!skipByTime && contentComparable(l, r, o)) {
        return if (contentEquals(l, r)) PairState.SAME else PairState.DIFF
    }
    return if (timeSame) PairState.SAME else PairState.DIFF
}

/**
 * Pair the two sides' children by name. Multiple same-name entries (ext4's `A.txt` and
 * `a.txt` can coexist and would collide under case-insensitive pairing) are paired in
 * order of appearance; unmatched ones become orphans on their respective sides — **none
 * may be dropped**. A directory and a file with the same name count as different keys
 * and will not pair with each other.
 */
internal fun pairEntries(
    left: List<XFile>,
    right: List<XFile>,
    ignoreCase: Boolean,
): List<Pair<XFile?, XFile?>> {
    fun key(f: XFile) = (if (ignoreCase) f.name.lowercase() else f.name) + (if (f.isDir) "/" else "")
    val buckets = LinkedHashMap<String, ArrayDeque<XFile>>()
    for (f in right) buckets.getOrPut(key(f)) { ArrayDeque() }.addLast(f)
    val out = ArrayList<Pair<XFile?, XFile?>>(left.size + right.size)
    for (f in left) out += f to buckets[key(f)]?.removeFirstOrNull()
    for (rest in buckets.values) for (f in rest) out += null to f
    return out
}

/**
 * Recursively compares [leftRoot] with [rightRoot], emitting events as it scans.
 *
 * **Preorder emit children, postorder fill in directory state**: as soon as the top
 * directory is listed the user sees content (so the first top-level directory being
 * large does not leave a blank screen for a long time); the aggregate state of that
 * directory is updated once its entire subtree has been scanned.
 *
 * Concurrency is only "both sides' list calls in parallel", **never multiple directories
 * in parallel**: `smb2_context` is serialized via a reentrant lock, FTP has only one
 * control connection, so firing multiple directories at once does not go faster — it
 * just stalls the lock or blows out the connection.
 *
 * Single-side-only directories are still recursively listed — the user needs to see
 * what is inside and to be able to copy the whole thing across, and skipping them
 * would skew the entry count shown in progress.
 *
 * Cancellation is done by the caller cancelling the coroutine; the one network
 * round-trip blocked in `list()` / `openInput()` is not interrupted and exits at the
 * checkpoint after it returns (same convention as [scanDirStat]).
 */
fun scanCompare(
    leftRoot: XFile?,
    rightRoot: XFile?,
    options: CompareOptions,
    io: CoroutineDispatcher = Dispatchers.IO,
): Flow<CompareEvent> = flow {
    var entries = 0
    var diffs = 0
    var truncated = false

    suspend fun list(f: XFile?): List<XFile> =
        if (f == null) emptyList()
        else runCatching { FsRegistry.of(f).list(f) }.getOrDefault(emptyList())

    /** Returns the aggregate state of this directory's subtree. */
    suspend fun walk(
        left: XFile?,
        right: XFile?,
        dirKey: String,
        depth: Int,
        forced: PairState?,
    ): PairState {
        coroutineContext.ensureActive()
        if (truncated) return forced ?: PairState.SCANNING
        emit(CompareEvent.Progress(entries, diffs, dirKey))

        // Both sides' list calls run in parallel: with different backends it is real
        // parallelism; with the same backend the layer below serializes itself — harmless.
        val (ls, rs) = coroutineScope {
            val a = async { list(left) }
            val b = async { list(right) }
            a.await() to b.await()
        }

        val rows = ArrayList<CompareEntry>()
        val subDirs = ArrayList<Triple<CompareEntry, String, PairState?>>()
        for ((l, r) in pairEntries(ls, rs, options.ignoreCase)) {
            coroutineContext.ensureActive()
            val rep = l ?: r!!
            val rel = if (dirKey.isEmpty()) rep.name else "$dirKey/${rep.name}"
            val rule = excludeRuleFor(rep.name, rel, options.excludes)
            if (rule != null) {
                emit(CompareEvent.Excluded(dirKey, rep.name, rule))
                continue
            }
            if (entries >= COMPARE_MAX_ENTRIES) { truncated = true; break }
            entries++

            // Single-side-only entries, and "one side is a directory, the other is a file" pairs
            // that cannot be matched, are both treated as orphans.
            val side = forced ?: when {
                l == null -> PairState.RIGHT_ONLY
                r == null -> PairState.LEFT_ONLY
                else -> null
            }
            if (rep.isDir) {
                // A directory that is single-side-only (or already locked in by its parent) has a
                // state determined at this very moment — no need to first report SCANNING
                // and then change it after the subtree finishes; in the UI that would just
                // flash "…" before turning into "◀".
                val e = CompareEntry(rep.name, l, r, isDir = true, state = side ?: PairState.SCANNING)
                rows += e
                if (depth < COMPARE_MAX_DEPTH) subDirs += Triple(e, rel, side)
            } else {
                val st = side ?: compareFiles(l!!, r!!, options)
                if (st != PairState.SAME) diffs++
                rows += CompareEntry(rep.name, l, r, isDir = false, state = st)
            }
        }
        emit(CompareEvent.Children(dirKey, rows))
        if (truncated) emit(CompareEvent.Truncated)

        var worst = PairState.SAME
        for (row in rows) if (!row.isDir && row.state != PairState.SAME) worst = PairState.DIFF
        for ((e, rel, side) in subDirs) {
            val sub = walk(e.left, e.right, rel, depth + 1, side)
            if (sub != PairState.SAME) worst = PairState.DIFF
        }
        // A single-side-only directory: its own state is "only on one side", and is not
        // overwritten by what its subtree contains.
        val self = forced ?: when {
            left == null -> PairState.RIGHT_ONLY
            right == null -> PairState.LEFT_ONLY
            else -> worst
        }
        emit(CompareEvent.DirDone(dirKey, self))
        return self
    }

    walk(leftRoot, rightRoot, "", 0, null)
    emit(CompareEvent.Progress(entries, diffs, ""))
}.flowOn(io)
