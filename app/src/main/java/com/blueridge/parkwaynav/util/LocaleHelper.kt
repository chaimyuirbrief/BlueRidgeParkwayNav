package com.blueridge.parkwaynav.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Applies the in-app display language (English/Spanish) by wrapping the base context. */
object LocaleHelper {
    fun wrap(base: Context, languageTag: String): Context {
        val locale = Locale.forLanguageTag(languageTag.ifBlank { "en" })
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Reads the persisted language synchronously from DataStore's backing prefs file name. */
    fun storedLanguageBlocking(context: Context): String {
        // DataStore is async; for attachBaseContext we read the simple cached value via
        // a lightweight SharedPreferences mirror written on each language change.
        val sp = context.getSharedPreferences("brp_locale", Context.MODE_PRIVATE)
        return sp.getString("language", "en") ?: "en"
    }

    fun cacheLanguage(context: Context, languageTag: String) {
        context.getSharedPreferences("brp_locale", Context.MODE_PRIVATE)
            .edit().putString("language", languageTag).apply()
    }
}
