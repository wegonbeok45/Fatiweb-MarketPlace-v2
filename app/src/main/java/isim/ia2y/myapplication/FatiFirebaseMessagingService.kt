package isim.ia2y.myapplication

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL

class FatiFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        serviceScope.launch {
            runCatching { UserService.saveFcmToken(uid, token) }
                .onFailure { error -> Log.w(TAG, "FCM token update failed", error) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.notifications_fallback_title)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["message"]
            ?: getString(R.string.notifications_fallback_message)
        serviceScope.launch {
            val avatar = loadNotificationAvatar(message.data["avatarUrl"])
            showLocalNotification(title, body, message.data, avatar)
        }
    }

    private fun showLocalNotification(
        title: String,
        body: String,
        data: Map<String, String>,
        avatar: Bitmap?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        AppNotificationChannels.ensureCreated(this)
        val orderId = data["orderId"].orEmpty()
        val conversationId = data["conversationId"].orEmpty()
        val intent = if (conversationId.isNotBlank()) {
            ConversationActivity.createIntent(this, conversationId)
        } else if (orderId.isNotBlank()) {
            OrderDetailsActivity.createIntent(this, orderId)
        } else {
            Intent(this, NotificationsActivity::class.java)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.ifBlank { orderId.ifBlank { title + body } }.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, AppNotificationChannels.UPDATES_ID)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (conversationId.isNotBlank()) {
            builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }
        if (avatar != null) {
            builder.setLargeIcon(avatar)
        }

        NotificationManagerCompat.from(this).notify(
            (conversationId.ifBlank { orderId.ifBlank { title + body } }).hashCode(),
            builder.build()
        )
    }

    private fun loadNotificationAvatar(url: String?): Bitmap? {
        val safeUrl = url?.trim()?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        } ?: return null
        return runCatching {
            val connection = URL(safeUrl).openConnection().apply {
                connectTimeout = 2500
                readTimeout = 3500
            }
            connection.getInputStream().use { stream ->
                BitmapFactory.decodeStream(stream)?.let(::circleCrop)
            }
        }.onFailure { error ->
            Log.w(TAG, "Notification avatar load failed", error)
        }.getOrNull()
    }

    private fun circleCrop(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        val squared = Bitmap.createBitmap(source, x, y, size, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(squared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(output).drawCircle(
            size / 2f,
            size / 2f,
            size / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        )
        if (squared !== source) {
            squared.recycle()
        }
        return output
    }

    private companion object {
        const val TAG = "FatiFirebaseMessaging"
    }
}
