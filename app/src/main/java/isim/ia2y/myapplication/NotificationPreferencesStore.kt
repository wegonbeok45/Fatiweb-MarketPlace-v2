package isim.ia2y.myapplication

import android.content.Context

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ORDER_UPDATES, value.orderUpdates)
            .putBoolean(KEY_PROMOTIONS, value.promotions)
            .putBoolean(KEY_ANNOUNCEMENTS, value.announcements)
            .putBoolean(KEY_PUSH_ENABLED, value.pushEnabled)
            .apply()
    }
}
