package com.twig.app.ui

import android.content.Context
import android.content.Intent
import android.app.SearchManager
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.R
import com.twig.app.SearchEngine
import com.twig.app.WebsiteStore

object WebSearch {
    fun open(ctx: Context, text: String) {
        val query = text.trim().takeIf { it.isNotEmpty() } ?: return
        launch(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
    }

    fun home(ctx: Context) = ctx.startActivity(Intent(ctx, WebSearchActivity::class.java)
        .setAction(Intent.ACTION_WEB_SEARCH))

    private fun launch(ctx: Context, request: Intent) {
        ctx.startActivity(Intent.createChooser(request, ctx.getString(R.string.web_search)))
    }

    fun addSelectionAction(ctx: Context, menu: Menu, selectedText: () -> String, finish: () -> Unit) {
        if (menu.findItem(R.id.web_search) != null) return
        menu.add(0, R.id.web_search, 10, R.string.web_search).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                val text = selectedText()
                finish()
                open(ctx, text)
                true
            }
        }
    }

    fun attach(view: TextView) {
        view.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                addSelectionAction(view.context, menu, {
                    val start = minOf(view.selectionStart, view.selectionEnd)
                    val end = maxOf(view.selectionStart, view.selectionEnd)
                    if (start < 0 || end > view.text.length) "" else view.text.subSequence(start, end).toString()
                }, { mode.finish() })
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
    }

    fun configure(ctx: Context) {
        fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val preset = android.widget.Button(ctx).apply {
            setText(R.string.search_engine_presets)
            form.addView(this)
        }
        fun field(label: Int, value: String): EditText {
            form.addView(TextView(ctx).apply {
                setText(label)
                setPadding(0, dp(12), 0, 0)
            })
            return EditText(ctx).apply {
                tag = label
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setText(value)
                form.addView(this)
            }
        }
        val home = field(R.string.search_engine_home, SearchEngine.home(ctx))
        val template = field(R.string.search_engine_template, SearchEngine.template(ctx))
        form.addView(TextView(ctx).apply {
            setText(R.string.search_engine_hint)
            setPadding(0, dp(8), 0, dp(12))
        })
        fun fill(provider: SearchEngine.Provider) {
            home.setText(provider.home)
            template.setText(provider.template)
            home.error = null
            template.error = null
        }
        preset.setOnClickListener {
            AlertDialog.Builder(ctx).setTitle(R.string.search_engine_presets)
                .setItems(SearchEngine.presets.map { it.name }.toTypedArray()) { _, index ->
                    fill(SearchEngine.presets[index])
                }.show()
        }
        val dialog = AlertDialog.Builder(ctx).setTitle(R.string.search_engine)
            .setView(ScrollView(ctx).apply { addView(form) })
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.search_engine_reset, null)
            .setPositiveButton(R.string.dialog_ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { fill(SearchEngine.default) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (WebsiteStore.normalizeUrl(home.text.toString()) == null) {
                    home.error = ctx.getString(R.string.website_invalid_url)
                    home.requestFocus()
                } else if (!SearchEngine.validTemplate(template.text.toString().trim())) {
                    template.error = ctx.getString(R.string.search_engine_invalid_template)
                    template.requestFocus()
                } else {
                    SearchEngine.save(ctx, home.text.toString(), template.text.toString())
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
