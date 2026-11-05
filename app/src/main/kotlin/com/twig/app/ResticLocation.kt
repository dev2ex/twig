package com.twig.app

import android.content.Context
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.restic.ResticFileSystem
import com.twig.fs.restic.ResticRepo

/** Repository, snapshot selector and in-snapshot path are separate locations. No IO here. */
data class ResticLocation(
    val repositoryAddress: String,
    val repositoryName: String,
    val path: String,
    val snapshotLabel: String = "",
    val resolvedSnapshotId: String = "",
) {
    companion object {
        const val LATEST_NAME = "Latest"
    }

    val selector: String get() = path.trim('/').substringBefore('/')
    val insidePath: String get() = path.trim('/').substringAfter('/', "").let { "/$it" }

    fun snapshotName(): String = when (selector) {
        "" -> ""
        "latest" -> LATEST_NAME
        else -> snapshotLabel.ifEmpty { selector }
    }

    fun compact(): String = buildString {
        append("restic · ").append(repositoryName)
        if (selector.isNotEmpty()) append(" › ").append(snapshotName()).append(" › ").append(insidePath)
    }

    /** Keep the snapshot selector visible when a long repository/path exceeds the bar. */
    fun fitted(paint: android.text.TextPaint, width: Float): String {
        val full = compact()
        if (width <= 0 || paint.measureText(full) <= width || selector.isEmpty()) return full
        val prefix = "restic · "
        val separator = " › "
        val available = width - paint.measureText(prefix + separator + separator)
        if (available <= 0) return full
        fun shorten(text: String, budget: Float, where: android.text.TextUtils.TruncateAt) =
            android.text.TextUtils.ellipsize(text, paint, budget.coerceAtLeast(0f), where).toString()
        val snapshot = shorten(snapshotName(), available * 0.45f, android.text.TextUtils.TruncateAt.END)
        val remaining = available - paint.measureText(snapshot)
        val repository = shorten(repositoryName, remaining * 0.35f, android.text.TextUtils.TruncateAt.MIDDLE)
        val inside = shorten(insidePath, remaining - paint.measureText(repository), android.text.TextUtils.TruncateAt.START)
        return prefix + repository + separator + snapshot + separator + inside
    }

    fun favoriteName(): String = when {
        selector.isEmpty() -> repositoryName
        insidePath == "/" -> "$repositoryName:${snapshotName()}"
        else -> "$repositoryName:${insidePath.substringAfterLast('/')}"
    }

    fun details(ctx: Context): String = buildString {
        append(ctx.getString(R.string.restic_location_repository)).append("\n").append(repositoryAddress)
        if (selector.isNotEmpty()) {
            append("\n\n").append(ctx.getString(R.string.restic_location_snapshot)).append("\n")
            append(snapshotName())
            if (selector == "latest" && snapshotLabel.isNotEmpty()) append(" · ").append(snapshotLabel)
            val id = resolvedSnapshotId.ifEmpty { selector.takeUnless { it == "latest" }.orEmpty() }
            if (id.isNotEmpty() && id != snapshotName()) append(" · ").append(id)
            append("\n\n").append(ctx.getString(R.string.info_path)).append("\n").append(insidePath)
        }
    }
}

fun resticSnapshotLabel(snapshot: ResticRepo.Snapshot): String =
    listOf(snapshot.timeLabel, snapshot.hostname).filter { it.isNotEmpty() }.joinToString(" · ")

fun resticLocation(fav: Favorite, conn: SavedConnection?): ResticLocation = ResticLocation(
    formatRawLocationPath(fav.repoConnLabel, fav.repoPath, conn),
    fav.repoPath.trimEnd('/').substringAfterLast('/').ifEmpty {
        conn?.shortLabel() ?: fav.repoConnLabel.ifEmpty { "/" }
    },
    fav.path,
    fav.snapshotLabel,
)

fun resticLocation(file: XFile): ResticLocation? {
    if (!file.scheme.startsWith("restic")) return null
    val fs = runCatching { FsRegistry.of(file) as? ResticFileSystem }.getOrNull() ?: return null
    val repository = fs.repository ?: return null
    val conn = Connections.ofScheme(repository.scheme)
    val snapshot = fs.listedSnapshot(file.path)
    return ResticLocation(
        formatRawLocationPath(conn?.label().orEmpty(), repository.path, conn),
        repository.name.ifEmpty { conn?.shortLabel() ?: "/" },
        file.path,
        snapshot?.let(::resticSnapshotLabel).orEmpty(),
        snapshot?.shortId.orEmpty(),
    )
}
