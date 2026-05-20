package isim.ia2y.myapplication

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

object NotificationService {
    private const val NOTIFICATION_PAGE_LIMIT = 50L
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val notificationsRef get() = db.collection(FirestoreCollections.IN_APP_NOTIFICATIONS)
    private fun inboxRef(uid: String) = db.collection(FirestoreCollections.USERS)
        .document(uid)
        .collection(FirestoreCollections.INBOX)
    private fun notificationReadsRef(uid: String) = db.collection(FirestoreCollections.USERS)
        .document(uid)
        .collection(FirestoreCollections.NOTIFICATION_READS)

    suspend fun fetchNotifications(): List<FirestoreService.InAppNotification> {
        val snapshot = notificationsRef
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(NOTIFICATION_PAGE_LIMIT)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            FirestoreService.InAppNotification(
                id = doc.id,
                title = data["title"] as? String ?: "",
                message = data["message"] as? String ?: "",
                createdAt = data["createdAt"].toEpochMillis(),
                createdBy = data["createdBy"] as? String ?: "",
                audience = data["audience"] as? String ?: "all"
            ).takeIf { isValidNotificationCopy(it.title, it.message) }
        }
    }

    suspend fun fetchPublicAnnouncementNotifications(): List<AppNotification> {
        val currentUser = FirebaseAuthManager.currentUser
        val uid = currentUser?.uid
        val accountCreatedAt = currentUser?.metadata?.creationTimestamp ?: 0L
        val role = if (uid != null) {
            runCatching { UserService.fetchUserRole(uid) }.getOrDefault(UserRoles.CLIENT)
        } else UserRoles.CLIENT

        val allowedAudiences = when (role) {
            UserRoles.ADMIN -> listOf("all", "clients", "vendeurs", "admins")
            UserRoles.VENDEUR -> listOf("all", "vendeurs")
            else -> listOf("all", "clients")
        }

        val snapshot = notificationsRef
            .whereIn("audience", allowedAudiences)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(NOTIFICATION_PAGE_LIMIT)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val message = (data["body"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: (data["message"] as? String).orEmpty()
            val timestamp = data["createdAt"].toEpochMillis()
            if (accountCreatedAt > 0L && timestamp > 0L && timestamp < accountCreatedAt) return@mapNotNull null

            AppNotification(
                id = doc.id,
                title = data["title"] as? String ?: "",
                message = message,
                timestamp = timestamp,
                isRead = false,
                route = "notifications",
                entityRef = doc.id
            ).takeIf { isValidNotificationCopy(it.title, it.message) }
        }
    }

    suspend fun fetchUserInboxNotifications(uid: String): List<AppNotification> {
        val accountCreatedAt = FirebaseAuthManager.currentUser
            ?.takeIf { it.uid == uid }
            ?.metadata?.creationTimestamp ?: 0L
        val snapshot = inboxRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(NOTIFICATION_PAGE_LIMIT)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val message = (data["body"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: (data["message"] as? String).orEmpty()
            val timestamp = data["createdAt"].toEpochMillis()
            if (accountCreatedAt > 0L && timestamp > 0L && timestamp < accountCreatedAt) return@mapNotNull null

            AppNotification(
                id = doc.id,
                title = data["title"] as? String ?: "",
                message = message,
                timestamp = timestamp,
                isRead = data["readAt"] != null,
                route = data["route"] as? String ?: "",
                entityRef = data["entityRef"] as? String ?: "",
                orderId = data["orderId"] as? String ?: ""
            ).takeIf { isValidNotificationCopy(it.title, it.message) }
        }
    }

    suspend fun fetchInAppNotifications(): List<FirestoreService.InAppNotification> = fetchNotifications()

    suspend fun createInAppNotification(
        title: String,
        message: String,
        createdBy: String,
        audience: String = "all"
    ): FirestoreService.InAppNotification {
        check(createdBy.isNotBlank()) { "createdBy is required." }
        validateNotificationCopyOrThrow(title, message)
        check(canWriteAnnouncementsDirectly()) { "Admin privileges are required." }
        return directCreateAnnouncement(title, message, createdBy, audience)
    }

    suspend fun markNotificationsRead(uid: String, notificationIds: Set<String>) {
        if (notificationIds.isEmpty()) return

        val now = System.currentTimeMillis()
        notificationIds
            .filter { it.isNotBlank() }
            .chunked(450)
            .forEach { ids ->
                val batch = db.batch()
                ids.forEach { id ->
                    batch.set(
                        notificationReadsRef(uid).document(id),
                        mapOf(
                            "createdAt" to now,
                            "readAt" to now,
                            "updatedAt" to now
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                }
                batch.commit().await()
            }
    }

    suspend fun fetchNotificationReadIds(uid: String): Set<String> {
        val readSnapshot = notificationReadsRef(uid)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(NOTIFICATION_PAGE_LIMIT)
            .get()
            .await()
        val explicitReads = readSnapshot.documents.map { it.id }.toSet()

        val inboxSnapshot = inboxRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(NOTIFICATION_PAGE_LIMIT)
            .get()
            .await()
        val legacyInboxReads = inboxSnapshot.documents
            .filter { it.get("readAt") != null }
            .map { it.id }
            .toSet()
        return explicitReads + legacyInboxReads
    }

    suspend fun replaceNotificationReadIds(uid: String, readIds: Set<String>) {
        markNotificationsRead(uid, readIds)
    }

    suspend fun markNotificationRead(uid: String, notificationId: String) {
        markNotificationsRead(uid, setOf(notificationId))
    }

    suspend fun isNotificationRead(uid: String, notificationId: String): Boolean {
        val readDoc = notificationReadsRef(uid).document(notificationId).get().await()
        if (readDoc.exists()) return true
        val snapshot = inboxRef(uid).document(notificationId).get().await()
        return snapshot.get("readAt") != null
    }

    private suspend fun canWriteAnnouncementsDirectly(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        return AdminSession.isVerified(uid) || UserService.fetchUserRole(uid) == UserRoles.ADMIN
    }

    private suspend fun directCreateAnnouncement(
        title: String,
        message: String,
        createdBy: String,
        audience: String
    ): FirestoreService.InAppNotification {
        val notificationDoc = notificationsRef.document()
        val notificationId = notificationDoc.id
        val createdAt = System.currentTimeMillis()

        notificationDoc.set(
            mapOf(
                "id" to notificationId,
                "type" to "announcement",
                "title" to title,
                "message" to message,
                "body" to message,
                "createdBy" to createdBy,
                "audience" to audience,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "schemaVersion" to 2
            )
        ).await()

        return FirestoreService.InAppNotification(
            id = notificationId,
            title = title,
            message = message,
            createdAt = createdAt,
            createdBy = createdBy,
            audience = audience
        )
    }

}

private fun validateNotificationCopyOrThrow(title: String, message: String) {
    require(isValidNotificationCopy(title, message)) {
        "Announcement content looks malformed. Please use a clear title and message."
    }
}

private fun Any?.toEpochMillis(): Long = when (this) {
    is Number -> this.toLong()
    is com.google.firebase.Timestamp -> this.toDate().time
    is Map<*, *> -> (this["seconds"] as? Number)?.toLong()?.times(1000L) ?: 0L
    else -> 0L
}

private fun isValidNotificationCopy(title: String, message: String): Boolean {
    return title.trim().length >= 3 && message.trim().length >= 5
}
