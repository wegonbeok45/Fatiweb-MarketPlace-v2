package isim.ia2y.myapplication

import android.util.Log

object FirebaseCostTracker {
    private const val TAG = "FirebaseCost"
    private const val REMOTE_CONFIG_CLASS = "com.google.firebase.remoteconfig.FirebaseRemoteConfig"
    private const val COST_TRACKER_FLAG = "cost_tracker_enabled"

    fun read(screen: String, path: String, count: Int, source: String = "default") {
        if (isEnabled()) {
            Log.v(TAG, "read screen=$screen path=$path count=$count source=$source")
        }
    }

    fun write(screen: String, path: String, count: Int = 1) {
        if (isEnabled()) {
            Log.v(TAG, "write screen=$screen path=$path count=$count")
        }
    }

    private fun isEnabled(): Boolean {
        return runCatching {
            val remoteConfigClass = Class.forName(REMOTE_CONFIG_CLASS)
            val instance = remoteConfigClass.getMethod("getInstance").invoke(null)
            remoteConfigClass.getMethod("getBoolean", String::class.java)
                .invoke(instance, COST_TRACKER_FLAG) as? Boolean ?: false
        }.getOrDefault(false)
    }
}
