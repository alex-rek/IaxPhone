package com.glaude.iaxphone

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePref.apply(ThemePref.get(this))
        AudioPrefs.load(this)
    }
}

/** Выбор темы: системная, светлая или тёмная. */
enum class ThemeMode(val nightMode: Int, val title: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    DARK(AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark),
}

object ThemePref {
    private const val PREFS = "general"
    private const val KEY = "theme"

    fun get(ctx: Context): ThemeMode {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
    }

    fun set(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        apply(mode)
    }

    /** Открытые экраны пересоздаются автоматически. */
    fun apply(mode: ThemeMode) = AppCompatDelegate.setDefaultNightMode(mode.nightMode)
}
