package com.boat.cineagent.audio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// GET_EQUALIZER / SET_EQUALIZER / SET_DOLBY: the projector's sound mode and Dolby Audio.
//
// Equalizer: android.media.audiofx.Equalizer on the global output mix (session 0), so it applies to
// everything the projector plays (NXP engine on the F1 family: 5 bands, -15..+15 dB, 10 presets).
// Modes are "default" (no equalizer, the projector's own sound), one of SHOWN_PRESETS by name
// ("classical", "heavy metal", ...), or "custom" (one whole-dB level per band). Levels are reported both
// exactly ("gainsMb", millibels as the engine gives them) and rounded ("gainsDb", for older apps).
// The mode and the custom levels are remembered and re-applied by restore() when the service
// starts, including after a reboot.
//
// Dolby Audio: the projector's own "Dolby Audio Processing" switch (Settings > Sound). It lives in
// the MediaTek TV agent's sound database, row AQ_DolbyDap, which the agent re-applies at boot and
// the Sound settings show. Confirmed live 2026-10-13 that the agent does NOT push a changed row to
// the audio HAL itself - the Sound settings write the row AND call setParameters("AQ_DolbyDap=..."),
// so we do both. The Sound settings screen reads the switch only when it opens, so when it is on
// screen we reopen it; left stale it shows the old value and can write it back.
//
// Dolby and the equalizer are never on together (the HAL's Dolby chain and the equalizer both shape
// the same speaker output): an equalizer mode other than default turns Dolby off, Dolby on puts the
// equalizer back to default. Responses carry "switchedOff" ("dolby" / "equalizer") when that
// happened. A plain GET_EQUALIZER also settles it, Dolby winning, when Dolby was turned on from the
// projector's own menu.
class AudioEffectsController(
    private val context: Context,
    private val foregroundPackage: () -> String?
) {
    companion object {
        private const val TAG = "AudioEffects"
        private const val PREFS = "audio_effects"
        private const val KEY_MODE = "mode"
        private const val KEY_CUSTOM = "custom_db"
        private const val PRIORITY = 1000
        private const val GLOBAL_OUTPUT_MIX = 0

        const val DEFAULT = "default"
        const val CUSTOM = "custom"
        // The engine presets offered, in this order; its others (Normal, Dance, Flat, Jazz, Rock)
        // are left out.
        private val SHOWN_PRESETS = listOf("Classical", "Folk", "Heavy Metal", "Hip Hop", "Pop")

        // MediaTek TV agent sound database: one row per item, the value for all inputs in "common".
        private val SOUND_DB = Uri.parse("content://com.mediatek.tv.agent.sounddb/input_source")
        private const val COLUMN = "common"
        private const val DOLBY_DAP = "AQ_DolbyDap"
        /** The database notifies observers of this URI when the Dolby switch changes. */
        val DOLBY_DAP_URI: Uri = Uri.parse("content://com.mediatek.tv.agent.sounddb/general/$DOLBY_DAP")
        private const val ON = "On"
        private const val OFF = "Off"
        private const val SOUND_SETTINGS_PACKAGE = "com.mediatek.tv.settings"
        private const val SOUND_SETTINGS_ACTION = "android.settings.oem.SOUND_SETTINGS"

        // "switchedOff" values.
        private const val SWITCHED_DOLBY = "dolby"
        private const val SWITCHED_EQUALIZER = "equalizer"

        /** The Dolby Audio Processing switch; null when this projector has none (no TV agent sound
         *  database or row). Also read by SystemEventMonitor to push DOLBY_CHANGED. */
        fun readDolby(context: Context): Boolean? = try {
            context.contentResolver.query(SOUND_DB, arrayOf(COLUMN), "item=?", arrayOf(DOLBY_DAP), null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).equals(ON, ignoreCase = true) else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read Dolby Audio Processing", t)
            null
        }
    }

    private val resolver = context.contentResolver
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var effect: Equalizer? = null
    private var mode: String = prefs.getString(KEY_MODE, null) ?: DEFAULT

    /** Re-applies the remembered mode; called when the service starts. */
    @Synchronized
    fun restore() {
        try {
            if (mode != DEFAULT && dolby() == true) {
                Log.i(TAG, "Dolby Audio is on, so the equalizer ($mode) stays off")
                turnOffEqualizer()
                return
            }
            apply(mode, savedCustomDb())
        } catch (e: IllegalArgumentException) {
            // A mode saved by an earlier build that this projector has no preset for.
            Log.w(TAG, "Saved equalizer mode $mode no longer exists; using the projector default", e)
            turnOffEqualizer()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not restore equalizer mode $mode", t)
        }
    }

    /** GET_EQUALIZER: the current state (settling a Dolby/equalizer clash, Dolby winning). */
    @Synchronized
    fun get(): JSONObject {
        var switchedOff: String? = null
        if (mode != DEFAULT && dolby() == true) {
            turnOffEqualizer()
            switchedOff = SWITCHED_EQUALIZER
        }
        return state(switchedOff)
    }

    /**
     * SET_EQUALIZER: switches to [modeId] ([customDb]: one whole-dB level per band, for "custom";
     * the saved custom levels when omitted). Throws IllegalArgumentException for an unknown mode or
     * a wrong number of levels.
     */
    @Synchronized
    fun setEqualizer(modeId: String, customDb: List<Int>?): JSONObject {
        val id = modeId.lowercase()
        apply(id, customDb ?: savedCustomDb())
        prefs.edit().putString(KEY_MODE, mode).apply {
            if (mode == CUSTOM && customDb != null) putString(KEY_CUSTOM, customDb.joinToString(","))
        }.apply()
        var switchedOff: String? = null
        if (mode != DEFAULT && dolby() == true) {
            setDolbyNow(false)
            switchedOff = SWITCHED_DOLBY
        }
        return state(switchedOff)
    }

    /** SET_DOLBY: turns Dolby Audio Processing on or off. Throws IllegalStateException without it. */
    @Synchronized
    fun setDolby(on: Boolean): JSONObject {
        setDolbyNow(on)
        var switchedOff: String? = null
        if (on && mode != DEFAULT) {
            turnOffEqualizer()
            switchedOff = SWITCHED_EQUALIZER
        }
        return state(switchedOff)
    }

    private fun apply(modeId: String, customDb: List<Int>?) {
        if (modeId == DEFAULT) {
            effect?.release()
            effect = null
            mode = DEFAULT
            return
        }
        val eq = effect ?: Equalizer(PRIORITY, GLOBAL_OUTPUT_MIX).also { effect = it }
        if (modeId == CUSTOM) {
            val bands = eq.numberOfBands.toInt()
            val levels = customDb ?: List(bands) { 0 }
            require(levels.size == bands) { "Custom needs $bands levels (one per band), got ${levels.size}" }
            val (min, max) = eq.bandLevelRange.let { it[0] / 100 to it[1] / 100 }
            // The engine works in whole dB; out-of-range values are clamped to what it supports.
            levels.forEachIndexed { band, db -> eq.setBandLevel(band.toShort(), (db.coerceIn(min, max) * 100).toShort()) }
        } else {
            val preset = shownPresets(eq).firstOrNull { it.second.equals(modeId, ignoreCase = true) }?.first
                ?: throw IllegalArgumentException("Unknown mode $modeId. Use $DEFAULT, $CUSTOM or a preset: " +
                    shownPresets(eq).joinToString { it.second.lowercase() })
            eq.usePreset(preset)
        }
        eq.enabled = true
        mode = modeId
    }

    private fun turnOffEqualizer() {
        apply(DEFAULT, null)
        prefs.edit().putString(KEY_MODE, DEFAULT).apply()
    }

    /** Bands, modes and the levels in effect now; reads a temporary instance when the equalizer is off. */
    private fun state(switchedOff: String?): JSONObject {
        val eq = effect ?: Equalizer(0, GLOBAL_OUTPUT_MIX)
        try {
            val bands = (0 until eq.numberOfBands).map { it.toShort() }
            val modes = JSONArray().put(JSONObject().put("id", DEFAULT).put("name", "Projector default"))
            shownPresets(eq).forEach { (_, name) -> modes.put(JSONObject().put("id", name.lowercase()).put("name", name)) }
            modes.put(JSONObject().put("id", CUSTOM).put("name", "Custom"))
            val json = JSONObject()
                .put("mode", mode)
                .put("modes", modes)
                .put("bandsHz", JSONArray(bands.map { eq.getCenterFreq(it) / 1000 }))
                .put("levelRangeDb", JSONArray(eq.bandLevelRange.map { it / 100 }))
                .put("customDb", JSONArray(savedCustomDb() ?: List(bands.size) { 0 }))
                // Null when off: the projector plays its own sound.
                .put("gainsDb", if (effect == null) JSONObject.NULL else JSONArray(bands.map { (eq.getBandLevel(it) / 100.0).roundToInt() }))
                // The same levels unrounded, in millibels (1/100 dB), exactly as the engine reports them.
                .put("gainsMb", if (effect == null) JSONObject.NULL else JSONArray(bands.map { eq.getBandLevel(it).toInt() }))
            effect?.let { json.put("active", it.enabled && it.hasControl()) }
            // Left out when this projector has no Dolby Audio Processing switch.
            dolby()?.let { json.put("dolby", it) }
            switchedOff?.let { json.put("switchedOff", it) }
            return json
        } finally {
            if (eq !== effect) eq.release()
        }
    }

    /** The engine's presets we offer, as (index, name), in the order of [SHOWN_PRESETS]. */
    private fun shownPresets(eq: Equalizer): List<Pair<Short, String>> {
        val all = (0 until eq.numberOfPresets).map { it.toShort() to eq.getPresetName(it.toShort()) }
        return SHOWN_PRESETS.mapNotNull { wanted -> all.firstOrNull { it.second.equals(wanted, ignoreCase = true) } }
    }

    private fun savedCustomDb(): List<Int>? =
        prefs.getString(KEY_CUSTOM, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }

    private fun dolby(): Boolean? = readDolby(context)

    private fun setDolbyNow(on: Boolean) {
        val rows = try {
            resolver.update(SOUND_DB, ContentValues().apply { put(COLUMN, if (on) ON else OFF) }, "item=?", arrayOf(DOLBY_DAP))
        } catch (e: IllegalArgumentException) {
            0 // Unknown URI: no TV agent sound database on this projector.
        }
        check(rows > 0) { "This projector has no Dolby Audio Processing switch" }
        audio.setParameters("$DOLBY_DAP=${if (on) ON else OFF}")
        refreshSoundSettings()
    }

    /** Reopens the projector's Sound settings if they are on screen, so they show the switch's new value. */
    private fun refreshSoundSettings() {
        try {
            if (foregroundPackage() != SOUND_SETTINGS_PACKAGE) return
            context.startActivity(Intent(SOUND_SETTINGS_ACTION).setPackage(SOUND_SETTINGS_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (t: Throwable) {
            // Only the menu's display is affected; the switch itself has already changed.
            Log.w(TAG, "Could not reopen the Sound settings", t)
        }
    }
}
