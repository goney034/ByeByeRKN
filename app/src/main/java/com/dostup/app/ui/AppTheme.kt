package com.dostup.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Fork: day and night mode. The choice is kept in the app's own preferences and works the same on
 * every Android version. Night (the original look of the app) is the default.
 */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val modes = listOf(SYSTEM, LIGHT, DARK)

    // The same file as Store and AppLocale.
    private const val PREFS = "dostup"
    private const val KEY = "themeMode"

    var mode by mutableStateOf(DARK)
        private set

    /** What the phone is set to; only matters in [SYSTEM] mode. */
    var systemDark by mutableStateOf(true)

    /** True when the screen is dark. Reading it inside a composable makes that composable follow changes. */
    val isDark: Boolean
        get() = when (mode) {
            LIGHT -> false
            DARK -> true
            else -> systemDark
        }

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = if (saved in modes) saved!! else DARK
        systemDark = isNight(context.resources.configuration)
    }

    fun set(context: Context, newMode: String) {
        require(newMode in modes)
        mode = newMode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newMode).apply()
    }

    /** The day/night button: from what is shown now to the other one. */
    fun toggle(context: Context) = set(context, if (isDark) LIGHT else DARK)

    fun isNight(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
