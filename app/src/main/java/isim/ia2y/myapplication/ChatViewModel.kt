package isim.ia2y.myapplication

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val loadingPlaceholder = ChatMessage(
        role = ChatMessage.Role.BOT,
        text = "...",
        isLoading = true
    )

    fun sendMessage(userText: String, userId: String? = null) {
        if (userText.isBlank() || _isLoading.value) return

        val userMsg = ChatMessage(role = ChatMessage.Role.USER, text = userText.trim())
        val historyWithUser = _messages.value + userMsg

        _messages.value = historyWithUser + loadingPlaceholder
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val reply = GeminiChatService.sendMessage(
                    history = historyWithUser,
                    userId = userId
                )
                val botMsg = ChatMessage(role = ChatMessage.Role.BOT, text = reply)
                _messages.value = historyWithUser + botMsg
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Chat request failed", e)
                _messages.value = historyWithUser
                _error.value = mapErrorToMessage(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun addWelcomeMessage() {
        if (_messages.value.isNotEmpty()) return
        val welcome = ChatMessage(
            role = ChatMessage.Role.BOT,
            text = app.getString(R.string.chat_welcome_message)
        )
        _messages.value = listOf(welcome)
    }

    private fun mapErrorToMessage(error: Exception): String = when {
        (error as? BackendFunctionException)?.code == com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
            app.getString(R.string.chat_error_quota)
        (error as? BackendFunctionException)?.code == com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            app.getString(R.string.chat_error_auth)
        error.message?.contains("RATE_LIMIT") == true ->
            app.getString(R.string.chat_error_rate_limit)
        error.message?.contains("blocked by safety filter") == true ->
            app.getString(R.string.chat_error_safety)
        error.message?.contains("API error 400") == true ->
            app.getString(R.string.chat_error_bad_request)
        error.message?.contains("API error 401") == true ->
            app.getString(R.string.chat_error_auth)
        error.message?.contains("API error 429") == true ->
            app.getString(R.string.chat_error_quota)
        else -> app.getString(R.string.chat_error_network)
    }
}
