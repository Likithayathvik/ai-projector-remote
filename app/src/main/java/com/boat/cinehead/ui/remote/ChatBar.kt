package com.boat.cinehead.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/** Integrated chat card (mic + text input) - a floating, rounded card rather than an
 *  edge-docked bottom bar, so it reads as part of the remote rather than a system chrome. */
@Composable
fun ChatInputCard(
    isSending: Boolean,
    onSend: (String) -> Unit,
    onMicClick: () -> Unit,
    modifier: Modifier = Modifier,
    isListening: Boolean = false,
    /** What the mic has heard so far - shown in the field while [isListening], in place of
     *  the typed text. The final transcript reaches the conversation only via the caller once
     *  capture stops, not from here. */
    liveTranscript: String = ""
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    // Once the keyboard is dismissed (system Back, or its own hide key) the field shouldn't sit
    // there still focused with a blinking cursor - otherwise the *next* Back has nothing to do
    // but close the app. Only reacts to the keyboard going from shown to hidden, so focusing the
    // field (keyboard not up yet) doesn't immediately un-focus it.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible && isFocused) focusManager.clearFocus()
        imeWasVisible = imeVisible
    }
    // Belt and braces: if the field is still focused, Back just releases it instead of
    // exiting the app.
    BackHandler(enabled = isFocused) { focusManager.clearFocus() }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1E232A),
        shadowElevation = 10.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = if (isListening) liveTranscript else text,
                onValueChange = { if (!isListening) text = it },
                placeholder = { Text(if (isListening) "Listening…" else "Ask or Type a Command") },
                singleLine = true,
                readOnly = isListening,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { isFocused = it.isFocused }
            )
            // Mic toggle: shows a "muted" mic when idle and an active mic (red) while
            // capturing - tapping flips between the two, same as a call mute button.
            // Explicit light tint so it stays visible against the dark card.
            IconButton(onClick = onMicClick) {
                Icon(
                    if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = if (isListening) "Mute microphone" else "Unmute microphone",
                    tint = if (isListening) Color(0xFFEF5350) else Color.White
                )
            }
            if (isSending) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                IconButton(onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                }) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
