package com.twig.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.twig.app.PlaylistImport
import com.twig.app.PlaylistStore
import com.twig.app.R
import com.twig.core.XFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A scan survives rotation; finishing the list page cancels it before any list is changed. */
internal class PlaylistImportModel(app: Application) : AndroidViewModel(app) {
    data class State(val busy: Boolean = false, val count: Int = 0, val path: String = "",
                     val added: Int? = null, val error: String? = null)
    val state = MutableLiveData(State())
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var job: Job? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun start(id: String, source: XFile, directory: Boolean) {
        if (job?.isActive == true) return
        val app = getApplication<Application>()
        state.value = State(busy = true)
        job = viewModelScope.launch {
            try {
                val tracks = withContext(ioDispatcher) {
                    var lastProgress = 0L
                    if (directory) PlaylistImport.directory(app, source) { count, path ->
                        // Queue progress on Main with this scan's cancellation lifetime.
                        val now = System.nanoTime()
                        if (now - lastProgress >= 100_000_000L) {
                            lastProgress = now
                            launch(Dispatchers.Main) { state.value = State(busy = true, count = count, path = path) }
                        }
                    } else PlaylistImport.playlist(app, source)
                }
                ensureActive()
                check(tracks.isNotEmpty()) { app.getString(R.string.music_no_playable) }
                val before = requireNotNull(PlaylistStore.get(app, id)) { app.getString(R.string.music_export_missing) }
                check(!before.temporary) { app.getString(R.string.music_import_source_unsupported) }
                val updated = requireNotNull(PlaylistStore.addTracks(app, id, tracks))
                MusicEngine.appendToQueue(app, id)
                MusicEngine.notifyFavChanged()
                state.value = State(added = updated.tracks.size - before.tracks.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = State(error = e.message.orEmpty())
            }
        }
    }

    fun cancel() { job?.cancel(); state.value = State() }
    fun consumed() { state.value = State() }
}
