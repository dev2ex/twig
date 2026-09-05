package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.app.databinding.ActivityGitBinding
import com.twig.app.databinding.ItemGitChangeBinding
import com.twig.app.databinding.ItemGitCommitBinding
import com.twig.app.databinding.ItemGitHeaderBinding
import com.twig.git.ChangeKind
import com.twig.git.GitCommit
import com.twig.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Git repository viewer: changes (staged / unstaged / untracked) and commit history, read-only. */
class GitActivity : AppCompatActivity() {

    private lateinit var b: ActivityGitBinding
    private var repo: GitRepo? = null
    private val adapter = RowAdapter()
    private val commits = ArrayList<GitCommit>()
    private var historyEnd = false
    private var loadingMore = false
    private var tab = 0 // 0=changes 1=history

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGitBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val name = File(path).name
        b.toolbar.title = name
        b.toolbar.setNavigationOnClickListener { finish() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription("Git · $name"))

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.tabChanges.setOnClickListener { switchTab(0) }
        b.tabHistory.setOnClickListener { switchTab(1) }
        // Auto-load the next page when the history scrolls to the bottom
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (tab != 1 || dy <= 0 || historyEnd || loadingMore) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 10) loadMoreHistory()
            }
        })

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { GitRepo.open(File(path)) }.getOrNull() }
            if (r == null) {
                Toast.makeText(this@GitActivity, R.string.git_open_failed, Toast.LENGTH_SHORT).show()
                finish(); return@launch
            }
            repo = r
            b.toolbar.subtitle = withContext(Dispatchers.IO) { runCatching { r.branch() }.getOrDefault("?") }
            loadChanges()
        }
    }

    private fun switchTab(t: Int) {
        if (tab == t) return
        tab = t
        b.tabChanges.setTextColor(ContextCompat.getColor(this, if (t == 0) R.color.primary else R.color.text_secondary))
        b.tabHistory.setTextColor(ContextCompat.getColor(this, if (t == 1) R.color.primary else R.color.text_secondary))
        if (t == 0) loadChanges() else showHistory()
    }

    private fun loadChanges() {
        val r = repo ?: return
        b.loading.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { runCatching { r.status() }.getOrNull() }
            if (tab != 0 || isDestroyed) return@launch
            b.loading.visibility = View.GONE
            val rows = ArrayList<Row>()
            if (s != null) {
                if (s.staged.isNotEmpty()) {
                    rows.add(Row.Header(getString(R.string.git_staged, s.staged.size)))
                    s.staged.forEach { rows.add(Row.Change(it.path, it.kind)) }
                }
                if (s.unstaged.isNotEmpty()) {
                    rows.add(Row.Header(getString(R.string.git_unstaged, s.unstaged.size)))
                    s.unstaged.forEach { rows.add(Row.Change(it.path, it.kind)) }
                }
                if (s.untracked.isNotEmpty()) {
                    rows.add(Row.Header(getString(R.string.git_untracked, s.untracked.size)))
                    s.untracked.forEach { rows.add(Row.Change(it, null)) }
                }
            }
            if (rows.isEmpty()) {
                b.tvEmpty.text = getString(R.string.git_no_changes)
                b.tvEmpty.visibility = View.VISIBLE
            }
            adapter.submit(rows)
        }
    }

    private fun showHistory() {
        b.tvEmpty.visibility = View.GONE
        if (commits.isEmpty() && !historyEnd) {
            adapter.submit(emptyList())
            loadMoreHistory()
        } else {
            renderHistory()
        }
    }

    private fun loadMoreHistory() {
        val r = repo ?: return
        loadingMore = true
        if (commits.isEmpty()) b.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { r.log(commits.size, PAGE) }.getOrDefault(emptyList())
            }
            loadingMore = false
            if (isDestroyed) return@launch
            b.loading.visibility = View.GONE
            if (page.size < PAGE) historyEnd = true
            commits.addAll(page)
            if (tab == 1) renderHistory()
        }
    }

    private fun renderHistory() {
        if (commits.isEmpty()) {
            b.tvEmpty.text = getString(R.string.git_no_commits)
            b.tvEmpty.visibility = View.VISIBLE
        }
        adapter.submit(commits.map { Row.Commit(it) })
    }

    private fun showCommit(c: GitCommit) {
        val date = DATE.format(Date(c.timeSec * 1000))
        AlertDialog.Builder(this)
            .setTitle(c.shortSha)
            .setMessage("${c.message}\n\n${c.author} <${c.email}>\n$date")
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        val r = repo; repo = null
        if (r != null) Thread { runCatching { r.close() } }.start()
    }

    // ---- list ----

    private sealed interface Row {
        class Header(val text: String) : Row
        class Change(val path: String, val kind: ChangeKind?) : Row // kind=null → untracked
        class Commit(val c: GitCommit) : Row
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Change -> 1
            is Row.Commit -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderVH(ItemGitHeaderBinding.inflate(inf, parent, false))
                1 -> ChangeVH(ItemGitChangeBinding.inflate(inf, parent, false))
                else -> CommitVH(ItemGitCommitBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).b.tvHeader.text = row.text
                is Row.Change -> (holder as ChangeVH).bind(row)
                is Row.Commit -> (holder as CommitVH).bind(row.c)
            }
        }
    }

    private class HeaderVH(val b: ItemGitHeaderBinding) : RecyclerView.ViewHolder(b.root)

    private class ChangeVH(val b: ItemGitChangeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.Change) {
            val (letter, color) = when (row.kind) {
                ChangeKind.ADDED -> "A" to 0xFF66BB6A.toInt()
                ChangeKind.MODIFIED -> "M" to 0xFFFFA000.toInt()
                ChangeKind.DELETED -> "D" to 0xFFEF5350.toInt()
                null -> "?" to 0xFF9E9E9E.toInt()
            }
            b.tvKind.text = letter
            b.tvKind.setTextColor(color)
            b.tvPath.text = row.path
        }
    }

    private inner class CommitVH(val b: ItemGitCommitBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: GitCommit) {
            b.tvTitle.text = c.title
            b.tvMeta.text = "${c.author} · ${DATE.format(Date(c.timeSec * 1000))} · ${c.shortSha}"
            b.root.setOnClickListener { showCommit(c) }
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val PAGE = 100
        private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun start(context: Context, path: String) {
            context.startActivity(
                Intent(context, GitActivity::class.java).putExtra(EXTRA_PATH, path),
            )
        }
    }
}
