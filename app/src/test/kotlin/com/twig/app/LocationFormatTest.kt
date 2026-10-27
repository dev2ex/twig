package com.twig.app

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFormatTest {
    @Test
    fun `network displays share the history format and protocol delimiter`() {
        val smb = SavedConnection("smb", "nas.test", share = "media", name = "Home NAS")
        val sftp = SavedConnection("sftp", "ssh.test", port = 2222, share = "backup", name = "Backup")
        for ((conn, expected) in listOf(smb to "smb://Home NAS/photos", sftp to "sftp://Backup/photos")) {
            val file = XFile(Connections.schemeOf(conn), "/photos", isDir = true)
            assertEquals(expected, formatLocationPath(conn.label(), file.path, conn))
            assertEquals(expected, Format.pathLabel(file, conn))
            assertEquals(expected, formatLocationPath(conn.label(), "photos", conn))
        }
    }

    @Test
    fun `raw paths keep the address port and mounted root instead of the alias`() {
        val smb = SavedConnection("smb", "nas.test", share = "media", name = "Home NAS")
        val sftp = SavedConnection("sftp", "ssh.test", port = 2222, share = "backup", name = "Backup")
        assertEquals("smb://nas.test/media/photos", formatRawLocationPath(smb.label(), "/photos", smb))
        assertEquals("sftp://ssh.test:2222/backup/photos", formatRawLocationPath(sftp.label(), "/photos", sftp))
        assertEquals("smb://nas.test/media/", formatRawLocationPath(smb.label(), "/", smb))
        assertEquals("smb://Home NAS/", formatLocationPath(smb.label(), "/", smb))
    }

    @Test
    fun `deleted connections retain a single protocol prefix and local paths stay absolute`() {
        assertEquals("sftp://user@ssh.test:2222/backup/photos",
            formatLocationPath("sftp://user@ssh.test:2222/backup", "/photos", null))
        assertEquals("/storage/emulated/0/Pictures", formatLocationPath("", "/storage/emulated/0/Pictures", null))
        assertEquals("/storage/emulated/0/Pictures", Format.pathLabel(XFile("file", "/storage/emulated/0/Pictures", true)))
        assertEquals("zip:/archive.zip!/photos", Format.pathLabel(XFile("zip", "/archive.zip!/photos", true)))
    }
}
