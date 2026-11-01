package com.twig.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.twig.app.R
import com.twig.fs.archive.ArchiveWriter
import com.twig.core.CopyEngine
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
    ) {
        fun effectiveDestination(): XFile? =
            if (compression?.saveToSource == true) compression.sourceDirectory else destination

        fun blockReason(destination: XFile): Int? =
            if (!destination.isWritableDir()) R.string.msg_dest_not_writable
            else FileClipboard.pasteBlockReason(destination, sources, allowSameDirectory = compression != null)
    }

    class Compression(
        var name: String,
        var format: ArchiveWriter.Format,
        var password: String = "",
        var level: ArchiveWriter.Level = ArchiveWriter.Level.STANDARD,
        var hideNames: Boolean = true,
        val sourceDirectory: XFile? = null,
        var saveToSource: Boolean = false,
    )

    companion object {
        const val CONFIRMATION_TAG = "destination-confirmation"
    }
}
