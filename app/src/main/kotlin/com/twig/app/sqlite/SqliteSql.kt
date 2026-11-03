package com.twig.app.sqlite

import android.content.Context
import com.twig.app.R

/** SQLite still parses the grammar; this lexer only enforces a single read-only entry point. */
internal object SqliteSql {
    data class Statement(val sql: String)
    private val pragmaArguments = setOf("table_info", "table_xinfo", "index_info", "index_xinfo",
        "index_list", "foreign_key_list", "integrity_check", "quick_check", "table_list")
    private val pragmaGetters = pragmaArguments + setOf("database_list", "compile_options", "collation_list",
        "encoding", "user_version", "schema_version", "application_id", "page_size", "page_count",
        "freelist_count", "journal_mode", "locking_mode", "synchronous", "auto_vacuum", "cache_size",
        "query_only", "foreign_keys", "recursive_triggers", "temp_store", "secure_delete", "busy_timeout",
        "mmap_size", "max_page_count", "read_uncommitted", "reverse_unordered_selects", "trusted_schema",
        "function_list", "module_list", "pragma_list")

    fun statement(context: Context, input: String): Statement {
        val invalid = context.getString(R.string.sqlite_sql_read_only)
        val tokens = mutableListOf<String>()
        var index = 0
        var end = input.length
        var terminated = false
        while (index < input.length) {
            val c = input[index]
            when {
                c.isWhitespace() -> index++
                input.startsWith("--", index) -> {
                    index = input.indexOf('\n', index + 2).let { if (it < 0) input.length else it + 1 }
                }
                input.startsWith("/*", index) -> {
                    val close = input.indexOf("*/", index + 2)
                    require(close >= 0) { invalid }
                    index = close + 2
                }
                else -> {
                    require(!terminated) { context.getString(R.string.sqlite_sql_single_statement) }
                    if (c == ';') {
                        terminated = true; end = index; index++; continue
                    }
                    if (c in "'\"`[") {
                        val close = if (c == '[') ']' else c
                        val start = ++index
                        var closed = false
                        while (index < input.length) {
                            if (input[index++] != close) continue
                            if (close != ']' && index < input.length && input[index] == close) { index++; continue }
                            closed = true; break
                        }
                        require(closed) { invalid }
                        tokens.add(input.substring(start, index - 1))
                    } else if (c.isLetterOrDigit() || c == '_') {
                        val start = index++
                        while (index < input.length && (input[index].isLetterOrDigit() || input[index] == '_')) index++
                        tokens.add(input.substring(start, index).lowercase(java.util.Locale.ROOT))
                    } else { tokens.add(c.toString()); index++ }
                }
            }
        }
        val first = tokens.firstOrNull()
        require(first in setOf("select", "with", "values", "pragma", "explain")) { invalid }
        if (first == "pragma") {
            val nameIndex = if (tokens.getOrNull(2) == ".") 3 else 1
            val name = tokens.getOrNull(nameIndex)?.lowercase(java.util.Locale.ROOT)
            val args = tokens.drop(nameIndex + 1)
            require(name in pragmaGetters && (args.isEmpty() ||
                name in pragmaArguments && args.first() == "(" && args.last() == ")" && "=" !in args)) { invalid }
        }
        return Statement(input.substring(0, end).trim())
    }
}
