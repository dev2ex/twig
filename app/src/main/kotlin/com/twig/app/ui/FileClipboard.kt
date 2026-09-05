package com.twig.app.ui

import com.twig.app.R
import com.twig.app.isMovableSource
import com.twig.core.XFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Global clipboard (shared between the two panes): the long-press menu's "copy to clipboard" places things here,
 * and tapping "paste" on the bottom bar of the main screen uses the **active pane's** current directory as the
 * destination, so either pane can act as the target — unlike the action bar's copy / move, which is fixed at
 * "this pane → the other pane".
 *
 * In-memory only (process-local), not persisted: the [XFile]s inside may come from archives / network sessions,
 * and rebuilding them across processes is expensive and may point at stale connections. [move] is the checkbox
 * state on the clipboard bar; it follows the clipboard rather than belonging to any pane, and is not reset when
 * the content changes (people who often use move don't have to re-tick each time).
 */
object FileClipboard {

    data class State(
        val items: List<XFile> = emptyList(),
        val move: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val items: List<XFile> get() = _state.value.items
    val move: Boolean get() = _state.value.move

    /**
     * Replace-mode put (no append): clipboard semantics match the system's — a new "copy to clipboard" replaces old content.
     *
     * ★ Re-evaluate "move" against the new content as a courtesy: [move] follows the clipboard rather than resetting
     * with the content, so a previously ticked move carries over verbatim to the new entries — if any of them cannot
     * be moved (the document-tree-root authorization, the "Apps" entries), pasting becomes a move that is doomed
     * to fail at the source-deletion step. The bar's checkbox is greyed out at the same time.
     */
    fun put(files: List<XFile>) {
        if (files.isEmpty()) return
        _state.value = State(items = files.toList(), move = _state.value.move && movable(files))
    }

    /** Whether this batch can serve as a whole as the source of a "move" (only if every one is movable). */
    fun movable(files: List<XFile> = items): Boolean =
        files.isNotEmpty() && files.all { it.isMovableSource() }

    fun setMove(move: Boolean) {
        if (_state.value.move != move) _state.value = _state.value.copy(move = move)
    }

    fun clear() {
        if (_state.value.items.isNotEmpty()) _state.value = _state.value.copy(items = emptyList())
    }

    /**
     * Reason (string resource id) when the destination directory is not a valid paste target; null = can paste.
     * The bar greys out "paste" accordingly and writes the reason on the target row;
     * [PaneFragment.pasteFromClipboard] catches it once more (button state may lag the green frame by one beat).
     *
     * Two kinds of blockage:
     * - **Same directory**: the source is inside the destination, so copying can only produce name conflicts /
     *   duplicates, and moving is a no-op where it stands.
     * - **Paste into self**: pasting a directory into itself or one of its descendants is an infinitely recursive move.
     *
     * Both require matching schemes — different sources (local vs SMB) with colliding path strings are not
     * necessarily the same place.
     */
    fun pasteBlockReason(dest: XFile?): Int? {
        val items = _state.value.items
        if (items.isEmpty() || dest == null) return null
        if (items.any { it.scheme == dest.scheme && it.parentPath == dest.path }) {
            return R.string.clip_same_dir
        }
        val destPath = dest.path.trimEnd('/')
        if (items.any {
                it.isDir && it.scheme == dest.scheme &&
                    (destPath == it.path.trimEnd('/') || destPath.startsWith(it.path.trimEnd('/') + "/"))
            }
        ) {
            return R.string.clip_into_self
        }
        return null
    }
}
