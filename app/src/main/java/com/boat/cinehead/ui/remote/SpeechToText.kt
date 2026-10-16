package com.boat.cinehead.ui.remote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper over Android's SpeechRecognizer for the mic button (SRS FR5).
 *
 *  Tap once and speak. The app - not the platform recognizer, whose own end-of-speech timing
 *  varies by phone and ignores the silence hints on many - decides when the command is over:
 *  - Silence threshold: once words have been heard, [SILENCE_TIMEOUT_MS] with no new word ends
 *    the capture and delivers the transcript once via [onResult]. If the recognizer ends its
 *    session sooner on its own, it is quietly restarted so the threshold still holds.
 *  - No-speech timeout: nothing said within [NO_SPEECH_TIMEOUT_MS] of the tap turns the mic off
 *    with nothing delivered.
 *  - Max duration: a single command is capped at [MAX_COMMAND_MS], delivering whatever was heard.
 *  Tapping the mic again still stops it early. */
class SpeechToText(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onListeningChanged: (Boolean) -> Unit = {},
    /** Live transcript while capturing (everything heard so far plus the in-progress words),
     *  for display only - [onResult] is still the single final delivery. */
    private val onPartial: (String) -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    // Set the moment the user taps the mic again; the current segment is then finalized and
    // the joined transcript delivered instead of restarting.
    private var stoppedByUser = false

    // True only while a recognizer segment is actually running. Between segments (the short
    // RESTART_DELAY_MS gap) nothing will ever call back, so a stop tap there must finish directly.
    private var segmentActive = false

    private val transcript = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Latest in-progress words of the running segment, not yet part of [transcript]. Used to
    // detect when a *new* word arrives (the recognizer repeats identical partials) and to keep
    // those words if the app ends the capture before the recognizer finalizes them.
    private var lastPartial = ""

    private val silenceTimeout = Runnable { endCapture("silence ${SILENCE_TIMEOUT_MS}ms") }
    private val noSpeechTimeout = Runnable {
        if (transcript.isEmpty() && lastPartial.isEmpty()) endCapture("no speech ${NO_SPEECH_TIMEOUT_MS}ms")
    }
    private val maxDurationTimeout = Runnable { endCapture("max duration ${MAX_COMMAND_MS}ms") }

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

    private fun publishPartial(inProgress: String? = null) {
        val p = inProgress?.trim().orEmpty()
        onPartial(if (p.isEmpty()) transcript.toString() else if (transcript.isEmpty()) p else "$transcript $p")
    }

    /** Restarts the silence countdown - called whenever a new word is heard. */
    private fun armSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeout)
        mainHandler.postDelayed(silenceTimeout, SILENCE_TIMEOUT_MS)
    }

    /** The app's own timers decided the command is over: drop the running segment without
     *  waiting for the recognizer, keep the words it had shown so far, and deliver. */
    private fun endCapture(reason: String) {
        if (!isListening) return
        Log.d(TAG, "capture ended: $reason")
        if (segmentActive) recognizer?.cancel()
        segmentActive = false
        appendSegment(lastPartial)
        finish()
    }

    /** End the capture: hand back everything gathered (if anything) and reset. */
    private fun finish() {
        mainHandler.removeCallbacksAndMessages(null)
        lastPartial = ""
        val result = transcript.toString().trim()
        transcript.setLength(0)
        stoppedByUser = false
        fastFailures = 0
        setListening(false)
        if (result.isNotEmpty()) onResult(result)
    }

    private fun fail(message: String) {
        mainHandler.removeCallbacksAndMessages(null)
        appendSegment(lastPartial)
        lastPartial = ""
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
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // On-device recognition streams partial words back far sooner than the online path,
        // which is what makes the live transcript in the chat field feel instant.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // How long a pause after speaking counts as "done". Honoured on some devices, ignored on
        // others (which use their own, similar default) - harmless either way.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
    }

    private fun startSegment() {
        val r = recognizer ?: return
        segmentStartedAt = SystemClock.elapsedRealtime()
        segmentActive = true
        r.cancel()
        r.startListening(buildIntent())
    }

    private fun restartOrFinish() {
        if (stoppedByUser) { finish(); return }
        if (!isListening) return
        // Whether or not anything's been said yet, keep listening - ending the command is left
        // to the silence / no-speech / max-duration timers, so it behaves the same on every
        // phone regardless of when its recognizer decides a session is over.
        mainHandler.postDelayed({
            if (!stoppedByUser && isListening) startSegment()
        }, RESTART_DELAY_MS)
    }

    private fun getOrCreateRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                // A late result for a capture the app already ended (its own timers / cancel).
                if (!isListening) return
                segmentActive = false
                fastFailures = 0
                val final = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                val hadPartials = lastPartial.isNotEmpty()
                appendSegment(if (final.isNullOrBlank()) lastPartial else final)
                lastPartial = ""
                // The silence countdown already started at the last partial word; only start it
                // here when this result brought words no partial showed (some recognizers skip
                // partials) - otherwise the result's own delay would stretch the threshold.
                if (!hadPartials && !final.isNullOrBlank()) armSilenceTimeout()
                publishPartial()
                restartOrFinish()
            }

            override fun onError(error: Int) {
                Log.w(TAG, "recognizer error $error (listening=$isListening, segmentActive=$segmentActive)")
                // Late callback for a capture that's already over - typically the recognizer
                // reporting our own cancel() after the silence/max timer ended the command. The
                // command already went through; surfacing this would be a bogus error popup.
                if (!isListening) return
                segmentActive = false
                // Keep any words this segment already showed before it errored out.
                appendSegment(lastPartial)
                lastPartial = ""
                when (error) {
                    // Silence / nothing recognised this segment - the normal case while
                    // waiting for the user to speak or between sentences.
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    // Raised by the cancel()+startListening() pair when a segment restarts -
                    // a client-side race, not a real failure.
                    SpeechRecognizer.ERROR_CLIENT -> {
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
            override fun onBeginningOfSpeech() {
                fastFailures = 0
                Log.d(TAG, "speech began +${SystemClock.elapsedRealtime() - segmentStartedAt}ms")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (!isListening) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.d(TAG, "partial +${SystemClock.elapsedRealtime() - segmentStartedAt}ms: $partial")
                val p = partial?.trim().orEmpty()
                if (p.isNotEmpty() && p != lastPartial) {
                    lastPartial = p
                    armSilenceTimeout()
                }
                publishPartial(partial)
            }
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
            if (r != null && segmentActive) r.stopListening() else finish()
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
        lastPartial = ""
        setListening(true)
        mainHandler.postDelayed(noSpeechTimeout, NO_SPEECH_TIMEOUT_MS)
        mainHandler.postDelayed(maxDurationTimeout, MAX_COMMAND_MS)
        getOrCreateRecognizer()
        startSegment()
    }

    /** Full teardown - lifecycle cleanup, not the mic button. */
    fun stop() {
        stoppedByUser = false
        fastFailures = 0
        transcript.setLength(0)
        lastPartial = ""
        segmentActive = false
        mainHandler.removeCallbacksAndMessages(null)
        setListening(false)
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private const val TAG = "SpeechToText"
        private const val RESTART_DELAY_MS = 120L
        private const val FAST_FAIL_MS = 400L
        private const val MAX_FAST_FAILURES = 5
        // Silence threshold: no new word for this long after speaking ends the command.
        private const val SILENCE_TIMEOUT_MS = 2_000L
        // How long the mic waits for the user to start speaking at all.
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L
        // Hard cap on one voice command, however long the user keeps talking.
        private const val MAX_COMMAND_MS = 15_000L
    }
}
