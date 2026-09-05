package com.twig.fs.archive

/**
 * Thrown when an archive needs a password to read.
 * [wrong] = false means no password has been supplied yet;
 * [wrong] = true means the password that was supplied is incorrect.
 * The UI uses these two cases to prompt "enter password" vs. "wrong password, try again".
 *
 * The message is in English — this pure-JVM module has no Context/R (see CLAUDE.md's
 * "Conventions" / strings section); the app layer catches this type and overrides
 * the message via strings.xml so the user never sees the English.
 */
class ArchivePasswordException(
    val archivePath: String,
    val wrong: Boolean = false,
) : RuntimeException(
    if (wrong) "Wrong password for archive: $archivePath" else "Password required for archive: $archivePath",
)
