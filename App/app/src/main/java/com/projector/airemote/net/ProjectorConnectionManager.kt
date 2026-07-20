package com.projector.airemote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.projector.airemote.adb.AdbConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val host: String) : ConnectionState()
    /** Connection dropped unexpectedly while in use (vs. a user-initiated disconnect). */
    data class Lost(val message: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/** Owns the single active projector connection for the app's lifetime, plus a heartbeat that
 *  detects power-off / Wi-Fi drop / adbd stop while the Remote screen is active (SRS: ADB
 *  connection monitoring). */
class ProjectorConnectionManager(
    private val appContext: Context,
    private val autoReconnectGate: AutoReconnectGate
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    var executor: RemoteExecutor? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    suspend fun connect(host: String, port: Int = 5555) {
        disconnect()
        _state.value = ConnectionState.Connecting
        val result = AdbConnection.connect(host, port)
        result.fold(
            onSuccess = { connection ->
                executor = AdbRemoteExecutor(connection)
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

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
                val currentExecutor = executor ?: break
                val result = currentExecutor.rawShell("echo ping")
                if (!result.success || !currentExecutor.isConnected) {
                    Log.w(TAG, "Heartbeat failed: ${result.message}")
                    handleConnectionLost()
                    break
                }
            }
        }
    }

    private fun handleConnectionLost() {
        executor?.disconnect()
        executor = null
        // Unexpected loss (e.g. the ADB socket dying while the phone was asleep) - re-arm the
        // gate so returning to Discovery retries the same projector directly instead of
        // falling through to a full subnet scan.
        autoReconnectGate.reset()
        _state.value = ConnectionState.Lost("Connection to projector lost.")
    }

    /** Explicit, user-initiated disconnect (no "lost" message shown). */
    fun disconnect() {
        monitorJob?.cancel()
        monitorJob = null
        executor?.disconnect()
        executor = null
        _state.value = ConnectionState.Disconnected
    }

    companion object {
        private const val TAG = "ProjectorConnectionManager"
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        /** Safety net only - normally awaitWifiConnectivity() returns as soon as Android reports
         *  Wi-Fi back, however long that actually takes. This just stops it hanging forever
         *  if Wi-Fi never comes back at all. */
        private const val WIFI_WAIT_TIMEOUT_MS = 120_000L
        /** Once Wi-Fi is confirmed up, how long to keep retrying the ADB connect itself - this
         *  only needs to cover the handshake being briefly flaky right as Wi-Fi settles, not
         *  network re-association, so it's much shorter than the Wi-Fi wait. */
        private const val RECONNECT_BUDGET_MS = 20_000L
        private const val RECONNECT_RETRY_DELAY_MS = 1500L
    }
}
