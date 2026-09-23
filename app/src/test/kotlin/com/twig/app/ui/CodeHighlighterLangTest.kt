package com.twig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comment and variable rules of the languages whose syntax does not follow the C family.
 * Token kinds are indices into the theme palette: 0 keyword, 1 string, 2 comment, 4 annotation.
 */
class CodeHighlighterLangTest {

    private fun spans(name: String, text: String, kind: Int): List<String> {
        val lang = CodeHighlighter.langFor(name)
        assertNotNull(name, lang)
        return CodeHighlighter.tokenize(text, lang!!).filter { it.kind == kind }
            .map { text.substring(it.start, it.end).trim() }
    }

    @Test fun luaBlockCommentIsNotCutShortByTheLineComment() {
        val src = "--[[ one\ntwo ]]\nlocal x = 1 -- tail"
        assertEquals(listOf("--[[ one\ntwo ]]", "-- tail"), spans("a.lua", src, 2))
        assertTrue("local" in spans("a.lua", src, 0))
    }

    @Test fun rubyBeginEndBlock() {
        val src = "=begin\ndoc\n=end\ndef f; @x end # c"
        assertEquals(listOf("=begin\ndoc\n=end", "# c"), spans("a.rb", src, 2))
        assertEquals(listOf("@x"), spans("a.rb", src, 4))
    }

    @Test fun perlPodAndSigils() {
        val src = "my \$x = 1;\n=head1 NAME\nfoo\n=cut\nprint \$x; # done"
        assertEquals(listOf("=head1 NAME\nfoo\n=cut", "# done"), spans("a.pl", src, 2))
        assertEquals(listOf("\$x", "\$x"), spans("a.pl", src, 4))
    }

    @Test fun phpHashCommentsVariablesAndCaseInsensitiveKeywords() {
        val src = "<?php\nFUNCTION f(\$a) { return \$a; } # c\n// d"
        assertEquals(listOf("# c", "// d"), spans("a.php", src, 2))
        assertTrue("FUNCTION" in spans("a.php", src, 0))
        assertEquals(listOf("\$a", "\$a"), spans("a.php", src, 4))
    }

    @Test fun csharpPreprocessorAndKeywords() {
        val src = "#region x\nnamespace N { class C { string s = \"q\"; } }"
        assertEquals(listOf("#region"), spans("a.cs", src, 4))
        assertTrue(spans("a.cs", src, 0).containsAll(listOf("namespace", "class", "string")))
    }
}
