package com.boat.cinehead.ui.remote

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boat.cinehead.agent.VoiceAgentClient
import com.boat.cinehead.net.AppSettingsStore
import com.boat.cinehead.net.CommandResult
import com.boat.cinehead.net.ConnectionState
import com.boat.cinehead.net.FocusedTextField
import com.boat.cinehead.net.ProjectorConnectionManager
import com.boat.cinehead.net.ToolDispatcher
import com.boat.cinehead.net.VolumeInfo
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatMessage(val text: String, val isUser: Boolean, val isError: Boolean = false)

/** One keystone corner reading. Values are pixels - integers from the MediaTek trapezoid HAL,
 *  fractional from persist.vendor.hwc.keystone, so kept as Float. */
data class KeystonePoint(val x: Float, val y: Float)

/** The four keystone corners, laid out as the panel draws them:
 *  [topLeft] = (x1,y1), [topRight] = (x2,y2), [bottomLeft] = (x3,y3), [bottomRight] = (x4,y4). */
data class KeystoneQuad(
    val topLeft: KeystonePoint,
    val topRight: KeystonePoint,
    val bottomLeft: KeystonePoint,
    val bottomRight: KeystonePoint,
)

/** What Keyboard Mode should currently show, or null to hide it.
 *  - [FieldFocused] is driven by CineAgent's TEXT_FIELD_FOCUSED push - a real editable field
 *    is focused on the projector (login / password / a search box that exposes one). "Send"
 *    there goes to SEND_TEXT (accessibility ACTION_SET_TEXT).
 *  - [SearchQuery] is opened by us, on a timer, after the Search button fired OPEN_SEARCH for
 *    an app whose in-app search has no field the projector can report as focused (YouTube TV).
 *    "Send" there re-fires OPEN_SEARCH with the typed query, which CineAgent turns into the
 *    search_query= deep link. */
sealed class KeyboardMode {
    data class FieldFocused(val field: FocusedTextField) : KeyboardMode()
    data object SearchQuery : KeyboardMode()
}

data class AppPackageSettings(
    val netflix: String,
    val youtube: String,
    val primeVideo: String,
    val hotstar: String,
    val usbSourceCommand: String,
    val serverUrl: String,
)

private const val TAG = "RemoteViewModel"
private val VOLUME_REFRESH_TOOLS = setOf("volume_up", "volume_down", "set_volume", "mute", "unmute")
// Retry window covers the projector waking from screensaver before its power dialog renders;
// the disconnect delay is the fixed tail wait after a successful command. Best case (dialog
// was already up) totals ~15s, worst case (retries exhaust the window) ~20s.
private const val POWER_RETRY_WINDOW_MS = 5_000L
private const val POWER_RETRY_INTERVAL_MS = 1_000L
private const val POWER_OFF_DISCONNECT_DELAY_MS = 15_000L

// GET_DEVICE_INFO used to be a single attempt on every (re)connect - fine most of the time,
// but a quick app relaunch (kill + reopen within a second or two, e.g. from recents) forces a
// brand new TCP connect right as this runs, and that first attempt can lose the race (timeout,
// or the connection isn't fully settled yet) and silently fall back to showing USB with no
// retry and no visible error. Retried a few times, short-interval, before giving up - still
// bounded, so a genuinely unreachable projector doesn't hang the Remote screen.
private const val DEVICE_CAPABILITIES_MAX_ATTEMPTS = 3
private const val DEVICE_CAPABILITIES_RETRY_INTERVAL_MS = 700L

// After the Search button opens YouTube TV's (empty) search screen, how long to let the
// projector render it before the phone brings up Keyboard Mode for the query. Matches the
// flow confirmed live: fire OPEN_SEARCH -> ~3s -> Keyboard Mode -> type -> Send re-fires
// OPEN_SEARCH{query}.
private const val SEARCH_KEYBOARD_OPEN_DELAY_MS = 3_000L

// Auto Keystone Correction panel cadence while it's open. One cycle is: show the current
// four-corner quad for KEYSTONE_DISPLAY_MS, then a loader for KEYSTONE_LOADER_MS while the
// next GET_KEYSTONE round-trips, then repeat - the quad is always re-shown afterwards whether
// or not the value moved. GET_KEYSTONE on CineAgent reads persist.vendor.hwc.keystone, or
// falls back to the MediaTek TrapezoidHal logcat line on F1-class firmware where that
// property is SELinux-blocked.
private const val KEYSTONE_DISPLAY_MS = 2_000L
private const val KEYSTONE_LOADER_MS = 3_000L

/** Reference projection frame the keystone corner coordinates are expressed in - the panel
 *  maps the quad from this space into its drawing area. */
internal const val KEYSTONE_REF_W = 1920f
internal const val KEYSTONE_REF_H = 1080f

/** The only tool-calling actions the voice/chat agent is allowed to trigger.
 *  Anything else gets the "not supported" popup instead of being dispatched. */
private val SUPPORTED_AGENT_TOOLS = setOf(
    "set_volume", "open_settings", "open_wifi_settings", "open_bluetooth_settings", "search"
)

/** Maps CommandRouter's canonical platform names (from a SEARCH result) to the existing
 *  launch_X tools ToolDispatcher already supports. */
private val PLATFORM_LAUNCH_TOOLS = mapOf(
    "YOUTUBE" to "launch_youtube",
    "JIO_HOTSTAR" to "launch_hotstar",
    "NETFLIX" to "launch_netflix",
    "AMAZON_PRIME_VIDEO" to "launch_prime_video",
)

/** How long to wait after launching a platform app before firing the search - heuristic,
 *  not measured per-app; tune if search fires before the app's finished loading. */
private const val SEARCH_APP_LAUNCH_WAIT_MS = 3000L

class RemoteViewModel(
    private val connectionManager: ProjectorConnectionManager,
    private val appSettingsStore: AppSettingsStore
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.state

    /** Keyboard Mode Screen: non-null exactly when the projector reports an editable field
     *  focused, so the UI shows/hides purely by observing this - see RemoteScreen's Dialog. */
    val focusedTextField: StateFlow<FocusedTextField?> = connectionManager.focusedTextField

    /** Drives the timer-based (non-FieldFocused) Keyboard Mode: set by [runSearch] after
     *  CineAgent opens an in-app search screen, so the user can type from the phone instead
     *  of the projector's on-screen keyboard. Cleared on Send, on Back, or on disconnect. */
    private val _searchKeyboardVisible = MutableStateFlow(false)

    /** From the last OPEN_SEARCH response: true when the app exposes no typeable field and
     *  needs the query as a search_query= deep link (YouTube TV); false when we should type
     *  into its focused field via SEND_TEXT (Hotstar, and anything else). */
    private var lastSearchAcceptsQuery = false

    /** The single source RemoteScreen observes to decide whether - and which kind of -
     *  Keyboard Mode to show. A genuinely focused field always wins over our timer-driven
     *  search entry, so if an app ever does expose a focusable search box the SEND_TEXT path
     *  is used instead. */
    val keyboardMode: StateFlow<KeyboardMode?> =
        combine(connectionManager.focusedTextField, _searchKeyboardVisible) { field, searchVisible ->
            when {
                field != null -> KeyboardMode.FieldFocused(field)
                searchVisible -> KeyboardMode.SearchQuery
                else -> null
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isSendingText = MutableStateFlow(false)
    val isSendingText: StateFlow<Boolean> = _isSendingText

    private val toolDispatcher = ToolDispatcher(appSettingsStore) { connectionManager.executor }

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    val volume: StateFlow<VolumeInfo?> = _volume

    /** CineAgent has no GET_MUTE_STATE query, so this is tracked locally from the mute/unmute
     *  button itself (toggled on each press) - the mute button uses this to decide which of
     *  CineAgent's two commands to send, instead of always sending MUTE. */
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    /** True from the moment Power is pressed until the post-power-off disconnect fires -
     *  the projector itself takes a few seconds to click through its power dialog and shut
     *  down, so the screen needs to show that as in-progress rather than looking frozen. */
    private val _isPoweringOff = MutableStateFlow(false)
    val isPoweringOff: StateFlow<Boolean> = _isPoweringOff

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    /** Some projector models (F1, F1 Pro) don't support USB as a source at all, so the remote
     *  hides that button rather than offering a control that always fails - refreshed from
     *  CineAgent's GET_DEVICE_INFO on every (re)connect, retried a few times before giving up
     *  (see [refreshDeviceCapabilities]). Defaults to true (shown) and stays true only if every
     *  attempt fails or the field is missing (e.g. an older CineAgent build on the projector
     *  that predates this field) - never hides USB on a guess. */
    private val _usbSupported = MutableStateFlow(true)
    val usbSupported: StateFlow<Boolean> = _usbSupported

    /** F1 / F1 Pro map Menu to a recents action their launcher has no surface for, so it does
     *  nothing there - CineAgent reports this via GET_DEVICE_INFO's `menuSupported` and the
     *  remote hides the Menu button, exactly like [_usbSupported]. Defaults to true (shown) and
     *  only goes false on an explicit `menuSupported=false` from a CineAgent build new enough to
     *  send the field - an older build that omits it leaves Menu visible. */
    private val _menuSupported = MutableStateFlow(true)
    val menuSupported: StateFlow<Boolean> = _menuSupported

    /** Auto Keystone Correction only works where CineAgent can actually read the projector's
     *  keystone geometry - the E1 (via persist.vendor.hwc.keystone). On F1 / F1 Pro that
     *  property is SELinux-blocked and the TrapezoidHal log is unreadable to a sideloaded
     *  CineAgent, so GET_KEYSTONE always fails there and the button is hidden. Derived from
     *  GET_DEVICE_INFO's `model` (see [refreshDeviceCapabilities]); defaults to true. */
    private val _keystoneSupported = MutableStateFlow(true)
    val keystoneSupported: StateFlow<Boolean> = _keystoneSupported

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    /** Auto Keystone Correction panel: true while the overlay is open and [keystonePollJob]
     *  is running its display/loader cycle. RemoteScreen renders the panel over the D-pad area
     *  only while this is true, so the D-pad itself stays visible and clickable underneath -
     *  adjusting a corner on the projector and watching this panel update is the whole point. */
    private val _keystoneLogVisible = MutableStateFlow(false)
    val keystoneLogVisible: StateFlow<Boolean> = _keystoneLogVisible

    /** The current four-corner quad, replaced after every GET_KEYSTONE whether or not it moved.
     *  Null until the first successful read after the panel opens. */
    private val _keystoneQuad = MutableStateFlow<KeystoneQuad?>(null)
    val keystoneQuad: StateFlow<KeystoneQuad?> = _keystoneQuad

    /** True during the loader phase of each cycle (while the next GET_KEYSTONE is in flight). */
    private val _keystoneLoading = MutableStateFlow(false)
    val keystoneLoading: StateFlow<Boolean> = _keystoneLoading

    private var keystonePollJob: Job? = null

    /**
     * Popup shown only when the agent tries to call a tool outside the four
     * supported voice/chat actions (set_volume, open_settings,
     * open_wifi_settings, open_bluetooth_settings).
     */
    private val _actionPopup = MutableStateFlow<String?>(null)
    val actionPopup: StateFlow<String?> = _actionPopup

    /**
     * True only while a query is in flight (connected to server).
     * False at all other times — no persistent connection.
     */
    private val _agentConnected = MutableStateFlow(false)
    val agentConnected: StateFlow<Boolean> = _agentConnected

    // Current active client — replaced per query
    private var voiceAgentClient: VoiceAgentClient? = null

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    Log.d(TAG, "connectionState -> Connected(${state.host}); refreshing volume + capabilities")
                    refreshVolume()
                    // No GET_MUTE_STATE in CineAgent's protocol - a 0% reading on connect is
                    // taken as "already muted" so the first press unmutes rather than re-muting.
                    _isMuted.value = _volume.value?.current == 0
                    refreshDeviceCapabilities()
                }
            }
        }
        // No persistent connection on init — server runs, app connects per query
    }

    private suspend fun handleAgentCommand(action: String, args: Map<String, Any?>) {
        if (action !in SUPPORTED_AGENT_TOOLS) {
            _actionPopup.value = "Tool is not supported and Please use remote for your action"
            _isSending.value = false
            return
        }
        val result = runTool(action, args)
        // Supported tools just perform the action on the projector — no confirmation
        // popup on success. Failures still surface, but as a lightweight snackbar
        // (statusMessage) rather than a blocking dialog.
        if (!result.success) {
            _statusMessage.value = "Failed: ${result.message}"
        }
        _isSending.value = false
    }

    fun pressButton(toolName: String, label: String) {
        if (toolName == "power") {
            _isPoweringOff.value = true
            viewModelScope.launch { powerOff() }
            return
        }
        if (toolName == "search") {
            viewModelScope.launch { runSearch() }
            return
        }
        viewModelScope.launch {
            // The remote's single Mute button toggles CineAgent's separate MUTE/UNMUTE
            // commands - without this, repeated presses just re-sent MUTE and the projector
            // never unmuted.
            val effectiveTool = if (toolName == "mute") {
                if (_isMuted.value) "unmute" else "mute"
            } else {
                toolName
            }
            val result = runTool(effectiveTool, emptyMap())
            if (toolName == "mute" && result.success) _isMuted.value = !_isMuted.value
        }
    }

    /**
     * CineAgent's POWER command opens the projector's Lockdown/Power off/Restart dialog and
     * clicks "Power off" itself, but only gets one attempt at finding that on-screen text. If
     * the projector was asleep, waking the display before the dialog can even render takes
     * over a second on its own, and CineAgent's single attempt can fire before it's there -
     * failing with "Power off option not found on screen". Rather than teach CineAgent to
     * poll, retry the whole command from here until it succeeds or [POWER_RETRY_WINDOW_MS]
     * runs out, all while the "Powering off…" overlay is already covering the screen.
     */
    private suspend fun powerOff() {
        val executor = connectionManager.executor
        if (executor == null || !executor.isConnected) {
            _isPoweringOff.value = false
            _statusMessage.value = "Not connected to a projector"
            return
        }

        val deadline = System.currentTimeMillis() + POWER_RETRY_WINDOW_MS
        var result = toolDispatcher.dispatch("power")
        while (!result.success && System.currentTimeMillis() < deadline) {
            delay(POWER_RETRY_INTERVAL_MS)
            result = toolDispatcher.dispatch("power")
        }

        if (!result.success) {
            _isPoweringOff.value = false
            _statusMessage.value = "Failed: ${result.message}"
            return
        }
        delay(POWER_OFF_DISCONNECT_DELAY_MS)
        disconnect()
    }

    private suspend fun runTool(toolName: String, args: Map<String, Any?>): CommandResult {
        val result = toolDispatcher.dispatch(toolName, args)
        if (!result.success) _statusMessage.value = result.message
        if (toolName in VOLUME_REFRESH_TOOLS) refreshVolume()
        return result
    }

    /**
     * Search button. Sends OPEN_SEARCH with no query - CineAgent opens whichever app is
     * foreground on the projector to its own search screen. If that app is one where the
     * phone has to supply the query itself (YouTube TV: CineAgent replies `acceptsQuery=true`),
     * wait for its search screen to render, then raise Keyboard Mode - [sendSearchQuery]
     * re-fires OPEN_SEARCH with whatever the user types. Every other app just gets its search
     * screen opened, exactly as before.
     */
    private suspend fun runSearch() {
        val executor = connectionManager.executor ?: run {
            _statusMessage.value = "Not connected to a projector"
            return
        }
        val response = executor.query("OPEN_SEARCH")
        val status = response?.optString("status").orEmpty()
        if (status != "SUCCESS" && status != "OK") {
            _statusMessage.value = response?.optString("message")?.ifBlank { null } ?: "Search is not available here"
            return
        }
        lastSearchAcceptsQuery = response?.optBoolean("acceptsQuery", false) == true

        // CineAgent opened *some* in-app search screen. Bring up Keyboard Mode from the phone
        // so the user can type here instead of on the projector's on-screen keyboard. Skip it
        // only when CineAgent already reports a real focused field on the projector - the
        // FieldFocused path covers that. Hotstar is why this is no longer gated on
        // acceptsQuery: it opens a plain EditText search box (OPEN_SEARCH -> SUCCESS) that
        // CineAgent never flags as acceptsQuery and never reports focused, so previously the
        // phone showed nothing and stranded the user on the projector keyboard.
        delay(SEARCH_KEYBOARD_OPEN_DELAY_MS)
        if (connectionManager.focusedTextField.value == null) {
            _searchKeyboardVisible.value = true
        }
    }

    /** Keyboard Mode "Send" for the timer-driven [KeyboardMode.SearchQuery] variant. When the
     *  last OPEN_SEARCH flagged acceptsQuery (YouTube TV) it re-fires OPEN_SEARCH with the
     *  query, which CineAgent turns into a search_query= deep link. Otherwise (Hotstar, ...)
     *  it types straight into the focused field with SEND_TEXT, falling back to the deep-link
     *  form if that's rejected. Then closes Keyboard Mode. */
    fun sendSearchQuery(text: String) {
        val query = text.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSendingText.value = true
            val executor = connectionManager.executor
            var result = if (lastSearchAcceptsQuery) null
                         else executor?.sendCommand("SEND_TEXT", mapOf("text" to query))
            if (result?.success != true) {
                result = executor?.sendCommand("OPEN_SEARCH", mapOf("query" to query))
            }
            if (result?.success != true) {
                _statusMessage.value = "Search failed: ${result?.message ?: "not connected"}"
            }
            _isSendingText.value = false
            _searchKeyboardVisible.value = false
        }
    }

    /** Back out of the timer-driven search Keyboard Mode without sending anything. */
    fun cancelSearchKeyboard() {
        _searchKeyboardVisible.value = false
    }

    private suspend fun refreshVolume() {
        val executor = connectionManager.executor ?: return
        val info = executor.getVolume()
        if (info != null) _volume.value = info
    }

    /** F1 (AP0034 / "CineHead F1") and F1 Pro (AP0039) - the models where GET_KEYSTONE can't
     *  work. Matches the board codes plus any "F1" in the reported model string. */
    private fun isF1ClassModel(model: String): Boolean {
        val m = model.trim().uppercase(java.util.Locale.US)
        return m == "AP0034" || m == "AP0039" || m.contains("F1")
    }

    private suspend fun refreshDeviceCapabilities() {
        // Start each connect optimistic so a stale F1 result doesn't hide the button on the
        // next projector; only an explicit F1-class `model` flips it back off below.
        _keystoneSupported.value = true
        repeat(DEVICE_CAPABILITIES_MAX_ATTEMPTS) { attempt ->
            val executor = connectionManager.executor
            if (executor == null) {
                Log.w(TAG, "refreshDeviceCapabilities attempt ${attempt + 1}: no executor (not connected), aborting")
                return
            }
            val response = executor.query("GET_DEVICE_INFO")
            Log.d(TAG, "refreshDeviceCapabilities attempt ${attempt + 1}/$DEVICE_CAPABILITIES_MAX_ATTEMPTS: response=$response")
            if (response != null && response.optString("status") == "SUCCESS") {
                val usbSupported = response.optBoolean("usbSupported", true)
                val menuSupported = response.optBoolean("menuSupported", true)
                val model = response.optString("model")
                val keystoneSupported = !isF1ClassModel(model)
                Log.d(TAG, "refreshDeviceCapabilities: usbSupported=$usbSupported (field present=${response.has("usbSupported")}), menuSupported=$menuSupported (field present=${response.has("menuSupported")}), model=$model keystoneSupported=$keystoneSupported")
                _usbSupported.value = usbSupported
                _menuSupported.value = menuSupported
                _keystoneSupported.value = keystoneSupported
                if (!keystoneSupported && _keystoneLogVisible.value) hideKeystoneLog()
                return
            }
            if (attempt < DEVICE_CAPABILITIES_MAX_ATTEMPTS - 1) {
                delay(DEVICE_CAPABILITIES_RETRY_INTERVAL_MS)
            } else {
                Log.w(TAG, "refreshDeviceCapabilities: all $DEVICE_CAPABILITIES_MAX_ATTEMPTS attempts failed, leaving usbSupported=${_usbSupported.value}")
            }
        }
    }

    /**
     * Called when user clicks Send (text) or finishes speaking (mic).
     * Creates a fresh WebSocket connection per query:
     *   connect → send query → receive response → disconnect
     */
    /**
     * Routes free-text chat/voice input entirely on-device via CommandRouter - no network
     * call, no server, no GCP dependency. See router/CommandRouter.java for the classifier
     * itself.
     */
    fun sendChat(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // Add user message to chat immediately
            _chatMessages.value = _chatMessages.value + ChatMessage(text, isUser = true)
            _isSending.value = true

            val result = com.boat.cinehead.router.CommandRouter.route(text)
            when (result.id) {
                1 -> {
                    val percent = result.value
                    if (percent != null) {
                        runTool("set_volume", mapOf("percent" to percent))
                    } else {
                        _actionPopup.value = "Tool is not supported and Please use remote for your action"
                    }
                }
                2 -> runTool("open_bluetooth_settings", emptyMap())
                3 -> runTool("open_settings", emptyMap())
                4 -> runTool("open_wifi_settings", emptyMap())
                5 -> {
                    // e.g. "search paw patrol in netflix" / "hotstar search tangle" - the
                    // router extracts both the platform and the query. Launch that app first
                    // (reusing the existing launch_X tools QuickLaunchRow already uses), give
                    // it a moment to come up, then fire the search with the query. If the
                    // platform wasn't one of the four we can launch, best-effort fall back to
                    // just searching whatever's already on screen.
                    val launchTool = PLATFORM_LAUNCH_TOOLS[result.platform]
                    if (launchTool != null) {
                        runTool(launchTool, emptyMap())
                        delay(SEARCH_APP_LAUNCH_WAIT_MS)
                    }
                    runTool("search", mapOf("query" to (result.query.orEmpty())))
                }
                else -> _actionPopup.value = "Tool is not supported and Please use remote for your action"
            }

            _isSending.value = false
        }
    }



    /** Keyboard Mode Screen "Send" - goes straight to CineAgent's SEND_TEXT (accessibility
     *  ACTION_SET_TEXT on whatever field is still focused), not through the Gemini voice-agent
     *  pipeline [sendChat] uses - that's a different, unrelated feature that happens to share a
     *  similar text+mic input card. */
    fun sendKeyboardText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isSendingText.value = true
            val result = connectionManager.executor?.sendCommand("SEND_TEXT", mapOf("text" to text))
            if (result?.success != true) {
                _statusMessage.value = "Failed to send text: ${result?.message ?: "not connected"}"
            }
            _isSendingText.value = false
        }
    }

    /** Quick Launch's Auto Keystone Correction button - opens or closes the live log panel. */
    fun toggleKeystoneLog() {
        if (_keystoneLogVisible.value) hideKeystoneLog() else showKeystoneLog()
    }

    private fun showKeystoneLog() {
        _keystoneLogVisible.value = true
        _keystoneQuad.value = null
        _keystoneLoading.value = false
        keystonePollJob?.cancel()
        keystonePollJob = viewModelScope.launch {
            while (true) {
                // Loader phase: GET_KEYSTONE round-trips, loader is shown for at least
                // KEYSTONE_LOADER_MS even if the reply comes back sooner.
                _keystoneLoading.value = true
                val startedAt = System.currentTimeMillis()
                val response = connectionManager.executor?.query("GET_KEYSTONE")
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < KEYSTONE_LOADER_MS) delay(KEYSTONE_LOADER_MS - elapsed)
                _keystoneLoading.value = false

                // Replace the quad on every successful read, changed or not. A failed read
                // leaves the previous quad on screen.
                response
                    ?.takeIf { it.optString("status") == "SUCCESS" }
                    ?.let { parseKeystoneQuad(it) }
                    ?.let { _keystoneQuad.value = it }

                // Display phase: hold the quad before the next cycle.
                delay(KEYSTONE_DISPLAY_MS)
            }
        }
    }

    /**
     * Builds the four-corner [KeystoneQuad] from CineAgent's GET_KEYSTONE response, as ABSOLUTE
     * coordinates in a [KEYSTONE_REF_W] x [KEYSTONE_REF_H] frame. This is the *electronic*
     * keystone-correction geometry (the region the compositor warps the source into), not the
     * shape on the wall - the wall also carries the projector's fixed physical throw angle,
     * which isn't in this data.
     *
     * - `trapezoid` object (F1-class, from the MediaTek TrapezoidHal log): per-corner inward
     *   pixel offsets - converted here to absolute frame corners.
     * - `keystone` CSV (E1-class, from persist.vendor.hwc.keystone): 8 absolute numbers, but in
     *   the display pipeline's BOTTOM-LEFT origin (y grows upward - confirmed live 2026-09-10 on
     *   a CineHead E1: DPAD_UP in the keystone UI increases the stored y). Corner order in that
     *   frame is BL, BR, TR, TL. Flipped to the panel's top-left screen space here.
     * Returns null if neither is usable.
     */
    private fun parseKeystoneQuad(response: JSONObject): KeystoneQuad? {
        response.optJSONObject("trapezoid")?.let { t ->
            fun off(name: String): KeystonePoint? {
                val p = t.optJSONObject(name) ?: return null
                return KeystonePoint(p.optDouble("x").toFloat(), p.optDouble("y").toFloat())
            }
            val lt = off("leftTop"); val rt = off("rightTop")
            val lb = off("leftBot"); val rb = off("rightBot")
            if (lt != null && rt != null && lb != null && rb != null) {
                return KeystoneQuad(
                    topLeft = KeystonePoint(lt.x, lt.y),
                    topRight = KeystonePoint(KEYSTONE_REF_W - rt.x, rt.y),
                    bottomLeft = KeystonePoint(lb.x, KEYSTONE_REF_H - lb.y),
                    bottomRight = KeystonePoint(KEYSTONE_REF_W - rb.x, KEYSTONE_REF_H - rb.y),
                )
            }
        }
        val n = response.optString("keystone").split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (n.size != 8) return null
        // n is BL, BR, TR, TL in a bottom-left origin; flip y into top-left screen space.
        fun flipY(y: Float) = KEYSTONE_REF_H - y
        return KeystoneQuad(
            bottomLeft = KeystonePoint(n[0], flipY(n[1])),
            bottomRight = KeystonePoint(n[2], flipY(n[3])),
            topRight = KeystonePoint(n[4], flipY(n[5])),
            topLeft = KeystonePoint(n[6], flipY(n[7])),
        )
    }

    private fun hideKeystoneLog() {
        _keystoneLogVisible.value = false
        _keystoneLoading.value = false
        keystonePollJob?.cancel()
        keystonePollJob = null
    }

    fun consumeStatusMessage() { _statusMessage.value = null }
    fun consumeActionPopup() { _actionPopup.value = null }

    fun getAppPackageSettings() = AppPackageSettings(
        netflix = appSettingsStore.netflixPackage,
        youtube = appSettingsStore.youtubePackage,
        primeVideo = appSettingsStore.primeVideoPackage,
        hotstar = appSettingsStore.hotstarPackage,
        usbSourceCommand = appSettingsStore.usbSourceCommand,
        serverUrl = appSettingsStore.serverUrl,
    )

    fun saveAppPackageSettings(settings: AppPackageSettings) {
        appSettingsStore.netflixPackage = settings.netflix
        appSettingsStore.youtubePackage = settings.youtube
        appSettingsStore.primeVideoPackage = settings.primeVideo
        appSettingsStore.hotstarPackage = settings.hotstar
        appSettingsStore.usbSourceCommand = settings.usbSourceCommand
        appSettingsStore.serverUrl = settings.serverUrl
    }

    fun disconnect() {
        voiceAgentClient?.disconnect()
        connectionManager.disconnect()
        _isPoweringOff.value = false
        _searchKeyboardVisible.value = false
        hideKeystoneLog()
    }

    override fun onCleared() {
        super.onCleared()
        voiceAgentClient?.disconnect()
        keystonePollJob?.cancel()
    }
}
