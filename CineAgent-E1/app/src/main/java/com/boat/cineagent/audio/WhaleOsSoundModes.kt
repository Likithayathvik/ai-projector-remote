package com.boat.cineagent.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// GET_EQUALIZER / SET_EQUALIZER on WhaleOS projectors (Whale TV, Amlogic): the projector's own sound
// modes instead of the NXP equalizer.
//
// WhaleOS keeps the mode in settings global db_id_sound_effect_sound_mode_eq (0 Standard, 1 Music,
// 2 News, 3 Movie, 4 Game, 5 User) and each mode's 5 band levels (0..100, 50 is the middle) in
// db_id_sound_effect_band_all<mode><band>. The Amlogic AudioEffectsService (com.droidlogic) observes
// the mode setting and applies a change itself (confirmed live 2026-10-16), so a mode is set by
// writing it. Band levels have no observer: they go through the service's
// setUserSoundModeParam(band, value, bandCount), which changes the CURRENT mode's band, saves it
// and applies it - so the User mode is selected first. The service is exported without a
// permission; it is called with raw binder transactions (codes from its AIDL stub) rather than
// through the droidlogic library, whose manager binds asynchronously and drops early calls.
class WhaleOsSoundModes(private val context: Context) {
    companion object {
        private const val TAG = "WhaleOsSoundModes"
        private const val KEY_MODE = "db_id_sound_effect_sound_mode_eq"
        private const val KEY_BAND = "db_id_sound_effect_band_all"
        private const val SERVICE_PACKAGE = "com.droidlogic"
        private const val SERVICE_ACTION = "com.droidlogic.audioservice.services.AudioEffectsService"
        private const val DESCRIPTOR = "com.droidlogic.audioservice.services.IAudioEffectsService"
        private const val TRANSACTION_SET_USER_SOUND_MODE_PARAM = 26
        private const val BIND_TIMEOUT_MS = 3000L

        const val USER = "user"
        // Ids in mode-number order: the index is the value WhaleOS stores.
        private val MODES = listOf("standard" to "Standard", "music" to "Music", "news" to "News",
            "movie" to "Movie", "game" to "Game", USER to "User")
        // The modes offered, in the order the projector's Sound Mode menu lists them (mode numbers).
        // Game (4) is left out: its bands are flat, the same as Standard. When it is picked on the
        // projector itself, "mode" still reports "game" and the app highlights no mode.
        private val MENU_ORDER = listOf(0, 1, 3, 2, 5)

        /** A settings global key whose change alters the sound mode or a band level - watched by
         *  SystemEventMonitor to push EQUALIZER_CHANGED when they change on the projector itself. */
        fun isSoundModeKey(key: String?): Boolean = key != null && (key == KEY_MODE || key.startsWith(KEY_BAND))
        private val BANDS_HZ = listOf(120, 500, 1500, 5000, 10000)
        private const val MIN_LEVEL = 0
        private const val MAX_LEVEL = 100
        private const val MIDDLE = 50
    }

    private val resolver = context.contentResolver
    @Volatile private var service: IBinder? = null
    private var bound = false
    private var connected = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** GET_EQUALIZER: the current mode and its band levels. */
    @Synchronized
    fun get(): JSONObject = state()

    /**
     * SET_EQUALIZER: switches to [modeId]. With [levels] (one 0..100 value per band) the User mode is
     * selected and set to them. Throws IllegalArgumentException for an unknown mode or a wrong number
     * of levels, IllegalStateException when the projector's audio service cannot be reached.
     */
    @Synchronized
    fun set(modeId: String, levels: List<Int>?): JSONObject {
        val index = if (levels != null) MODES.indexOfFirst { it.first == USER }
            else MODES.indexOfFirst { it.first.equals(modeId, ignoreCase = true) }
        require(index >= 0) { "Unknown mode $modeId. Use one of: ${MODES.joinToString { it.first }}" }
        if (levels != null) {
            require(levels.size == BANDS_HZ.size) { "User needs ${BANDS_HZ.size} levels (one per band), got ${levels.size}" }
            // The service edits the current mode's bands, so User must be current first.
            writeMode(index)
            val binder = binder()
            levels.forEachIndexed { band, level -> setUserBand(binder, band, level.coerceIn(MIN_LEVEL, MAX_LEVEL)) }
        } else {
            writeMode(index)
        }
        return state()
    }

    private fun writeMode(index: Int) {
        if (currentMode() != index) Settings.Global.putInt(resolver, KEY_MODE, index)
    }

    private fun setUserBand(binder: IBinder, band: Int, level: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(band)
            data.writeInt(level)
            data.writeInt(BANDS_HZ.size)
            binder.transact(TRANSACTION_SET_USER_SOUND_MODE_PARAM, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** The bound AudioEffectsService, binding (and waiting for it) on first use. */
    private fun binder(): IBinder {
        service?.takeIf { it.isBinderAlive }?.let { return it }
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        connected = CountDownLatch(1)
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not bind the projector's audio service", e)
            false
        }
        check(bound) { "The projector's audio service is not available" }
        connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return service ?: throw IllegalStateException("The projector's audio service did not answer")
    }

    private fun currentMode(): Int = Settings.Global.getInt(resolver, KEY_MODE, 0)

    private fun state(): JSONObject {
        val mode = currentMode().coerceIn(0, MODES.size - 1)
        val modes = JSONArray()
        MENU_ORDER.map { MODES[it] }.forEach { (id, name) -> modes.put(JSONObject().put("id", id).put("name", name)) }
        val levels = BANDS_HZ.indices.map { band -> Settings.Global.getInt(resolver, "$KEY_BAND$mode$band", MIDDLE) }
        return JSONObject()
            .put("equalizer", "whaleos")
            .put("mode", MODES[mode].first)
            .put("modes", modes)
            .put("bandsHz", JSONArray(BANDS_HZ))
            .put("levelRange", JSONArray(listOf(MIN_LEVEL, MAX_LEVEL)))
            .put("levels", JSONArray(levels))
    }
}
