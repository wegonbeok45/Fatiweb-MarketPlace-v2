package isim.ia2y.myapplication.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class FatiVoiceService(private val context: Context) {

    companion object {
        private const val TAG = "FatiVoiceService"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isInitializing = false
    private var isSpeaking = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingReadyCallbacks = mutableListOf<() -> Unit>()
    private val pendingSpeakRequests = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val enableDebugLogs = true

    // Initialize TTS properly
    fun initialize(onReady: () -> Unit) {
        if (isTtsReady) {
            mainHandler.post(onReady)
            return
        }

        pendingReadyCallbacks.add(onReady)
        if (isInitializing || tts != null) {
            return
        }

        isInitializing = true
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                tts?.setSpeechRate(0.85f)
                tts?.setPitch(0.95f)

                val frenchVoices = tts?.voices?.filter { voice ->
                    voice.locale.language == "fr" && !voice.isNetworkConnectionRequired
                }
                val bestVoice = frenchVoices?.minByOrNull { it.latency }
                if (bestVoice != null) {
                    tts?.voice = bestVoice
                }

                isTtsReady = true
                Log.d(TAG, "TTS ready for French")
            } else {
                Log.w(TAG, "TTS initialization failed with status=$status")
                isTtsReady = false
            }

            isInitializing = false
            val callbacks = pendingReadyCallbacks.toList()
            pendingReadyCallbacks.clear()
            callbacks.forEach { mainHandler.post(it) }
            flushPendingSpeaks()
        }
    }

    private fun flushPendingSpeaks() {
        if (!isTtsReady) return
        val queued = pendingSpeakRequests.toList()
        pendingSpeakRequests.clear()
        queued.forEach { (text, onDone) -> speak(text, onDone) }
    }

    // Only speak when TTS is ready
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isTtsReady) {
            pendingSpeakRequests.add(text to onDone)
            if (tts == null) {
                initialize {}
            }
            return
        }
        if (enableDebugLogs) Log.d(TAG, "speak() called -> $text")
        val utteranceId = "fativoice_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onDone(utteranceId: String) {
                if (enableDebugLogs) Log.d(TAG, "TTS onDone -> $utteranceId")
                isSpeaking = false
                mainHandler.post { onDone?.invoke() }
            }

            override fun onError(utteranceId: String) {
                Log.w(TAG, "TTS error: $utteranceId")
                if (enableDebugLogs) Log.d(TAG, "TTS error detail -> $utteranceId")
                isSpeaking = false
                mainHandler.post { onDone?.invoke() }
            }

            override fun onStart(utteranceId: String) {
                if (enableDebugLogs) Log.d(TAG, "TTS onStart -> $utteranceId")
                isSpeaking = true
            }
        })
        if (enableDebugLogs) Log.d(TAG, "tts.speak utteranceId=$utteranceId")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun isSpeaking(): Boolean = isSpeaking

    fun stopSpeaking() {
        isSpeaking = false
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isTtsReady = false
        isInitializing = false
        isSpeaking = false
        pendingReadyCallbacks.clear()
        pendingSpeakRequests.clear()
    }
}
