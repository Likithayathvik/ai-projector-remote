package com.boat.cinehead.net

import android.content.Context

/** Key #2 for one projector, as issued in its DISCOVERY_RESPONSE. */
data class SessionCredentials(val sessionId: String, val sessionKey: String)

data class LastConnectedProjector(
    val deviceName: String,
    val ipAddress: String,
    val tcpPort: Int,
    val credentials: SessionCredentials? = null
)

/** Persists the most recently connected projector so the app can auto-reconnect on launch,
 *  together with its session (Key #2) so that reconnect - after a Wi-Fi drop, an app restart or
 *  a projector reboot - authenticates without discovering again. Never logged. */
class LastConnectedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_connected", Context.MODE_PRIVATE)

    var lastConnected: LastConnectedProjector?
        get() {
            val ip = prefs.getString(KEY_IP, null) ?: return null
            val sessionId = prefs.getString(KEY_SESSION_ID, null)
            val sessionKey = prefs.getString(KEY_SESSION_KEY, null)
            return LastConnectedProjector(
                deviceName = prefs.getString(KEY_DEVICE_NAME, "") ?: "",
                ipAddress = ip,
                tcpPort = prefs.getInt(KEY_PORT, CineAgentDiscovery.DEFAULT_TCP_PORT),
                credentials = if (sessionId != null && sessionKey != null) SessionCredentials(sessionId, sessionKey) else null
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
                    .putString(KEY_SESSION_ID, value.credentials?.sessionId)
                    .putString(KEY_SESSION_KEY, value.credentials?.sessionKey)
                    .apply()
            }
        }

    /** Drops only the session (Key #2), keeping the projector's name and address. */
    fun clearCredentials() {
        prefs.edit().remove(KEY_SESSION_ID).remove(KEY_SESSION_KEY).apply()
    }

    fun forget() {
        lastConnected = null
    }

    companion object {
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_KEY = "session_key"
    }
}

/** This phone's identity towards CineAgent: a random id made once per install, sent with every
 *  discovery so CineAgent keeps one session per phone - a second phone gets its own Key #2 and
 *  never disturbs this one. */
class ClientIdentity(context: Context) {
    val clientId: String

    init {
        val prefs = context.applicationContext.getSharedPreferences("client_identity", Context.MODE_PRIVATE)
        clientId = prefs.getString(KEY_CLIENT_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
    }
}
