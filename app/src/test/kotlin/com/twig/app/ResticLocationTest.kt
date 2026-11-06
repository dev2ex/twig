package com.twig.app

import android.app.Application
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResticLocationTest {
    private val conn = SavedConnection("smb", "nas.test", share = "media", name = "Home NAS")
    private val favorite = Favorite("old name", "restic", "/a1b2c3d4/home/vale/Documents",
        repoPath = "/backups/laptop", repoConnLabel = conn.label(),
        snapshotLabel = "2026-11-04 09:30:00 · laptop")

    @Test fun `favorite identifies its target and separates the repository from snapshot paths`() {
        val location = resticLocation(favorite, conn)
        assertEquals("laptop:Documents", favoriteDisplayName(favorite, conn))
        assertEquals("restic · laptop › 2026-11-04 09:30:00 · laptop › /home/vale/Documents",
            favoriteFullPath(favorite, conn))
        assertEquals("smb://nas.test/media/backups/laptop", location.repositoryAddress)
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val details = location.details(ctx)
        assertTrue(details.contains("smb://nas.test/media/backups/laptop"))
        assertTrue(details.contains("a1b2c3d4"))
        assertTrue(details.endsWith("/home/vale/Documents"))
        assertEquals("My documents", favoriteDisplayName(favorite.copy(customLabel = "My documents"), conn))
    }

    @Test fun `latest remains an alias even when its current snapshot is known`() {
        val location = ResticLocation("/backup", "backup", "/latest/home/vale/Documents",
            "2026-11-04 09:30:00 · laptop", "a1b2c3d4")
        assertEquals("restic · backup › Latest › /home/vale/Documents", location.compact())
        assertEquals("backup:Latest", location.copy(path = "/latest").favoriteName())
        assertEquals("backup:2026-11-04 09:30:00 · laptop",
            location.copy(path = "/a1b2c3d4").favoriteName())
        assertEquals("backup", location.copy(path = "/").favoriteName())
    }

    @Test fun `old favorites and deleted connections remain readable without a mounted filesystem`() {
        val old = Favorite.fromJson(JSONObject(favorite.toJson().toString()).apply { remove("snapshotLabel") })
        assertEquals("restic · laptop › a1b2c3d4 › /home/vale/Documents", favoriteFullPath(old, null))
        assertEquals("smb://nas.test/media/backups/laptop", resticLocation(old, null).repositoryAddress)
        val local = old.copy(repoPath = "/storage/backups/laptop", repoConnLabel = "")
        assertEquals("/storage/backups/laptop", resticLocation(local, null).repositoryAddress)
    }

    @Test fun `fixed snapshot metadata survives persistence without changing favorite identity`() {
        val restored = Favorite.fromJson(JSONObject(favorite.toJson().toString()))
        assertEquals(favorite.snapshotLabel, restored.snapshotLabel)
        assertEquals(favorite.id, restored.copy(snapshotLabel = "").id)
        assertEquals(favoriteFullPath(favorite, conn), favoriteFullPath(restored, conn))
    }

    @Test fun `narrow address bar retains latest and the target directory`() {
        val location = ResticLocation("/backup", "a-very-long-repository-name", "/latest/home/vale/a-very-long-parent-directory/Documents")
        val paint = TextPaint().apply { textSize = 12f }
        val result = location.fitted(paint, 300f)
        assertTrue(result, result.contains("Latest"))
        assertTrue(result, result.endsWith("Documents"))
        assertTrue(result, paint.measureText(result) <= 301f)
    }

    @Test fun `bound favorite row shows directory and snapshot context before connecting`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ctx = android.view.ContextThemeWrapper(app, R.style.Theme_Twig)
        val parent = android.widget.FrameLayout(ctx)
        val adapter = com.twig.app.ui.FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        val node = com.twig.app.ui.PaneViewModel.FavoriteNode(favorite, conn, 1, false, false)
        adapter.submitList(listOf(node))
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertEquals("laptop:Documents", holder.itemView.findViewById<android.widget.TextView>(R.id.name).text.toString())
        val meta = holder.itemView.findViewById<android.widget.TextView>(R.id.meta)
        assertEquals(favoriteFullPath(favorite, conn), meta.text.toString())
        assertEquals(android.text.TextUtils.TruncateAt.MIDDLE, meta.ellipsize)
        adapter.submitList(null)
        adapter.submitList(listOf(node.copy(fav = Favorite("local", "local", "/Documents"), conn = null)))
        adapter.bindViewHolder(holder, 0)
        assertEquals("/Documents", meta.text.toString())
        assertEquals(android.text.TextUtils.TruncateAt.END, meta.ellipsize)
    }
}
