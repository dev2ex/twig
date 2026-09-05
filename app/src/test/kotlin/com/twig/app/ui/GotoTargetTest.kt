package com.twig.app.ui

import com.twig.app.AppsFileSystem
import com.twig.app.SafFileSystem
import com.twig.app.SavedConnection
import com.twig.core.XFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which roots offer "Go to path" ([supportsGoto]). The three exclusions are a product
 * decision, not a limitation of the descent — so only a test keeps them out once
 * somebody adds the menu item to another shared code path.
 */
class GotoTargetTest {

    private fun dir(scheme: String) = XFile(scheme, "/", isDir = true)

    private fun conn(type: String) =
        SavedConnection(type = type, host = "h", port = 0, user = "u")

    @Test
    fun `local storage and ordinary servers offer the jump`() {
        assertTrue(supportsGoto(dir("file"), null))
        assertTrue(supportsGoto(dir("smb12ab"), conn("smb")))
        assertTrue(supportsGoto(dir("sftp12ab"), conn("sftp")))
        assertTrue(supportsGoto(dir("s312ab"), conn("s3")))
    }

    /** Jellyfin and Emby: the tree is the server's own organisation, not a path. */
    @Test
    fun `media servers do not offer the jump`() {
        assertFalse(supportsGoto(dir("jf12ab"), conn("jellyfin")))
        assertFalse(supportsGoto(dir("emby12ab"), conn("emby")))
    }

    @Test
    fun `document trees and the apps tree do not offer the jump`() {
        assertFalse(supportsGoto(dir(SafFileSystem.SCHEME), null))
        assertFalse(supportsGoto(dir(AppsFileSystem.SCHEME), null))
    }
}
