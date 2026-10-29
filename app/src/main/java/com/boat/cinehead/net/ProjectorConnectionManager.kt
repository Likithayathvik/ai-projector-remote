package com.boat.cinehead.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Mirrors CineAgent's TEXT_FIELD_FOCUSED push event (Keyboard Mode Screen): [isPassword]
 *  distinguishes a login/password field from a plain search field so the UI can mask input,
 *  [text] is the field's current contents on the projector, used to pre-fill the phone side. */
data class FocusedTextField(val packageName: String, val isPassword: Boolean, val text: String)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val host: String) : ConnectionState()
    /** The connection dropped unexpectedly and is being re-established to the same projector;
     *  the Remote screen stays up meanwhile. Becomes [Lost] if that doesn't work. */
    data object Reconnecting : ConnectionState()
    /** Connection dropped unexpectedly while in use (vs. a user-initiated disconnect). */
    data class Lost(val message: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
    /** The projector refused this phone because another phone is connected to it
     *  (CineAgent allows one at a time). Shown as a popup on the Discovery screen. */
    data class Busy(val deviceName: String) : ConnectionState()
}

/** Owns the single active projector connection for the app's lifetime, plus a heartbeat
 *  (spec section 4.4: PING every 10s) that detects power-off / Wi-Fi drop / CineAgent
 *  stopping while the Remote screen is active. Also reacts to CineAgent's own
 *  TCP_DISCONNECT_WARNING / DEVICE_REBOOTING events (spec section 7) so a connection loss
 *  the server already knows about is surfaced immediately, without waiting for the next
 *  heartbeat to fail first. */
class ProjectorConnectionManager(
    private val appContext: Context,
    private val autoReconnectGate: AutoReconnectGate,
    private val lastConnectedStore: LastConnectedStore,
    private val discovery: CineAgentDiscovery
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    /** Null whenever the projector reports no editable field focused - the Keyboard Mode screen
     *  is shown/hidden purely by observing this, no separate open/close call needed. */
    private val _focusedTextField = MutableStateFlow<FocusedTextField?>(null)
    val focusedTextField: StateFlow<FocusedTextField?> = _focusedTextField

    /** CineAgent's DOLBY_CHANGED push: Dolby Audio was switched on the projector (e.g. from its
     *  own Sound menu with the remote). Carries the new on/off value. */
    private val _dolbyChanged = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val dolbyChanged: SharedFlow<Boolean> = _dolbyChanged

    /** CineAgent's EQUALIZER_CHANGED push (WhaleOS): the sound mode or a band level was changed on
     *  the projector itself (its Sound menu, via the remote). */
    private val _equalizerChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val equalizerChanged: SharedFlow<Unit> = _equalizerChanged

    /** Which equalizer the connected projector has, from GET_DEVICE_INFO's `equalizer` on every
     *  (re)connect: [EQUALIZER_WHALEOS] (Whale TV: its own sound modes) or [EQUALIZER_NXP] (F1
     *  family and the rest, also when an older CineAgent build omits the field). */
    private val _equalizerType = MutableStateFlow(EQUALIZER_NXP)
    val equalizerType: StateFlow<String> = _equalizerType

    fun setEqualizerType(type: String) {
        _equalizerType.value = type
    }

    var executor: RemoteExecutor? = null
        private set

    private var connection: CineAgentConnection? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null

    /** Key #2 the current connection authenticated with; sent again with DISCONNECT. */
    private var currentCredentials: SessionCredentials? = null

    private enum class OpenResult { CONNECTED, AUTH_REJECTED, UNREACHABLE, BUSY }

    /**
     * Opens an authenticated session to [host] (KeyAuthentication spec). With [credentials] (from
     * a fresh discovery, or the stored Key #2 on reconnect) it sends AUTH straight away. If there
     * are none, or CineAgent rejects them - its sessions were wiped by a reinstall or factory
     * reset, or this phone disconnected explicitly - it runs a unicast discovery to [host] with
     * Key #1 to get a new Key #2 and authenticates with that. A rejected stored key is cleared
     * at once, so retries never repeat a failing AUTH (CineAgent blocks an address after a few).
     * On success the projector and its Key #2 are saved for the next reconnect.
     */
    suspend fun connect(
        host: String,
        port: Int = CineAgentDiscovery.DEFAULT_TCP_PORT,
        deviceName: String = host,
        credentials: SessionCredentials? = null
    ) {
        disconnect()
        _state.value = ConnectionState.Connecting
        establish(host, port, deviceName, credentials)?.let {
            _state.value = if (it == BUSY) ConnectionState.Busy(deviceName) else ConnectionState.Error(it)
        }
    }

    /** The work behind [connect]: on success the state becomes Connected; on failure it is left
     *  alone and the reason is returned, so [reconnectAfterLoss] can retry without the screen
     *  leaving the Remote view. */
    private suspend fun establish(
        host: String,
        port: Int,
        deviceName: String,
        credentials: SessionCredentials?
    ): String? {
        if (credentials != null) {
            when (open(host, port, credentials)) {
                OpenResult.CONNECTED -> {
                    saveLastConnected(deviceName, host, port, credentials)
                    return null
                }
                OpenResult.UNREACHABLE -> return "Could not reach the projector"
                OpenResult.BUSY -> return BUSY
                OpenResult.AUTH_REJECTED -> {
                    Log.w(TAG, "Stored session rejected by $host - discovering again")
                    if (lastConnectedStore.lastConnected?.credentials == credentials) {
                        lastConnectedStore.clearCredentials()
                    }
                }
            }
        }

        val found = discovery.discoverAt(host) ?: return "Projector did not answer discovery"
        return when (open(found.ipAddress, found.tcpPort, found.credentials)) {
            OpenResult.CONNECTED -> {
                saveLastConnected(found.deviceName, found.ipAddress, found.tcpPort, found.credentials)
                null
            }
            OpenResult.AUTH_REJECTED -> "Projector rejected the connection"
            OpenResult.UNREACHABLE -> "Could not reach the projector"
            OpenResult.BUSY -> BUSY
        }
    }

    private suspend fun open(host: String, port: Int, credentials: SessionCredentials): OpenResult {
        val newConnection = CineAgentConnection.connect(host, port).getOrElse {
            return OpenResult.UNREACHABLE
        }
        val response = newConnection.sendCommand(
            "AUTH",
            mapOf("sessionId" to credentials.sessionId, "sessionKey" to credentials.sessionKey)
        )
        if (response == null) {
            newConnection.close()
            return OpenResult.UNREACHABLE
        }
        if (response.optInt("errorCode") == CineAgentErrorCodes.DEVICE_BUSY) {
            // Key #2 was fine - another phone holds the projector. Keep the key.
            newConnection.close()
            return OpenResult.BUSY
        }
        if (response.optString("status") != "SUCCESS") {
            newConnection.close()
            return OpenResult.AUTH_REJECTED
        }
        connection = newConnection
        executor = CineAgentRemoteExecutor(newConnection)
        currentCredentials = credentials
        newConnection.onEvent = { event -> handleEvent(event) }
        // Notices a dropped connection at once (projector closed it, or Android destroyed the
        // socket when the app went to the background) instead of after 4 missed heartbeats.
        newConnection.onClosed = {
            Log.w(TAG, "Connection to $host closed")
            handleConnectionLost(newConnection)
        }
        _state.value = ConnectionState.Connected(host)
        startMonitor()
        return OpenResult.CONNECTED
    }

    private fun saveLastConnected(deviceName: String, host: String, port: Int, credentials: SessionCredentials) {
        lastConnectedStore.lastConnected = LastConnectedProjector(deviceName, host, port, credentials)
    }

    /**
     * Connects to the saved projector the same way [connect] does, but first waits (however long
     * it takes) for Android to report Wi-Fi actually usable, then retries the connect itself for a
     * while. Meant to be triggered from the moment the app becomes visible again
     * (Activity.onResume) - that's the one point guaranteed to have real foreground network
     * access, regardless of how long the app was backgrounded/asleep or whether the process was
     * frozen in between, so this is deliberately independent of whatever the UI/navigation layer
     * happens to be doing. The saved entry is re-read on every attempt, since an attempt may have
     * replaced its Key #2.
     */
    suspend fun connectRetryingUntilWifiReady(saved: LastConnectedProjector) {
        awaitWifiConnectivity()
        withTimeoutOrNull(RECONNECT_BUDGET_MS) {
            while (true) {
                val current = lastConnectedStore.lastConnected ?: saved
                connect(current.ipAddress, current.tcpPort, current.deviceName, current.credentials)
                if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Busy) return@withTimeoutOrNull
                delay(RECONNECT_RETRY_DELAY_MS)
            }
        }
    }

    /** Suspends until the phone has a Wi-Fi network Android considers usable, with no assumption
     *  about how long that takes - returns immediately if it's already up. Capped by
     *  [WIFI_WAIT_TIMEOUT_MS] purely as a safety net (e.g. Wi-Fi genuinely off) so a caller can't
     *  hang forever waiting on this. */
    private suspend fun awaitWifiConnectivity() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val alreadyUp = cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        if (alreadyUp) return

        withTimeoutOrNull(WIFI_WAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) {
                            runCatching { cm.unregisterNetworkCallback(this) }
                            cont.resumeWith(Result.success(Unit))
                        }
                    }
                }
                cm.registerNetworkCallback(request, callback)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
            }
        }
    }

    private fun handleEvent(event: org.json.JSONObject) {
        when (event.optString("event")) {
            "TCP_DISCONNECT_WARNING" -> {
                // CineAgent hasn't seen traffic from us in a while (usually a Wi-Fi blip ate a
                // few PINGs). It won't drop us yet - answer immediately with a PING to reset its
                // activity timer and stay connected. Only a genuinely dead socket, caught by the
                // heartbeat monitor below, ends the connection.
                Log.w(TAG, "CineAgent TCP_DISCONNECT_WARNING - sending keepalive ping")
                scope.launch { executor?.ping() }
            }
            "DEVICE_REBOOTING" -> {
                Log.w(TAG, "CineAgent event: DEVICE_REBOOTING")
                // A reboot takes far longer than a reconnect attempt - go straight to Lost.
                connection?.let { handleConnectionLost(it, reconnect = false) }
            }
            "TEXT_FIELD_FOCUSED" -> {
                _focusedTextField.value = FocusedTextField(
                    packageName = event.optString("package"),
                    isPassword = event.optBoolean("isPassword"),
                    text = event.optString("text")
                )
            }
            "TEXT_FIELD_BLURRED" -> {
                _focusedTextField.value = null
            }
            "DOLBY_CHANGED" -> {
                _dolbyChanged.tryEmit(event.optBoolean("enabled"))
            }
            "EQUALIZER_CHANGED" -> {
                _equalizerChanged.tryEmit(Unit)
            }
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        val monitored = connection ?: return
        monitorJob = scope.launch {
            var missed = 0
            while (isActive) {
                val currentExecutor = executor ?: break
                val result = currentExecutor.ping()
                when {
                    // Socket actually closed (FIN/RST or CineAgent's hard timeout) - real loss,
                    // give up right away.
                    !currentExecutor.isConnected -> {
                        Log.w(TAG, "Heartbeat: socket closed")
                        handleConnectionLost(monitored)
                        break
                    }
                    result.success -> missed = 0
                    else -> {
                        missed++
                        Log.w(TAG, "Heartbeat miss $missed/$MAX_MISSED_HEARTBEATS: ${result.message}")
                        if (missed >= MAX_MISSED_HEARTBEATS) {
                            handleConnectionLost(monitored)
                            break
                        }
                    }
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /** Tears down [lost] if it is still the active connection (the read side, the heartbeat and
     *  DEVICE_REBOOTING can all report the same loss), then either reconnects quietly or, with
     *  [reconnect] false or no saved projector, reports [ConnectionState.Lost]. */
    @Synchronized
    private fun handleConnectionLost(lost: CineAgentConnection, reconnect: Boolean = true) {
        if (connection !== lost) return
        monitorJob?.cancel()
        monitorJob = null
        executor?.disconnect()
        executor = null
        connection = null
        currentCredentials = null
        _focusedTextField.value = null
        val saved = lastConnectedStore.lastConnected
        if (reconnect && saved != null) {
            _state.value = ConnectionState.Reconnecting
            reconnectJob?.cancel()
            reconnectJob = scope.launch { reconnectAfterLoss(saved) }
        } else {
            reportLost()
        }
    }

    /**
     * Brings the dropped connection back without leaving the Remote screen. The usual cause is
     * Android cutting a backgrounded app's network (it destroys the socket a few seconds after
     * the app leaves the screen), so nothing is tried until the app is visible again - then it
     * reconnects straight away, before the user's first button press. Falls back to
     * [ConnectionState.Lost] (the Discovery screen) only if that fails within
     * [RECONNECT_BUDGET_MS].
     */
    private suspend fun reconnectAfterLoss(saved: LastConnectedProjector) {
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
        awaitWifiConnectivity()
        val reconnected = withTimeoutOrNull(RECONNECT_BUDGET_MS) {
            while (true) {
                val current = lastConnectedStore.lastConnected ?: saved
                val error = establish(current.ipAddress, current.tcpPort, current.deviceName, current.credentials)
                    ?: break
                if (error == BUSY) {
                    // Another phone took the projector while this one was away - retrying won't help.
                    autoReconnectGate.reset()
                    _state.value = ConnectionState.Busy(current.deviceName)
                    return@withTimeoutOrNull false
                }
                Log.w(TAG, "Reconnect attempt failed: $error")
                delay(RECONNECT_RETRY_DELAY_MS)
            }
            true
        } == true
        if (!reconnected && _state.value is ConnectionState.Reconnecting) reportLost()
    }

    /** The Busy popup was dismissed. */
    fun dismissBusy() {
        if (_state.value is ConnectionState.Busy) _state.value = ConnectionState.Disconnected
    }

    private fun reportLost() {
        // Re-arm the gate so returning to Discovery retries the same projector directly instead
        // of falling through to a full rescan.
        autoReconnectGate.reset()
        _state.value = ConnectionState.Lost("Connection to projector lost.")
    }

    /** Closes the connection locally (no "lost" message shown). The session (Key #2) stays valid
     *  on both sides, so a later reconnect authenticates without discovering again - used before
     *  every new connect and after Power off. */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        monitorJob?.cancel()
        monitorJob = null
        executor?.disconnect()
        executor = null
        connection = null
        currentCredentials = null
        _focusedTextField.value = null
        _state.value = ConnectionState.Disconnected
    }

    /**
     * The user's explicit Disconnect: tells CineAgent to erase this phone's Key #2 (DISCONNECT),
     * then forgets it here too; the projector's name and address stay saved. The UI is detached
     * at once and the message is sent in the background on the old connection, so navigating
     * away or connecting again straight after can neither cancel it nor be closed by it.
     */
    fun endSession() {
        val oldConnection = connection
        val oldExecutor = executor
        val credentials = currentCredentials
        oldConnection?.onEvent = null
        oldConnection?.onClosed = null
        reconnectJob?.cancel()
        reconnectJob = null
        // Detach without closing: the old socket still has to carry DISCONNECT.
        monitorJob?.cancel()
        monitorJob = null
        executor = null
        connection = null
        currentCredentials = null
        _focusedTextField.value = null
        _state.value = ConnectionState.Disconnected
        lastConnectedStore.clearCredentials()
        if (oldExecutor == null || credentials == null) {
            oldExecutor?.disconnect()
            return
        }
        scope.launch {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                oldExecutor.query(
                    "DISCONNECT",
                    mapOf("sessionId" to credentials.sessionId, "sessionKey" to credentials.sessionKey)
                )
            }
            oldExecutor.disconnect()
        }
    }

    companion object {
        const val EQUALIZER_NXP = "nxp"
        const val EQUALIZER_WHALEOS = "whaleos"
        private const val TAG = "ProjectorConnectionManager"
        /** [establish]'s result when the projector answered DEVICE_BUSY. */
        private const val BUSY = "busy"

        /** PING cadence. Deliberately faster than spec's 10s so a few dropped PINGs on a lossy
         *  link don't cross CineAgent's warn threshold, and so recovery is quick. */
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        /** Consecutive failed PINGs (socket still open) tolerated before declaring the
         *  connection lost - covers a ~20-30s Wi-Fi blip without tearing down. */
        private const val MAX_MISSED_HEARTBEATS = 4
        /** Safety net only - normally awaitWifiConnectivity() returns as soon as Android reports
         *  Wi-Fi back, however long that actually takes. This just stops it hanging forever
         *  if Wi-Fi never comes back at all. */
        private const val WIFI_WAIT_TIMEOUT_MS = 120_000L
        /** Once Wi-Fi is confirmed up, how long to keep retrying the connect itself - this
         *  only needs to cover the handshake being briefly flaky right as Wi-Fi settles, not
         *  network re-association, so it's much shorter than the Wi-Fi wait. */
        private const val RECONNECT_BUDGET_MS = 20_000L
        private const val RECONNECT_RETRY_DELAY_MS = 1500L
        /** How long Disconnect waits for CineAgent to confirm Key #2 was erased. */
        private const val DISCONNECT_TIMEOUT_MS = 2000L
    }
}
