package com.twig.app.ui

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TreeKeys]: encoding/decoding between tree keys and the "last position" descriptor.
 *
 * The focus is the **round-trip**: the descriptor lives in SharedPreferences, and the next
 * cold start reads back data **written by the previous version**. Any asymmetry between
 * encoding and decoding shows up as "position doesn't restore after an upgrade" -- no
 * crash, no error, the position is just gone, and that is very hard to catch systematically
 * on a real device.
 */
class TreeKeysTest {

    /** This session: one already-connected server plus one whose config still exists but is not connected. */
    private val schemeOfConn: (String) -> String? = { label ->
        when (label) {
            "sftp://root@nas:22" -> "sftp1a2b"
            else -> null // deleted, or never expanded this session
        }
    }
    private val connLabelOf: (String) -> String? = { scheme ->
        if (scheme == "sftp1a2b") "sftp://root@nas:22" else null
    }

    private fun roundTrip(key: String) {
        val d = TreeKeys.descriptorOf(key, connLabelOf)
        assertTrue("should be encodable: $key", d != null)
        assertEquals("round-trip asymmetric: $key -> $d", key, TreeKeys.keyOfDescriptor(d!!, schemeOfConn))
    }

    // ---- fileKey ----

    @Test
    fun fileKeyEncodesSchemeAndPath() {
        assertEquals("f:file:/sdcard/a.txt", TreeKeys.fileKey(XFile("file", "/sdcard/a.txt", false)))
        // the same name from different sources must be different keys, or rows from two trees would clobber each other in various tables
        assertTrue(
            TreeKeys.fileKey(XFile("file", "/x", true)) !=
                TreeKeys.fileKey(XFile("smb1a2b", "/x", true)),
        )
    }

    // ---- descriptor round-trip ----

    @Test
    fun roundTripsEveryPersistableKind() {
        roundTrip("f:file:/sdcard/Download")
        roundTrip("f:apps:/user")
        roundTrip("f:sftp1a2b:/home/root/code")
        roundTrip("g:lan")
        roundTrip("s:sftp://root@nas:22")
        roundTrip("fav:abc123")
    }

    @Test
    fun roundTripsAwkwardButLegalPaths() {
        roundTrip("f:file:/sdcard/带空格 和中文/x")
        roundTrip("f:file:/sdcard/a:b:c")   // a colon inside the path, must not be confused with the scheme separator
        roundTrip("f:file:/")               // root
    }

    /** The local/apps prefixes must not be stolen by the general `f:` branch (order-sensitive). */
    @Test
    fun localAndAppsTakePrecedenceOverConnBranch() {
        assertEquals("file\t/sdcard", TreeKeys.descriptorOf("f:file:/sdcard", connLabelOf))
        assertEquals("apps\t/user", TreeKeys.descriptorOf("f:apps:/user", connLabelOf))
    }

    // ---- non-persistable / undecodable cases ----

    @Test
    fun unpersistableSourcesEncodeToNull() {
        // inside an archive, restic, SAF, the git virtual tree: there is no way to store "how to get back here", so it must not be written into the descriptor
        assertNull(TreeKeys.descriptorOf("f:zip:/sdcard/a.zip!/inner", connLabelOf))
        assertNull(TreeKeys.descriptorOf("f:restic9f/latest", connLabelOf))
        assertNull(TreeKeys.descriptorOf("f:saf:content%3A%2F%2Fx", connLabelOf))
        assertNull(TreeKeys.descriptorOf("restic:f:file:/x", connLabelOf))
    }

    /**
     * ★ A path containing '\t' / '\n' (both legal filename characters on Linux) must be
     * dropped entirely: the descriptor is tab-separated and the whole list is
     * newline-separated, so letting one through would tear apart **other** entries too.
     * Better to leave this one node unrestored.
     */
    @Test
    fun pathsWithSeparatorsAreDropped() {
        assertNull(TreeKeys.descriptorOf("f:file:/sdcard/tab\there", connLabelOf))
        assertNull(TreeKeys.descriptorOf("f:file:/sdcard/new\nline", connLabelOf))
    }

    /** A deleted connection -> cannot produce a descriptor; not connected this session -> cannot decode a key. */
    @Test
    fun missingConnectionFailsBothDirections() {
        assertNull("must not emit a conn descriptor when the connection is deleted", TreeKeys.descriptorOf("f:ftp9z9z:/pub", connLabelOf))
        assertNull(
            "cannot resolve a scheme when the server was not connected this session",
            TreeKeys.keyOfDescriptor("conn\tftp://other:21\t/pub", schemeOfConn),
        )
    }

    @Test
    fun malformedDescriptorsDecodeToNull() {
        assertNull(TreeKeys.keyOfDescriptor("", schemeOfConn))
        assertNull(TreeKeys.keyOfDescriptor("bogus\t/x", schemeOfConn))
        assertNull(TreeKeys.keyOfDescriptor("file", schemeOfConn))          // missing argument
        assertNull(TreeKeys.keyOfDescriptor("conn\tsftp://root@nas:22", schemeOfConn)) // missing path
    }

    // ---- dirOfDescriptor ----

    @Test
    fun dirOfDescriptorBuildsNavigableDirs() {
        val local = TreeKeys.dirOfDescriptor("file\t/sdcard/Download", schemeOfConn)!!
        assertEquals("file", local.scheme)
        assertEquals("/sdcard/Download", local.path)
        assertTrue(local.isDir)

        val conn = TreeKeys.dirOfDescriptor("conn\tsftp://root@nas:22\t/home", schemeOfConn)!!
        assertEquals("sftp1a2b", conn.scheme)
        assertEquals("/home", conn.path)

        // the "Apps" tree is read-only
        assertEquals(false, TreeKeys.dirOfDescriptor("apps\t/user", schemeOfConn)!!.canWrite)
    }

    /** A group/server/favorite is not "some directory" and must not be resolved as a navigable directory. */
    @Test
    fun nonDirectoryKindsHaveNoDir() {
        assertNull(TreeKeys.dirOfDescriptor("group\tlan", schemeOfConn))
        assertNull(TreeKeys.dirOfDescriptor("server\tsftp://root@nas:22", schemeOfConn))
        assertNull(TreeKeys.dirOfDescriptor("fav\tabc", schemeOfConn))
    }

    // ---- ancestor chain (accordion collapse) ----

    private val rows = listOf(
        TreeKeys.Row("g:fav", 0),
        TreeKeys.Row("f:file:/sdcard", 0),
        TreeKeys.Row("f:file:/sdcard/a", 1),
        TreeKeys.Row("f:file:/sdcard/a/x", 2),
        TreeKeys.Row("f:file:/sdcard/a/x/deep", 3),
        TreeKeys.Row("f:file:/sdcard/b", 1),
        TreeKeys.Row("g:lan", 0),
    )

    @Test
    fun ancestorsWalkUpByIndentation() {
        assertEquals(
            setOf("f:file:/sdcard", "f:file:/sdcard/a", "f:file:/sdcard/a/x"),
            TreeKeys.ancestorKeys(rows, "f:file:/sdcard/a/x/deep"),
        )
    }

    @Test
    fun ancestorsStopAtTopLevel() {
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(rows, "f:file:/sdcard"))
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(rows, "g:lan"))
    }

    /** Collects only ancestors, not sibling rows at the same level, and not rows from an earlier branch of the tree. */
    @Test
    fun ancestorsExcludeSiblingsAndEarlierBranches() {
        val a = TreeKeys.ancestorKeys(rows, "f:file:/sdcard/b")
        assertEquals(setOf("f:file:/sdcard"), a)
    }

    @Test
    fun unknownKeyHasNoAncestors() {
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(rows, "f:file:/nope"))
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(emptyList(), "anything"))
    }
}
