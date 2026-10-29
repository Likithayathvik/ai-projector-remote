package com.boat.cinehead

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.boat.cinehead.net.ConnectionState
import com.boat.cinehead.ui.discovery.DiscoveryScreen
import com.boat.cinehead.ui.discovery.DiscoveryViewModel
import com.boat.cinehead.ui.equalizer.EqualizerViewModel
import com.boat.cinehead.ui.remote.RemoteScreen
import com.boat.cinehead.ui.remote.RemoteViewModel
import com.boat.cinehead.ui.theme.AiProjectorRemoteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AiProjectorRemoteApp

        setContent {
            AiProjectorRemoteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "discovery") {
                        composable("discovery") {
                            val viewModel: DiscoveryViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer {
                                        DiscoveryViewModel(
                                            app.discovery,
                                            app.connectionManager,
                                            app.lastConnectedStore
                                        )
                                    }
                                }
                            )
                            DiscoveryScreen(viewModel) {
                                navController.navigate("remote") {
                                    popUpTo("discovery") { inclusive = true }
                                }
                            }
                        }
                        composable("remote") {
                            val viewModel: RemoteViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer {
                                        RemoteViewModel(app.connectionManager, app.appSettingsStore)
                                    }
                                }
                            )
                            val equalizerViewModel: EqualizerViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer { EqualizerViewModel(app.connectionManager) }
                                }
                            )
                            RemoteScreen(viewModel, equalizerViewModel) {
                                navController.navigate("discovery") {
                                    popUpTo("remote") { inclusive = true }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The single authoritative trigger for "try reconnecting to the last-known projector
     * directly." onResume fires on every transition back to the foreground - cold launch,
     * returning from another app, or unlocking after the screen slept - regardless of how long
     * that took or whether the process was frozen in between, which a Compose/navigation-driven
     * trigger (e.g. reacting to the heartbeat's Lost state) can't guarantee: that chain only runs
     * once this Activity is actually resumed anyway, so starting it here directly, as early as
     * possible, avoids it silently losing time to backgrounded network restrictions before the
     * user ever sees anything try.
     */
    override fun onResume() {
        super.onResume()
        val app = application as AiProjectorRemoteApp
        // Reconnecting: the connection manager is already bringing a dropped connection back
        // (it waits for exactly this moment); starting a second connect would cut it off.
        when (app.connectionManager.state.value) {
            is ConnectionState.Connected, ConnectionState.Reconnecting, ConnectionState.Connecting -> return
            else -> {}
        }
        val saved = app.lastConnectedStore.lastConnected ?: return
        if (!app.autoReconnectGate.tryConsume()) return
        lifecycleScope.launch {
            app.connectionManager.connectRetryingUntilWifiReady(saved)
        }
    }
}
