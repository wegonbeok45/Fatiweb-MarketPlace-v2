package isim.ia2y.myapplication

import android.content.Context
import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.ktx.messaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object FcmTokenService {
    private const val TAG = "FcmTokenService"
    private const val PREFS_NAME = "fcm_token_cache"
    private const val KEY_UID = "uid"
    private const val KEY_TOKEN = "token"
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun syncCurrentUserTokenInBackground(context: Context) {
        val appContext = context.applicationContext
        backgroundScope.launch {
            runCatching { syncCurrentUserToken(appContext) }
        }
    }

    suspend fun syncCurrentUserToken(context: Context) {
        AppNotificationChannels.ensureCreated(context)
        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        runCatching {
            val token = Firebase.messaging.token.await()
            UserService.saveFcmToken(uid, token)
            rememberToken(context, uid, token)
        }.onFailure { error ->
            Log.w(TAG, "FCM token sync failed", error)
        }
    }

    fun clearTokenForSignOut(context: Context, uid: String) {
        if (uid.isBlank()) return
        val appContext = context.applicationContext
        val cachedToken = cachedTokenFor(appContext, uid)
        backgroundScope.launch {
            if (!cachedToken.isNullOrBlank()) {
                runCatching { UserService.deleteFcmToken(uid, cachedToken) }
                    .onFailure { error -> Log.w(TAG, "FCM token Firestore cleanup failed", error) }
            }
            runCatching { Firebase.messaging.deleteToken().await() }
                .onFailure { error -> Log.w(TAG, "FCM token invalidation failed", error) }
            forgetToken(appContext)
        }
    }

    private fun rememberToken(context: Context, uid: String, token: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UID, uid)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    private fun cachedTokenFor(context: Context, uid: String): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_UID, null) != uid) return null
        return prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    private fun forgetToken(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
