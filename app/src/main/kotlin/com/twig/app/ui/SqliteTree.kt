package com.twig.app.ui

import android.content.Context
import android.os.CancellationSignal
import com.twig.app.R
import com.twig.app.sqlite.SqliteCell
import com.twig.app.sqlite.SqlitePage
import com.twig.app.sqlite.SqliteSession
import com.twig.app.sqlite.SqliteTable
import com.twig.app.sqlite.SqliteSort
import com.twig.core.XFile
import kotlinx.coroutines.*

class SqliteViewport(var x: Float = 0f, var y: Float = 0f)

data class SqliteTableState(
    val table: SqliteTable,
    val query: String? = null,
    val sort: SqliteSort? = null,
    val expanded: Boolean = false,
    val properties: Boolean = false,
    val tab: Int = 0,
    val count: Long? = null,
    val counting: Boolean = true,
    val pages: Map<Int, SqlitePage> = emptyMap(),
    val pending: Set<Int> = emptySet(),
    val errors: Map<Int, String> = emptyMap(),
    val end: Int? = null,
    val loadedThrough: Int = 0,
    val viewport: SqliteViewport = SqliteViewport(),
    val columnsViewport: SqliteViewport = SqliteViewport(),
) {
    val pageSize get() = SqliteSession.pageSize(table)
    val rowCount: Int get() = listOfNotNull(count?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), end).minOrNull()
        ?: (loadedThrough.toLong() + pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

sealed interface SqliteNode : PaneViewModel.Node {
    val owner: String
    val state: SqliteTableState
    val tableKey: String get() = owner + (state.query?.let { "/search:${it.length}:$it" } ?: "") +
        "/sqlite:${state.table.name.length}:${state.table.name}"
}
data class SqliteTableNode(override val owner: String, override val depth: Int,
    override val state: SqliteTableState) : SqliteNode { override val key get() = tableKey }
data class SqliteDataNode(override val owner: String, override val depth: Int,
    override val state: SqliteTableState) : SqliteNode { override val key get() = "$tableKey/data" }
data class SqliteInfoNode(override val owner: String, override val depth: Int,
    override val state: SqliteTableState) : SqliteNode { override val key get() = "$tableKey/info" }

data class SqliteSearchNode(val owner: String, override val depth: Int, val query: String,
    val matches: Long, val scanning: Boolean, val failures: Int, val table: String? = null) : PaneViewModel.Node {
    override val key get() = "$owner/sqlite-search"
}

enum class SqliteCommand { TOGGLE, PROPERTIES, REFRESH, COLUMNS, DDL, CLOSE }

/** Owns browsing leases/jobs; tree nodes contain immutable results, never cursors or database handles. */
internal class SqliteTree(private val context: Context, private val scope: CoroutineScope,
    private val io: () -> CoroutineDispatcher, private val changed: () -> Unit) {
    private class Search(val query: String, val table: String? = null) {
        var job: Job? = null
        var scanning = true
        var failures = 0
        val tables = LinkedHashMap<String, SqliteTableState>()
    }
    private class Host(val file: XFile, val parent: XFile?) {
        var job: Job? = null
        var session: SqliteSession? = null
        var error: String? = null
        var loading = true
        val tables = LinkedHashMap<String, SqliteTableState>()
        var search: Search? = null
        val pages = HashMap<Triple<String, String?, Int>, Job>()
        fun tables(query: String?) = if (query == null) tables else search?.takeIf { it.query == query }?.tables
        fun ownerJob(query: String?) = if (query == null) job else search?.takeIf { it.query == query }?.job
    }
    private val hosts = HashMap<String, Host>()
    private val verified = HashSet<String>()
    fun canSearch(key: String) = key in verified
    fun loading(key: String) = hosts[key]?.loading == true

    /** Cancellation reaches SQLite even while the IO thread is inside a native query. */
    private suspend fun <T> cancellable(block: (CancellationSignal) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try { withContext(io()) { block(signal) } } finally { watcher.cancel() }
    }

    fun open(key: String, file: XFile, parent: XFile? = null, remembered: Map<String, SqliteTableState> = emptyMap(), query: String? = null, searchTable: String? = null) {
        if (hosts.containsKey(key)) return
        verified.remove(key)
        val host = Host(file, parent).apply { search = query?.let { Search(it, searchTable) } }
        hosts[key] = host
        host.job = scope.launch {
            // Keep the lease local until cancellation cleanup, including cancellation during open.
            var session: SqliteSession? = null
            try {
                cancellable { session = SqliteSession.open(context, file, it, parent) }
                val opened = checkNotNull(session)
                val tables = cancellable { opened.tables(it) }
                host.session = opened
                verified.add(key)
                tables.forEach { table ->
                    host.tables[table.name] = remembered[table.name]?.copy(table = table, count = null,
                        counting = true, pages = emptyMap(), pending = emptySet(), errors = emptyMap(), end = null,
                        sort = remembered[table.name]?.sort?.takeIf { sort -> table.visibleColumns.any { it.name == sort.column } })
                        ?: SqliteTableState(table)
                }
                host.loading = false
                runSearch(key, host)
                changed()
                // One count at a time; slow counts time out and never become a false zero.
                launch {
                    for (table in tables) {
                        val count = try {
                            withTimeoutOrNull(3000) { cancellable { opened.count(table, it) } }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        host.tables[table.name]?.let { host.tables[table.name] = it.copy(count = count, counting = false) }
                        changed()
                    }
                }
                awaitCancellation()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                host.error = e.message ?: context.getString(R.string.sqlite_open_failed)
                host.loading = false
                changed()
            } finally {
                withContext(NonCancellable + io()) { session?.close() }
            }
        }
    }

    fun search(key: String, query: String, table: String? = null) {
        val host = hosts[key] ?: return
        closeSearch(key)
        host.search = Search(query, table)
        runSearch(key, host)
        changed()
    }

    private fun runSearch(key: String, host: Host) {
        val search = host.search ?: return
        val session = host.session ?: return
        if (search.job != null) return
        search.job = scope.launch(context = checkNotNull(host.job), start = CoroutineStart.LAZY) {
            for (table in host.tables.values.map { it.table }.filter { search.table == null || it.name == search.table }) {
                try {
                    val count = cancellable { session.count(table, it, search.query) }
                    if (count > 0) {
                        val state = SqliteTableState(table, query = search.query, count = count, counting = false)
                        search.tables[table.name] = state
                        if (search.table != null) command(SqliteTableNode(key, 0, state), SqliteCommand.TOGGLE)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { search.failures++ }
                changed()
            }
            search.scanning = false
            changed()
            // Keep a parent job for result pages and detail reads until this search closes.
            awaitCancellation()
        }
        search.job?.start()
    }

    fun closeSearch(key: String) {
        val host = hosts[key] ?: return
        val search = host.search ?: return
        host.search = null
        search.job?.cancel()
        host.pages.keys.filter { it.second == search.query }.forEach { host.pages.remove(it)?.cancel() }
    }

    fun rows(key: String, depth: Int): List<PaneViewModel.Node> {
        val host = hosts[key] ?: return emptyList()
        val error = host.error
        if (error != null) return listOf(PaneViewModel.ActionNode("sqlite_error:$key", error, depth, informational = true))
        return buildList {
            fun addContent(state: SqliteTableState, contentDepth: Int) {
                if (state.properties) add(SqliteInfoNode(key, contentDepth, state))
                if (state.expanded) add(SqliteDataNode(key, contentDepth, state))
            }
            fun addSearch(search: Search, searchDepth: Int) {
                add(SqliteSearchNode(key, searchDepth, search.query, search.tables.values.sumOf { it.count ?: 0L },
                    search.scanning, search.failures, search.table))
                search.tables.values.forEach { state ->
                    if (search.table == null) add(SqliteTableNode(key, searchDepth + 1, state))
                    addContent(state, searchDepth + if (search.table == null) 2 else 1)
                }
                if (search.failures > 0) add(PaneViewModel.ActionNode("sqlite_search_error:$key",
                    context.getString(R.string.sqlite_search_incomplete, search.failures), searchDepth + 1, informational = true))
            }
            host.search?.takeIf { it.table == null }?.let { addSearch(it, depth) }
            if (!host.loading && host.tables.isEmpty()) add(PaneViewModel.ActionNode("sqlite_empty:$key",
                context.getString(R.string.sqlite_no_tables), depth, informational = true))
            host.tables.values.forEach { state ->
                val search = host.search?.takeIf { it.table == state.table.name }
                add(SqliteTableNode(key, depth, if (search == null) state else state.copy(expanded = true)))
                if (search == null) addContent(state, depth + 1)
                else {
                    if (state.properties) add(SqliteInfoNode(key, depth + 1, state))
                    addSearch(search, depth + 1)
                }
            }
        }
    }

    fun command(node: SqliteNode, command: SqliteCommand) {
        val host = hosts[node.owner] ?: return
        val name = node.state.table.name
        val query = node.state.query
        val tables = host.tables(query) ?: return
        val state = tables[name] ?: return
        if (command == SqliteCommand.TOGGLE && query == null && host.search?.table == name) {
            closeSearch(node.owner)
            if (!state.expanded) {
                changed()
                return
            }
        }
        when (command) {
            SqliteCommand.TOGGLE -> {
                if (!state.expanded) {
                    // Normal tables and search results share one accordion within the database.
                    for (bucket in listOfNotNull(host.tables, host.search?.tables)) {
                        for ((other, previous) in bucket.toMap()) {
                            if (bucket === tables && other == name || !previous.expanded) continue
                            cancelPages(host, other, previous.query)
                            bucket[other] = previous.copy(expanded = false,
                                pages = emptyMap(), pending = emptySet(), errors = emptyMap(), end = null,
                                loadedThrough = 0, viewport = SqliteViewport())
                        }
                    }
                }
                tables[name] = state.copy(expanded = !state.expanded,
                    pages = emptyMap(), pending = emptySet(), errors = emptyMap(), end = null,
                    loadedThrough = 0, viewport = SqliteViewport())
                cancelPages(host, name, query)
                if (!state.expanded) page(node.owner, name, 0, query)
            }
            SqliteCommand.PROPERTIES -> tables[name] = state.copy(properties = !state.properties)
            SqliteCommand.CLOSE -> tables[name] = state.copy(properties = false)
            SqliteCommand.COLUMNS -> tables[name] = state.copy(tab = 0)
            SqliteCommand.DDL -> tables[name] = state.copy(tab = 1)
            SqliteCommand.REFRESH -> {
                // Refresh schema/counts together, so a changed table cannot retain an old projection.
                refresh(node.owner, host.file)
            }
        }
        changed()
    }

    fun sort(node: SqliteDataNode, column: Int) {
        val host = hosts[node.owner] ?: return
        val tables = host.tables(node.state.query) ?: return
        val state = tables[node.state.table.name] ?: return
        val name = state.table.visibleColumns.getOrNull(column)?.name ?: return
        val sort = SqliteSort(name, state.sort?.let { it.column == name && !it.descending } == true)
        cancelPages(host, state.table.name, state.query)
        tables[state.table.name] = state.copy(sort = sort, pages = emptyMap(), pending = emptySet(),
            errors = emptyMap(), end = null, loadedThrough = 0, viewport = SqliteViewport(state.viewport.x, 0f))
        page(node.owner, state.table.name, 0, state.query)
        changed()
    }

    private fun cancelPages(host: Host, name: String, query: String?) {
        host.pages.keys.filter { it.first == name && it.second == query }.forEach { host.pages.remove(it)?.cancel() }
    }

    fun page(owner: String, name: String, offset: Int, query: String? = null) {
        val host = hosts[owner] ?: return
        val session = host.session ?: return
        val tables = host.tables(query) ?: return
        val state = tables[name] ?: return
        if (!state.expanded || offset in state.pages || offset in state.pending) return
        tables[name] = state.copy(pending = state.pending + offset, errors = state.errors - offset)
        val job = scope.launch(start = CoroutineStart.LAZY, context = host.ownerJob(query) ?: return) {
            try {
                val page = cancellable { session.page(state.table, offset, it, query, state.sort) }
                val live = tables[name] ?: return@launch
                val pages = LinkedHashMap(live.pages).apply {
                    put(offset, page)
                    while (size > 8) remove(keys.first())
                }
                tables[name] = live.copy(pages = pages, pending = live.pending - offset,
                    loadedThrough = maxOf(live.loadedThrough, offset + page.rows.size),
                    end = if (page.rows.size < live.pageSize) offset + page.rows.size else live.end)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val live = tables[name] ?: return@launch
                tables[name] = live.copy(pending = live.pending - offset,
                    errors = live.errors + (offset to (e.message ?: context.getString(R.string.sqlite_read_failed))))
            }
            host.pages.remove(Triple(name, query, offset))
            changed()
        }
        host.pages[Triple(name, query, offset)] = job
        job.start()
    }

    suspend fun cell(node: SqliteDataNode, row: Int, column: Int): SqliteCell? {
        val host = hosts[node.owner] ?: return null
        val session = host.session ?: return null
        val query = node.state.query
        if (host.tables(query)?.get(node.state.table.name)?.table != node.state.table) return null
        // Both leaving the screen and closing/refreshing the database cancel a detail read.
        val operation = scope.async(host.ownerJob(query) ?: return null) { cancellable { session.cell(node.state.table, row, column, it, query, node.state.sort) } }
        return try { operation.await() } finally { operation.cancel() }
    }

    /** Capture the current lease/job, so refreshing or replacing a search cannot retarget later chunks. */
    fun blobReader(node: SqliteDataNode, row: Int, column: Int, size: Long): (suspend (Long, Int) -> ByteArray)? {
        val host = hosts[node.owner] ?: return null
        val session = host.session ?: return null
        val query = node.state.query
        if (host.tables(query)?.get(node.state.table.name)?.table != node.state.table) return null
        val owner = host.ownerJob(query) ?: return null
        return { offset, length ->
            val operation = scope.async(owner) {
                cancellable { session.blob(node.state.table, row, column, offset, length, size, it, query, node.state.sort) }
            }
            try { operation.await() } finally { operation.cancel() }
        }
    }

    fun retain(keys: Set<String>, visible: Set<String> = keys) {
        hosts.keys.toList().filter { it !in keys }.forEach(::close)
        verified.retainAll(visible)
    }

    fun csvWriter(node: SqliteNode, columns: Set<String>): (suspend (java.io.OutputStream, (Long) -> Unit) -> Unit)? {
        val host = hosts[node.owner] ?: return null
        val session = host.session ?: return null
        val query = node.state.query
        if (host.tables[node.state.table.name]?.table != node.state.table) return null
        if (query != null && (host.search?.query != query ||
            host.search?.table?.let { it != node.state.table.name } == true)) return null
        val owner = host.ownerJob(query) ?: return null
        return { output, progress ->
            val operation = scope.async(owner) {
                runCatching { cancellable { session.exportCsv(node.state.table, columns, it, output, query, progress, node.state.sort) } }
            }
            try { operation.await().getOrThrow() } finally { withContext(NonCancellable) { operation.cancelAndJoin() } }
        }
    }
    fun refresh(key: String, file: XFile) {
        val remembered = hosts[key]?.tables?.toMap().orEmpty()
        val parent = hosts[key]?.parent
        val search = hosts[key]?.search
        close(key)
        open(key, file, parent, remembered, search?.query, search?.table)
    }
    fun close(key: String) {
        hosts.remove(key)?.let { host -> host.pages.values.forEach { it.cancel() }; host.job?.cancel() }
    }
}
