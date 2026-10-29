package com.boat.cinehead.ui.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.boat.cinehead.BuildConfig
import com.boat.cinehead.net.ConnectionState
import com.boat.cinehead.net.FocusedTextField
import com.boat.cinehead.ui.theme.RemoteBlack
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.saveable.rememberSaveable
import com.boat.cinehead.ui.equalizer.EqualizerScreen
import com.boat.cinehead.ui.equalizer.EqualizerViewModel

/** Placeholder field the shared [KeyboardModeScreen] renders for the timer-driven
 *  [KeyboardMode.SearchQuery] flow (YouTube TV), which has no real focused field on the
 *  projector: plain (non-password) with an empty prefill. */
private val SEARCH_QUERY_FIELD = FocusedTextField(packageName = "", isPassword = false, text = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel,
    equalizerViewModel: EqualizerViewModel,
    onDisconnected: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Remote) }
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val usbSupported by viewModel.usbSupported.collectAsState()
    val menuSupported by viewModel.menuSupported.collectAsState()
    val isPoweringOff by viewModel.isPoweringOff.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val actionPopup by viewModel.actionPopup.collectAsState()
    // (no agent/server connection concept anymore — commands route on-device)
    val keyboardMode by viewModel.keyboardMode.collectAsState()
    val isSendingText by viewModel.isSendingText.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var micError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Lost ||
            connectionState is ConnectionState.Busy
        ) {
            onDisconnected()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatusMessage()
        }
    }

    var micListening by remember { mutableStateOf(false) }
    var liveTranscript by remember { mutableStateOf("") }
    val onSpeechResult = rememberUpdatedState<(String) -> Unit> { text -> viewModel.sendChat(text) }
    val speechToText = remember {
        SpeechToText(
            context = context,
            onResult = { onSpeechResult.value(it) },
            onError = { micError = it },
            onListeningChanged = {
                micListening = it
                liveTranscript = ""
            },
            onPartial = { liveTranscript = it }
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) speechToText.toggle()
        else micError = "Microphone permission denied"
    }

    // Keyboard Mode takes over the whole screen rather than popping up on top of the remote
    // controls - render it in place of everything below and stop here. The LaunchedEffects
    // above still keep running across recompositions regardless of this early return, since
    // Compose identifies them by call-site position, not by what runs after them.
    when (val mode = keyboardMode) {
        is KeyboardMode.FieldFocused -> {
            KeyboardModeScreen(
                field = mode.field,
                isSending = isSendingText,
                onSend = { text -> viewModel.sendKeyboardText(text) },
                onBack = { viewModel.cancelSearchKeyboard() }
            )
            return
        }
        KeyboardMode.SearchQuery -> {
            KeyboardModeScreen(
                field = SEARCH_QUERY_FIELD,
                isSending = isSendingText,
                onSend = { text -> viewModel.sendSearchQuery(text) },
                onBack = { viewModel.cancelSearchKeyboard() }
            )
            return
        }
        null -> {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("AI Projector Remote")
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
            Column(modifier = Modifier.fillMaxWidth().imePadding()) {
                // The chat bar belongs to the Remote tab only.
                if (tab == MainTab.Remote) {
                    ChatInputCard(
                        isSending = isSending,
                        onSend = { viewModel.sendChat(it) },
                        onMicClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) speechToText.toggle()
                            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        isListening = micListening,
                        liveTranscript = liveTranscript,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                MainTabBar(selected = tab, onSelect = { tab = it }, modifier = Modifier.navigationBarsPadding())
            }
        }
    ) { padding ->
        when (tab) {
            MainTab.Equalizer -> Box(Modifier.fillMaxSize().padding(padding)) { EqualizerScreen(equalizerViewModel) }
            MainTab.Remote -> Box(modifier = Modifier.fillMaxWidth().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { RemoteControlPad(volume, isMuted, usbSupported, menuSupported) { tool, label -> viewModel.pressButton(tool, label) } }
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
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        }
    }

    // Popup shown only when a chat/voice command isn't a supported action.
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

    // The connection dropped (usually Android cutting the network while the app was in the
    // background) and is being brought back - the remote stays put, with presses held off
    // until it's ready, instead of failing with "Command timed out".
    if (connectionState is ConnectionState.Reconnecting && !isPoweringOff) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Column(
                modifier = Modifier
                    .background(RemoteBlack, RoundedCornerShape(20.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                Text("Reconnecting…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    // Shown for the few seconds between sending POWER and the app disconnecting - the
    // projector itself takes a moment to click through its power dialog, and without this
    // the screen just sits there looking unresponsive.
    if (isPoweringOff) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Column(
                modifier = Modifier
                    .background(RemoteBlack, RoundedCornerShape(20.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                Text("Powering off…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SettingsDialog(viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val packages = remember { viewModel.getAppPackageSettings() }
    var netflix by remember { mutableStateOf(packages.netflix) }
    var youtube by remember { mutableStateOf(packages.youtube) }
    var prime by remember { mutableStateOf(packages.primeVideo) }
    var hotstar by remember { mutableStateOf(packages.hotstar) }

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
                    )
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
