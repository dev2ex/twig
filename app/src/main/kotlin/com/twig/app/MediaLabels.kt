package com.twig.app

import android.content.Context
import com.twig.fs.network.JellyfinFileSystem

/**
 * Localized label table for Jellyfin / Emby virtual directories.
 *
 * `:fs-network` is a pure-JVM module (no Context, no R), and per project convention
 * everything inside it is **English only**; but "Continue Watching" / "Movies" are shown
 * directly to the user. So this follows the same pattern as "FsException carries an
 * error code + the UI layer looks it up": the module only knows ids, the names are
 * filled in here (see `JellyfinConfig.labels`).
 *
 * ★ Adding a virtual directory = add one entry in `JellyfinFileSystem.VIRTUAL` + one
 * line here + one entry in each of the two `strings.xml` files. ids not covered by
 * [of] fall back to the module's built-in English names — it won't crash, but a stray
 * English directory name will pop up in the UI, so don't miss one.
 */
object MediaLabels {

    fun of(ctx: Context): Map<String, String> = mapOf(
        JellyfinFileSystem.ID_RESUME to ctx.getString(R.string.media_resume),
        JellyfinFileSystem.ID_PLAYLISTS to ctx.getString(R.string.media_playlists),
        JellyfinFileSystem.ID_COLLECTIONS to ctx.getString(R.string.media_collections),
        JellyfinFileSystem.ID_LATEST to ctx.getString(R.string.media_latest),
        JellyfinFileSystem.ID_FOLDERS to ctx.getString(R.string.media_folders),
        // Four sections under the music library (matching the official client's tabs)
        JellyfinFileSystem.SEG_ALBUMS to ctx.getString(R.string.media_albums),
        JellyfinFileSystem.SEG_ALBUM_ARTISTS to ctx.getString(R.string.media_album_artists),
        JellyfinFileSystem.SEG_ARTISTS to ctx.getString(R.string.media_artists),
        JellyfinFileSystem.SEG_MUSIC_FOLDER to ctx.getString(R.string.media_folders),
    )
}
