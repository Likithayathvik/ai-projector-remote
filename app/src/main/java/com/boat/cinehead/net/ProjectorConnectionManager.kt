package com.boat.cinehead.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** Connection dropped unexpectedly while in use (vs. a user-initiated disconnect). */
    data class Lost(val message: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/** Owns the single active projector connection for the app's lifetime, plus a heartbeat
 *  (spec section 4.4: PING every 10s) that detects power-off / Wi-Fi drop / CineAgent
 *  stopping while the Remote screen is active. Also reacts to CineAgent's own
 *  TCP_DISCONNECT_WARNING / DEVICE_REBOOTING events (spec section 7) so a connection loss
 *  the server already knows about is surfaced immediately, without waiting for the next
 *  heartbeat to fail first. */
class ProjectorConnectionManager(
    private val appContext: Context,
    private val autoReconnectGate: AutoReconnectGate
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

    suspend fun connect(host: String, port: Int = CineAgentDiscovery.DEFAULT_TCP_PORT) {
        disconnect()
        _state.value = ConnectionState.Connecting
        val result = CineAgentConnection.connect(host, port)
        result.fold(
            onSuccess = { newConnection ->
                connection = newConnection
                executor = CineAgentRemoteExecutor(newConnection)
                newConnection.onEvent = { event -> handleEvent(event) }
                _state.value = ConnectionState.Connected(host)
                startMonitor()
            },
            onFailure = { error ->
                _state.value = ConnectionState.Error(error.message ?: "Failed to connect")
            }
        )
    }

    /**
     * Connects to [host] the same way [connect] does, but first waits (however long it takes)
     * for Android to report Wi-Fi actually usable, then retries the connect itself for a while.
     * Meant to be triggered from the moment the app becomes visible again (Activity.onResume) -
     * that's the one point guaranteed to have real foreground network access, regardless of how
     * long the app was backgrounded/asleep or whether the process was frozen in between, so this
     * is deliberately independent of whatever the UI/navigation layer happens to be doing.
     */
    suspend fun connectRetryingUntilWifiReady(host: String, port: Int) {
        awaitWifiConnectivity()
        withTimeoutOrNull(RECONNECT_BUDGET_MS) {
            while (true) {
                connect(host, port)
                if (_state.value is ConnectionState.Connected) return@withTimeoutOrNull
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
                handleConnectionLost()
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
                        handleConnectionLost()
                        break
                    }
                    result.success -> missed = 0
                    else -> {
                        missed++
                        Log.w(TAG, "Heartbeat miss $missed/$MAX_MISSED_HEARTBEATS: ${result.message}")
                        if (missed >= MAX_MISSED_HEARTBEATS) {
                            handleConnectionLost()
                            break
                        }
                    }
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun handleConnectionLost() {
        monitorJob?.cancel()
        monitorJob = null
        executor?.disconnect()
        executor = null
        connection = null
        // Unexpected loss (e.g. the socket dying while the phone was asleep) - re-arm the
        // gate so returning to Discovery retries the same projector directly instead of
        // falling through to a full rescan.
        autoReconnectGate.reset()
        _focusedTextField.value = null
        _state.value = ConnectionState.Lost("Connection to projector lost.")
    }

    /** Explicit, user-initiated disconnect (no "lost" message shown). */
    fun disconnect() {
        monitorJob?.cancel()
        monitorJob = null
        executor?.disconnect()
        executor = null
        connection = null
        _focusedTextField.value = null
        _state.value = ConnectionState.Disconnected
    }

    companion object {
        const val EQUALIZER_NXP = "nxp"
        const val EQUALIZER_WHALEOS = "whaleos"
        private const val TAG = "ProjectorConnectionManager"
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
    }
}
