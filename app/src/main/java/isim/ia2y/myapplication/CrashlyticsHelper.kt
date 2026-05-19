package isim.ia2y.myapplication

import android.util.Log
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase

/**
 * Thin wrapper for recording non-fatal exceptions to Firebase Crashlytics.
 * Use [recordNonFatal] instead of plain Log.e() in every catch block that
 * handles a user-visible failure (search, cart sync, order, reviews, …).
 */
object CrashlyticsHelper {
    private const val TAG = "CrashlyticsHelper"

    fun setUserId(uid: String?) {
        runCatching {
            Firebase.crashlytics.setUserId(uid?.takeIf { it.isNotBlank() } ?: "")
        }.onFailure { error ->
            Log.w(TAG, "Failed to update Crashlytics user id", error)
        }
    }

    fun setCustomKey(key: String, value: Boolean) {
        runCatching {
            Firebase.crashlytics.setCustomKey(key, value)
        }.onFailure { error ->
            Log.w(TAG, "Failed to update Crashlytics custom key $key", error)
        }
    }

    fun setCustomKey(key: String, value: String) {
        runCatching {
            Firebase.crashlytics.setCustomKey(key, value)
        }.onFailure { error ->
            Log.w(TAG, "Failed to update Crashlytics custom key $key", error)
        }
    }

    fun recordNonFatal(tag: String, message: String, error: Throwable) {
        Log.e(tag, message, error)
        runCatching {
            Firebase.crashlytics.log("$tag: $message")
            Firebase.crashlytics.recordException(error)
        }.onFailure { crashError ->
            Log.w(TAG, "Failed to record non-fatal to Crashlytics", crashError)
        }
    }
}
