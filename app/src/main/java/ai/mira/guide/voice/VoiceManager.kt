package ai.mira.guide.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speech in (SpeechRecognizer) and speech out (TextToSpeech) with barge-in:
 * calling [stopSpeaking] cuts TTS instantly so a new listen can start.
 */
class VoiceManager(context: Context) {
    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onListenError: ((String) -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private val tts: TextToSpeech
    @Volatile var speaking = false
        private set
    @Volatile private var pendingUtterances = 0

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.95f)
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { utteranceFinished() }
            @Deprecated("legacy")
            override fun onError(id: String?) { utteranceFinished() }
            override fun onError(id: String?, code: Int) { utteranceFinished() }
        })
    }

    fun startListening(ctx: Context) {
        stopSpeaking()
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onFinal?.invoke(text)
                else onListenError?.invoke("I did not catch that. Try again.")
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onPartial?.invoke(text)
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "I did not catch that. Try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I heard silence. Tap and ask again."
                    else -> "Mic hiccup. Tap and try again."
                }
                onListenError?.invoke(msg)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        sr.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    private fun utteranceFinished() {
        pendingUtterances = (pendingUtterances - 1).coerceAtLeast(0)
        if (pendingUtterances == 0) {
            speaking = false
            onSpeakDone?.invoke()
        }
    }

    fun speak(text: String) = speakQueued(text, flush = true)

    /** Queue sentences so streamed answers start speaking before they finish generating. */
    fun speakQueued(text: String, flush: Boolean) {
        if (flush) {
            tts.stop()
            pendingUtterances = 0
        }
        pendingUtterances++
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "mira-${System.nanoTime()}")
    }

    fun stopSpeaking() {
        if (speaking) tts.stop()
        speaking = false
        pendingUtterances = 0
    }

    fun shutdown() {
        recognizer?.destroy()
        tts.shutdown()
    }
}
