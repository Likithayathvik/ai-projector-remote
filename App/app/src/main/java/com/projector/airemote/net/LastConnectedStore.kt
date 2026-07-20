package com.projector.airemote.net

import android.content.Context

data class LastConnectedProjector(val ipAddress: String, val port: Int, val model: String, val manufacturer: String)

/** Persists the most recently connected projector so the app can auto-reconnect on launch. */
class LastConnectedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_connected", Context.MODE_PRIVATE)

    var lastConnected: LastConnectedProjector?
        get() {
            val ip = prefs.getString(KEY_IP, null) ?: return null
            return LastConnectedProjector(
                ipAddress = ip,
                port = prefs.getInt(KEY_PORT, 5555),
                model = prefs.getString(KEY_MODEL, "") ?: "",
                manufacturer = prefs.getString(KEY_MANUFACTURER, "") ?: ""
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit().clear().apply()
            } else {
                prefs.edit()
                    .putString(KEY_IP, value.ipAddress)
                    .putInt(KEY_PORT, value.port)
                    .putString(KEY_MODEL, value.model)
                    .putString(KEY_MANUFACTURER, value.manufacturer)
                    .apply()
            }
        }

    fun forget() {
        lastConnected = null
    }

    companion object {
        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
        private const val KEY_MODEL = "model"
        private const val KEY_MANUFACTURER = "manufacturer"
    }
}
