package com.twig.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlighterLimitsTest {
    private fun tokens(name: String, text: String) =
        CodeHighlighter.tokenize(text, requireNotNull(CodeHighlighter.langFor(name)))

    @Test fun minifiedXmlBelowFileSizeLimitSkipsHighlighting() {
        val text = "<node value=\"text\"/>".repeat(4500)
        assertTrue(text.length < CodeHighlighter.MAX_HIGHLIGHT)
        assertTrue(tokens("dump.xml", text).isEmpty())
    }

    @Test fun shortSingleLineCodeStillHighlights() {
        assertFalse(tokens("script.js", "const answer = 42;").isEmpty())
    }

    @Test fun multilineFileWithOneLongLineSkipsHighlighting() {
        val text = "const answer = 42;\n" + "x".repeat(9000) + "\nreturn answer;"
        assertTrue(tokens("script.js", text).isEmpty())
    }

    @Test fun similarlySizedMultilineXmlStillHighlights() {
        val text = "<node value=\"text\"/>\n".repeat(4500)
        assertFalse(tokens("dump.xml", text).isEmpty())
    }
}
