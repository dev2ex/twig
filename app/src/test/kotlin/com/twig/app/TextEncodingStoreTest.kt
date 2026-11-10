package com.twig.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextEncodingStoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        ctx.getSharedPreferences("twig_text_encodings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `choices belong to full paths rather than names or file sizes`() {
        val first = XFile("file", "/first/note.txt", false, size = 10)
        val second = first.copy(path = "/second/note.txt")
        TextEncodingStore.put(ctx, first, Charsets.UTF_16LE)
        assertNull(TextEncodingStore.get(ctx, second))
        assertEquals(Charsets.UTF_16LE, TextEncodingStore.get(ctx, first.copy(size = 20)))
        TextEncodingStore.put(ctx, first, null)
        assertNull(TextEncodingStore.get(ctx, first))
    }

    @Test fun `stored choices have a fixed upper bound`() {
        repeat(205) {
            TextEncodingStore.put(ctx, XFile("file", "/note-$it.txt", false), Charsets.UTF_16BE)
        }
        assertEquals(200, ctx.getSharedPreferences("twig_text_encodings", Context.MODE_PRIVATE).all.size)
        assertEquals(Charsets.UTF_16BE, TextEncodingStore.get(ctx, XFile("file", "/note-204.txt", false)))
    }
}
