package com.projector.airemote.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projector.airemote.agent.VoiceAgentClient
import com.projector.airemote.net.AppSettingsStore
import com.projector.airemote.net.CommandResult
import com.projector.airemote.net.ConnectionState
import com.projector.airemote.net.FirebaseServerController
import com.projector.airemote.net.ProjectorConnectionManager
import com.projector.airemote.net.ToolDispatcher
import com.projector.airemote.net.VolumeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatMessage(val text: String, val isUser: Boolean, val isError: Boolean = false)

data class AppPackageSettings(
    val netflix: String,
    val youtube: String,
    val primeVideo: String,
    val hotstar: String,
    val usbSourceCommand: String,
    val serverUrl: String,
)

private val VOLUME_REFRESH_TOOLS = setOf("volume_up", "volume_down", "set_volume", "mute")
private const val POWER_OFF_DISCONNECT_DELAY_MS = 3000L

/** The only tool-calling actions the voice/chat agent is allowed to trigger.
 *  Anything else gets the "not supported" popup instead of being dispatched. */
private val SUPPORTED_AGENT_TOOLS = setOf(
    "set_volume", "open_settings", "open_wifi_settings", "open_bluetooth_settings"
)

class RemoteViewModel(
    private val connectionManager: ProjectorConnectionManager,
    private val appSettingsStore: AppSettingsStore,
    private val firebaseServerController: FirebaseServerController
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.state

    val serverState: StateFlow<FirebaseServerController.ServerState> get() = _serverState
    private val _serverState = MutableStateFlow(FirebaseServerController.ServerState.UNKNOWN)
    private var serverStartSignaled = false

    private val toolDispatcher = ToolDispatcher(appSettingsStore) { connectionManager.executor }

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    val volume: StateFlow<VolumeInfo?> = _volume

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

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
                if (state is ConnectionState.Connected) refreshVolume()
            }
        }
        // No persistent connection on init — server runs, app connects per query
        firebaseServerController.observeStatus { state -> _serverState.value = state }
    }

    /**
     * Ensures the voice agent server has been asked to start, and waits
     * (with a timeout) until the VM reports it's actually running. Only
     * does real work on the FIRST query of the session — after that the
     * server is assumed to still be running until the app is closed.
     */
    private suspend fun ensureServerStarted() {
        if (serverStartSignaled && _serverState.value == FirebaseServerController.ServerState.RUNNING) return

        serverStartSignaled = true
        firebaseServerController.signalStart()
        _statusMessage.value = "Starting voice agent server…"

        val timeoutMs = 20_000L
        val pollIntervalMs = 500L
        var waited = 0L
        while (_serverState.value != FirebaseServerController.ServerState.RUNNING && waited < timeoutMs) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
        if (_serverState.value != FirebaseServerController.ServerState.RUNNING) {
            _statusMessage.value = "Server is taking longer than expected to start — trying anyway"
        }
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
        viewModelScope.launch {
            runTool(toolName, emptyMap())
            if (toolName == "power") {
                delay(POWER_OFF_DISCONNECT_DELAY_MS)
                disconnect()
            }
        }
    }

    private suspend fun runTool(toolName: String, args: Map<String, Any?>): CommandResult {
        val result = toolDispatcher.dispatch(toolName, args)
        if (!result.success) _statusMessage.value = result.message
        if (toolName in VOLUME_REFRESH_TOOLS) refreshVolume()
        return result
    }

    private suspend fun refreshVolume() {
        val executor = connectionManager.executor ?: return
        val info = executor.getVolume()
        if (info != null) _volume.value = info
    }

    /**
     * Called when user clicks Send (text) or finishes speaking (mic).
     * Creates a fresh WebSocket connection per query:
     *   connect → send query → receive response → disconnect
     */
    fun sendChat(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // Add user message to chat immediately
            _chatMessages.value = _chatMessages.value + ChatMessage(text, isUser = true)
            _isSending.value = true

            ensureServerStarted()

            val serverUrl = appSettingsStore.serverUrl.ifBlank { "ws://34.170.101.179:8765" }

            // Disconnect any previous query's connection
            voiceAgentClient?.disconnect()

            // Create fresh client for this query
            voiceAgentClient = VoiceAgentClient(
                serverUrl = serverUrl,
                onCommand = { action, args ->
                    handleAgentCommand(action, args)
                },
                onTextResponse = { message ->
                    _chatMessages.value = _chatMessages.value +
                        ChatMessage(message, isUser = false)
                    _isSending.value = false
                },
                onError = { error ->
                    _statusMessage.value = error
                    _isSending.value = false
                },
                onConnected = {
                    _agentConnected.value = true
                },
                onDisconnected = {
                    _agentConnected.value = false
                }
            )

            // Connect to server and send query — disconnects automatically after response
            voiceAgentClient?.connectAndSend(text)
        }
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
    }

    override fun onCleared() {
        super.onCleared()
        voiceAgentClient?.disconnect()
    }
}
