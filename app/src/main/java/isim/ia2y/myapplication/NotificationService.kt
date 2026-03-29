package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

object NotificationService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val notificationsRef = db.collection(FirestoreCollections.IN_APP_NOTIFICATIONS)
    private fun notificationReadsRef(uid: String) = db.collection(FirestoreCollections.USERS).document(uid).collection(FirestoreCollections.NOTIFICATION_READS)

    suspend fun fetchNotifications(): List<FirestoreService.InAppNotification> {
        val snapshot = notificationsRef
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            FirestoreService.InAppNotification(
                id = doc.id,
                title = data["title"] as? String ?: "",
                message = data["message"] as? String ?: "",
                createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
                createdBy = data["createdBy"] as? String ?: "",
                audience = data["audience"] as? String ?: "all"
            )
        }
    }

    suspend fun fetchInAppNotifications(): List<FirestoreService.InAppNotification> = fetchNotifications()

    suspend fun createInAppNotification(
        title: String,
        message: String,
        createdBy: String,
        audience: String = "all"
    ): FirestoreService.InAppNotification {
        val doc = notificationsRef.document()
        val notification = FirestoreService.InAppNotification(
            id = doc.id,
            title = title,
            message = message,
            createdAt = System.currentTimeMillis(),
            createdBy = createdBy,
            audience = audience
        )
        doc.set(notification.toMap()).await()
        return notification
    }

    suspend fun fetchNotificationReadIds(uid: String): Set<String> {
        val snapshot = notificationReadsRef(uid).get().await()
        return snapshot.documents.map { it.id }.toSet()
    }

    suspend fun replaceNotificationReadIds(uid: String, readIds: Set<String>) {
        val existing = notificationReadsRef(uid).get().await()
        val batch = db.batch()
        existing.documents.forEach { batch.delete(it.reference) }
        val now = System.currentTimeMillis()
        readIds.filter { it.isNotBlank() }.forEach { notificationId ->
            batch.set(
                notificationReadsRef(uid).document(notificationId),
                mapOf("readAt" to now)
            )
        }
        batch.commit().await()
    }

    suspend fun markNotificationRead(uid: String, notificationId: String) {
        notificationReadsRef(uid).document(notificationId)
            .set(mapOf("readAt" to System.currentTimeMillis()))
            .await()
    }

    suspend fun isNotificationRead(uid: String, notificationId: String): Boolean {
        return notificationReadsRef(uid).document(notificationId).get().await().exists()
    }
}
