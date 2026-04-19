package com.clipcat.app.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clipcat.app.model.PairingConfig

class PairingStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "clipcat_pairing",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(config: PairingConfig) {
        prefs.edit()
            .putString(KEY_IP, config.ip)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_AES, config.keyBase64)
            .putBoolean(KEY_FAST_TRANSFER, config.fastTransfer)
            .apply()
    }

    fun updateFastTransfer(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FAST_TRANSFER, enabled).apply()
    }

    fun load(): PairingConfig? {
        val ip = prefs.getString(KEY_IP, null) ?: return null
        val port = prefs.getInt(KEY_PORT, -1)
        val key = prefs.getString(KEY_AES, null) ?: return null
        val fastTransfer = prefs.getBoolean(KEY_FAST_TRANSFER, false)
        if (port <= 0) return null
        return PairingConfig(ip, port, key, fastTransfer)
    }

    companion object {
        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
        private const val KEY_AES = "aes"
        private const val KEY_FAST_TRANSFER = "fast_transfer"
    }
}
