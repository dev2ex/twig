package com.twig.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Shared theme initialization for screens and transparent relays, before AppCompat reads window colors. */
open class TwigActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
    }
}
