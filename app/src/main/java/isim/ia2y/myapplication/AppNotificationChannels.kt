package isim.ia2y.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

object AppNotificationChannels {
    const val UPDATES_ID = "order_updates"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            UPDATES_ID,
            appContext.getString(R.string.notifications_channel_updates_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = appContext.getString(R.string.notifications_channel_updates_description)
            enableLights(true)
            lightColor = ContextCompat.getColor(appContext, R.color.colorPrimary)
            enableVibration(true)
        }

        manager.createNotificationChannel(channel)
    }
}
