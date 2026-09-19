package com.twig.app.ui

/**
 * Reading a directory out of a terminal title.
 *
 * A title is free text — whatever the running program decided to say — so this refuses far more
 * than it accepts. It is its own file, and unit-tested, because the accepted shapes come from what
 * real prompts write and a silent change here would send "Locate current directory" to the wrong
 * place with no error anywhere.
 */
object TermTitle {

    /**
     * The directory the title names, or null when it names none.
     *
     * Accepted: an absolute path, and a `~` one — the latter left unexpanded, because only the
     * machine the session runs on knows what `~` stands for. The path may sit after a colon, which
     * is how every mainstream distribution's default prompt writes it (`user@host: ~/work` on
     * Debian and Ubuntu, `user@host:~/work` elsewhere).
     *
     * A title naming a *file* (`vim: ~/notes.txt`) parses as that path: a title cannot say whether
     * it means a file or a directory, and the pane reports what it finds. Everything that does not
     * look like a path at all — `htop`, a bare hostname — is null, so the caller can say "this
     * session has not reported a directory" instead of guessing.
     */
    fun path(title: String): String? = candidate(title) ?: candidate(title.substringAfterLast(':', ""))

    private fun candidate(s: String): String? = s.trim()
        .takeIf { it == "~" || it.startsWith("/") || it.startsWith("~/") }
}
