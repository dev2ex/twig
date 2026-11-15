package com.twig.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.twig.app.R
import com.twig.fs.archive.ArchiveWriter
import com.twig.core.CopyEngine
import com.twig.core.SourceLayout
import com.twig.core.XFile
import com.twig.core.isWritableDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One in-memory operation draft survives rotation and destination changes, including its size plan. */
class DestinationState : ViewModel() {
    var draft: Draft? = null
        private set
    val plan = MutableStateFlow<CopyEngine.Plan?>(null)
    private var planning: Job? = null
    var choosingDestination = false
    var returnToConfirmation = false
    var checkedSources: List<XFile> = emptyList()

    fun prepare(draft: Draft) {
        clear()
        this.draft = draft
        planning = viewModelScope.launch {
            plan.value = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                runCatching { CopyEngine.plan(draft.sources) { !context.isActive } }.getOrNull()
            }
        }
    }

    fun clear() {
        planning?.cancel()
        planning = null
        draft?.compression?.password = ""
        draft = null
        plan.value = null
        choosingDestination = false
        returnToConfirmation = false
        checkedSources = emptyList()
    }

    class Draft(
        val sources: List<XFile>,
        var destination: XFile?,
        var move: Boolean,
        val movable: Boolean,
        val compression: Compression? = null,
        val relativePaths: Map<String, String>? = runCatching { SourceLayout.paths(sources) }.getOrNull(),
        var keepStructure: Boolean = false,
    ) {
        fun outputPaths(): Map<String, String> {
            if (!keepStructure) return emptyMap()
            val root = compression?.structureRoot ?: return relativePaths.orEmpty()
            return relativePaths.orEmpty().mapValues { (_, path) ->
                if (path == root) "" else path.removePrefix("$root/")
            }
        }

        fun preview(): String = sources.take(4).joinToString("\n") {
            outputPaths()[it.toUri()]?.ifEmpty { "./" } ?: it.name
        } + if (sources.size > 4) "\n…" else ""

        fun effectiveDestination(): XFile? =
            if (compression?.saveToSource == true) compression.sourceDirectory else destination

        fun blockReason(destination: XFile): Int? {
            if (!destination.isWritableDir()) return R.string.msg_dest_not_writable
            if (keepStructure && relativePaths == null) return R.string.transfer_duplicate_paths
            if (compression != null && !keepStructure && sources.map { it.name }.distinct().size != sources.size)
                return R.string.transfer_duplicate_paths
            FileClipboard.pasteBlockReason(destination, sources,
                allowSameDirectory = compression != null || keepStructure)?.let { return it }
            if (compression == null && keepStructure && destination.path.startsWith("/")) {
                if (sources.any { source -> source.scheme == destination.scheme &&
                    destination.path.trimEnd('/') + "/" + outputPaths()[source.toUri()] == source.path })
                    return R.string.clip_same_dir
            }
            return null
        }

    }

    class Compression(
        var name: String,
        var format: ArchiveWriter.Format,
        var password: String = "",
        var level: ArchiveWriter.Level = ArchiveWriter.Level.STANDARD,
        var hideNames: Boolean = true,
        val sourceDirectory: XFile? = null,
        var saveToSource: Boolean = false,
        val structureRoot: String? = null,
    )

    companion object {
        const val CONFIRMATION_TAG = "destination-confirmation"
    }
}
