package com.twig.app.ui

import com.twig.app.AppsFileSystem
import com.twig.app.SafFileSystem
import com.twig.app.SavedConnection
import com.twig.core.XFile

/**
 * Does "Go to path" (see [PaneViewModel.revealUnder]) belong on this root's menu?
 *
 * The jump is for roots whose contents the user can *name*: local storage, a removable
 * volume, an ordinary file server. Three are deliberately left out — not because the
 * descent cannot handle them (it matches by name, so it can), but because there is
 * nothing to type:
 * - **media servers** (Jellyfin/Emby): the tree is the server's own organisation
 *   (Continue watching / libraries / seasons), not a path anybody keeps in their head;
 * - **document trees** (SAF): a grant is usually one folder deep and is browsed by tapping;
 * - **Apps**: those rows are packages, and their "path" is a package name.
 *
 * [conn] is the connection owning [file]'s scheme, or null when it is not a server.
 *
 * ★ It lives outside `PaneFragment` so a plain unit test can pin the three exclusions:
 * a menu item quietly coming back is not something a real-device walkthrough catches.
 */
internal fun supportsGoto(file: XFile, conn: SavedConnection?): Boolean = when {
    file.scheme == SafFileSystem.SCHEME -> false
    file.scheme == AppsFileSystem.SCHEME -> false
    conn?.isMediaServer() == true -> false
    else -> true
}
