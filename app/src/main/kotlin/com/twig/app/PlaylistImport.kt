package com.twig.app

import android.content.Context
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Collect locations only; metadata and covers keep using the playlist's existing lazy loaders. */
internal object PlaylistImport {
    private fun connectionMap(ctx: Context) = ConnectionStore.all(ctx).associateBy { Connections.schemeOf(it) }

    suspend fun directory(ctx: Context, root: XFile, progress: (Int, String) -> Unit): List<PlaylistTrack> {
        val connections = connectionMap(ctx)
        require(root.scheme == "file" || root.scheme == "saf" || root.scheme in connections) {
            ctx.getString(R.string.music_import_source_unsupported)
        }
        val tracks = ArrayList<PlaylistTrack>()
        val pending = ArrayDeque<Pair<XFile, Int>>()
        val visited = HashSet<String>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (entry, depth) = pending.removeLast()
            if (entry.isDir) {
                // Local directory links may lead back to an ancestor under a different path.
                val identity = if (entry.scheme == "file") File(entry.path).canonicalPath else entry.path
                if (!visited.add("${entry.scheme}:$identity")) continue
                check(depth <= 128) { entry.path }
                val children = try {
                    FsRegistry.of(entry).list(entry)
                } catch (e: Exception) {
                    throw IllegalStateException("${entry.name}: ${e.message.orEmpty()}", e)
                }
                progress(tracks.size, entry.name)
                children.asReversed().forEach { pending.addLast(it to depth + 1) }
            } else if (OpenFiles.isAudio(entry)) {
                val conn = connections[entry.scheme]
                val kind = when (entry.scheme) {
                    "file" -> "local"
                    "saf" -> "saf"
                    else -> if (conn != null) "conn" else error(ctx.getString(R.string.music_import_source_unsupported))
                }
                tracks += PlaylistTrack(kind, entry.path, connLabel = conn?.label().orEmpty(), size = entry.size,
                    lastModified = entry.lastModified, displayName = entry.displayName.orEmpty())
            }
        }
        return tracks.distinctBy { it.id }
    }

    suspend fun playlist(ctx: Context, file: XFile): List<PlaylistTrack> {
        require(file.extension in setOf("m3u", "m3u8")) { ctx.getString(R.string.music_import_playlist_only) }
        currentCoroutineContext().ensureActive()
        val tracks = M3uPlaylist.tracks(ctx, file)
        currentCoroutineContext().ensureActive()
        require(tracks.none { it.temporary }) { ctx.getString(R.string.music_import_source_unsupported) }
        return tracks.distinctBy { it.id }
    }
}
