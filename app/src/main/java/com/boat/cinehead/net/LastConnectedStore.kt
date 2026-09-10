package com.boat.cinehead.net

import android.content.Context

data class LastConnectedProjector(val deviceName: String, val ipAddress: String, val tcpPort: Int)

/** Persists the most recently connected projector so the app can auto-reconnect on launch. */
class LastConnectedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_connected", Context.MODE_PRIVATE)

    var lastConnected: LastConnectedProjector?
        get() {
            val ip = prefs.getString(KEY_IP, null) ?: return null
            return LastConnectedProjector(
                deviceName = prefs.getString(KEY_DEVICE_NAME, "") ?: "",
                ipAddress = ip,
                tcpPort = prefs.getInt(KEY_PORT, CineAgentDiscovery.DEFAULT_TCP_PORT)
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit().clear().apply()
            } else {
                prefs.edit()
                    .putString(KEY_DEVICE_NAME, value.deviceName)
                    .putString(KEY_IP, value.ipAddress)
                    .putInt(KEY_PORT, value.tcpPort)
                    .apply()
            }
        }

    fun forget() {
        lastConnected = null
    }

    companion object {
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
    }
}
