package com.termux.view

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TermSelectionTest {
    @Test fun `terminal selection uses column row order and follows changed endpoints`() {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Application>(), null)
        val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
            arrayOf(TerminalSessionClient::class.java)) { _, method, _ ->
            if (method.name == "getTerminalCursorStyle") 0 else null
        } as TerminalSessionClient
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        view.mEmulator = TerminalEmulator(output, 80, 24, 100, client)
        val bytes = "ignored\r\nabc first second".toByteArray()
        view.mEmulator.append(bytes, bytes.size)
        val selection = view.getTextSelectionCursorController()
        ReflectionHelpers.setField(selection, "mIsSelectingText", true)
        ReflectionHelpers.setField(selection, "mSelY1", 1)
        ReflectionHelpers.setField(selection, "mSelY2", 1)
        ReflectionHelpers.setField(selection, "mSelX1", 4)
        ReflectionHelpers.setField(selection, "mSelX2", 8)
        assertEquals("first", TermSelection.text(view))
        ReflectionHelpers.setField(selection, "mSelX1", 10)
        ReflectionHelpers.setField(selection, "mSelX2", 15)
        assertEquals("second", TermSelection.text(view))
        ReflectionHelpers.setField(selection, "mIsSelectingText", false)
        assertEquals("", TermSelection.text(view))
    }
}
