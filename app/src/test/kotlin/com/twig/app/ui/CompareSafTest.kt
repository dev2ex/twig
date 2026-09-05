package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.CompareSession
import com.twig.app.SafFileSystem
import com.twig.app.compareSideOf
import com.twig.app.resolveCompareSide
import com.twig.core.XFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A saved comparison with a document tree (SAF) side.
 *
 * Same reasoning as the plain favorite: the grant is persistable, so the document URI
 * is a location that reopens after a restart — while the *name* is not in the path and
 * has to travel alongside it (the path bar shows `XFile.name` for saf).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompareSafTest {

    private lateinit var app: Application

    private val uri =
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM" +
            "/document/primary%3ADCIM%2FPhotos"

    private fun safDir() = XFile(SafFileSystem.SCHEME, uri, isDir = true, displayName = "Photos")

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a document tree side can be saved`() {
        val side = compareSideOf(safDir())
        assertNotNull("a SAF folder must be savable as a compare side", side)
        assertEquals("saf", side!!.kind)
        assertEquals(uri, side.path)
    }

    /** Reopening must hand back a target that still knows its name — the path bar reads it. */
    @Test
    fun `reopening keeps the scheme, the URI and the name`() {
        val side = compareSideOf(safDir())!!
        val back = resolveCompareSide(app, side)
        assertNotNull(back)
        assertEquals(SafFileSystem.SCHEME, back!!.scheme)
        assertEquals(uri, back.path)
        assertEquals("Photos", back.name)
        assertTrue(back.isDir)
    }

    /** The pair goes through SharedPreferences as JSON; both sides must come back whole. */
    @Test
    fun `a saved pair survives the json round trip`() {
        val left = compareSideOf(safDir())!!
        val right = compareSideOf(XFile("file", "/sdcard/DCIM/Photos", isDir = true))!!
        val s = CompareSession("photos", left, right, CompareOptions())
        val back = CompareSession.fromJson(JSONObject(s.toJson().toString()))

        assertEquals(s.id, back.id)
        assertEquals("Photos", resolveCompareSide(app, back.left)?.name)
        assertEquals("/sdcard/DCIM/Photos", resolveCompareSide(app, back.right)?.path)
    }

    /** Restic and archives stay unsupported — a saved side has to reopen without asking. */
    @Test
    fun `archive and restic sides are still refused`() {
        assertNull(compareSideOf(XFile("zip1234", "/inside", isDir = true)))
        assertNull(compareSideOf(XFile("restic1234", "/snap/x", isDir = true)))
    }

    /**
     * Starting a comparison from the panes goes through an Intent, and the name has to
     * ride along: without it the path bar shows the encoded URI — and worse, saving that
     * session would store the encoded string as the folder's name.
     */
    @Test
    fun `the folder name survives the launch intent`() {
        val right = XFile("file", "/sdcard/DCIM/Photos", isDir = true)
        // an Application context cannot start an activity without NEW_TASK; use a real one
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        CompareActivity.start(act, safDir(), right)
        val intent = org.robolectric.Shadows.shadowOf(act).nextStartedActivity

        val (l, r) = CompareActivity.sidesFrom(intent)!!
        assertEquals("Photos", l.name)
        assertEquals(uri, l.path)
        assertTrue(l.isDir)
        assertEquals("Photos", r.name) // a slash-shaped path names itself, with or without the extra
        // and saving that session stores the folder name, not the encoded string
        assertEquals("Photos", compareSideOf(l)!!.pathName)
    }

    /**
     * A SAF file is a file on this device (fd + pread), so it takes the *local* content
     * limit. Left on the network limit it would be compared by size/time only, since
     * that limit defaults to 0.
     */
    @Test
    fun `a SAF side counts as local for content comparison`() {
        assertTrue(isLocalSide(safDir()))
        assertTrue(isLocalSide(XFile("file", "/sdcard/a", isDir = true)))
        assertFalse(isLocalSide(XFile("smb12ab", "/share/a", isDir = true)))
        assertFalse("an entry inside an archive may sit on SMB", isLocalSide(XFile("zip1234", "/a", isDir = true)))
    }
}
