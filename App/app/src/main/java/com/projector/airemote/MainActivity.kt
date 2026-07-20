package com.projector.airemote

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
import com.projector.airemote.net.ConnectionState
import com.projector.airemote.net.ProjectorScanner
import com.projector.airemote.ui.discovery.DiscoveryScreen
import com.projector.airemote.ui.discovery.DiscoveryViewModel
import com.projector.airemote.ui.remote.RemoteScreen
import com.projector.airemote.ui.remote.RemoteViewModel
import com.projector.airemote.ui.theme.AiProjectorRemoteTheme
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
                                            ProjectorScanner(app),
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
                                        // No ApiKeyStore needed — uses VoiceAgentClient (WebSocket)
                                        RemoteViewModel(app.connectionManager, app.appSettingsStore, app.firebaseServerController)
                                    }
                                }
                            )
                            RemoteScreen(viewModel) {
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
        if (app.connectionManager.state.value is ConnectionState.Connected) return
        val saved = app.lastConnectedStore.lastConnected ?: return
        if (!app.autoReconnectGate.tryConsume()) return
        lifecycleScope.launch {
            app.connectionManager.connectRetryingUntilWifiReady(saved.ipAddress, saved.port)
        }
    }
}
