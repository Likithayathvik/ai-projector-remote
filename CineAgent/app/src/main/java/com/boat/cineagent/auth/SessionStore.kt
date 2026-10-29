package com.boat.cineagent.auth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

// Authenticated connection (KeyAuthentication spec): Key #1 is the shared discovery key every
// CineHead build carries; Key #2 is a random session key CineAgent hands out per phone in the
// discovery response and then requires as the first TCP message (AUTH).
//
// Sessions are persisted in this app's private SharedPreferences so a phone's Key #2 survives a
// projector reboot or a CineAgent restart. They are deleted only by that phone's own valid
// DISCONNECT, by the same phone discovering again (its old key is replaced), or by eviction
// once more than MAX_SESSIONS phones are known. A factory reset wipes this app's data - the
// phone's stored key is then rejected and CineHead re-discovers with Key #1 to get a new one.
//
// Keys are never logged.
class SessionStore(context: Context) {

    companion object {
        const val DISCOVERY_KEY = "r31Co46Aej8cr28ePod46ienCe"
        private const val PREFS = "cineagent_sessions"
        private const val KEY_SESSIONS = "sessions"
        private const val MAX_SESSIONS = 10
        private const val SESSION_KEY_BYTES = 32
        private const val SESSION_ID_BYTES = 16
        private const val REISSUE_WINDOW_MS = 10_000L
        const val MAX_CLIENT_ID_LENGTH = 64

        /** Constant-time compare, so response timing never leaks how much of a key matched. */
        fun secureEquals(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
        }

        fun isValidDiscoveryKey(key: String?): Boolean = secureEquals(key, DISCOVERY_KEY)
    }

    data class Session(val sessionId: String, val sessionKey: String)

    private data class Entry(val key: String, val clientId: String, var lastUsed: Long, val issuedAt: Long)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val lock = Any()
    private val sessions: MutableMap<String, Entry> = load()

    /** Issues a fresh Key #2 for [clientId], replacing any session that phone already had.
     *  One scan can deliver the same request more than once (broadcast on several interfaces,
     *  or the phone's own retries), so a session issued to this phone within REISSUE_WINDOW_MS
     *  is returned again instead of being replaced - otherwise the phone could keep a key from
     *  the first reply that the second reply already revoked. */
    fun issue(clientId: String): Session = synchronized(lock) {
        val now = System.currentTimeMillis()
        sessions.entries.firstOrNull { it.value.clientId == clientId && now - it.value.issuedAt < REISSUE_WINDOW_MS }
            ?.let { return Session(it.key, it.value.key) }
        sessions.entries.removeAll { it.value.clientId == clientId }
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.minByOrNull { it.value.lastUsed } ?: break
            sessions.remove(oldest.key)
        }
        val session = Session(randomToken(SESSION_ID_BYTES), randomToken(SESSION_KEY_BYTES))
        sessions[session.sessionId] = Entry(session.sessionKey, clientId, now, now)
        save()
        session
    }

    fun validate(sessionId: String?, sessionKey: String?): Boolean = synchronized(lock) {
        val entry = sessionId?.let { sessions[it] } ?: return false
        if (!secureEquals(entry.key, sessionKey)) return false
        entry.lastUsed = System.currentTimeMillis()
        save()
        true
    }

    /** Deletes the session, but only when the caller proves it holds the key. */
    fun revoke(sessionId: String?, sessionKey: String?): Boolean = synchronized(lock) {
        val entry = sessionId?.let { sessions[it] } ?: return false
        if (!secureEquals(entry.key, sessionKey)) return false
        sessions.remove(sessionId)
        save()
        true
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun load(): MutableMap<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return result
        try {
            val json = JSONObject(raw)
            for (id in json.keys()) {
                val item = json.getJSONObject(id)
                result[id] = Entry(item.getString("key"), item.getString("clientId"), item.optLong("lastUsed"), item.optLong("issuedAt"))
            }
        } catch (t: Throwable) {
            // Corrupt store: start empty. Phones fall back to discovery and get new keys.
        }
        return result
    }

    private fun save() {
        val json = JSONObject()
        for ((id, entry) in sessions) {
            json.put(id, JSONObject().apply {
                put("key", entry.key)
                put("clientId", entry.clientId)
                put("lastUsed", entry.lastUsed)
                put("issuedAt", entry.issuedAt)
            })
        }
        prefs.edit().putString(KEY_SESSIONS, json.toString()).apply()
    }
}
