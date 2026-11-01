package com.twig.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.R
import com.twig.core.XFile
import kotlinx.coroutines.launch

/** ACTION_CREATE_SHORTCUT returns an action to the launcher, including gesture editors. */
open class CreateShortcutActivity : PickerActivity() {
    protected open val command = false
    private var selectionObserver: kotlinx.coroutines.Job? = null

    override val shortcutResult: ((ShortcutInfoCompat) -> Unit) = { shortcut ->
        // Legacy launchers execute the returned Intent themselves. Use an exported,
        // opaque-id relay so viewers and website activities can remain private.
        val relay = LauncherShortcutActivity.save(this, shortcut.intent)
        setResult(RESULT_OK, resultIntent(shortcut.shortLabel.toString(), relay))
        finish()
    }

    @Suppress("DEPRECATION")
    private fun resultIntent(label: String, relay: Intent): Intent = Intent()
        .putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
        .putExtra(Intent.EXTRA_SHORTCUT_INTENT, relay)
        // Nova forwards this whole result through another Activity. Bitmap icons can
        // parcel as ashmem file descriptors, which finishActivity refuses to forward
        // lazily ("Not allowed to write file descriptors here"). Resource names are
        // ordinary strings. Do not add a platform PinItemRequest or bitmap here.
        .putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(
            this, if (command) R.drawable.ic_shortcut_terminal else R.drawable.ic_shortcut_launcher,
        ))

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ignore caller-supplied picker extras: configuration always browses Twig's tree.
        intent = Intent(this, javaClass)
        super.onCreate(savedInstanceState)
        b.toolbar.setTitle(if (command) R.string.twig_command_shortcut else R.string.twig_shortcut)
        b.tvSummary.setText(if (command) R.string.shortcut_pick_command else R.string.shortcut_pick_node)
        b.btnConfirm.visibility = View.VISIBLE
        b.btnConfirm.isEnabled = false
        b.btnConfirm.alpha = 0.35f
        b.btnConfirm.setOnClickListener {
            selected()?.takeIf(::supports)?.let { pane()?.createNodeShortcut(it, command) }
        }
    }

    override fun onPaneReady() {
        selectionObserver?.cancel()
        updateSelection()
        selectionObserver = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pane()?.viewModel?.state?.collect { updateSelection() }
            }
        }
    }

    private fun selected(): PaneViewModel.Node? {
        val state = pane()?.viewModel?.state?.value ?: return null
        return state.rows.firstOrNull { it.key == state.currentKey }
    }

    private fun file(node: PaneViewModel.Node): XFile? = when (node) {
        is PaneViewModel.FileNode -> node.file
        is PaneViewModel.ServerNode -> pane()?.viewModel?.serverTarget(node)
        is PaneViewModel.FavoriteNode -> pane()?.viewModel?.favoriteTarget(node)
        else -> null
    }

    private fun supports(node: PaneViewModel.Node): Boolean {
        if (!command && node is PaneViewModel.WebsiteNode) return true
        val file = file(node)
        if (command) {
            if (file?.scheme == "file" && file.isDir) {
                return true
            }
            if (node is PaneViewModel.ServerNode) return node.conn.type == "sftp"
            return file?.isDir == true && pane()?.viewModel?.connOf(file.scheme)?.type == "sftp"
        }
        // Session-only mounts (archives, restic, git, incoming shares) cannot be
        // reopened by a launcher after the process dies.
        return file != null && (file.scheme == "file" || file.scheme == "saf" ||
            ConnectionStore.all(this).any { Connections.schemeOf(it) == file.scheme })
    }

    private fun updateSelection() {
        val node = selected()
        b.btnConfirm.isEnabled = node != null && supports(node)
        b.btnConfirm.alpha = if (b.btnConfirm.isEnabled) 1f else 0.35f
        b.tvSummary.text = when {
            node is PaneViewModel.WebsiteNode -> node.site.name
            node is PaneViewModel.ServerNode -> node.conn.shortLabel()
            node != null && file(node) != null -> file(node)?.name
            else -> getString(if (command) R.string.shortcut_pick_command else R.string.shortcut_pick_node)
        }
    }

    override fun onPickNode(node: PaneViewModel.Node): Boolean {
        pane()?.viewModel?.highlightNode(node)
        updateSelection()
        return when (node) {
            is PaneViewModel.FileNode -> !node.file.isDir && (!node.expandable || command)
            is PaneViewModel.WebsiteNode, is PaneViewModel.CompareNode -> true
            else -> false
        }
    }

    override fun onPickFile(file: XFile) = true
}

class CreateCommandShortcutActivity : CreateShortcutActivity() {
    override val command = true
}
