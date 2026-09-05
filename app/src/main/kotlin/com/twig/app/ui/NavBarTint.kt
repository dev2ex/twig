package com.twig.app.ui

import android.app.Activity
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.twig.app.R

/**
 * The navigation bar background follows the actual background of the page's bottom, so it looks
 * "transparent" — the system default pure-black navigation bar is a jarring black strip at the
 * bottom in the light theme, and even in the dark theme it's a shade off from the list background.
 *
 * Not actually making it transparent — that requires `setDecorFitsSystemWindows(false)` plus
 * handling insets in the layout ourselves; the entire page layout would need to change (the music
 * player page already does this, which is why it doesn't go through here). This just tints the
 * bar the same color.
 *
 * ★ **A light tint is not allowed on API < 26**: `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR`, which
 * darkens the navigation bar icons, only exists from API 26. On older systems the icons are
 * always white — a light tint means white icons on a white background, so home/back are still
 * there but invisible. In that case keep the system default (black).
 */
object NavBarTint {
    /**
     * Use the panel background color of the current theme (`@color/surface`, one entry for each
     * of light and dark). ★ Takes the **actual background of the bottom-most layer of the
     * screen**, not `@color/bg` — the file pane (`fragment_pane`) and both sides of the directory
     * compare page are surfaced; tinting to bg would be a shade off and the seam would show.
     */
    fun surface(a: Activity) = apply(a, ContextCompat.getColor(a, R.color.surface))

    fun apply(a: Activity, color: Int) {
        val light = MusicTint.isLight(color)
        if (light && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        a.window.navigationBarColor = color
        WindowCompat.getInsetsController(a.window, a.window.decorView)
            .isAppearanceLightNavigationBars = light
    }
}
