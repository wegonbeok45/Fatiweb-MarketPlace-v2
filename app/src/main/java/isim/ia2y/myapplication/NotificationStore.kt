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
    var isRead: Boolean = false,
    val route: String = "",
    val entityRef: String = "",
    val orderId: String = ""
)

object NotificationStore {
    private const val PREFS_NAME = "fatiweb_notifications"
    private const val KEY_NOTIFICATIONS = "notifications_json"
    private const val KEY_LAST_REFRESH_AT = "last_refresh_at"
    private const val GUEST_KEY = "guest"
    private const val DEFAULT_REFRESH_TTL_MS = 2 * 60 * 1000L
    private const val WELCOME_GRACE_PERIOD_MS = 14L * 24 * 60 * 60 * 1000
    private const val WELCOME_NOTIFICATION_PREFIX = "welcome_"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getPrefsForAccount(context: Context, accountKey: String): SharedPreferences {
        return context.getSharedPreferences("${accountKey}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    private fun currentUidOrNull(): String? = runCatching { FirebaseAuthManager.currentRealUid }.getOrNull()

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
                    isRead = obj.optBoolean("isRead", false),
                    route = obj.optString("route"),
                    entityRef = obj.optString("entityRef"),
                    orderId = obj.optString("orderId")
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
        val uid = currentUidOrNull()
        val remote = if (uid != null) {
            val readIds = getAllForAccount(context, uid)
                .filter { it.isRead }
                .map { it.id }
                .toSet() + runCatching { FirestoreService.fetchNotificationReadIds(uid) }.getOrDefault(emptySet())
            val inbox = runCatching { FirestoreService.fetchUserInboxNotifications(uid) }.getOrDefault(emptyList())
            val publicAnnouncements = FirestoreService.fetchPublicAnnouncementNotifications()
            val welcome = buildWelcomeNotification(context, uid)
            (inbox + publicAnnouncements + listOfNotNull(welcome))
                .distinctBy { it.id }
                .map { notification ->
                    notification.apply {
                        if (id in readIds) isRead = true
                    }
                }
                .sortedByDescending { it.timestamp }
                .take(50)
        } else {
            emptyList()
        }
        saveAll(context, remote)
        return remote
    }

    private fun buildWelcomeNotification(context: Context, uid: String): AppNotification? {
        val createdAt = FirebaseAuthManager.currentUser
            ?.takeIf { it.uid == uid }
            ?.metadata?.creationTimestamp ?: return null
        if (createdAt <= 0L) return null
        if (System.currentTimeMillis() - createdAt > WELCOME_GRACE_PERIOD_MS) return null
        return AppNotification(
            id = "$WELCOME_NOTIFICATION_PREFIX$uid",
            title = context.getString(R.string.notification_welcome_title),
            message = context.getString(R.string.notification_welcome_message),
            timestamp = createdAt,
            isRead = false,
            route = "",
            entityRef = ""
        )
    }

    fun hasUnread(context: Context): Boolean {
        return getAll(context).any { !it.isRead }
    }

    fun markAllAsRead(context: Context) {
        val notifications = getAll(context)
        val unreadIds = notifications.filter { !it.isRead }.map { it.id }.toSet()
        notifications.forEach { it.isRead = true }
        saveAll(context, notifications)
        syncCurrentReadStateToCloud(unreadIds)
    }

    fun markRead(context: Context, notificationId: String) {
        val notifications = getAll(context)
        val unreadIds = notifications
            .filter { it.id == notificationId && !it.isRead }
            .map { it.id }
            .toSet()
        if (unreadIds.isEmpty()) return
        notifications.forEach { notification ->
            if (notification.id == notificationId) {
                notification.isRead = true
            }
        }
        saveAll(context, notifications)
        syncCurrentReadStateToCloud(unreadIds)
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
                put("route", it.route)
                put("entityRef", it.entityRef)
                put("orderId", it.orderId)
            }
            array.put(obj)
        }
        getPrefsForAccount(context, accountKey)
            .edit()
            .putString(KEY_NOTIFICATIONS, array.toString())
            .putLong(KEY_LAST_REFRESH_AT, System.currentTimeMillis())
            .apply()
    }

    private fun syncCurrentReadStateToCloud(unreadIds: Set<String>) {
        val uid = currentUidOrNull() ?: return
        if (unreadIds.isEmpty()) return
        scope.launch {
            runCatching { FirestoreService.markNotificationsRead(uid, unreadIds) }
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
