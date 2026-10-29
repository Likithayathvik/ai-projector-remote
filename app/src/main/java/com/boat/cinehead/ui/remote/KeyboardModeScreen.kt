package com.boat.cinehead.ui.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.boat.cinehead.net.FocusedTextField

/**
 * Shown only after the phone's Search button opened a search screen on the projector - never
 * just because a field (login, password) got focused there. The caller (RemoteScreen) drives it
 * from RemoteViewModel.keyboardMode, and Back closes it until Search is pressed again.
 * A real full-screen destination rather than a Dialog overlay - takes over the whole screen, with
 * its own back action, instead of popping up on top of the remote controls.
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
    // Pre-filled from the projector's field only when a different field is focused: after a
    // Send the projector re-reports the same field holding the sent text, which must not land
    // back in this box (it is cleared on Send, ready for the next query).
    var text by remember(field.packageName, field.isPassword) { mutableStateOf(field.text) }
    val send: (String) -> Unit = { value ->
        onSend(value)
        text = ""
    }
    var micError by remember { mutableStateOf<String?>(null) }
    var micListening by remember { mutableStateOf(false) }
    var liveTranscript by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // The whole point of this screen is typing, so focus the field and raise the phone's
    // keyboard as soon as it appears (and again for each newly focused projector field)
    // instead of waiting for the user to tap into it.
    LaunchedEffect(field) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Starting/stopping the mic or a send finishing can pull focus off the field; put it back
    // and keep the phone keyboard up the whole time the user is on this screen.
    LaunchedEffect(micListening, isSending) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Voice input ends on its own once the user pauses, and goes straight to the projector -
    // no extra mic tap or Send needed.
    val onSpeechResult = rememberUpdatedState<(String) -> Unit> { result -> send(result) }
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
                // While the mic is capturing, show what it's heard so far; the final transcript
                // lands in [text] once capture stops.
                value = if (micListening) liveTranscript else text,
                onValueChange = { if (!micListening) text = it },
                placeholder = if (micListening) {
                    { Text("Listening…") }
                } else null,
                // Not readOnly while listening - a readOnly field drops the phone keyboard, and
                // it should stay up through voice input and sends alike. Typing during capture
                // is simply ignored by onValueChange above.
                singleLine = true,
                visualTransformation = if (field.isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
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
                    IconButton(onClick = { if (text.isNotBlank()) send(text) }) {
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
