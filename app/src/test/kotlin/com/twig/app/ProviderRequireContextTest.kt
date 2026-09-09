package com.twig.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ★ Regression: `ContentProvider.requireContext()` is **API 30**, and calling it compiles
 * cleanly at minSdk 24 (2026-09-09, LM-G710 / Android 9, version 1.9.0).
 *
 * Inside a `Fragment` — the class this idiom comes from — `requireContext()` has existed
 * forever, so the call reads as harmless and slipped into [StreamProvider] unnoticed. On any
 * device below API 30 every provider entry point then throws
 * `NoSuchMethodError: No virtual method requireContext()`, and it throws on **the caller's**
 * thread (media3's `MetadataRetriever`, ExoPlayer's loader), far outside any of our
 * `runCatching` — so the process dies. The reported symptom was "tapping track info in the
 * music page shows nothing and closes the page".
 *
 * A device below API 30 is not in the test matrix and lint's `NewApi` cannot be enforced here
 * (the module carries a large backlog of unrelated lint errors), so the rule is pinned as a
 * source check: a `ContentProvider` uses `requireNotNull(context)`, never `requireContext()`.
 */
class ProviderRequireContextTest {

    @Test
    fun `content providers never call requireContext`() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val text = f.readText()
                // ★ Comments are stripped first — the doc comment on StreamProvider.ctx()
                // explains this very rule and names the call it forbids
                ": ContentProvider(" in text && text.lineSequence().any { line ->
                    val code = line.trim()
                    !code.startsWith("*") && !code.startsWith("//") && "requireContext()" in code
                }
            }
            .map { it.path }
            .toList()
        assertTrue(
            "ContentProvider.requireContext() is API 30 — use requireNotNull(context): $offenders",
            offenders.isEmpty(),
        )
    }
}
