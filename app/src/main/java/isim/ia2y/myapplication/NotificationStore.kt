package isim.ia2y.myapplication

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class AppNotification(
    val id: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    var isRead: Boolean = false
)

object NotificationStore {
    private const val PREFS_NAME = "fatiweb_notifications"
    private const val KEY_NOTIFICATIONS = "notifications_json"
    private const val KEY_LAST_REFRESH_AT = "last_refresh_at"
    private const val GUEST_KEY = "guest"
    private const val DEFAULT_REFRESH_TTL_MS = 2 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getPrefsForAccount(context: Context, accountKey: String): SharedPreferences {
        return context.getSharedPreferences("${accountKey}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    private fun currentUidOrNull(): String? = runCatching { FirebaseAuthManager.currentUser?.uid }.getOrNull()

    private fun getAllForAccount(context: Context, accountKey: String): List<AppNotification> {
        val json = getPrefsForAccount(context, accountKey).getString(KEY_NOTIFICATIONS, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<AppNotification>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                AppNotification(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    message = obj.getString("message"),
                    timestamp = obj.getLong("timestamp"),
                    isRead = obj.optBoolean("isRead", false)
                )
            )
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun getAll(context: Context): List<AppNotification> {
        return getAllForAccount(context, currentUidOrNull() ?: GUEST_KEY)
    }

    fun shouldRefreshFromCloud(context: Context, maxAgeMs: Long = DEFAULT_REFRESH_TTL_MS): Boolean {
        val accountKey = currentUidOrNull() ?: GUEST_KEY
        val lastRefreshAt = getPrefsForAccount(context, accountKey).getLong(KEY_LAST_REFRESH_AT, 0L)
        if (lastRefreshAt == 0L) return true
        return (System.currentTimeMillis() - lastRefreshAt) >= maxAgeMs
    }

    suspend fun refreshFromCloud(context: Context): List<AppNotification> {
        val existingById = getAll(context).associateBy { it.id }
        val remote = FirestoreService.fetchInAppNotifications()
        val readIds = currentUidOrNull()?.let { uid ->
            runCatching { FirestoreService.fetchNotificationReadIds(uid) }.getOrDefault(
                existingById.values.filter { it.isRead }.map { it.id }.toSet()
            )
        } ?: existingById.values.filter { it.isRead }.map { it.id }.toSet()
        val merged = remote.map { notification ->
            AppNotification(
                id = notification.id,
                title = notification.title,
                message = notification.message,
                timestamp = notification.createdAt,
                isRead = notification.id in readIds
            )
        }
        saveAll(context, merged)
        return merged
    }

    fun hasUnread(context: Context): Boolean {
        return getAll(context).any { !it.isRead }
    }

    fun markAllAsRead(context: Context) {
        val notifications = getAll(context)
        notifications.forEach { it.isRead = true }
        saveAll(context, notifications)
        syncCurrentReadStateToCloud(context, notifications)
    }

    private fun saveAll(context: Context, notifications: List<AppNotification>) {
        saveAllForAccount(context, currentUidOrNull() ?: GUEST_KEY, notifications)
    }

    private fun saveAllForAccount(context: Context, accountKey: String, notifications: List<AppNotification>) {
        val cappedList = notifications.sortedByDescending { it.timestamp }.take(50)
        val array = JSONArray()
        cappedList.forEach {
            val obj = JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("message", it.message)
                put("timestamp", it.timestamp)
                put("isRead", it.isRead)
            }
            array.put(obj)
        }
        getPrefsForAccount(context, accountKey)
            .edit()
            .putString(KEY_NOTIFICATIONS, array.toString())
            .putLong(KEY_LAST_REFRESH_AT, System.currentTimeMillis())
            .apply()
    }

    private fun syncCurrentReadStateToCloud(context: Context, notifications: List<AppNotification>) {
        val uid = currentUidOrNull() ?: return
        val readIds = notifications.filter { it.isRead }.map { it.id }.toSet()
        scope.launch {
            runCatching { FirestoreService.replaceNotificationReadIds(uid, readIds) }
        }
    }

    suspend fun mergeGuestNotificationsIntoCurrent(context: Context) {
        val uid = currentUidOrNull() ?: return
        val guestPrefs = getPrefsForAccount(context, GUEST_KEY)
        val guestReadIds = getAllForAccount(context, GUEST_KEY)
            .filter { it.isRead }
            .map { it.id }
            .toSet()
        val currentReadIds = getAllForAccount(context, uid)
            .filter { it.isRead }
            .map { it.id }
            .toSet()
        val mergedReadIds = runCatching { FirestoreService.fetchNotificationReadIds(uid) }
            .getOrDefault(currentReadIds) + guestReadIds + currentReadIds
        guestPrefs.edit().remove(KEY_NOTIFICATIONS).apply()
        runCatching { FirestoreService.replaceNotificationReadIds(uid, mergedReadIds) }
        refreshFromCloud(context)
    }
    
    fun clearAll(context: Context) {
        val accountKey = currentUidOrNull() ?: GUEST_KEY
        getPrefsForAccount(context, accountKey).edit().remove(KEY_NOTIFICATIONS).apply()
    }
}
