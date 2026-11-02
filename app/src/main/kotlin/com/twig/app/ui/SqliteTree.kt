package com.twig.app.ui

import android.content.Context
import android.os.CancellationSignal
import com.twig.app.R
import com.twig.app.sqlite.SqliteCell
import com.twig.app.sqlite.SqlitePage
import com.twig.app.sqlite.SqliteSession
import com.twig.app.sqlite.SqliteTable
import com.twig.core.XFile
import kotlinx.coroutines.*

class SqliteViewport(var x: Float = 0f, var y: Float = 0f)

data class SqliteTableState(
    val table: SqliteTable,
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
    val ddlViewport: SqliteViewport = SqliteViewport(),
) {
    val pageSize get() = SqliteSession.pageSize(table)
    val rowCount: Int get() = listOfNotNull(count?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), end).minOrNull()
        ?: (loadedThrough.toLong() + pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

sealed interface SqliteNode : PaneViewModel.Node {
    val owner: String
    val state: SqliteTableState
    val tableKey: String get() = "$owner/sqlite:${state.table.name.length}:${state.table.name}"
}
data class SqliteTableNode(override val owner: String, override val depth: Int,
    override val state: SqliteTableState) : SqliteNode { override val key get() = tableKey }
data class SqliteDataNode(override val owner: String, override val depth: Int,
    override val state: SqliteTableState) : SqliteNode { override val key get() = "$tableKey/data" }
data class SqliteInfoNode(override val owner: String, override val depth: Int,
    override val state: SqliteTableState) : SqliteNode { override val key get() = "$tableKey/info" }

enum class SqliteCommand { TOGGLE, PROPERTIES, REFRESH, COLUMNS, DDL, CLOSE }

/** Owns browsing leases/jobs; tree nodes contain immutable results, never cursors or database handles. */
internal class SqliteTree(private val context: Context, private val scope: CoroutineScope,
    private val io: () -> CoroutineDispatcher, private val changed: () -> Unit) {
    private class Host(val file: XFile, val parent: XFile?) {
        var job: Job? = null
        var session: SqliteSession? = null
        var error: String? = null
        var loading = true
        val tables = LinkedHashMap<String, SqliteTableState>()
        val pages = HashMap<Pair<String, Int>, Job>()
    }
    private val hosts = HashMap<String, Host>()
    fun loading(key: String) = hosts[key]?.loading == true

    /** Cancellation reaches SQLite even while the IO thread is inside a native query. */
    private suspend fun <T> cancellable(block: (CancellationSignal) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try { withContext(io()) { block(signal) } } finally { watcher.cancel() }
    }

    fun open(key: String, file: XFile, parent: XFile? = null, remembered: Map<String, SqliteTableState> = emptyMap()) {
        if (hosts.containsKey(key)) return
        val host = Host(file, parent)
        hosts[key] = host
        host.job = scope.launch {
            // Keep the lease local until cancellation cleanup, including cancellation during open.
            var session: SqliteSession? = null
            try {
                cancellable { session = SqliteSession.open(context, file, it, parent) }
                val opened = checkNotNull(session)
                val tables = cancellable { opened.tables(it) }
                host.session = opened
                tables.forEach { table ->
                    host.tables[table.name] = remembered[table.name]?.copy(table = table, count = null,
                        counting = true, pages = emptyMap(), pending = emptySet(), errors = emptyMap(), end = null)
                        ?: SqliteTableState(table)
                }
                host.loading = false
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

    fun rows(key: String, depth: Int): List<PaneViewModel.Node> {
        val host = hosts[key] ?: return emptyList()
        val error = host.error
        if (error != null) return listOf(PaneViewModel.ActionNode("sqlite_error:$key", error, depth, informational = true))
        if (!host.loading && host.tables.isEmpty()) return listOf(PaneViewModel.ActionNode("sqlite_empty:$key",
            context.getString(R.string.sqlite_no_tables), depth, informational = true))
        return buildList {
            host.tables.values.forEach { state ->
                add(SqliteTableNode(key, depth, state))
                if (state.properties) add(SqliteInfoNode(key, depth + 1, state))
                if (state.expanded) add(SqliteDataNode(key, depth + 1, state))
            }
        }
    }

    fun command(node: SqliteNode, command: SqliteCommand) {
        val host = hosts[node.owner] ?: return
        val name = node.state.table.name
        val state = host.tables[name] ?: return
        when (command) {
            SqliteCommand.TOGGLE -> {
                if (!state.expanded) {
                    // Match directory accordion behavior within this database.
                    host.tables.keys.filter { it != name && host.tables[it]?.expanded == true }.forEach { other ->
                        cancelPages(host, other)
                        host.tables[other]?.let { previous ->
                            host.tables[other] = previous.copy(expanded = false,
                                pages = emptyMap(), pending = emptySet(), errors = emptyMap(), end = null,
                                loadedThrough = 0, viewport = SqliteViewport())
                        }
                    }
                }
                host.tables[name] = state.copy(expanded = !state.expanded,
                    pages = emptyMap(), pending = emptySet(), errors = emptyMap(), end = null,
                    loadedThrough = 0, viewport = SqliteViewport())
                cancelPages(host, name)
                if (!state.expanded) page(node.owner, name, 0)
            }
            SqliteCommand.PROPERTIES -> host.tables[name] = state.copy(properties = !state.properties)
            SqliteCommand.CLOSE -> host.tables[name] = state.copy(properties = false)
            SqliteCommand.COLUMNS -> host.tables[name] = state.copy(tab = 0)
            SqliteCommand.DDL -> host.tables[name] = state.copy(tab = 1)
            SqliteCommand.REFRESH -> {
                // Refresh schema/counts together, so a changed table cannot retain an old projection.
                refresh(node.owner, host.file)
            }
        }
        changed()
    }

    private fun cancelPages(host: Host, name: String) {
        host.pages.keys.filter { it.first == name }.forEach { host.pages.remove(it)?.cancel() }
    }

    fun page(owner: String, name: String, offset: Int) {
        val host = hosts[owner] ?: return
        val session = host.session ?: return
        val state = host.tables[name] ?: return
        if (!state.expanded || offset in state.pages || offset in state.pending) return
        host.tables[name] = state.copy(pending = state.pending + offset, errors = state.errors - offset)
        val job = scope.launch(start = CoroutineStart.LAZY, context = checkNotNull(host.job)) {
            try {
                val page = cancellable { session.page(state.table, offset, it) }
                val live = host.tables[name] ?: return@launch
                val pages = LinkedHashMap(live.pages).apply {
                    put(offset, page)
                    while (size > 8) remove(keys.first())
                }
                host.tables[name] = live.copy(pages = pages, pending = live.pending - offset,
                    loadedThrough = maxOf(live.loadedThrough, offset + page.rows.size),
                    end = if (page.rows.size < live.pageSize) offset + page.rows.size else live.end)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val live = host.tables[name] ?: return@launch
                host.tables[name] = live.copy(pending = live.pending - offset,
                    errors = live.errors + (offset to (e.message ?: context.getString(R.string.sqlite_read_failed))))
            }
            host.pages.remove(name to offset)
            changed()
        }
        host.pages[name to offset] = job
        job.start()
    }

    suspend fun cell(node: SqliteDataNode, row: Int, column: Int): SqliteCell? {
        val host = hosts[node.owner] ?: return null
        val session = host.session ?: return null
        if (host.tables[node.state.table.name]?.table != node.state.table) return null
        // Both leaving the screen and closing/refreshing the database cancel a detail read.
        val operation = scope.async(checkNotNull(host.job)) { cancellable { session.cell(node.state.table, row, column, it) } }
        return try { operation.await() } finally { operation.cancel() }
    }

    fun retain(keys: Set<String>) { hosts.keys.toList().filter { it !in keys }.forEach(::close) }
    fun refresh(key: String, file: XFile) {
        val remembered = hosts[key]?.tables?.toMap().orEmpty()
        val parent = hosts[key]?.parent
        close(key)
        open(key, file, parent, remembered)
    }
    fun close(key: String) {
        hosts.remove(key)?.let { host -> host.pages.values.forEach { it.cancel() }; host.job?.cancel() }
    }
}
