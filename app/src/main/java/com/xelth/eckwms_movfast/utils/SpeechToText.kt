package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Thin PUSH-TO-TALK wrapper around Android SpeechRecognizer.
 *
 * Hold to talk: call [start] on press, [stop] on release. Partial results
 * stream live (isFinal = false) so the caller can echo them into the console's
 * freshest line; the final result arrives with isFinal = true. German locale.
 * No dialog UI — fully in-app.
 */
class SpeechToText(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onText: (String, Boolean) -> Unit) {
        if (!isAvailable()) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.let { if (it.isNotBlank()) onText(it, true) }
                    }
                    override fun onPartialResults(partial: Bundle?) {
                        partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.let { if (it.isNotBlank()) onText(it, false) }
                    }
                    override fun onError(error: Int) { Log.d(TAG, "recognition error $error") }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rms: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed: ${e.message}")
        }
    }

    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    fun destroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    companion object { private const val TAG = "SpeechToText" }
}
