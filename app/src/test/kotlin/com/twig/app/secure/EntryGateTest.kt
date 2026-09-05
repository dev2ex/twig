package com.twig.app.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Every exported Activity must pass through the master password gate.**
 *
 * Miss one and the master password only locks the front door — and these entry points
 * can all see files and reach servers: "Open with Twig" can open a viewer and even mount
 * an archive onto the tree; "Copy to here" can write into any directory; "Choose files"
 * exposes the whole tree; a remote-command shortcut runs SSH straight away with the saved
 * password.
 *
 * The check uses **source scanning** rather than launching every Activity: the latter
 * would need building a full intent, permissions and external dependencies for each of
 * six Activities — slow and brittle — while what this guards against is precisely "a new
 * entry point was added and someone forgot to wire it up", which is a static fact best
 * checked statically.
 *
 * When adding a new exported Activity: either wire up `SecurityUi.gate`, or write clearly
 * below why it does not need to.
 */
class EntryGateTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val srcRoot = File("src/main/kotlin/com/twig/app")

    /** Explicitly exempt from the gate: none right now. Add one here with a stated reason. */
    private val exempt = emptySet<String>()

    @Test
    fun `every exported Activity has wired up SecurityUi_gate`() {
        val exported = exportedActivities()
        assertTrue("MainActivity at least should be found — if not, the parsing is wrong", exported.contains(".MainActivity"))

        val missing = exported.filter { it !in exempt }.filter { name ->
            val simple = name.substringAfterLast('.')
            val src = srcRoot.walkTopDown().firstOrNull { it.name == "$simple.kt" }
                ?: return@filter true // not finding the source is itself a problem
            "SecurityUi.gate(" !in src.readText()
        }
        assertEquals("these entry points do not pass through the master password gate - miss one and the master password only locks the front door", emptyList<String>(), missing)
    }

    /**
     * A launcher shortcut is an entry point too, and one that skips the main UI —
     * so whatever [com.twig.app.ui.Shortcuts] points at must gate as well, whether
     * or not that activity is exported (the system launches it as this app).
     */
    @Test
    fun `every launcher shortcut target gates on the master password`() {
        val publisher = File("src/main/kotlin/com/twig/app/ui/Shortcuts.kt").readText()
        val targets = Regex("""Intent\(act, (\w+)::class\.java\)""").findAll(publisher)
            .map { it.groupValues[1] }.toList()
        assertTrue("no shortcut target found — the parsing is wrong", targets.isNotEmpty())

        val missing = targets.filter { simple ->
            val src = srcRoot.walkTopDown().firstOrNull { it.name == "$simple.kt" }
                ?: return@filter true
            "SecurityUi.gate(" !in src.readText()
        }
        assertEquals("a launcher shortcut walks straight past the master password", emptyList<String>(), missing)
    }

    /**
     * These two headless relay pages must use an **AppCompat theme**: AppCompat's
     * AlertDialog requires the host to have an AppCompat theme, and pairing it with the
     * platform's `Theme.Translucent` crashes the moment the unlock dialog pops up
     * (`You need to use a Theme.AppCompat theme`) — and these are precisely the two paths
     * most easily forgotten.
     */
    @Test
    fun `the headless relay pages use the AppCompat translucent theme`() {
        assertTrue(
            "do not fall back to @android:style/Theme.Translucent - the unlock dialog would crash on the spot",
            "@android:style/Theme.Translucent" !in manifest,
        )
        assertTrue("Theme.Twig.Translucent" in manifest)
    }

    /** The android:name inside `<activity ... android:exported="true">`. */
    private fun exportedActivities(): List<String> =
        Regex("""<activity\b[^>]*?>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .map { it.value }
            .filter { "android:exported=\"true\"" in it }
            .mapNotNull { Regex("""android:name="([^"]+)"""").find(it)?.groupValues?.get(1) }
            .toList()
}
