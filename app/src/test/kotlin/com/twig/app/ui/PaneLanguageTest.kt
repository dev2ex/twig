package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 32], application = Application::class, qualifiers = "en-rUS")
class PaneLanguageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun cleanup() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    @Test fun `retained pane translates roots and groups when app language changes`() {
        val pane = PaneViewModel(app)
        for ((tag, storage, favorites) in listOf(
            Triple("zh", "内部存储", "收藏"),
            Triple("de", "Interner Speicher", "Favoriten"),
            Triple("en", "Internal Storage", "Favorites"),
            Triple("zh", "内部存储", "收藏"),
            Triple("", "Internal Storage", "Favorites"),
        )) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            pane.refreshTree()
            val rows = pane.state.value.rows
            assertEquals(tag, storage, rows.filterIsInstance<PaneViewModel.FileNode>()
                .first { it.file.scheme == "file" &&
                    it.file.path == Environment.getExternalStorageDirectory().absolutePath }.label)
            assertEquals(tag, favorites, rows.filterIsInstance<PaneViewModel.GroupNode>()
                .first { it.key == "g:fav" }.label)
            // This is the pre-33 distinction that previously kept the pane in English.
            assertEquals("Internal Storage", app.getString(R.string.group_internal_storage))
        }
    }
}
