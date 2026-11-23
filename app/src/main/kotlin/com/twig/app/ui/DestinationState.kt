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
        replan()
    }

    fun replan() {
        planning?.cancel()
        plan.value = null
        val draft = draft ?: return
        if (draft.extraction?.streamingTar != null) return
        val format = draft.compression?.format
        planning = viewModelScope.launch {
            plan.value = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                runCatching {
                    if (format == null) CopyEngine.plan(draft.sources) { !context.isActive }
                    else ArchiveWriter.plan(draft.sources, format) { !context.isActive }
                }.getOrNull()
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
        val extraction: Extraction? = null,
        var preservePermissions: Boolean = false,
    ) {
        fun outputPaths(): Map<String, String> {
            if (!keepStructure) return emptyMap()
            val root = compression?.structureRoot ?: return relativePaths.orEmpty()
            return relativePaths.orEmpty().mapValues { (_, path) ->
                if (path == root) "" else path.removePrefix("$root/")
            }
        }

        fun preview(): String {
            extraction?.streamingProbe?.let { probe ->
                return probe.roots.joinToString("\n") { name ->
                    extraction.folderName?.let { "$it/$name" } ?: name
                } + if (probe.complete) "" else "\n…"
            }
            return sources.take(4).joinToString("\n") {
                val path = outputPaths()[it.toUri()]?.ifEmpty { "./" } ?: it.name
                extraction?.folderName?.let { folder -> "$folder/$path" } ?: path
            } + if (sources.size > 4) "\n…" else ""
        }

        fun effectiveDestination(): XFile? = when {
            compression?.saveToSource == true -> compression.sourceDirectory
            extraction?.saveToSource == true -> extraction.sourceDirectory
            else -> destination
        }

        fun usePickedDestination() {
            compression?.saveToSource = false
            extraction?.saveToSource = false
        }

        fun blockReason(destination: XFile): Int? {
            if (!destination.isWritableDir()) return R.string.msg_dest_not_writable
            if (keepStructure && relativePaths == null) return R.string.transfer_duplicate_paths
            if (compression != null && !keepStructure && sources.map { it.name }.distinct().size != sources.size)
                return R.string.transfer_duplicate_paths
            FileClipboard.pasteBlockReason(destination, sources,
                allowSameDirectory = compression != null || extraction != null || keepStructure)?.let { return it }
            if (compression == null && keepStructure && destination.path.startsWith("/")) {
                if (sources.any { source -> source.scheme == destination.scheme &&
                    destination.path.trimEnd('/') + "/" + outputPaths()[source.toUri()] == source.path })
                    return R.string.clip_same_dir
            }
            return null
        }

    }

    class Extraction(
        val archive: XFile,
        val sourceDirectory: XFile?,
        var saveToSource: Boolean = false,
        val folderName: String? = null,
        val streamingTar: XFile? = null,
        val streamingProbe: com.twig.fs.archive.StreamingTar.Probe? = null,
    ) {
        companion object {
            fun folderName(archive: XFile, itemCount: Int): String? {
                if (itemCount <= 1) return null
                var stem = archive.name.substringBeforeLast('.', archive.name)
                if (archive.extension in setOf("gz", "xz", "bz2", "zst") && stem.endsWith(".tar", ignoreCase = true))
                    stem = stem.dropLast(4)
                return stem.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
                    .joinToString("").takeIf(SourceLayout::isSafeSegment) ?: "archive"
            }
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
