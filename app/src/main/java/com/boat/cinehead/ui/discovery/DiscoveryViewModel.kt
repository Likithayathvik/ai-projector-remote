package com.boat.cinehead.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boat.cinehead.net.CineAgentDiscovery
import com.boat.cinehead.net.ConnectionState
import com.boat.cinehead.net.DiscoveredProjector
import com.boat.cinehead.net.LastConnectedProjector
import com.boat.cinehead.net.LastConnectedStore
import com.boat.cinehead.net.ProjectorConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiscoveryViewModel(
    private val discovery: CineAgentDiscovery,
    private val connectionManager: ProjectorConnectionManager,
    private val lastConnectedStore: LastConnectedStore
) : ViewModel() {

    private val _devices = MutableStateFlow<List<DiscoveredProjector>>(emptyList())
    val devices: StateFlow<List<DiscoveredProjector>> = _devices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _lastConnected = MutableStateFlow(lastConnectedStore.lastConnected)
    val lastConnected: StateFlow<LastConnectedProjector?> = _lastConnected

    val connectionState: StateFlow<ConnectionState> = connectionManager.state

    init {
        viewModelScope.launch {
            // MainActivity.onResume() is the authoritative trigger for "try the last connected
            // projector directly" (see its doc for why it lives there instead of here) - it fires
            // on every app foreground, independent of Compose/navigation timing, which matters
            // because that attempt can already be in flight (ConnectionState.Connecting) by the
            // time this screen is (re)composed. Starting a redundant attempt/scan here on top of
            // it would just waste a lot of network activity for no benefit, so this only takes
            // over once nothing else is already trying.
            if (connectionManager.state.value !is ConnectionState.Connecting) {
                scan()
            }
        }
    }

    fun scan() {
        if (_isScanning.value) return
        _devices.value = emptyList()
        _isScanning.value = true
        viewModelScope.launch {
            // A single broadcast + 3s listening window (spec section 3.5) - if nothing turns
            // up, stop and let the UI show a manual Refresh prompt rather than retrying forever.
            discovery.scan(
                onFound = { found ->
                    _devices.update { current -> current.filterNot { it.ipAddress == found.ipAddress } + found }
                }
            )
            _isScanning.value = false
        }
    }

    fun connect(projector: DiscoveredProjector) {
        viewModelScope.launch {
            connectionManager.connect(projector.ipAddress, projector.tcpPort)
            if (connectionManager.state.value is ConnectionState.Connected) {
                saveLastConnected(
                    LastConnectedProjector(projector.deviceName, projector.ipAddress, projector.tcpPort)
                )
            }
        }
    }

    fun connectManual(ip: String) {
        viewModelScope.launch {
            connectionManager.connect(ip, CineAgentDiscovery.DEFAULT_TCP_PORT)
            if (connectionManager.state.value is ConnectionState.Connected) {
                saveLastConnected(LastConnectedProjector("Manual entry", ip, CineAgentDiscovery.DEFAULT_TCP_PORT))
            }
        }
    }

    fun forgetLastConnected() {
        lastConnectedStore.forget()
        _lastConnected.value = null
    }

    private fun saveLastConnected(projector: LastConnectedProjector) {
        lastConnectedStore.lastConnected = projector
        _lastConnected.value = projector
    }
}
