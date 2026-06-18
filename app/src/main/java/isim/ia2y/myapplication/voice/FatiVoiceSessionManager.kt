package isim.ia2y.myapplication.voice

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

class FatiVoiceSessionManager(private val context: Context) {
    companion object {
        private const val PREFS_SESSION = "fativoice_session"
        private const val KEY_STATE = "key_session_state"
        private const val KEY_PROMPT = "key_session_prompt"
        private const val KEY_ORDER_ID = "key_session_order_id"
        private const val KEY_UPDATED_AT = "key_session_updated_at"
        private val SESSION_TIMEOUT_MS = TimeUnit.HOURS.toMillis(24)
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)

    data class Session(
        val state: FatiVoiceController.ConversationState,
        val prompt: String,
        val orderId: String?,
        val updatedAt: Long
    )

    fun saveSession(state: FatiVoiceController.ConversationState, prompt: String, orderId: String? = null) {
        prefs.edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_PROMPT, prompt)
            .putString(KEY_ORDER_ID, orderId)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun loadSession(): Session? {
        val stateName = prefs.getString(KEY_STATE, FatiVoiceController.ConversationState.IDLE.name)
            ?: return null
        val state = runCatching { FatiVoiceController.ConversationState.valueOf(stateName) }.getOrNull()
            ?: FatiVoiceController.ConversationState.IDLE
        if (state == FatiVoiceController.ConversationState.IDLE) return null

        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (System.currentTimeMillis() - updatedAt > SESSION_TIMEOUT_MS) {
            clearSession()
            return null
        }

        return Session(
            state = state,
            prompt = prefs.getString(KEY_PROMPT, "") ?: "",
            orderId = prefs.getString(KEY_ORDER_ID, null),
            updatedAt = updatedAt
        )
    }

    fun hasSavedSession(): Boolean = loadSession() != null

    fun clearSession() {
        prefs.edit()
            .putString(KEY_STATE, FatiVoiceController.ConversationState.IDLE.name)
            .remove(KEY_PROMPT)
            .remove(KEY_ORDER_ID)
            .remove(KEY_UPDATED_AT)
            .apply()
    }
}
