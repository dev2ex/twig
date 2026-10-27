package com.twig.app.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** System backups are distinct from the user-exported, password-protected .twigbak format. */
class SystemBackupRulesTest {
    private fun rules(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/$name.xml"))

    private fun exclusions(root: Element): Set<Pair<String, String>> {
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val node = nodes.item(it) as Element
            node.getAttribute("domain") to node.getAttribute("path")
        }.toSet()
    }

    @Test
    fun `credentials stay out of legacy backups cloud backups and device transfers`() {
        val legacy = exclusions(rules("backup_rules").documentElement)
        val modern = rules("data_extraction_rules")
        val sensitive = setOf(
            "sharedpref" to "twig_connections.xml",
            "sharedpref" to "twig_restic_pw.xml",
            "sharedpref" to "twig_archive_pw.xml",
            "sharedpref" to "twig_share.xml",
            "sharedpref" to "twig_stream.xml",
            "sharedpref" to "twig_websites.xml",
            "file" to "keys/",
            "file" to ".ssh/",
            "root" to "app_webview/",
            "root" to "private_space/",
        )
        assertTrue("Legacy backup includes credentials", legacy.containsAll(sensitive))
        for (channel in listOf("cloud-backup", "device-transfer")) {
            val entries = exclusions(modern.getElementsByTagName(channel).item(0) as Element)
            assertTrue("$channel includes credentials", entries.containsAll(sensitive))
            assertEquals("Backup channels must stay in sync", legacy, entries)
        }
    }
}
