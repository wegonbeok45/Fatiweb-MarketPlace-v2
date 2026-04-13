package isim.ia2y.myapplication

import java.io.IOException

/**
 * Trusted AI chat service.
 *
 * Android no longer talks to Gemini directly or holds the API key.
 * Requests go through a Firebase Callable Function so catalog/order context,
 * rate limiting, and secret handling stay on the backend.
 */
object GeminiChatService {

    private var lastRequestTime = 0L
    private const val COOLDOWN_MS = 3000L

    suspend fun sendMessage(
        history: List<ChatMessage>,
        userId: String? = null
    ): String {
        val now = System.currentTimeMillis()
        if (now - lastRequestTime < COOLDOWN_MS) {
            throw IOException("RATE_LIMIT")
        }
        lastRequestTime = now
        return BackendFunctionsService.assistantSendMessage(history, userId)
    }
}
