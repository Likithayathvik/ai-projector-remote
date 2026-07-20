package com.projector.airemote.ui.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.projector.airemote.net.FirebaseServerController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.projector.airemote.BuildConfig
import com.projector.airemote.net.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel,
    onDisconnected: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val actionPopup by viewModel.actionPopup.collectAsState()
    val agentConnected by viewModel.agentConnected.collectAsState()
    val serverState by viewModel.serverState.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var micError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Lost) {
            onDisconnected()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatusMessage()
        }
    }

    val onSpeechResult = rememberUpdatedState<(String) -> Unit> { text -> viewModel.sendChat(text) }
    val speechToText = remember {
        SpeechToText(
            context = context,
            onResult = { onSpeechResult.value(it) },
            onError = { micError = it }
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) speechToText.start()
        else micError = "Microphone permission denied"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Projector Remote")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (agentConnected) Color(0xFF4CAF50) else Color(0xFFEF5350),
                                        shape = CircleShape
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (serverState == FirebaseServerController.ServerState.RUNNING) {
                                            Color(0xFF4CAF50)
                                        } else {
                                            Color(0xFFEF5350)
                                        },
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    TextButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(modifier = Modifier.padding(12.dp)) { Text(data.visuals.message) }
            }
        },
        bottomBar = {
            ChatInputCard(
                isSending = isSending,
                onSend = { viewModel.sendChat(it) },
                onMicClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) speechToText.start()
                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item { RemoteControlPad(volume) { tool, label -> viewModel.pressButton(tool, label) } }
            item { PlayControlRow { tool, label -> viewModel.pressButton(tool, label) } }
            item { QuickLaunchRow { tool, label -> viewModel.pressButton(tool, label) } }
            if (chatMessages.isNotEmpty()) {
                item {
                    Text(
                        "Conversation",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(chatMessages) { message ->
                    Text(
                        text = (if (message.isUser) "You: " else "Agent: ") + message.text,
                        color = if (message.isError) Color(0xFFEF5350)
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    // Popup shown only when the agent requests an unsupported tool/action.
    actionPopup?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeActionPopup() },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeActionPopup() }) { Text("OK") }
            },
            text = { Text(message, style = MaterialTheme.typography.bodyLarge) }
        )
    }

    micError?.let { message ->
        AlertDialog(
            onDismissRequest = { micError = null },
            confirmButton = { TextButton(onClick = { micError = null }) { Text("OK") } },
            text = { Text(message) }
        )
    }

    if (showSettings) {
        SettingsDialog(viewModel = viewModel, onDismiss = { showSettings = false })
    }
}

@Composable
private fun SettingsDialog(viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val packages = remember { viewModel.getAppPackageSettings() }
    var serverUrl by remember { mutableStateOf(packages.serverUrl) }
    var netflix by remember { mutableStateOf(packages.netflix) }
    var youtube by remember { mutableStateOf(packages.youtube) }
    var prime by remember { mutableStateOf(packages.primeVideo) }
    var hotstar by remember { mutableStateOf(packages.hotstar) }
    var usbCommand by remember { mutableStateOf(packages.usbSourceCommand) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Voice Agent Server URL — most important setting
                Text("Voice Agent Server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL (ws://IP:8765)") },
                    placeholder = { Text("ws://34.xxx.xxx.xxx:8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("App package names", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vary by projector firmware — adjust if launches fail.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                OutlinedTextField(
                    value = netflix, onValueChange = { netflix = it },
                    label = { Text("Netflix package") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = youtube, onValueChange = { youtube = it },
                    label = { Text("YouTube package") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prime, onValueChange = { prime = it },
                    label = { Text("Prime Video package") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hotstar, onValueChange = { hotstar = it },
                    label = { Text("Hotstar package") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = usbCommand, onValueChange = { usbCommand = it },
                    label = { Text("USB source ADB command") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                Text(
                    "App version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.saveAppPackageSettings(
                    AppPackageSettings(
                        netflix = netflix,
                        youtube = youtube,
                        primeVideo = prime,
                        hotstar = hotstar,
                        usbSourceCommand = usbCommand,
                        serverUrl = serverUrl,
                    )
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
