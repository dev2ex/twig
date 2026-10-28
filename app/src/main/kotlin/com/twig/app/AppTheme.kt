package com.twig.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import java.util.Collections
import java.util.WeakHashMap

/** OLED uses night-mode text and controls with a separate, theme-backed surface palette. */
object AppTheme : Application.ActivityLifecycleCallbacks {
    // Separate from AppCompat's mode constants; persisted in the existing theme preference.
    const val MODE_OLED = 3
    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    private fun nightMode(choice: Int): Int =
        if (choice == MODE_OLED) AppCompatDelegate.MODE_NIGHT_YES else choice

    fun install(app: Application) {
        AppCompatDelegate.setDefaultNightMode(nightMode(Prefs.themeMode(app)))
        applyApplicationTheme(app)
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun applyApplicationTheme(context: Context) {
        context.setTheme(if (Prefs.themeMode(context) == MODE_OLED) R.style.Theme_Twig_Oled else R.style.Theme_Twig)
    }

    /** Select a complete theme before AppCompat initializes its Window and decor. */
    fun apply(activity: Activity) {
        if (Prefs.themeMode(activity) != MODE_OLED) return
        val translucent = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowIsTranslucent, translucent, true)
        activity.setTheme(if (translucent.data != 0) R.style.Theme_Twig_Oled_Translucent else R.style.Theme_Twig_Oled)
    }

    fun change(context: Context, previous: Int, choice: Int) {
        // Reset application-context colors as well, for views built outside an Activity.
        applyApplicationTheme(context.applicationContext)
        val targetNight = when (nightMode(choice)) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            else -> context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        val paletteOnly = if (previous == MODE_OLED || choice == MODE_OLED) {
            activities.toList().filter {
                !it.isFinishing && !it.isDestroyed &&
                    (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == targetNight
            }
        } else emptyList()
        AppCompatDelegate.setDefaultNightMode(nightMode(choice))
        // Also covers Follow system <-> OLED when the system is already dark:
        // AppCompat only recreates screens whose effective uiMode changes.
        paletteOnly.forEach { it.recreate() }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        activities.add(activity)
    }

    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
