package isim.ia2y.myapplication

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class NotificationPreferences(
    val orderUpdates: Boolean = true,
    val promotions: Boolean = true,
    val announcements: Boolean = true,
    val pushEnabled: Boolean = true
)

object NotificationPreferencesStore {
    private const val PREFS = "notification_preferences"
    private const val KEY_ORDER_UPDATES = "order_updates"
    private const val KEY_PROMOTIONS = "promotions"
    private const val KEY_ANNOUNCEMENTS = "announcements"
    private const val KEY_PUSH_ENABLED = "push_enabled"
    private const val CLOUD_FIELD = "notificationPreferences"
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(context: Context): NotificationPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NotificationPreferences(
            orderUpdates = prefs.getBoolean(KEY_ORDER_UPDATES, true),
            promotions = prefs.getBoolean(KEY_PROMOTIONS, true),
            announcements = prefs.getBoolean(KEY_ANNOUNCEMENTS, true),
            pushEnabled = prefs.getBoolean(KEY_PUSH_ENABLED, true)
        )
    }

    fun save(context: Context, value: NotificationPreferences) {
        saveLocal(context, value)
        syncToCloud(value)
    }

    suspend fun refreshFromCloud(context: Context): NotificationPreferences {
        if (FirebaseCostSafeMode.enabled) return load(context)
        val uid = FirebaseAuthManager.currentUser?.uid ?: return load(context)
        val remotePreferences = runCatching {
            val snapshot = FirebaseFirestore.getInstance()
                .collection(FirestoreCollections.USERS)
                .document(uid)
                .get()
                .await()
            FirebaseCostTracker.read("NotificationPreferencesStore.refreshFromCloud", "users/$uid", if (snapshot.exists()) 1 else 0)
            (snapshot.get(CLOUD_FIELD) as? Map<*, *>)?.toNotificationPreferences()
        }.getOrNull()

        return if (remotePreferences != null) {
            saveLocal(context, remotePreferences)
            remotePreferences
        } else {
            load(context)
        }
    }

    private fun saveLocal(context: Context, value: NotificationPreferences) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ORDER_UPDATES, value.orderUpdates)
            .putBoolean(KEY_PROMOTIONS, value.promotions)
            .putBoolean(KEY_ANNOUNCEMENTS, value.announcements)
            .putBoolean(KEY_PUSH_ENABLED, value.pushEnabled)
            .apply()
    }

    private fun syncToCloud(value: NotificationPreferences) {
        if (FirebaseCostSafeMode.enabled) return
        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        syncScope.launch {
            runCatching {
                FirebaseFirestore.getInstance()
                    .collection(FirestoreCollections.USERS)
                    .document(uid)
                    .set(
                        mapOf(
                            CLOUD_FIELD to value.toCloudMap(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    .await()
                FirebaseCostTracker.write("NotificationPreferencesStore.syncToCloud", "users/$uid")
            }
        }
    }

    private fun NotificationPreferences.toCloudMap(): Map<String, Boolean> = mapOf(
        "orderUpdates" to orderUpdates,
        "promotions" to promotions,
        "announcements" to announcements,
        "pushEnabled" to pushEnabled
    )

    private fun Map<*, *>.toNotificationPreferences(): NotificationPreferences {
        return NotificationPreferences(
            orderUpdates = this["orderUpdates"] as? Boolean ?: true,
            promotions = this["promotions"] as? Boolean ?: true,
            announcements = this["announcements"] as? Boolean ?: true,
            pushEnabled = this["pushEnabled"] as? Boolean ?: true
        )
    }
}
