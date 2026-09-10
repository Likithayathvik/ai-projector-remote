package com.boat.cinehead.ui.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.boat.cinehead.net.FocusedTextField

/**
 * Appears automatically whenever CineAgent reports an editable field focused on the projector
 * (search, login, password) and disappears when it reports focus lost - the caller (RemoteScreen)
 * drives this purely by observing the connection's focusedTextField, no separate open/close call.
 * A real full-screen destination rather than a Dialog overlay - takes over the whole screen, with
 * its own back action, instead of popping up on top of the remote controls. The caller resets the
 * dismissal once a *different* field gets focused, so leaving this screen for one field doesn't
 * suppress the next one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardModeScreen(
    field: FocusedTextField,
    isSending: Boolean,
    onSend: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var text by remember(field) { mutableStateOf(field.text) }
    var micError by remember { mutableStateOf<String?>(null) }
    var micListening by remember { mutableStateOf(false) }

    val onSpeechResult = rememberUpdatedState<(String) -> Unit> { result -> text = result }
    val speechToText = remember {
        SpeechToText(
            context = context,
            onResult = { onSpeechResult.value(it) },
            onError = { micError = it },
            onListeningChanged = { micListening = it }
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) speechToText.toggle()
        else micError = "Microphone permission denied"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (field.isPassword) "Keyboard Mode — Password" else "Keyboard Mode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                visualTransformation = if (field.isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) speechToText.toggle()
                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    // Same mute/unmute style mic toggle as the main remote's chat bar.
                    Icon(
                        if (micListening) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = if (micListening) "Mute microphone" else "Unmute microphone",
                        tint = if (micListening) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    IconButton(onClick = { if (text.isNotBlank()) onSend(text) }) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    micError?.let { message ->
        AlertDialog(
            onDismissRequest = { micError = null },
            confirmButton = {
                TextButton(onClick = { micError = null }) { Text("OK") }
            },
            text = { Text(message) }
        )
    }
}
