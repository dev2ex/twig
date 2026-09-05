package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.TextCodec
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset

/**
 * "Write back in whatever encoding it was read with" -- the step in the text viewer most
 * likely to destroy a file.
 *
 * Writing everything back as UTF-8 would show **no visible symptom inside Twig itself**
 * (it reads UTF-8 fine too), yet the user edits one line and destroys the whole file: any
 * other program opening it afterward sees the original content turned to mojibake. This
 * path cannot be caught by eyeballing the UI -- it has to be pinned down at the byte level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextFileCharsetTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val gbk: Charset = Charset.forName("GB18030")
    private lateinit var dir: File

    @Before
    fun setUp() {
        FsRegistry.register(LocalFileSystem())
        Prefs.setTextCharsets(app, listOf("GB18030"))
        TextCodec.load(app)
        dir = File.createTempFile("twig-text", "").let { it.delete(); it.mkdirs(); it }
    }

    private fun write(name: String, bytes: ByteArray): XFile {
        val f = File(dir, name)
        f.writeBytes(bytes)
        return XFile("file", f.path, isDir = false, size = f.length())
    }

    @Test
    fun `a GB18030 file reads back as GB18030, and saving one edited line still writes GB18030`() {
        val file = write("note.txt", "第一行\n第二行\n".toByteArray(gbk))

        val read = readTextFile(file, 1 shl 20)
        assertEquals("第一行\n第二行\n", read.text)
        assertEquals(gbk, read.charset)

        val edited = read.text.replace("第二行", "改过的第二行")
        app.writeAtomically(file, TextCodec.encode(edited, read.charset!!, read.bom)!!)

        assertArrayEquals(edited.toByteArray(gbk), File(file.path).readBytes())
        // reading it again still recognizes it -- this is what proves it was really written back as GB18030, not just "happens to also decode"
        assertEquals("改过的第二行", readTextFile(file, 1 shl 20).text.lines()[1])
    }

    @Test
    fun `a UTF-8 BOM survives being written back`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = write("bom.txt", bom + "hello".toByteArray())

        val read = readTextFile(file, 1 shl 20)
        assertEquals("hello", read.text)
        app.writeAtomically(file, TextCodec.encode(read.text + "!", read.charset!!, read.bom)!!)

        assertArrayEquals(bom + "hello!".toByteArray(), File(file.path).readBytes())
    }

    /** When no candidate is checked, a GB18030 file is simply "encoding unrecognized": viewable (as mojibake), but not allowed to be written back. */
    @Test
    fun `a file with an unrecognized encoding is not allowed to be written back`() {
        Prefs.setTextCharsets(app, emptyList())
        TextCodec.load(app)
        val file = write("cn.txt", "中文测试".toByteArray(gbk))

        assertNull(readTextFile(file, 1 shl 20).charset)
    }

    /**
     * Truncation is **something we cut ourselves** (only the leading portion is read past
     * the size cap), and the cut point can easily land in the middle of a multi-byte
     * character. That must not count as "the file's encoding is broken" -- truncation
     * already disables editing on its own, there is no need to stack a second reason on
     * top.
     */
    @Test
    fun `a truncated file does not count as having an encoding problem`() {
        val file = write("big.txt", "中文".repeat(100).toByteArray(Charsets.UTF_8))
        val read = readTextFile(file, 51) // 3 bytes per Chinese character, 51 lands mid-character
        assert(read.truncated)
        assertEquals(Charsets.UTF_8, read.charset)
    }
}
