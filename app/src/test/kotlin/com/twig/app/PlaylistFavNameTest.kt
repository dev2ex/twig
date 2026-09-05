package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Favorites" playlist's display name is owned by [R.string.playlist_favorites] (a
 * translatable resource) — not by whatever happened to be in memory the first time the
 * list was created. Before 2026-08-30 the name was captured into SharedPreferences on
 * first access, which froze the label in the user's launch locale; switching language
 * afterwards left "我的最爱" pinned even in English. The fix drops the `name` field
 * from the persisted record and reconstructs it from resources on every read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistFavNameTest {

    private lateinit var app: Application

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        // Each test gets a clean slate: the store lives under the Application context,
        // so previous tests' persisted "Favorites" would otherwise leak across cases.
        app.getSharedPreferences("twig_playlists", 0).edit().clear().commit()
    }

    @After fun tearDown() {
        app.getSharedPreferences("twig_playlists", 0).edit().clear().commit()
    }

    @Test fun `first access uses the current locale`() {
        assertEquals("Favorites", PlaylistStore.fav(app).name)
    }

    /**
     * Simulates a Chinese-locale first launch (the broken behavior): the persist layer
     * contains a "Favorites" record with `name = "我的最爱"` from the old code path.
     * After the fix, reading in English should drop that snapshot and surface the
     * translated resource instead — without the user having to manually reset.
     */
    @Test fun `a pre-fix snapshot under name is discarded on read`() {
        val sp = app.getSharedPreferences("twig_playlists", 0)
        val legacy = JSONObject()
            .put("id", Playlist.FAV)
            .put("name", "我的最爱") // exactly what the old code wrote on first access
            .put("lastIndex", 0)
            .put("lastPosMs", 0L)
            .put("tracks", org.json.JSONArray())
        sp.edit()
            .putString("list", org.json.JSONArray().put(legacy).toString())
            .commit()

        assertEquals("Favorites", PlaylistStore.fav(app).name)
    }

    /**
     * Round-trip: after the fix, calling fav() and then writing through a track
     * (toggleFav) must not reintroduce the `name` field into the persisted JSON —
     * otherwise the next read in a different locale would still pick up the stale
     * snapshot via the raw SharedPreferences string.
     */
    @Test fun `saving the fav list does not persist a name field`() {
        PlaylistStore.fav(app) // bootstraps the record
        val track = PlaylistTrack(
            kind = "local", path = "/music/a.mp3",
            size = 1L, lastModified = 0L, title = "a", artist = "", album = "",
            durationMs = 0L, sampleRate = 0, bitrate = 0, displayName = "a.mp3",
        )
        PlaylistStore.toggleFav(app, track)

        val raw = app.getSharedPreferences("twig_playlists", 0)
            .getString("list", null)!!
        val obj = org.json.JSONArray(raw).getJSONObject(0)
        assertEquals("Favorites record should carry no name field",
            false, obj.has("name"))
    }
}