package com.projector.airemote.ui.remote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper over Android's on-device SpeechRecognizer for the mic button (SRS FR5). */
class SpeechToText(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    // Reused for the lifetime of this SpeechToText instance instead of being destroyed and
    // recreated per tap — destroy() immediately followed by createSpeechRecognizer() +
    // startListening() tears down and rebinds the platform recognition service in the same
    // call, and a session starting mid-rebind reliably surfaces as ERROR_SERVER_DISCONNECTED (11).
    private fun getOrCreateRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                isListening = false
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text != null) onResult(text) else onError("No speech recognized")
            }

            override fun onError(error: Int) {
                isListening = false
                onError("Speech recognition error ($error)")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        return r
    }

    fun start() {
        // Ignore re-taps while a session is already in flight.
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device")
            return
        }
        isListening = true
        val r = getOrCreateRecognizer()
        r.cancel()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        r.startListening(intent)
    }

    fun stop() {
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }
}
