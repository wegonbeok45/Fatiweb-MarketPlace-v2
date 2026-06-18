package isim.ia2y.myapplication.voice

import android.content.Context
import android.media.MediaPlayer
import isim.ia2y.myapplication.BuildConfig
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class GoogleTtsService(private val context: Context) {
    companion object {
        private const val TAG = "GoogleTtsService"
        private const val GOOGLE_TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
    }

    private val apiKey: String = BuildConfig.GOOGLE_CLOUD_TTS_API_KEY

    fun canUseGoogleTts(): Boolean {
        return apiKey.isNotBlank() && isInternetAvailable()
    }

    fun isInternetAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    suspend fun speak(text: String, onComplete: () -> Unit): Boolean {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Google TTS API key is not configured")
            return false
        }

        if (!isInternetAvailable()) {
            Log.w(TAG, "No internet connection for Google TTS")
            return false
        }

        val audioData = fetchAudioData(text) ?: return false
        return playAudio(audioData, onComplete)
    }

    private suspend fun fetchAudioData(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = URL("$GOOGLE_TTS_URL?key=$apiKey")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        val payload = JSONObject().apply {
            put("input", JSONObject().put("text", text))
            put("voice", JSONObject().apply {
                put("languageCode", "fr-FR")
                put("name", "fr-FR-Wavenet-C")
                put("ssmlGender", "FEMALE")
            })
            put("audioConfig", JSONObject().apply {
                put("audioEncoding", "MP3")
                put("speakingRate", 0.9)
                put("pitch", -1.0)
            })
        }.toString()

        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use {
            it.write(payload)
        }

        val responseCode = connection.responseCode
        val responseStream = if (responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return@withContext null

        val responseBody = responseStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (responseCode != HttpURLConnection.HTTP_OK) {
            Log.w(TAG, "Google TTS response failed: $responseCode - $responseBody")
            return@withContext null
        }

        val json = JSONObject(responseBody)
        if (!json.has("audioContent")) {
            Log.w(TAG, "Google TTS response missing audioContent")
            return@withContext null
        }

        return@withContext Base64.decode(json.getString("audioContent"), Base64.DEFAULT)
    }

    private fun playAudio(audioData: ByteArray, onComplete: () -> Unit): Boolean {
        val outputFile = try {
            File.createTempFile("google_tts_", ".mp3", context.cacheDir).apply {
                outputStream().use { it.write(audioData) }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to write Google TTS audio file", error)
            return false
        }

        val mediaPlayer = MediaPlayer()
        return try {
            mediaPlayer.setDataSource(outputFile.absolutePath)
            mediaPlayer.setOnPreparedListener { it.start() }
            mediaPlayer.setOnCompletionListener {
                it.release()
                outputFile.delete()
                onComplete()
            }
            mediaPlayer.setOnErrorListener { player, what, extra ->
                player.release()
                outputFile.delete()
                Log.w(TAG, "MediaPlayer error, what=$what extra=$extra")
                false
            }
            mediaPlayer.prepareAsync()
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to play Google TTS audio", error)
            mediaPlayer.release()
            outputFile.delete()
            false
        }
    }
}
