package com.ashwin.visionai

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * "Ask Smarty": listens for one spoken question using Android's built-in
 * SpeechRecognizer (works via Google's on-device/cloud recognizer already
 * present on the phone - no extra API or key needed for the listening
 * part), then hands the recognized text to the caller to send to Gemini
 * along with the latest glasses photo.
 */
class VoiceAssistant(context: Context) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null

    fun listenOnce(onResult: (String?) -> Unit, onListeningStarted: () -> Unit = {}) {
        val r = recognizer
        if (r == null) { onResult(null); return }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { onListeningStarted() }
            override fun onResults(results: android.os.Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                onResult(text)
            }
            override fun onError(error: Int) { onResult(null) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        r.startListening(intent)
    }

    fun destroy() {
        recognizer?.destroy()
    }
}
