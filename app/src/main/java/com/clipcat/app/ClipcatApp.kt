package com.clipcat.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.clipcat.app.storage.AppSettingsStore
import com.clipcat.app.storage.ThemeMode

class ClipcatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val settings = AppSettingsStore(this)
        val uiMode = when (settings.getThemeMode()) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        
        AppCompatDelegate.setDefaultNightMode(uiMode)
    }
}
