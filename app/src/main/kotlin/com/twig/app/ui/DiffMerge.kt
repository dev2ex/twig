package com.twig.app.ui

import com.twig.git.Diff

/**
 * Pure logic for the two-pane diff's "move this block to the other side". Extracted so
 * it can be pinned down by a unit test — getting this wrong by even a little means
 * "edit one section, destroy the whole file", and the write-back is irreversible.
 */

/**
 * Split text into lines. A trailing newline does not count as a line (otherwise every
 * file would end with an extra empty line); strictly inverse of [joinLines], so any
 * text can be split and joined back byte-for-byte.
 */
internal fun toLines(text: String): List<String> =
    text.split('\n').let {
        if (it.lastOrNull() == "") it.dropLast(1) else it
    }.ifEmpty { emptyList() }

/**
 * Closed range of consecutive changed rows covered by one diff block (the starting index
 * is what's stored in [DiffActivity]'s `blocks`). [Diff.rows] pairs adjacent delete /
 * insert runs into "modify" rows, so a single diff block is just a run of `changed` rows.
 */
internal fun blockRange(rows: List<Diff.Row>, start: Int): IntRange {
    var e = start
    while (e + 1 < rows.size && rows[e + 1].changed) e++
    return start..e
}

/**
 * The full line sequence of the target side after applying this block: inside [range]
 * take the source-side rows (source-side being a placeholder means those rows on the
 * target are being deleted), outside the range keep the target-side rows as-is.
 *
 * Rebuild the whole side rather than splicing in place: [Diff.Row] is already the full
 * aligned sequence of both sides; walking through it once neither drops nor misaligns
 * rows, and we do not have to maintain a "which row index on the target maps to which
 * row" offset ourselves.
 */
internal fun mergedLines(rows: List<Diff.Row>, range: IntRange, toRight: Boolean): List<String> {
    val out = ArrayList<String>(rows.size)
    for (i in rows.indices) {
        // Inside the range take from source, outside from target; for toRight, source is left, target is right
        val take = if ((i in range) == toRight) rows[i].left else rows[i].right
        if (take != null) out.add(take)
    }
    return out
}

/**
 * Join a line sequence back into text.
 *
 * ★ **No line-ending detection is needed**: [DiffActivity.toLines] splits on `\n`, so the
 * trailing `\r` of every line in a CRLF file stays inside the line (invisible in the
 * diff view but it is there); joining back with `\n` is byte-for-byte — files mixing LF
 * and CRLF will not have their line endings rewritten across the whole file. The only
 * piece of information lost when splitting into lines is **whether the file ends with a
 * newline**, which must be carried separately: adding a newline out of thin air would
 * show up as a whole-file last-line change in git.
 */
internal fun joinLines(lines: List<String>, finalNewline: Boolean): String =
    if (lines.isEmpty()) "" else lines.joinToString("\n") + if (finalNewline) "\n" else ""
