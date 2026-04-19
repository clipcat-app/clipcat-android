package com.clipcat.app.storage

import android.content.Context
import android.content.SharedPreferences

class AppSettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("clipcat_app_settings", Context.MODE_PRIVATE)

    fun getImageFormat(): String {
        return prefs.getString(KEY_IMAGE_FORMAT, "jpg") ?: "jpg"
    }

    fun setImageFormat(format: String) {
        prefs.edit().putString(KEY_IMAGE_FORMAT, format).apply()
    }

    fun getImageQuality(): Int {
        // 0 = low (70), 1 = medium (85), 2 = high (95)
        return prefs.getInt(KEY_IMAGE_QUALITY, 1)
    }

    fun setImageQuality(quality: Int) {
        prefs.edit().putInt(KEY_IMAGE_QUALITY, quality).apply()
    }

    fun getQualityValue(): Int {
        return when (getImageQuality()) {
            0 -> 70  // Low
            1 -> 85  // Medium
            2 -> 95  // High
            else -> 85
        }
    }

    fun getHapticFeedback(): Boolean {
        return prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
    }

    fun setHapticFeedback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply()
    }

    fun getThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return try {
            ThemeMode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getPreviewMode(): String {
        return prefs.getString(KEY_PREVIEW_MODE, "FULL") ?: "FULL"
    }

    fun setPreviewMode(mode: String) {
        prefs.edit().putString(KEY_PREVIEW_MODE, mode).apply()
    }

    companion object {
        private const val KEY_IMAGE_FORMAT = "image_format"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LENS_FACING = "lens_facing"
        private const val KEY_PREVIEW_MODE = "preview_mode"
    }

    fun getLensFacing(): Int {
        return prefs.getInt(KEY_LENS_FACING, androidx.camera.core.CameraSelector.LENS_FACING_BACK)
    }

    fun setLensFacing(lensFacing: Int) {
        prefs.edit().putInt(KEY_LENS_FACING, lensFacing).apply()
    }
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}
