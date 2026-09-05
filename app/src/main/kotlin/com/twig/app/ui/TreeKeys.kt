package com.twig.app.ui

import com.twig.app.AppsFileSystem
import com.twig.core.XFile

/**
 * Tree **key encoding** and **"last position" descriptor codec**.
 *
 * Extracted from [PaneViewModel] so that it can be covered by plain JVM unit tests:
 * every method is plain string/data manipulation, no Android, no IO, and when these
 * get wrong they do not crash, they only manifest as "the position is not restored to
 * the original spot" or "the tree is collapsed to the wrong level" — and on a real
 * device that is very hard to walk through systematically.
 *
 * The descriptors deserve testing in particular: they live in SharedPreferences, and
 * **what is read back is data written by the previous version**, so an encode/decode
 * asymmetry means "after an upgrade, position cannot be restored". The two directions
 * here are therefore a pair of inverses ([descriptorOf] / [keyOfDescriptor]) and the
 * tests hammer them via round-trip.
 *
 * Session-scoped data is always passed in by the caller as a lambda (the dynamic
 * scheme ↔ connection label mapping only lives in [PaneViewModel]), so this object
 * keeps zero dependencies.
 */
object TreeKeys {

    /** Unique id for one row on the tree; a dozen tables inside the VM are indexed by it. */
    fun fileKey(file: XFile): String = "f:${file.scheme}:${file.path}"

    // ---- Descriptors ----
    //
    // Format: `kind \t args...`, with the whole list then joined by '\n' for storage
    // (see Prefs.saveLocation).
    //   file\t<absolute path>         local directory
    //   apps\t<path>                  directory inside the "Apps" tree
    //   conn\t<connection label>\t<path>  directory on some server
    //   group\t<group id>             LAN/FTP/... group heading
    //   server\t<connection label>    the server node itself
    //   fav\t<favorite id>            favorite node

    /**
     * Expanded node's key → persistable descriptor; sources that cannot be persisted
     * (inside zip/restic/saf/git) return null.
     *
     * @param connLabelOf dynamic scheme → saved connection label; returns null when
     *   that scheme is not a server.
     */
    fun descriptorOf(key: String, connLabelOf: (scheme: String) -> String?): String? {
        val parts: List<String> = when {
            key.startsWith("f:file:") -> listOf("file", key.removePrefix("f:file:"))
            key.startsWith("f:${AppsFileSystem.SCHEME}:") ->
                listOf("apps", key.removePrefix("f:${AppsFileSystem.SCHEME}:"))
            key.startsWith("f:") -> {
                val body = key.removePrefix("f:")
                val scheme = body.substringBefore(':')
                val path = body.substringAfter(':')
                connLabelOf(scheme)?.let { listOf("conn", it, path) }
            }
            key.startsWith("g:") -> listOf("group", key.removePrefix("g:"))
            key.startsWith("s:") -> listOf("server", key.removePrefix("s:"))
            key.startsWith("fav:") -> listOf("fav", key.removePrefix("fav:"))
            else -> null
        } ?: return null
        // A separator appearing in a path/label will tear this entry (and possibly the
        // whole list) apart. '\t' and '\n' are both legal filename chars on Linux, so
        // when this really happens we'd rather not restore that one node than let it
        // corrupt other entries.
        // ★ Check **each field separately** — never against the joined whole string,
        //   because the '\t' in there is exactly the separator we put there ourselves.
        if (parts.any { field -> SEPARATORS.any { it in field } }) return null
        return parts.joinToString("\t")
    }

    /**
     * Descriptor → tree node key, inverse of [descriptorOf]. Returns null when it
     * cannot be resolved (unknown format, corresponding connection deleted or not yet
     * registered in this session).
     *
     * @param schemeOfConn connection label → scheme registered in this session;
     *   null when absent.
     */
    fun keyOfDescriptor(d: String, schemeOfConn: (label: String) -> String?): String? {
        val p = d.split('\t')
        return when (p.getOrNull(0)) {
            "file" -> p.getOrNull(1)?.let { "f:file:$it" }
            "apps" -> p.getOrNull(1)?.let { "f:${AppsFileSystem.SCHEME}:$it" }
            "conn" -> {
                val label = p.getOrNull(1) ?: return null
                val path = p.getOrNull(2) ?: return null
                schemeOfConn(label)?.let { "f:$it:$path" }
            }
            "group" -> p.getOrNull(1)?.let { "g:$it" }
            "server" -> p.getOrNull(1)?.let { "s:$it" }
            "fav" -> p.getOrNull(1)?.let { "fav:$it" }
            else -> null
        }
    }

    /**
     * Descriptor → the directory [XFile] it points at; only the three kinds that can
     * actually point to a real directory (file/apps/conn) are meaningful — group,
     * server and favorite nodes return null.
     */
    fun dirOfDescriptor(d: String, schemeOfConn: (label: String) -> String?): XFile? {
        val p = d.split('\t')
        return when (p.getOrNull(0)) {
            "file" -> p.getOrNull(1)?.let { XFile("file", it, isDir = true) }
            "apps" -> p.getOrNull(1)?.let {
                XFile(AppsFileSystem.SCHEME, it, isDir = true, canWrite = false)
            }
            "conn" -> {
                val label = p.getOrNull(1) ?: return null
                val path = p.getOrNull(2) ?: return null
                schemeOfConn(label)?.let { XFile(it, path, isDir = true) }
            }
            else -> null
        }
    }

    // ---- Ancestor chain ----

    /** [ancestorKeys] only needs these two things per row. */
    data class Row(val key: String, val depth: Int)

    /**
     * Recover a row's ancestor chain from indentation depth (used by the accordion
     * collapse: collapse everything except that chain). Walk upwards from that row;
     * every time we hit a row at a shallower depth than "what we currently need",
     * collect it and lower the needed depth to its depth, until we reach depth 0.
     * Returns the empty set when [key] is not in [rows].
     */
    fun ancestorKeys(rows: List<Row>, key: String): Set<String> {
        val idx = rows.indexOfFirst { it.key == key }
        if (idx < 0) return emptySet()
        val res = HashSet<String>()
        var need = rows[idx].depth
        for (i in idx - 1 downTo 0) {
            if (rows[i].depth < need) {
                res.add(rows[i].key)
                need = rows[i].depth
                if (need == 0) break
            }
        }
        return res
    }

    private val SEPARATORS = charArrayOf('\t', '\n')
}
