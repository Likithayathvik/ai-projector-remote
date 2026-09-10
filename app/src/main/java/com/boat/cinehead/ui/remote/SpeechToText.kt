package com.boat.cinehead.ui.remote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper over Android's SpeechRecognizer for the mic button (SRS FR5).
 *
 *  Press-to-toggle: the first tap starts capturing and it KEEPS capturing - through silences -
 *  until the user taps the mic again, at which point the whole transcript is delivered once.
 *
 *  The platform recognizer ends its own session after a few seconds of silence regardless of
 *  the EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS hints (the Samsung / Google on-device
 *  recognizer caps them), so "keep listening" is done by transparently restarting the
 *  recognizer after each segment and stitching the pieces together. */
class SpeechToText(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onListeningChanged: (Boolean) -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    // Set the moment the user taps the mic again; the current segment is then finalized and
    // the joined transcript delivered instead of restarting.
    private var stoppedByUser = false

    private val transcript = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var captureStartedAt = 0L
    private var segmentStartedAt = 0L
    // Consecutive segments that failed almost immediately - means the recognizer is genuinely
    // broken (permission pulled mid-session, service dead), not just silence. Bail instead of
    // spinning.
    private var fastFailures = 0

    private fun setListening(value: Boolean) {
        if (isListening == value) return
        isListening = value
        onListeningChanged(value)
    }

    private fun appendSegment(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return
        if (transcript.isNotEmpty()) transcript.append(' ')
        transcript.append(t)
    }

    /** End the capture: hand back everything gathered (if anything) and reset. */
    private fun finish() {
        mainHandler.removeCallbacksAndMessages(null)
        val result = transcript.toString().trim()
        transcript.setLength(0)
        stoppedByUser = false
        fastFailures = 0
        setListening(false)
        if (result.isNotEmpty()) onResult(result)
    }

    private fun fail(message: String) {
        mainHandler.removeCallbacksAndMessages(null)
        val salvaged = transcript.toString().trim()
        transcript.setLength(0)
        stoppedByUser = false
        fastFailures = 0
        setListening(false)
        // If we already heard something this capture, deliver that rather than throwing away
        // the user's words over a mid-session hiccup.
        if (salvaged.isNotEmpty()) onResult(salvaged) else onError(message)
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        // Honoured on some devices, ignored on others - harmless. The restart loop is what
        // actually keeps the capture alive across pauses.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
    }

    private fun startSegment() {
        val r = recognizer ?: return
        segmentStartedAt = SystemClock.elapsedRealtime()
        r.cancel()
        r.startListening(buildIntent())
    }

    private fun restartOrFinish() {
        if (stoppedByUser) { finish(); return }
        if (!isListening) return
        if (SystemClock.elapsedRealtime() - captureStartedAt > MAX_CAPTURE_MS) {
            // Safety cap - a mic left open indefinitely is a battery/privacy hazard. The user
            // can tap again to continue.
            finish()
            return
        }
        mainHandler.postDelayed({
            if (!stoppedByUser && isListening) startSegment()
        }, RESTART_DELAY_MS)
    }

    private fun getOrCreateRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                fastFailures = 0
                appendSegment(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                restartOrFinish()
            }

            override fun onError(error: Int) {
                when (error) {
                    // Silence / nothing recognised this segment - the normal case while
                    // waiting for the user to speak or between sentences.
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        val quick = SystemClock.elapsedRealtime() - segmentStartedAt < FAST_FAIL_MS
                        fastFailures = if (quick) fastFailures + 1 else 0
                        if (fastFailures >= MAX_FAST_FAILURES) {
                            fail("Speech recognition unavailable")
                        } else {
                            restartOrFinish()
                        }
                    }
                    else -> fail("Speech recognition error ($error)")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { fastFailures = 0 }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        return r
    }

    /** The mic button. First tap starts capturing; tapping again stops it and delivers the
     *  full transcript. */
    fun toggle() {
        if (isListening) {
            stoppedByUser = true
            // Cancel any pending segment restart so the tap can't race one in after it.
            mainHandler.removeCallbacksAndMessages(null)
            val r = recognizer
            if (r != null) r.stopListening() else finish()
            return
        }
        start()
    }

    fun start() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device")
            return
        }
        stoppedByUser = false
        fastFailures = 0
        transcript.setLength(0)
        captureStartedAt = SystemClock.elapsedRealtime()
        setListening(true)
        getOrCreateRecognizer()
        startSegment()
    }

    /** Full teardown - lifecycle cleanup, not the mic button. */
    fun stop() {
        stoppedByUser = false
        fastFailures = 0
        transcript.setLength(0)
        mainHandler.removeCallbacksAndMessages(null)
        setListening(false)
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private const val RESTART_DELAY_MS = 120L
        private const val FAST_FAIL_MS = 400L
        private const val MAX_FAST_FAILURES = 5
        // Hard ceiling on a single hands-off capture.
        private const val MAX_CAPTURE_MS = 5 * 60_000L
    }
}
