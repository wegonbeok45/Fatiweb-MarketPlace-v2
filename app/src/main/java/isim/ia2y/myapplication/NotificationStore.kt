package isim.ia2y.myapplication

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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
    private const val KEY_FIRST_LAUNCH = "notification_first_launch"

    private fun getPrefs(context: Context): SharedPreferences {
        val uid = FirebaseAuthManager.currentUser?.uid ?: "guest"
        return context.getSharedPreferences("${uid}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    fun getAll(context: Context): List<AppNotification> {
        checkFirstLaunch(context)

        val json = getPrefs(context).getString(KEY_NOTIFICATIONS, "[]") ?: "[]"
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
                    isRead = obj.getBoolean("isRead")
                )
            )
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun hasUnread(context: Context): Boolean {
        return getAll(context).any { !it.isRead }
    }

    fun markAllAsRead(context: Context) {
        val notifications = getAll(context)
        notifications.forEach { it.isRead = true }
        saveAll(context, notifications)
    }

    private fun saveAll(context: Context, notifications: List<AppNotification>) {
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
        getPrefs(context).edit().putString(KEY_NOTIFICATIONS, array.toString()).apply()
    }

    private fun checkFirstLaunch(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_FIRST_LAUNCH, true)) {
            val welcome = AppNotification(
                id = "welcome_msg",
                title = context.getString(R.string.notification_welcome_title),
                message = context.getString(R.string.notification_welcome_msg),
                timestamp = System.currentTimeMillis()
            )
            saveAll(context, listOf(welcome))
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        }
    }
}
