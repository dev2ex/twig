package com.twig.app.ui

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import com.twig.app.SearchEngine
import com.twig.app.TwigActivity

/** Handle the system search contract using Twig's provider, without re-entering the chooser. */
class WebSearchActivity : TwigActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != Intent.ACTION_WEB_SEARCH) {
            finish()
            return
        }
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        SecurityUi.gate(this) {
            if (isFinishing || isDestroyed) return@gate
            val url = if (query.isBlank()) SearchEngine.home(this) else SearchEngine.url(this, query)
            if (url != null) startActivity(WebsiteActivity.searchIntent(this, url))
            finish()
        }
    }
}
