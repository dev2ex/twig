package com.twig.app.ui

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Gate for URIs that other apps hand to Twig's exported entry points
 * ([ViewIntentActivity], [ShareTargetActivity]).
 *
 * ★ Those URIs are opened **with Twig's identity** (2026-09-17 review). A `file://` path
 * into Twig's own data directory — imported SSH keys, the local shell's `.ssh`, the wrapped
 * key material — would be read by Twig and copied wherever the user tapped, i.e. to a place
 * the sending app can read. Twig's own `content://…stream` URIs are covered separately:
 * they carry a signature (see `StreamProvider`), so a forged one fails when opened.
 *
 * Plain `file://` from elsewhere is still accepted: apps targeting API < 24 may send it, and
 * anything outside our own directories is readable only if Twig could read it anyway —
 * which the user browsing in Twig already can.
 */
object IncomingUri {

    fun allowed(ctx: Context, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content" -> true
        "file" -> uri.path?.let { !isPrivatePath(ctx, it) } ?: false
        else -> false
    }

    /** Whether [path] resolves (following links) into this app's private storage. */
    fun isPrivatePath(ctx: Context, path: String): Boolean {
        val p = runCatching { File(path).canonicalPath }.getOrElse { return true }
        return privateDirs(ctx).any { p == it || p.startsWith("$it/") }
    }

    private fun privateDirs(ctx: Context): List<String> = listOfNotNull(
        ctx.dataDir,
        ctx.applicationInfo.deviceProtectedDataDir?.let { File(it) },
    ).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
}
