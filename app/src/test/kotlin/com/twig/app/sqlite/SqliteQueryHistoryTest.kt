package com.twig.app.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteQueryHistoryTest {
    @Test fun `recent SQL is bounded deduplicated and isolated by source without executing anything`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("twig_sqlite_queries", Context.MODE_PRIVATE).edit().clear().commit()
        val history = SqliteQueryHistory(context)
        val source = XFile("file", "/not-a-database", false)
        val other = source.copy(scheme = "sftp")
        repeat(12) { history.remember(source, "SELECT $it") }
        assertEquals(10, history.recent(source).size)
        assertEquals("SELECT 11", history.recent(source).first())
        history.remember(source, "SELECT 5")
        assertEquals("SELECT 5", SqliteQueryHistory(context).recent(source).first())
        assertEquals(10, history.recent(source).toSet().size)
        history.remember(other, "SELECT 'remote'")
        assertEquals(listOf("SELECT 'remote'"), history.recent(other))
        history.remember(source, "x".repeat(16385))
        assertEquals("SELECT 5", history.recent(source).first())
        history.clear(source)
        assertTrue(history.recent(source).isEmpty())
        assertEquals(listOf("SELECT 'remote'"), history.recent(other))
    }
}
