package com.boat.cinehead.ui.discovery

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.boat.cinehead.BuildConfig
import com.boat.cinehead.R
import com.boat.cinehead.net.ConnectionState
import com.boat.cinehead.net.DiscoveredProjector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onConnected: (String) -> Unit
) {
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lastConnected by viewModel.lastConnected.collectAsState()
    var manualIp by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        val connected = connectionState as? ConnectionState.Connected
        if (connected != null) onConnected(connected.host)
    }

    // Any of these mean "don't show the device list yet" - collapse them all to a single
    // "Searching" indicator rather than surfacing raw state/model text to the user.
    // Connecting covers both a manual/scan-triggered connect and the silent auto-reconnect
    // MainActivity.onResume() kicks off on every app foreground.
    val isConnecting = connectionState is ConnectionState.Connecting
    val isSearching = isScanning || isConnecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projector Discovery") },
                actions = {
                    IconButton(onClick = { viewModel.scan() }, enabled = !isConnecting) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Scan")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSearching) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "Searching",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { viewModel.scan() }
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(40.dp))
                            Text(
                                "Refresh",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            if (devices.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                    items(devices) { device ->
                        DeviceCard(device) { viewModel.connect(device) }
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val current = lastConnected
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (current == null) {
                            Text("No last-connected projector saved.")
                        } else {
                            Text("Last connected projector:", style = MaterialTheme.typography.labelSmall)
                            Text(current.deviceName.ifBlank { current.ipAddress }, style = MaterialTheme.typography.bodyLarge)
                            Text("${current.ipAddress}:${current.tcpPort}", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    HorizontalDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter IP address manually", style = MaterialTheme.typography.labelSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = { manualIp = it },
                                label = { Text("Projector IP address") },
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (manualIp.isNotBlank()) {
                                        viewModel.connectManual(manualIp)
                                        showSettings = false
                                    }
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text("Connect")
                            }
                        }
                    }

                    HorizontalDivider()
                    Text(
                        "App version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetLastConnected()
                        showSettings = false
                    },
                    enabled = lastConnected != null
                ) { Text("Forget last connected") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Close") } }
        )
    }
}

private data class ProjectorDisplay(val name: String, val iconRes: Int)

/** Discovery only ever surfaces CineAgent devices (spec section 3 discovery is
 *  CineAgent-specific), so the card just shows the device's own reported name -
 *  the icon match is cosmetic only, picking one of the two known product photos
 *  when the name says which model it is. */
private fun projectorDisplay(deviceName: String): ProjectorDisplay = when {
    deviceName.contains("M1", ignoreCase = true) -> ProjectorDisplay(deviceName, R.drawable.cinehead_m1)
    deviceName.contains("E1", ignoreCase = true) -> ProjectorDisplay(deviceName, R.drawable.cinehead_e1)
    else -> ProjectorDisplay(deviceName, R.drawable.ic_projector)
}

@Composable
private fun DeviceCard(device: DiscoveredProjector, onConnect: () -> Unit) {
    val display = projectorDisplay(device.deviceName)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(display.iconRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
                Text(display.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
            }
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}
