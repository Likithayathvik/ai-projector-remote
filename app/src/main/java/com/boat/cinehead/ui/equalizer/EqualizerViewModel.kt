package com.boat.cinehead.ui.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boat.cinehead.net.ProjectorConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class EqMode(val id: String, val name: String)

/**
 * The Equalizer tab's state, from CineAgent's GET_EQUALIZER / SET_EQUALIZER / SET_DOLBY.
 * [gains] are the slider positions in dB (the preset's exact levels as the projector reports them,
 * 0s for the projector default, or the user's drag in progress). [dolby] is null when the projector has no Dolby switch.
 * [whaleOs]: a WhaleOS projector (Whale TV) - its own sound modes, with [gains] as the projector's
 * 0..100 band levels (50 the middle) in [minDb]..[maxDb] instead of dB; dragging edits its User mode.
 */
data class EqualizerUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val unsupported: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val whaleOs: Boolean = false,
    val modes: List<EqMode> = emptyList(),
    val mode: String = DEFAULT,
    val bandsHz: List<Int> = emptyList(),
    val minDb: Int = -15,
    val maxDb: Int = 15,
    val gains: List<Float> = emptyList(),
    val dolby: Boolean? = null,
) {
    companion object {
        const val DEFAULT = "default"
        const val CUSTOM = "custom"
        /** The WhaleOS mode slider edits go to. */
        const val WHALEOS_USER = "user"
    }
}

/** Dolby Audio and the equalizer are never on together; CineAgent switches the other off itself. */
class EqualizerViewModel(private val connectionManager: ProjectorConnectionManager) : ViewModel() {

    private val _state = MutableStateFlow(EqualizerUiState())
    val state: StateFlow<EqualizerUiState> = _state

    init {
        // The projector type is known from GET_DEVICE_INFO at connect; re-read when it changes
        // (another projector connected) so the right modes show at once.
        viewModelScope.launch {
            connectionManager.equalizerType.collect { type ->
                _state.update { it.copy(whaleOs = type == ProjectorConnectionManager.EQUALIZER_WHALEOS) }
                refresh()
            }
        }
        // Mode or band changed on the projector itself (WhaleOS Sound menu): re-read so the
        // chips and sliders follow it.
        viewModelScope.launch {
            connectionManager.equalizerChanged.collect { refresh() }
        }
        // Dolby switched on the projector itself (its Sound menu, via the remote): flip the switch
        // here at once, then re-read the full state - turning Dolby on also resets the equalizer.
        viewModelScope.launch {
            connectionManager.dolbyChanged.collect { on ->
                _state.update { s -> if (s.dolby != null) s.copy(dolby = on) else s }
                refresh()
            }
        }
    }

    fun refresh() = send("GET_EQUALIZER", emptyMap(), showBusy = false)

    fun selectMode(id: String) {
        if (_state.value.whaleOs) {
            if (id != _state.value.mode) send("SET_EQUALIZER", mapOf("mode" to id))
            return
        }
        if (id == _state.value.mode && id != EqualizerUiState.CUSTOM) return
        val params = if (id == EqualizerUiState.CUSTOM) {
            mapOf("mode" to id, "gains" to JSONArray(_state.value.gains.map { Math.round(it) }))
        } else mapOf("mode" to id)
        send("SET_EQUALIZER", params)
    }

    fun setDolby(on: Boolean) = send("SET_DOLBY", mapOf("enabled" to on))

    /** A slider moving: shown at once, sent as Custom when the drag ends ([commitCustom]). */
    fun dragBand(band: Int, db: Float) {
        _state.update { s ->
            s.copy(gains = s.gains.toMutableList().also { if (band in it.indices) it[band] = Math.round(db).toFloat().coerceIn(s.minDb.toFloat(), s.maxDb.toFloat()) })
        }
    }

    fun commitCustom() {
        val levels = JSONArray(_state.value.gains.map { Math.round(it) })
        if (_state.value.whaleOs) {
            send("SET_EQUALIZER", mapOf("mode" to EqualizerUiState.WHALEOS_USER, "levels" to levels))
        } else {
            send("SET_EQUALIZER", mapOf("mode" to EqualizerUiState.CUSTOM, "gains" to levels))
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    private fun send(cmd: String, params: Map<String, Any?>, showBusy: Boolean = true) {
        if (_state.value.busy) return
        if (showBusy) _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val executor = connectionManager.executor
            val response = executor?.query(cmd, params)
            _state.update { s -> apply(s, response, executor == null).copy(busy = false, loading = false) }
        }
    }

    private fun apply(s: EqualizerUiState, r: JSONObject?, offline: Boolean): EqualizerUiState {
        if (r == null) return s.copy(error = if (offline) "Not connected to the projector" else "The projector did not answer")
        if (r.optString("status") != "SUCCESS") {
            // 1002: a CineAgent build from before the equalizer.
            if (r.optInt("errorCode") == 1002) return s.copy(unsupported = true, error = null)
            return s.copy(error = r.optString("message", "Command failed"))
        }
        val modes = r.optJSONArray("modes")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { m -> EqMode(m.getString("id"), m.getString("name")) } }
        }.orEmpty()
        fun ints(key: String) = r.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        val bands = ints("bandsHz").orEmpty()
        if (r.optString("equalizer") == ProjectorConnectionManager.EQUALIZER_WHALEOS) {
            // WhaleOS: the mode's band levels, 0..100.
            val range = ints("levelRange") ?: listOf(0, 100)
            return s.copy(
                error = null, unsupported = false, whaleOs = true,
                modes = modes, mode = r.optString("mode"), bandsHz = bands,
                minDb = range[0], maxDb = range[1],
                gains = ints("levels")?.map { it.toFloat() } ?: List(bands.size) { (range[0] + range[1]) / 2f },
                dolby = null,
            )
        }
        val range = ints("levelRangeDb") ?: listOf(-15, 15)
        // Exact levels: "gainsMb" (millibels, as the engine reports them) when CineAgent sends it; older
        // builds send only "gainsDb", already rounded to whole dB.
        val gains = (if (r.isNull("gainsMb")) null else ints("gainsMb")?.map { it / 100f })
            ?: (if (r.isNull("gainsDb")) null else ints("gainsDb")?.map { it.toFloat() })
            ?: List(bands.size) { 0f }
        val notice = when (r.optString("switchedOff")) {
            "dolby" -> "Dolby Audio turned off - it can't be used with the equalizer"
            "equalizer" -> "Equalizer set to default - it can't be used with Dolby Audio"
            else -> null
        }
        return s.copy(
            error = null, unsupported = false, notice = notice ?: s.notice, whaleOs = false,
            modes = modes, mode = r.optString("mode", EqualizerUiState.DEFAULT), bandsHz = bands,
            minDb = range[0], maxDb = range[1], gains = gains,
            dolby = if (r.has("dolby")) r.getBoolean("dolby") else null,
        )
    }
}
