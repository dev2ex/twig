package com.twig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What "Locate current directory" is allowed to read out of a terminal title. See [TermTitle]. */
class TermTitleTest {

    @Test
    fun `absolute path is taken as is`() {
        assertEquals("/var/log", TermTitle.path("/var/log"))
        assertEquals("/", TermTitle.path("/"))
        assertEquals("/storage/emulated/0", TermTitle.path("/storage/emulated/0"))
    }

    @Test
    fun `distribution default prompts put the path after a colon`() {
        // Debian / Ubuntu write "\u@\h: \w"
        assertEquals("~/work", TermTitle.path("vale@nas: ~/work"))
        assertEquals("/etc/nginx", TermTitle.path("root@nas: /etc/nginx"))
        // …others leave the space out
        assertEquals("~", TermTitle.path("vale@nas:~"))
    }

    @Test
    fun `a title that names no path is refused rather than guessed`() {
        assertNull(TermTitle.path("htop"))
        assertNull(TermTitle.path("nas"))
        assertNull(TermTitle.path(""))
        assertNull(TermTitle.path("   "))
        // A colon with nothing path-shaped behind it stays refused
        assertNull(TermTitle.path("vim: notes.txt"))
        assertNull(TermTitle.path("make: *** [all] Error 2"))
    }

    @Test
    fun `tilde stays unexpanded - only the far side knows what it means`() {
        assertEquals("~", TermTitle.path("~"))
        assertEquals("~/src/twig", TermTitle.path("~/src/twig"))
    }

    @Test
    fun `a relative path is not a path we can act on`() {
        assertNull(TermTitle.path("work/twig"))
        assertNull(TermTitle.path("vale@nas: work"))
    }
}
