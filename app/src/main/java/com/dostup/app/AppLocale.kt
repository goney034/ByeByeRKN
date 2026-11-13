package com.dostup.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Fork: the interface language is chosen inside the app (Settings > About > Language) and does not
 * depend on the phone's language. Persian is the default.
 *
 * Every place that shows text passes its context through [wrap]: the activity, the application,
 * the status texts, the notification and the quick settings tile.
 */
object AppLocale {
    const val DEFAULT = "fa"

    /** Language code to the name written in that language. */
    val languages = listOf(
        "fa" to "فارسی",
        "en" to "English",
        "ru" to "Русский",
    )

    // The same file as Store: it is read before the Application is created.
    private const val PREFS = "dostup"
    private const val KEY = "appLanguage"

    /** The chosen language code, or [DEFAULT] if nothing was chosen yet. */
    fun get(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return if (languages.any { it.first == saved }) saved!! else DEFAULT
    }

    /** Saves the language; the caller has to recreate the screen to see it. */
    fun set(context: Context, language: String) {
        require(languages.any { it.first == language })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    fun nameOf(language: String): String = languages.firstOrNull { it.first == language }?.second ?: language

    /** The same context with the chosen language, and with the matching layout direction (right to left for Persian). */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(get(base))
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
