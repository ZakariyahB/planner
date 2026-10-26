package app.planner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech to text on the phone, preferring offline recognition. The transcript is handed back as text so it
 * can be read and edited in the chat box before sending. Create and use it on the main thread.
 */
class VoiceInput(
    private val context: Context,
    private val onTranscript: (text: String, final: Boolean) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        stop()
        if (!isAvailable) {
            onProblem("Speech recognition isn't available on this phone")
            return
        }
        val created = SpeechRecognizer.createSpeechRecognizer(context)
        created.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onListening(true)

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = onListening(false)

                override fun onError(error: Int) {
                    onListening(false)
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onProblem("I didn't catch that. Try again.")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onProblem("Allow the microphone to use voice.")
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> onProblem("Offline speech isn't installed. Add it in your phone's language settings.")
                        else -> onProblem("Voice input failed (code $error)")
                    }
                }

                override fun onResults(results: Bundle?) {
                    onListening(false)
                    results.firstResult()?.let { onTranscript(it, true) }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.firstResult()?.let { onTranscript(it, false) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        recognizer = created
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
        onListening(false)
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
