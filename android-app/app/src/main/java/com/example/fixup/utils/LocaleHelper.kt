package com.example.fixup.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "fixup_prefs"
    const val KEY_LANGUAGE = "app_language"
    const val LANG_EN = "en"
    const val LANG_UR = "ur"

    fun setLocale(context: Context): Context {
        val lang = getSavedLanguage(context)
        return applyLocale(context, lang)
    }

    fun toggleAndApply(context: Context): String {
        val current = getSavedLanguage(context)
        val next = if (current == LANG_EN) LANG_UR else LANG_EN
        save(context, next)
        return next
    }

    fun getSavedLanguage(context: Context): String =
        getPrefs(context).getString(KEY_LANGUAGE, LANG_EN) ?: LANG_EN

    fun save(context: Context, lang: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, lang).apply()
    }

    private fun applyLocale(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
